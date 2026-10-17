"""End-to-end extraction pipeline for one song (see README)."""
import json
import os
import subprocess
from typing import Dict, List, Optional

from . import brr, dsplog, fifa, ipl, midi, portlog, protocol, snsf, spcfile, xcorr

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.environ.get("SNESFIFA_BUILD", os.path.join(HERE, "..", "build"))
FPS = 50.0  # PAL; snes9x PAL frame rate is 50.007 Hz, close enough for tags
GAME = "FIFA International Soccer"


def tool(name: str) -> str:
    p = os.path.join(BUILD, name)
    if not os.path.exists(p):
        raise FileNotFoundError(f"{p} missing: run emu/build.sh and replay/build.sh")
    return p


def snestrace(rom_path: str, frames: int, *extra: str) -> None:
    subprocess.run([tool("snestrace"), "--rom", rom_path, "--frames", str(frames), *extra], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def decode_log(path: str):
    ev = list(portlog.read_events(path))
    sessions = ipl.parse_ipl(ev)
    start = sessions[0].jump_clock + 1 if sessions and sessions[0].jump_clock else 0
    return ev, sessions, protocol.decode(ev, after_clock=start)


def write_commands(path: str, cmds: List[protocol.Command]) -> None:
    with open(path, "w") as f:
        f.write("# frame\tspc_clock\tpc\tcmd\tname\tphases(port0..3 per ack)\tdescription\n")
        for c in cmds:
            ph = " ".join("".join(f"{b:02x}" for b in p) for p in c.phases)
            f.write(f"{c.frame}\t{c.clock}\t{c.pc:06x}\t{c.cmd}\t{c.name}\t{ph}\t{c.describe()}\n")


def build_snsf_set(rom: bytes, outdir: str, unneeded: Optional[List] = None,
                   loops: Optional[Dict[int, dict]] = None) -> Dict[str, object]:
    """Write fifa.snsflib (minimised ROM + stub) and one .minisnsf per song."""
    from . import minimize
    base = minimize.zero_regions(rom, unneeded or [])
    lib_rom = fifa.build_rom(base, 1)
    tags = {"game": GAME, "year": "1994", "copyright": "1994 Electronic Arts",
            "snsfby": "tools/snes-fifa", "_video": "PAL"}
    lib = snsf.pack_snsf(0, lib_rom, tags)
    with open(os.path.join(outdir, "fifa.snsflib"), "wb") as f:
        f.write(lib)
    off = fifa.w65816.lorom_offset(fifa.TRACK_BYTE)
    files = {}
    for t in fifa.SONGS:
        t_tags = {"_lib": "fifa.snsflib", "title": f"Song {t}"}
        if loops and t in loops:
            info = loops[t]
            t_tags["length"] = snsf.length_tag(info["play_seconds"])
            t_tags["fade"] = "10"
        name = f"fifa_{t:02d}.minisnsf"
        with open(os.path.join(outdir, name), "wb") as f:
            f.write(snsf.pack_snsf(off, bytes([t]), t_tags))
        files[t] = name
    return {"lib": "fifa.snsflib", "lib_bytes": len(lib), "minisnsf": files}


def rip_track(rom: bytes, track: int, outdir: str, lib_dir: str, probe_frames: int = 18000,
              unneeded: Optional[List] = None, reassemble: bool = True) -> Dict[str, object]:
    from . import minimize, reassemble as reasm
    os.makedirs(outdir, exist_ok=True)
    res: Dict[str, object] = {"track": track}
    # the ROM actually played is the one packed into the SNSF (minimised lib + track byte)
    lib_rom, tags = snsf.load_snsf(os.path.join(lib_dir, f"fifa_{track:02d}.minisnsf"))
    expect = fifa.build_rom(minimize.zero_regions(rom, unneeded or []), track)
    res["snsf_rom_matches_stub"] = bytes(lib_rom) == expect
    rom_path = os.path.join(outdir, "snsf_rom.sfc")
    with open(rom_path, "wb") as f:
        f.write(lib_rom)
    # pass 1: find first note and loop
    probe = os.path.join(outdir, "probe.tsv")
    snestrace(rom_path, probe_frames, "--log", probe, "--reads", "none")
    _, _, cmds = decode_log(probe)
    first = min(c.frame for c in cmds if c.cmd == 6)
    loop = dsplog.find_loop(cmds)
    os.remove(probe)
    if loop:
        ls, ll, _, period = loop
        play_frames = (ls - first) + 2 * ll
        res.update({"first_note_frame": first, "loop_start_frame": ls, "loop_frames": ll,
                    "loop_notes": period, "intro_seconds": (ls - first) / FPS, "loop_seconds": ll / FPS})
    else:
        play_frames = 180 * FPS
        res.update({"first_note_frame": first, "loop": None})
    res["play_seconds"] = play_frames / FPS
    total = int(first + play_frames + 10 * FPS)
    snap_frame = first - 1
    # pass 2: full log, audio, snapshot
    log = os.path.join(outdir, "ports.tsv")
    emu_wav = os.path.join(outdir, "emu_capture.wav")
    snap = os.path.join(outdir, "snapshot_raw.spc")
    snestrace(rom_path, total, "--log", log, "--wav", emu_wav, "--spc", f"{snap_frame}:{snap}")
    ev, sessions, cmds = decode_log(log)
    first_clock = {}
    for e in ev:
        first_clock.setdefault(e.frame, e.clock)
    fr = sorted(first_clock)
    cpf = (first_clock[fr[-1]] - first_clock[fr[len(fr) // 10]]) / (fr[-1] - fr[len(fr) // 10])
    res["spc_clocks_per_frame"] = cpf
    snap_clock = None
    with open(log) as f:
        for line in f:
            if line.startswith("# frame") and "action spc" in line:
                snap_clock = int(line.split("smp_clock")[1])
    write_commands(os.path.join(outdir, "commands.tsv"), cmds)
    res["commands"] = len(cmds)
    res["driver_blocks"] = [{"addr": b.addr, "bytes": len(b.data)} for s in sessions for b in s.blocks]
    res["sample_uploads_after_first_note"] = sum(1 for c in cmds if c.cmd == 3 and c.frame >= first)
    # snapshot: fix timer target from the last TIMER command
    t0 = 0x10
    for c in cmds:
        if c.cmd == 12 and c.clock <= snap_clock:
            t0 = c.phases[0][3]
    s = spcfile.Spc.load(snap)
    spcfile.fix_timer_registers(s, 0x01, t0=t0)
    s.set_tags(title=f"Song {track} (initial state)", game=GAME, dumper="snes-fifa",
               comment="65816-driven: silent alone")
    snap_fixed = os.path.join(outdir, "initial_state.spc")
    s.save(snap_fixed)
    os.remove(snap)
    # replay into snes_spc
    rep_wav = os.path.join(outdir, "replay_snes_spc.wav")
    r = subprocess.run([tool("spcreplay"), "--spc", snap_fixed, "--log", log, "--start-clock", str(snap_clock),
                        "--out", rep_wav, "--seconds", str((total - snap_frame) / FPS)],
                       stderr=subprocess.PIPE, text=True)
    res["replay"] = r.stderr.strip()
    lag_s = snap_clock / 32.0 / 32040.0  # snes9x outputs one stereo frame per 32 SMP clocks
    res["validate_replay_vs_emu"] = xcorr.compare(emu_wav, rep_wav, expected_lag_s=lag_s)
    # samples
    sdir = os.path.join(outdir, "samples")
    os.makedirs(sdir, exist_ok=True)
    nslots = sum(1 for c in cmds if c.cmd == 3 and c.clock <= snap_clock and c.frame >= first - 200) or 16
    nslots = min(nslots, 64)
    samples = brr.directory(bytes(s.ram), 2, nslots)
    res["samples"] = brr.export(samples, sdir)
    res["sample_bytes"] = sum(len(x.data) for x in samples)
    # MIDI transcription
    end_clock = None
    if loop:
        end_clock = snap_clock + int((play_frames + 1) / FPS * midi.SPC_CLOCK)
    with open(os.path.join(outdir, f"song{track:02d}.mid"), "wb") as f:
        f.write(midi.transcribe(cmds, snap_clock, end_clock, title=f"{GAME} - Song {track}"))
    # DSP simulation check + reassembled SPC
    if reassemble and loop:
        res["reassembled"] = reasm.build(cmds, s, snap_clock, first, ls, ll, outdir, track, emu_wav,
                                         clocks_per_frame=cpf, ref_lag_s=lag_s)
    with open(os.path.join(outdir, "result.json"), "w") as f:
        json.dump(res, f, indent=1, default=str)
    os.remove(rom_path)
    return res
