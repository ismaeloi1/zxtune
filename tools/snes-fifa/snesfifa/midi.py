"""Transcribe the driver command stream into a Standard MIDI File.

This is a transcription of what the 65816 sequencer *sent* to the SPC700, not a decode of the
65816-side song data: one MIDI channel per sample (SRCN), note number from the DSP pitch
(pitch $1000 = the sample's own rate -> MIDI note 60 + 12*log2(P/$1000) + per-sample offset),
velocity from the voice volume, pan from the L/R balance, pitch bends for in-note pitch
changes (bend range +-12 semitones), note off at VOICE_CUT / re-trigger / KEY_OFF_ALL.
Timing: 1 tick = 1 ms (PPQ 500 at 120 BPM) from the SPC clock (~1.0253 MHz in snes9x).
"""
import math
import struct
from typing import Dict, List, Optional, Sequence, Tuple

from .protocol import Command

SPC_CLOCK = 1025290.0  # snes9x SMP clock rate (32040 Hz * 32)


def _vlq(n: int) -> bytes:
    out = [n & 0x7F]
    n >>= 7
    while n:
        out.append(0x80 | (n & 0x7F))
        n >>= 7
    return bytes(reversed(out))


def _signed8(v: int) -> int:
    return v - 256 if v >= 128 else v


def pitch_to_note(p: int) -> float:
    return 60.0 + 12.0 * math.log2(max(p, 1) / 0x1000)


def transcribe(cmds: Sequence[Command], start_clock: int, end_clock: Optional[int] = None,
               root_offsets: Optional[Dict[int, float]] = None, names: Optional[Dict[int, str]] = None,
               title: str = "") -> bytes:
    root_offsets = root_offsets or {}
    events: List[Tuple[int, int, bytes]] = []  # (tick, order, bytes)
    order = 0

    def ev(clock, data):
        nonlocal order
        tick = int(round((clock - start_clock) / SPC_CLOCK * 1000))
        events.append((max(tick, 0), order, data))
        order += 1

    chan_of: Dict[int, int] = {}

    def channel(srcn):
        if srcn not in chan_of:
            free = [c for c in range(16) if c != 9 and c not in chan_of.values()]
            chan_of[srcn] = free[0] if free else len(chan_of) % 16
            c = chan_of[srcn]
            ev(start_clock, bytes([0xC0 | c, srcn & 0x7F]))
            # bend range 12 semitones
            ev(start_clock, bytes([0xB0 | c, 101, 0, 0xB0 | c, 100, 0, 0xB0 | c, 6, 12, 0xB0 | c, 38, 0]))
        return chan_of[srcn]

    setup: Dict[int, Tuple[int, int, int]] = {}  # voice -> (srcn, volL, volR)
    playing: Dict[int, Tuple[int, int, float]] = {}  # voice -> (channel, note, base pitch note)

    def note_off(v, clock):
        if v in playing:
            c, n, _ = playing.pop(v)
            ev(clock, bytes([0x80 | c, n, 0]))

    for cmd in cmds:
        if cmd.clock < start_clock:
            continue
        if end_clock is not None and cmd.clock > end_clock:
            break
        a = cmd.phases[0]
        if cmd.cmd == 7 and len(cmd.phases) > 1:
            setup[a[1] & 7] = (cmd.phases[1][1], a[2], a[3])
        elif cmd.cmd == 6:
            v = a[1] & 7
            note_off(v, cmd.clock)
            srcn, vl, vr = setup.get(v, (0, 64, 64))
            c = channel(srcn)
            nf = pitch_to_note(a[2] | a[3] << 8) + root_offsets.get(srcn, 0.0)
            n = min(127, max(0, int(round(nf))))
            l, r = abs(_signed8(vl)), abs(_signed8(vr))
            vel = min(127, max(1, int(round(127 * math.sqrt(max(l, r) / 127.0)))))
            pan = 64 if l + r == 0 else min(127, max(0, int(round(127 * r / (l + r)))))
            ev(cmd.clock, bytes([0xB0 | c, 10, pan]))
            ev(cmd.clock, bytes([0xE0 | c, 0x00, 0x40]))
            ev(cmd.clock, bytes([0x90 | c, n, vel]))
            playing[v] = (c, n, nf)
        elif cmd.cmd == 11:
            v = a[1] & 7
            if v in playing:
                c, n, base = playing[v]
                nf = pitch_to_note(a[2] | a[3] << 8) + (base - pitch_to_note(a[2] | a[3] << 8)) * 0
                srcn = next((s for s, ch in chan_of.items() if ch == c), 0)
                cur = pitch_to_note(a[2] | a[3] << 8) + root_offsets.get(srcn, 0.0)
                bend = int(round(8192 + (cur - n) / 12.0 * 8192))
                bend = min(16383, max(0, bend))
                ev(cmd.clock, bytes([0xE0 | c, bend & 0x7F, bend >> 7]))
        elif cmd.cmd == 5:
            note_off(a[1] & 7, cmd.clock)
        elif cmd.cmd in (1, 13):
            for v in list(playing):
                note_off(v, cmd.clock)
        elif cmd.cmd == 14 and len(cmd.phases) > 1:
            v = a[1] & 7
            if v in playing:
                b = cmd.phases[1]
                scale = (b[1] << 1) & 0xFF
                l, r = abs(_signed8((b[2] * scale) >> 8)), abs(_signed8((b[3] * scale) >> 8))
                c = playing[v][0]
                ev(cmd.clock, bytes([0xB0 | c, 11, min(127, int(round(127 * math.sqrt(max(l, r) / 127.0))))]))
    last = max((e[0] for e in events), default=0)
    for v in list(playing):
        c, n, _ = playing.pop(v)
        events.append((last, order, bytes([0x80 | c, n, 0])))
        order += 1
    events.sort(key=lambda e: (e[0], e[1]))
    trk = bytearray()
    if title:
        t = title.encode("latin-1", "replace")
        trk += b"\x00\xff\x03" + _vlq(len(t)) + t
    trk += b"\x00\xff\x51\x03" + struct.pack(">I", 500000)[1:]
    if names:
        for srcn, c in sorted(chan_of.items(), key=lambda x: x[1]):
            nm = names.get(srcn, f"sample {srcn:02d}").encode("latin-1", "replace")
            trk += b"\x00\xff\x04" + _vlq(len(nm)) + nm
    now = 0
    for tick, _, data in events:
        # split multi-message blobs (each message 3 bytes, or 2 for program change)
        msgs = []
        i = 0
        while i < len(data):
            st = data[i]
            n = 2 if st & 0xF0 in (0xC0, 0xD0) else 3
            msgs.append(data[i:i + n])
            i += n
        for m in msgs:
            trk += _vlq(tick - now) + m
            now = tick
    trk += b"\x00\xff\x2f\x00"
    return b"MThd" + struct.pack(">IHHH", 6, 0, 1, 500) + b"MTrk" + struct.pack(">I", len(trk)) + bytes(trk)
