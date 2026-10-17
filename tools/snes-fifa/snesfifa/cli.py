"""snes-fifa command line.

  python3 -m snesfifa.cli identify  --rom ROM
  python3 -m snesfifa.cli driver    --rom ROM --out DIR        # SPC700 driver image + disassembly
  python3 -m snesfifa.cli minimize  --rom ROM --out DIR        # find ROM regions not needed (slow)
  python3 -m snesfifa.cli snsf      --rom ROM --out DIR        # fifa.snsflib + fifa_NN.minisnsf
  python3 -m snesfifa.cli track     --rom ROM --out DIR -t N   # everything for one song
  python3 -m snesfifa.cli all       --rom ROM --out DIR        # snsf + all songs + summary

ROM defaults to $FIFA_ROM.  Outputs are derived from your own cartridge: keep them private.
"""
import argparse
import json
import os
import sys

from . import fifa, ipl, portlog, rip, snsf, spc700


def _rom(a) -> bytes:
    path = a.rom or os.environ.get("FIFA_ROM")
    if not path:
        sys.exit("no ROM: pass --rom or set FIFA_ROM")
    return open(path, "rb").read()


def cmd_identify(a):
    print(json.dumps(fifa.identify(_rom(a)), indent=1))


def cmd_driver(a):
    rom = _rom(a)
    os.makedirs(a.out, exist_ok=True)
    p = os.path.join(a.out, "boot.sfc")
    open(p, "wb").write(rom)
    log = os.path.join(a.out, "boot.tsv")
    rip.snestrace(p, 60, "--log", log)
    os.remove(p)
    sessions = ipl.parse_ipl(list(portlog.read_events(log)))
    info = []
    for s in sessions:
        for b in s.blocks:
            name = os.path.join(a.out, f"spc_driver_{b.addr:04x}.bin")
            open(name, "wb").write(b.data)
            with open(name[:-4] + ".asm", "w") as f:
                f.write("\n".join(spc700.disassemble(bytes(b.data), b.addr, b.addr + len(b.data), b.addr)) + "\n")
            info.append({"addr": hex(b.addr), "bytes": len(b.data), "jump": hex(s.jump or 0)})
    print(json.dumps(info, indent=1))


def _unneeded(a, rom):
    path = os.path.join(a.out, "minimize.json")
    if os.path.exists(path):
        return json.load(open(path))
    if getattr(a, "no_minimize", False):
        return []
    return cmd_minimize(a, rom)


def cmd_minimize(a, rom=None):
    from . import minimize
    rom = rom or _rom(a)
    os.makedirs(a.out, exist_ok=True)
    roms = {t: fifa.build_rom(rom, t) for t in fifa.SONGS}
    st = rip.tool("snestrace")
    keep = lambda lo, hi: lo < 0x8000  # bank $80: code, vectors, header, stub
    coarse = [(x, x + 0x8000) for x in range(0, len(rom), 0x8000)]
    un = minimize.find_unneeded(st, roms, coarse, a.frames, keep)
    need = [r for r in coarse if r not in un and r[0] >= 0x8000]
    fine = [(x, x + 0x1000) for s, e in need for x in range(s, e, 0x1000)]
    un += minimize.find_unneeded(st, roms, fine, a.frames, keep)
    json.dump(un, open(os.path.join(a.out, "minimize.json"), "w"))
    kept = len(rom) - sum(e - s for s, e in un)
    print(f"ROM bytes still needed: {kept} of {len(rom)}")
    return un


def cmd_snsf(a, loops=None):
    rom = _rom(a)
    os.makedirs(a.out, exist_ok=True)
    info = rip.build_snsf_set(rom, a.out, _unneeded(a, rom), loops)
    print(json.dumps(info, indent=1))
    return info


def cmd_track(a):
    rom = _rom(a)
    os.makedirs(a.out, exist_ok=True)
    if not os.path.exists(os.path.join(a.out, "fifa.snsflib")):
        cmd_snsf(a)
    for t in a.track:
        res = rip.rip_track(rom, t, os.path.join(a.out, f"song{t:02d}"), a.out, unneeded=_unneeded(a, rom))
        print(json.dumps({k: v for k, v in res.items() if k != "samples"}, indent=1, default=str))


def cmd_all(a):
    rom = _rom(a)
    os.makedirs(a.out, exist_ok=True)
    un = _unneeded(a, rom)
    rip.build_snsf_set(rom, a.out, un)
    results = {}
    for t in fifa.SONGS:
        results[t] = rip.rip_track(rom, t, os.path.join(a.out, f"song{t:02d}"), a.out, unneeded=un)
    # rebuild the minisnsf files with proper length tags
    info = rip.build_snsf_set(rom, a.out, un, {t: r for t, r in results.items()})
    summary = {"rom": fifa.identify(rom), "snsf": info,
               "songs": {t: {k: v for k, v in r.items() if k != "samples"} for t, r in results.items()}}
    json.dump(summary, open(os.path.join(a.out, "summary.json"), "w"), indent=1, default=str)
    print(json.dumps(summary, indent=1, default=str))


def main(argv=None):
    ap = argparse.ArgumentParser(prog="snes-fifa", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name, fn in (("identify", cmd_identify), ("driver", cmd_driver), ("minimize", cmd_minimize),
                     ("snsf", cmd_snsf), ("track", cmd_track), ("all", cmd_all)):
        p = sub.add_parser(name)
        p.add_argument("--rom")
        p.add_argument("--out", default="out")
        p.add_argument("--frames", type=int, default=6000, help="test window for minimize")
        p.add_argument("--no-minimize", action="store_true", help="pack the full ROM into the snsflib")
        if name == "track":
            p.add_argument("-t", "--track", type=int, action="append", required=True)
        p.set_defaults(fn=fn)
    a = ap.parse_args(argv)
    a.fn(a)


if __name__ == "__main__":
    main()
