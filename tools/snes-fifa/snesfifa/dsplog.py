"""Turn decoded driver commands into the S-DSP register writes the SPC700 driver performs,
detect song loops, and build per-frame event lists.

The mapping below is our reading of the driver's command handlers (docs/driver_protocol.md).
The software-envelope commands (19/20) are not used by the music of this game; they are
reported as unsupported if met.
"""
from dataclasses import dataclass
from typing import Dict, List, Optional, Sequence, Tuple

from .protocol import Command

KON, KOF, FLG = 0x4C, 0x5C, 0x6C


@dataclass
class DspWrite:
    frame: int
    clock: int
    reg: int
    value: int


def command_writes(c: Command, state: Dict[str, int]) -> List[Tuple[int, int]]:
    """DSP (reg, value) writes performed by one command. `state` keeps driver variables."""
    p = c.phases
    a = p[0]
    w: List[Tuple[int, int]] = []
    if c.cmd == 1 or c.cmd == 13:
        w.append((KOF, 0xFF))
    elif c.cmd == 5:
        v = a[1] & 7
        w += [(v * 16 + 7, 0xBF), (v * 16 + 6, 0), (v * 16 + 5, 0)]
    elif c.cmd == 6:
        v = a[1] & 7
        w += [(v * 16 + 2, a[2]), (v * 16 + 3, a[3]), (KOF, 0), (KON, 1 << v)]
    elif c.cmd == 7:
        v = a[1] & 7
        w += [(v * 16 + 0, a[2]), (v * 16 + 1, a[3])]
        if len(p) > 1:
            b = p[1]
            w += [(v * 16 + 4, b[1]), (v * 16 + 5, b[2]), (v * 16 + 6, b[3]), (v * 16 + 7, 0)]
    elif c.cmd == 8:
        w += [(0x0C, a[1]), (0x1C, a[2]), (0x2D, a[3])]
        if len(p) > 1:
            b = p[1]
            w += [(0x3D, b[1]), (0x4D, b[2]), (FLG, b[3] | state.get("flags", 0x20))]
    elif c.cmd == 9:
        w += [(0x2C, a[1]), (0x3C, a[2]), (0x0D, a[3])]
        if len(p) > 1:
            b = p[1]
            state["flags"] = 0
            w += [(0x6D, b[1]), (0x7D, b[2]), (FLG, 0)]
    elif c.cmd == 11:
        v = a[1] & 7
        w += [(v * 16 + 2, a[2]), (v * 16 + 3, a[3])]
    elif c.cmd == 14 and len(p) > 1:
        v = a[1] & 7
        b = p[1]
        scale = (b[1] << 1) & 0xFF
        w += [(v * 16 + 0, (b[2] * scale) >> 8), (v * 16 + 1, (b[3] * scale) >> 8)]
    elif c.cmd == 18:
        w += [(0x0C, a[1]), (0x1C, a[2])]
    elif c.cmd in (19, 20):
        state.setdefault("unsupported", 0)
        state["unsupported"] += 1
    return w


def dsp_writes(cmds: Sequence[Command]) -> Tuple[List[DspWrite], Dict[str, int]]:
    state: Dict[str, int] = {"flags": 0x20}
    out = []
    for c in cmds:
        for reg, val in command_writes(c, state):
            out.append(DspWrite(c.frame, c.clock, reg, val))
    return out, state


def dedupe(writes: Sequence[DspWrite], initial: Optional[bytes] = None) -> List[DspWrite]:
    """Drop writes that do not change a register (KON is a trigger and is always kept;
    KOF is kept when it changes)."""
    regs = list(initial) if initial is not None else [None] * 128
    out = []
    for w in writes:
        if w.reg == KON or regs[w.reg] != w.value:
            out.append(w)
            regs[w.reg] = w.value
    return out


def note_signature(cmds: Sequence[Command]) -> List[Tuple[int, tuple]]:
    """(frame, voice-independent tuple) for every KEY_ON, with the setup that preceded it."""
    setup: Dict[int, tuple] = {}
    sig = []
    for c in cmds:
        if c.cmd == 7 and len(c.phases) > 1:
            setup[c.phases[0][1] & 7] = (c.phases[0][2], c.phases[0][3]) + c.phases[1][1:]
        elif c.cmd == 6:
            v = c.phases[0][1] & 7
            sig.append((c.frame, setup.get(v, ()) + (c.phases[0][2], c.phases[0][3])))
    return sig


def find_loop(cmds: Sequence[Command], min_period: int = 20) -> Optional[Tuple[int, float, int, int]]:
    """Find the song loop from repeated note patterns (timing-independent: the 65816 tempo
    accumulator gives +-1 frame jitter between repetitions).
    Returns (loop_start_frame, loop_length_frames (mean), loop_start_note, period_notes)."""
    sig = [s for s in note_signature(cmds) if s[1]]
    n = len(sig)
    keys = [s[1] for s in sig]
    best = None
    for period in range(min_period, n // 2 + 1):
        m = sum(1 for i in range(n - period) if keys[i] == keys[i + period]) / (n - period)
        if best is None or m > best[0] + 1e-9:
            best = (m, period)
    if best is None or best[0] < 0.8:
        return None
    period = best[1]
    # earliest start s with a full period of matches
    run = 0
    s = None
    for i in range(n - period):
        run = run + 1 if keys[i] == keys[i + period] else 0
        if run >= period:
            s = i - period + 1
            break
    if s is None:
        # fall back: longest matching run
        s = max(range(n - period), key=lambda i: keys[i] == keys[i + period])
    diffs = sorted(sig[i + period][0] - sig[i][0] for i in range(s, n - period))
    length = sum(diffs) / len(diffs)
    return sig[s][0], length, s, period
