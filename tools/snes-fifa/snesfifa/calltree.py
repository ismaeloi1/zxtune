"""Build a call tree from a snestrace --exec-trace file (65816).

Each line of the trace is: frame pbpc A= X= Y= S= D= DB= P= E=
The opcode at each PC is read from the ROM (LoROM) or, for WRAM code, unknown (shown as '?').
"""
import argparse
import sys
from collections import Counter
from typing import Callable, Iterator, List, Optional, Tuple

from . import w65816


def parse_trace(path: str) -> Iterator[Tuple[int, int, dict]]:
    with open(path) as f:
        for line in f:
            p = line.split()
            regs = dict(kv.split("=") for kv in p[2:])
            yield int(p[0]), int(p[1], 16), regs


def call_tree(path: str, read: Callable[[int], int], max_depth: int = 64,
              only_frame: Optional[int] = None) -> List[str]:
    out: List[str] = []
    depth = 0
    pending_call = False
    for frame, pc, regs in parse_trace(path):
        if only_frame is not None and frame != only_frame:
            continue
        if pending_call:
            if depth < max_depth:
                out.append(f"{frame:5d} {'  ' * depth}{pc:06x}  A={regs['A']} X={regs['X']} Y={regs['Y']}")
            depth += 1
            pending_call = False
        if (pc & 0xFFFF) < 0x8000 and (pc >> 16) & 0x7F < 0x40:
            continue  # RAM / IO: opcode not known from ROM
        op = read(pc)
        if op in (0x20, 0x22, 0xFC):
            pending_call = True
        elif op in (0x60, 0x6B):
            depth = max(0, depth - 1)
        elif op == 0x40:
            depth = max(0, depth - 1)
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("rom")
    ap.add_argument("trace")
    ap.add_argument("--depth", type=int, default=8)
    ap.add_argument("--frame", type=int)
    a = ap.parse_args(argv)
    rom = open(a.rom, "rb").read()
    for line in call_tree(a.trace, w65816.lorom_reader(rom), a.depth, a.frame):
        print(line)


if __name__ == "__main__":
    main()
