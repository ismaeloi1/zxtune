"""Decoder for the 65816 -> SPC700 command protocol of the EA Canada (Hatlelid/Stewart) driver
as found in FIFA International Soccer (SNES). See docs/driver_protocol.md.

Every time the SPC700 writes $F4 (an acknowledge) we take a snapshot of the four CPU->SPC
ports; the driver always reads its arguments right around its acknowledge and the 65816
holds them until it sees the ack, so the snapshot is what the driver consumed.
A command is the run of snapshots between two `$F4 = 0` (idle) writes.
"""
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Tuple

from .portlog import PortEvent

CMD_NAMES: Dict[int, str] = {
    1: "SAMPLE_RESET",       # rewind sample upload pointer to $087C, slot 0, KOFF all
    2: "SAMPLE_QUERY",       # reply F5=slot, F6/F7=upload pointer
    3: "SAMPLE_UPLOAD",      # F6/F7=loop offset; then 3-byte data stream; end: port0=0, F6/F7=ptr adjust
    4: "SAMPLE_FREE",        # F5=number of slots to drop
    5: "VOICE_CUT",          # F5=voice: GAIN=$BF (fast exp. decrease), ADSR off, env idle
    6: "KEY_ON",             # F5=voice F6/F7=pitch (P-low,P-high)
    7: "VOICE_SETUP",        # ph1: F5=voice F6=VOL L F7=VOL R; ph2: F5=SRCN F6=ADSR1 F7=ADSR2 (GAIN=0)
    8: "GLOBAL_SETUP",       # ph1: MVOL L/R=F5/F6, PMON=F7; ph2: NON=F5 EON=F6 FLG=F7|flags
    9: "ECHO_SETUP",         # ph1: EVOL L/R=F5/F6, EFB=F7; ph2: ESA=F5 EDL=F6, FLG echo enable
    10: "NOP10",
    11: "PITCH",             # F5=voice F6/F7=pitch
    12: "TIMER",             # timer0 target = F7 (envelope tick = F7 * 125 us)
    13: "KEY_OFF_ALL",
    14: "VOICE_VOLUME",      # ph1: F5=voice; ph2: F5=scale F6=VOL L F7=VOL R -> vol*scale*2/256
    15: "NOP15", 16: "NOP16", 17: "NOP17",
    18: "MAIN_VOLUME",       # MVOL L/R = F5/F6
    19: "ENVELOPE",          # ph1: F5=voice F6=level F7=attack rate lo; ph2: F5=attack hi F6/F7=decay rate;
                             # ph3: F5=sustain fraction
    20: "RELEASE",           # F5=voice F6/F7=release rate; replies with a time estimate in F5/F6
}


@dataclass
class Command:
    cmd: int
    phases: List[Tuple[int, int, int, int]]   # (port0, port1, port2, port3) seen at each ack
    clock: int                                # SPC clock of the first ack
    frame: int
    pc: int                                   # 65816 PC that wrote the command byte
    data: bytearray = field(default_factory=bytearray)   # for SAMPLE_UPLOAD
    reply: Optional[Tuple[int, int, int]] = None

    @property
    def name(self) -> str:
        return CMD_NAMES.get(self.cmd, f"CMD{self.cmd:02X}")

    def args(self) -> List[int]:
        return [b for ph in self.phases for b in ph[1:]]

    def describe(self) -> str:
        p = self.phases
        a = p[0]
        c = self.cmd
        if c == 3:
            return f"{self.name} loop={a[2] | a[3] << 8:#06x} bytes={len(self.data)}"
        if c == 6:
            return f"{self.name} v{a[1]} pitch={a[2] | a[3] << 8:#06x}"
        if c == 11:
            return f"{self.name} v{a[1]} pitch={a[2] | a[3] << 8:#06x}"
        if c == 5:
            return f"{self.name} v{a[1]}"
        if c == 7 and len(p) > 1:
            return f"{self.name} v{a[1]} vol={a[2]},{a[3]} srcn={p[1][1]} adsr={p[1][2]:02x},{p[1][3]:02x}"
        if c == 14 and len(p) > 1:
            return f"{self.name} v{a[1]} scale={p[1][1]} vol={p[1][2]},{p[1][3]}"
        if c == 19 and len(p) > 2:
            return (f"{self.name} v{a[1]} level={a[2]} attack={a[3] | p[1][1] << 8:#06x} "
                    f"decay={p[1][2] | p[1][3] << 8:#06x} sustain={p[2][1]}")
        if c == 20:
            return f"{self.name} v{a[1]} rate={a[2] | a[3] << 8:#06x}"
        if c == 12:
            return f"{self.name} t0={a[3]}"
        return f"{self.name} " + " ".join(f"{x:02x}" for x in self.args())


def decode(events: Iterable[PortEvent], after_clock: int = 0) -> List[Command]:
    """Decode driver commands from a port log (events after the driver was started)."""
    ports = [0, 0, 0, 0]
    cmds: List[Command] = []
    cur: Optional[Command] = None
    last_cmd_pc = 0
    out = [0, 0, 0, 0]
    for e in events:
        if e.clock < after_clock:
            if e.kind == "CW":
                ports[e.port] = e.value
            continue
        if e.kind == "CW":
            ports[e.port] = e.value
            if e.port == 0:
                last_cmd_pc = e.pc
            continue
        if e.kind != "SW":
            continue
        out[e.port] = e.value
        if e.port != 0:
            continue
        v = e.value
        if v == 0:
            if cur is not None:
                cmds.append(cur)
            cur = None
            continue
        snap = (ports[0], ports[1], ports[2], ports[3])
        if cur is None:
            cur = Command(ports[0], [snap], e.clock, e.frame, last_cmd_pc)
            if cur.cmd == 2:
                cur.reply = (out[1], out[2], out[3])
            continue
        if cur.cmd == 3 and v >= 0x80:
            cur.data += bytes(snap[1:])
            continue
        cur.phases.append(snap)
    if cur is not None:
        cmds.append(cur)
    return cmds
