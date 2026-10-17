"""'Reassembled' SPC: a standalone .spc whose SPC700 program replays the song by itself.

The original 65816 sequencer cannot run on the SPC700, and the original SPC700 driver is only a
command server.  We therefore replace the driver (its code is not needed once samples are in
ARAM) by a small player of our own that writes the S-DSP registers exactly as the driver would
have, from a compact token stream recorded from the 65816 command stream:

  $01-$7F           wait n frames (50 Hz, timer 1, target 160)
  $80+v idx         note on voice v with setup #idx (dictionary of 8-byte register images:
  $88+v idx         VOL L, VOL R, P lo, P hi, SRCN, ADSR1, ADSR2, GAIN), then KOFF=0, KON=1<<v
                    ($88 form: idx+256)
  $90+v             cut voice v (GAIN=$BF, ADSR2=0, ADSR1=0)  -- the driver's VOICE_CUT
  $98+v lo hi       pitch of voice v
  $A0+v l r         volume of voice v
  $A8 reg val       any other DSP register write
  $B0 lo hi n       execute n tokens at address hi:lo, then return (LZ-style repeat, no nesting)
  $B8 lo hi         continue at address hi:lo (loop point / memory region change)
  $C0+k             wait until k*256 SPC clocks have elapsed in the current frame (reproduces the
                    position of the 65816's writes inside the frame)

Free memory: the driver area ($0240 + DIR..$087B) and everything after the last sample up to
$FFBF.  The token stream covers the intro and one loop, then jumps back to the loop start.
"""
import os
import subprocess
from typing import Dict, List, Optional, Sequence, Tuple

from . import dsplog, spc700, spcfile, xcorr
from .protocol import Command

PLAYER_SRC = """
start:
 clrp
 mov x,#$cf
 mov sp,x
 mov $f1,#$00
 mov $fb,#$02
 mov $f1,#$02
 mov $00,#$SLO
 mov $01,#$SHI
 mov $08,#$DLO
 mov $09,#$DHI
 mov $04,#$00
 mov $06,#$00
 mov $05,#$01
 mov $10,#$00
 mov $11,#$01
 mov $12,#$00
tick:
 mov a,$fe
 clrc
 adc a,$10
 mov $10,a
 cmp a,$11
 bcc tick
 setc
 sbc a,$11
 mov $10,a
 mov a,$12
 clrc
 adc a,#$TF
 mov $12,a
 mov a,#$TI
 adc a,#$00
 mov $11,a
 dec $05
 bne tick
next:
 mov a,$06
 beq fetch
 mov a,$04
 bne fetch
 movw ya,$02
 movw $00,ya
 mov $06,#$00
fetch:
 call !getb
 cmp a,#$80
 bcs notwait
 mov $05,a
 call !tokdone
 bra tick
notwait:
 cmp a,#$c0
 bcc notsub
 and a,#$3f
 mov $0d,a
sub1:
 mov a,$fe
 clrc
 adc a,$10
 mov $10,a
 cmp a,$0d
 bcc sub1
 call !tokdone
 jmp !next
notsub:
 mov $0c,a
 and a,#$07
 xcn a
 mov $07,a
 mov a,$0c
 and a,#$f8
 cmp a,#$80
 bne n1
 jmp !donote
n1:
 cmp a,#$88
 bne n2
 jmp !donote
n2:
 cmp a,#$90
 bne n3
 jmp !docut
n3:
 cmp a,#$98
 bne n4
 jmp !dopitch
n4:
 cmp a,#$a0
 bne n5
 jmp !dovol
n5:
 cmp a,#$a8
 bne n6
 jmp !dodsp
n6:
 cmp a,#$b0
 bne n7
 jmp !docall
n7:
 cmp a,#$b8
 bne n8
 jmp !dojump
n8:
 jmp !next
getb:
 mov y,#$00
 mov a,[$00]+y
 incw $00
 ret
tokdone:
 mov a,$06
 beq td1
 dec $04
td1:
 ret
donote:
 call !getb
 mov y,#$08
 mul ya
 mov $0d,a
 mov a,$0c
 and a,#$08
 beq dn1
 mov a,y
 clrc
 adc a,#$08
 mov y,a
dn1:
 mov a,$0d
 addw ya,$08
 movw $0a,ya
 mov x,$07
 mov y,#$00
dn2:
 mov a,[$0a]+y
 mov $f2,x
 mov $f3,a
 inc x
 inc y
 cmp y,#$08
 bne dn2
 mov $f2,#$5c
 mov $f3,#$00
 mov a,$0c
 and a,#$07
 mov y,a
 mov a,!bits+y
 mov $f2,#$4c
 mov $f3,a
 call !tokdone
 jmp !next
docut:
 mov a,$07
 or a,#$07
 mov $f2,a
 mov $f3,#$bf
 dec a
 mov $f2,a
 mov $f3,#$00
 dec a
 mov $f2,a
 mov $f3,#$00
 call !tokdone
 jmp !next
dopitch:
 mov a,$07
 or a,#$02
 mov $0d,a
 call !getb
 mov $f2,$0d
 mov $f3,a
 inc $0d
 call !getb
 mov $f2,$0d
 mov $f3,a
 call !tokdone
 jmp !next
dovol:
 mov a,$07
 mov $0d,a
 call !getb
 mov $f2,$0d
 mov $f3,a
 inc $0d
 call !getb
 mov $f2,$0d
 mov $f3,a
 call !tokdone
 jmp !next
dodsp:
 call !getb
 mov $0d,a
 call !getb
 mov $f2,$0d
 mov $f3,a
 call !tokdone
 jmp !next
docall:
 call !getb
 mov $0e,a
 call !getb
 mov $0f,a
 call !getb
 mov $04,a
 movw ya,$00
 movw $02,ya
 movw ya,$0e
 movw $00,ya
 mov $06,#$01
 jmp !next
dojump:
 call !getb
 mov $0e,a
 call !getb
 mov y,a
 mov a,$0e
 movw $00,ya
 jmp !next
bits:
 .db $01,$02,$04,$08,$10,$20,$40,$80
dict:
"""


def _player_src(stream: int, dict_addr: int, clocks_per_frame: float = 20503.0) -> str:
    """Frame timing: timer 1 ticks every 256 SPC clocks; a frame lasts clocks_per_frame/256
    ticks, kept exact on average with an 8-bit fractional accumulator."""
    t = clocks_per_frame / 256.0
    ti, tf = int(t), int(round((t - int(t)) * 256)) & 0xFF
    return (PLAYER_SRC.replace("$TI", f"${ti:02x}").replace("$TF", f"${tf:02x}")
            .replace("$SLO", f"${stream & 0xFF:02x}").replace("$SHI", f"${stream >> 8:02x}")
            .replace("$DLO", f"${dict_addr & 0xFF:02x}").replace("$DHI", f"${dict_addr >> 8:02x}"))


def tokenize(cmds: Sequence[Command], first: int, loop_start: int, end: int,
             dsp_regs: bytes, clocks_per_frame: float = 0.0, sub_unit: int = 256
             ) -> Tuple[List[bytes], List[bytes], int, Dict[str, int]]:
    """Return (tokens, dictionary entries, index of the loop-start token, stats).
    With clocks_per_frame > 0, sub-frame wait tokens keep each command's position in its frame."""
    regs = list(dsp_regs)
    tokens: List[bytes] = []
    dictionary: Dict[bytes, int] = {}
    entries: List[bytes] = []
    pending_setup: Dict[int, Tuple[int, ...]] = {}
    state = {"flags": 0x20}
    stats = {"unsupported": 0, "generic": 0}
    loop_token = None
    last_frame = first  # the player's first tick is the start of frame `first`
    sel = [c for c in cmds if first <= c.frame < end]
    fbase = None
    if clocks_per_frame and sel:
        fbase = min(c.clock - c.frame * clocks_per_frame for c in sel)
    sub = 0
    for c in sel:
        if c.frame != last_frame:
            sub = 0
            gap = c.frame - last_frame
            while gap > 0:
                n = min(gap, 127)
                tokens.append(bytes([n]))
                gap -= n
            last_frame = c.frame
        if loop_token is None and c.frame >= loop_start:
            loop_token = len(tokens)
        if fbase is not None and c.cmd != 7:
            k = int((c.clock - c.frame * clocks_per_frame - fbase) // sub_unit) * (sub_unit // 256)
            k = min(k, 63)
            if k > sub:
                tokens.append(bytes([0xC0 | k]))
                sub = k
        a = c.phases[0]
        v = a[1] & 7
        base = v * 16
        if c.cmd == 7 and len(c.phases) > 1:
            pending_setup[v] = (a[2], a[3]) + tuple(c.phases[1][1:4])
            continue
        if c.cmd == 6 and v in pending_setup:
            vl, vr, srcn, a1, a2 = pending_setup.pop(v)
            img = bytes([vl, vr, a[2], a[3], srcn, a1, a2, 0])
            if img not in dictionary:
                dictionary[img] = len(entries)
                entries.append(img)
            idx = dictionary[img]
            tokens.append(bytes([(0x80 if idx < 256 else 0x88) | v, idx & 0xFF]))
            for k, val in enumerate(img):
                regs[base + k] = val
            regs[dsplog.KOF] = 0
            continue
        for pv, setup in list(pending_setup.items()):
            # a setup that was not followed by a key-on: emit its writes generically
            vl, vr, srcn, a1, a2 = setup
            for k, val in ((0, vl), (1, vr), (4, srcn), (5, a1), (6, a2), (7, 0)):
                tokens.append(bytes([0xA8, pv * 16 + k, val]))
                regs[pv * 16 + k] = val
                stats["generic"] += 1
            del pending_setup[pv]
        if c.cmd == 5:
            tokens.append(bytes([0x90 | v]))
            regs[base + 7], regs[base + 6], regs[base + 5] = 0xBF, 0, 0
        elif c.cmd == 11:
            if regs[base + 2] != a[2] or regs[base + 3] != a[3]:
                tokens.append(bytes([0x98 | v, a[2], a[3]]))
                regs[base + 2], regs[base + 3] = a[2], a[3]
        elif c.cmd == 14 and len(c.phases) > 1:
            b = c.phases[1]
            scale = (b[1] << 1) & 0xFF
            l, r = (b[2] * scale) >> 8, (b[3] * scale) >> 8
            if regs[base] != l or regs[base + 1] != r:
                tokens.append(bytes([0xA0 | v, l, r]))
                regs[base], regs[base + 1] = l, r
        else:
            for reg, val in dsplog.command_writes(c, state):
                if reg == dsplog.KON or regs[reg] != val:
                    tokens.append(bytes([0xA8, reg, val]))
                    regs[reg] = val
                    stats["generic"] += 1
    stats["unsupported"] = state.get("unsupported", 0)
    # pad to the end of the loop
    if last_frame < end:
        gap = end - last_frame
        while gap > 0:
            n = min(gap, 127)
            tokens.append(bytes([n]))
            gap -= n
    if loop_token is None:
        loop_token = 0
    return tokens, entries, loop_token, stats


def lz_items(tokens: List[bytes], barrier: int, min_gain: int = 1) -> List[Tuple[str, int, int]]:
    """Greedy LZ over tokens. Items: ('lit', i, 1) or ('ref', src_token_index, n).
    A reference only points at tokens emitted literally (contiguous, not inside a ref),
    never spans `barrier` (the loop point)."""
    n = len(tokens)
    literal = [False] * n
    items: List[Tuple[str, int, int]] = []
    index: Dict[Tuple[bytes, bytes, bytes], List[int]] = {}
    i = 0
    while i < n:
        best = (0, 0, 0)  # gain, src, length
        if i + 3 <= n:
            key = (tokens[i], tokens[i + 1], tokens[i + 2])
            for j in index.get(key, [])[-64:]:
                L = 0
                size = 0
                while (i + L < n and j + L < i and literal[j + L] and tokens[j + L] == tokens[i + L]
                       and not (i < barrier <= i + L) and not (j < barrier <= j + L and False)):
                    size += len(tokens[i + L])
                    L += 1
                    if L >= 255:
                        break
                gain = size - 4
                if L >= 2 and gain > best[0]:
                    best = (gain, j, L)
        if best[0] >= min_gain and not (i < barrier < i + best[2]):
            _, j, L = best
            items.append(("ref", j, L))
            for k in range(L):
                pos = i + k
                if pos + 3 <= n:
                    index.setdefault((tokens[pos], tokens[pos + 1], tokens[pos + 2]), [])
            i += L
            continue
        items.append(("lit", i, 1))
        literal[i] = True
        if i + 3 <= n:
            index.setdefault((tokens[i], tokens[i + 1], tokens[i + 2]), []).append(i)
        i += 1
    return items


def layout(items, tokens, regions: List[Tuple[int, int]], loop_token: int) -> Optional[Tuple[Dict[int, bytes], int]]:
    """Place items into memory regions (inserting $B8 jumps between regions).
    Returns ({addr: bytes}, stream_start) or None if it does not fit."""
    addr_of_token: Dict[int, int] = {}
    # first pass: sizes to assign addresses
    def place(resolve):
        chunks: Dict[int, bytearray] = {}
        r = 0
        cur, limit = regions[0]
        start = cur
        chunks[cur] = bytearray()
        chunk_start = cur
        for kind, i, L in items + [("end", 0, 0)]:
            if kind == "lit":
                data = tokens[i]
            elif kind == "ref":
                src = addr_of_token.get(i, 0)
                data = bytes([0xB0, src & 0xFF, src >> 8, L])
            else:
                tgt = addr_of_token.get(loop_token, start)
                data = bytes([0xB8, tgt & 0xFF, tgt >> 8])
            if cur + len(data) + 3 > limit and kind != "end" or cur + len(data) > limit:
                # jump to next region
                r += 1
                if r >= len(regions):
                    return None
                nxt, nlimit = regions[r]
                chunks[chunk_start] += bytes([0xB8, nxt & 0xFF, nxt >> 8])
                cur, limit = nxt, nlimit
                chunk_start = cur
                chunks[cur] = bytearray()
            if kind == "lit":
                addr_of_token[i] = cur
            chunks[chunk_start] += data
            cur += len(data)
        return chunks, start
    res = place(False)
    if res is None:
        return None
    res = place(True)  # second pass with resolved addresses (sizes are unchanged)
    if res is None:
        return None
    chunks, start = res
    return {a: bytes(b) for a, b in chunks.items()}, start


def build(cmds, snap, snap_clock, first, loop_start, loop_frames, outdir, track, ref_wav=None,
          clocks_per_frame=20503.0, ref_lag_s=None):
    """Try sub-frame timing at 256-clock resolution, then 1024, then frame-only timing,
    and keep the first variant that fits in ARAM."""
    tried = []
    for unit in (256, 1024, 0):
        res = _build(cmds, snap, snap_clock, first, loop_start, loop_frames, outdir, track, ref_wav,
                     clocks_per_frame, ref_lag_s, sub_unit=unit)
        res["subframe_unit_clocks"] = unit
        tried.append({k: res.get(k) for k in ("subframe_unit_clocks", "needed_total", "free_total", "fits")})
        if res.get("fits"):
            break
    res["attempts"] = tried
    return res


def _build(cmds: Sequence[Command], snap: spcfile.Spc, snap_clock: int, first: int, loop_start: int,
          loop_frames: float, outdir: str, track: int, ref_wav: Optional[str] = None,
          clocks_per_frame: float = 20503.0, ref_lag_s: Optional[float] = None,
          sub_unit: int = 256) -> Dict[str, object]:
    end = loop_start + int(round(loop_frames))
    ram = bytearray(snap.ram)
    dsp = bytes(snap.dsp)
    tokens, entries, loop_tok, stats = tokenize(cmds, first, loop_start, end, dsp,
                                                clocks_per_frame if sub_unit else 0.0, sub_unit or 256)
    items = lz_items(tokens, loop_tok)
    # samples actually used by this song (SRCN of every note + generic SRCN writes)
    used = {e[4] for e in entries}
    used |= {t[2] for t in tokens if t[0] == 0xA8 and t[1] & 0x0F == 4}
    dir_page = dsp[0x5D]
    from . import brr
    slots = []
    for smp in brr.directory(bytes(ram), dir_page, 64):
        if smp.start < 0x0800 or smp.start >= 0xFFC0 or not smp.data:
            break
        slots.append(smp)
    nslots = len(slots)
    # compact ARAM: keep used samples only, packed from $087C, DIR rewritten (indices kept)
    pos = 0x087C
    new_ram = bytearray(ram)
    for smp in slots:
        if smp.index not in used:
            continue
        loop_off = smp.loop - smp.start
        if not 0 <= loop_off < len(smp.data) + 9:
            loop_off = 0
        new_ram[pos:pos + len(smp.data)] = smp.data
        e = dir_page * 0x100 + 4 * smp.index
        new_ram[e:e + 4] = bytes([pos & 0xFF, pos >> 8, (pos + loop_off) & 0xFF, (pos + loop_off) >> 8])
        pos += len(smp.data)
    sample_end = pos
    for smp in slots:
        if smp.index not in used:  # point unused entries at sample 0's data (never keyed)
            e = dir_page * 0x100 + 4 * smp.index
            new_ram[e:e + 4] = new_ram[dir_page * 0x100 + 4 * min(used):dir_page * 0x100 + 4 * min(used) + 4]
    new_ram[sample_end:0xFFC0] = bytes(0xFFC0 - sample_end)
    ram = new_ram
    region_a = (dir_page * 0x100 + 4 * nslots, 0x087C)
    region_b = (sample_end, 0xFFC0)
    player = spc700.assemble(_player_src(0, 0), region_a[0])
    syms_dict_addr = region_a[0] + len(player)  # 'dict' label == end of player code
    dict_bytes = b"".join(entries)
    stream_a_start = syms_dict_addr + len(dict_bytes)
    raw_tokens = sum(len(t) for t in tokens)
    lz_bytes = sum(len(tokens[i]) if k == "lit" else 4 for k, i, L in items) + 3
    result: Dict[str, object] = {
        "tokens": len(tokens), "token_bytes_raw": raw_tokens, "token_bytes_lz": lz_bytes,
        "dictionary_entries": len(entries), "dictionary_bytes": len(dict_bytes), "player_bytes": len(player),
        "free_region_a": [hex(region_a[0]), hex(region_a[1]), region_a[1] - region_a[0]],
        "free_region_b": [hex(region_b[0]), hex(region_b[1]), max(0, region_b[1] - region_b[0])],
        "free_total": (region_a[1] - region_a[0]) + max(0, region_b[1] - region_b[0]),
        "needed_total": len(player) + len(dict_bytes) + lz_bytes,
        "sample_bytes_all": sum(len(x.data) for x in slots), "sample_bytes_used": sample_end - 0x087C,
        "samples_used": sorted(used), "stats": stats,
    }
    regions = [(stream_a_start, region_a[1])]
    if region_b[1] - region_b[0] > 16:
        regions.append(region_b)
    if stream_a_start >= region_a[1]:
        # dictionary goes to the start of region B instead
        syms_dict_addr = region_b[0]
        regions = [(region_a[0] + len(player), region_a[1]), (region_b[0] + len(dict_bytes), region_b[1])]
        result["dictionary_in"] = "B"
        if region_b[0] + len(dict_bytes) > region_b[1]:
            result["fits"] = False
            result["reason"] = (f"player ({len(player)} B) + note dictionary ({len(dict_bytes)} B) + stream "
                                f"({lz_bytes} B after LZ) = {result['needed_total']} B, but only "
                                f"{result['free_total']} B of ARAM are free next to the used samples")
            return result
    regions = [(a, b) for a, b in regions if b - a > 16]
    placed = layout(items, tokens, regions, loop_tok)
    if placed is None:
        result["fits"] = False
        result["reason"] = (f"player ({len(player)} B) + note dictionary ({len(dict_bytes)} B) + stream "
                            f"({lz_bytes} B after LZ) = {result['needed_total']} B, but only "
                            f"{result['free_total']} B of ARAM are free next to the used samples")
        return result
    chunks, start = placed
    # re-assemble the player with the real stream address
    player = spc700.assemble(_player_src(start, syms_dict_addr, clocks_per_frame), region_a[0])
    ram[region_a[0]:region_a[0] + len(player)] = player
    ram[syms_dict_addr:syms_dict_addr + len(dict_bytes)] = dict_bytes
    for a, b in chunks.items():
        ram[a:a + len(b)] = b
    # clean the zero page / stack used by the player
    for k in range(0x00, 0x10):
        ram[k] = 0
    ram[0xF1] = 0x02
    ram[0xFB] = 0x02
    ram[0xFA] = 0x00
    ram[0xFD:0x100] = b"\x00\x00\x00"
    result["clocks_per_frame"] = clocks_per_frame
    out = spcfile.Spc.build(bytes(ram), dsp, region_a[0], sp=0xCF)
    play_s = (loop_start - first) / 50.0 + 2 * loop_frames / 50.0
    out.set_tags(title=f"Song {track}", game="FIFA International Soccer", dumper="snes-fifa",
                 comment="reassembled: custom SPC700 player", seconds=int(round(play_s)), fade_ms=10000)
    path = os.path.join(outdir, f"song{track:02d}_reassembled.spc")
    out.save(path)
    result.update({"fits": True, "file": os.path.basename(path), "player_addr": hex(region_a[0]),
                   "stream_start": hex(start), "used_bytes": len(player) + len(dict_bytes) + sum(len(b) for b in chunks.values())})
    if ref_wav:
        here = os.path.dirname(os.path.abspath(__file__))
        tool = os.path.join(os.environ.get("SNESFIFA_BUILD", os.path.join(here, "..", "build")), "spcreplay")
        wav = os.path.join(outdir, f"song{track:02d}_reassembled.wav")
        empty = os.path.join(outdir, ".empty.tsv")
        open(empty, "w").close()
        subprocess.run([tool, "--spc", path, "--log", empty, "--out", wav, "--seconds", str(play_s + 10)],
                       stderr=subprocess.DEVNULL, check=False)
        os.remove(empty)
        result["validate_vs_emu"] = xcorr.compare(ref_wav, wav, expected_lag_s=ref_lag_s)
    return result
