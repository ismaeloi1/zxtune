"""Reconstruct SPC700 IPL-ROM uploads from a port log.

The IPL protocol (boot ROM at $FFC0):
  * SPC signals ready with $F4=$AA, $F5=$BB.
  * 65816 puts destination in $2142/$2143, non-zero in $2141, $CC in $2140.
  * Each byte: data in $2141, index (0,1,2,...) in $2140; SPC echoes index.
  * Next block: new address in $2142/3, $2141 non-zero, $2140 = index+2..+127
    (anything 'ahead' of the expected index, signed compare).
  * Execute: $2141 = 0, $2142/3 = entry point, $2140 = index+2..
Writes that hit the SPC at the same clock (e.g. 16-bit stores) are applied together.
"""
from dataclasses import dataclass, field
from typing import List, Optional

from .portlog import PortEvent


@dataclass
class Block:
    addr: int
    data: bytearray = field(default_factory=bytearray)
    start_clock: int = 0
    end_clock: int = 0
    pc: int = 0


@dataclass
class Session:
    """One IPL session: from the $AA/$BB ready signature to the jump."""
    ready_clock: int
    blocks: List[Block] = field(default_factory=list)
    jump: Optional[int] = None
    jump_clock: Optional[int] = None


def _groups(events):
    cur = None
    batch = []
    for e in events:
        if cur is not None and e.clock != cur:
            yield cur, batch
            batch = []
        cur = e.clock
        batch.append(e)
    if batch:
        yield cur, batch


def parse_ipl(events: List[PortEvent], initially_ready: bool = True) -> List[Session]:
    """Follow the IPL protocol. Data bytes are taken from the 65816's $2141 value at the
    moment the SPC700 echoes the index on $F4 (the IPL reads $F5 right before echoing),
    which is robust against 16-bit port writes that straddle SPC clock ticks."""
    sessions: List[Session] = []
    ports = [0, 0, 0, 0]
    state = "ready" if initially_ready else "off"
    sess = Session(0) if initially_ready else None
    block = None
    expected = 0
    pending_cmd = None  # port0 value of a command the SPC has not yet acknowledged
    out = [0, 0, 0, 0]
    for e in events:
        if e.kind == "CW":
            ports[e.port] = e.value
            if e.port != 0:
                continue
            v = e.value
            if state == "ready" and v == 0xCC:
                pending_cmd = v
            elif state == "data" and v != expected and ((v - expected) & 0xFF) < 0x80:
                pending_cmd = v
            continue
        if e.kind != "SW":
            continue
        out[e.port] = e.value
        if state == "off":
            if out[0] == 0xAA and out[1] == 0xBB:
                state = "ready"
                sess = Session(e.clock)
            continue
        if e.port != 0:
            continue
        if pending_cmd is not None and e.value == pending_cmd:
            pending_cmd = None
            addr = ports[2] | ports[3] << 8
            if ports[1]:
                block = Block(addr, start_clock=e.clock, pc=e.pc)
                sess.blocks.append(block)
                state, expected = "data", 0
            else:
                sess.jump, sess.jump_clock = addr, e.clock
                sessions.append(sess)
                state, sess, block = "off", None, None
                out = [0, 0, 0, 0]
        elif state == "data" and e.value == expected:
            block.data.append(ports[1])
            block.end_clock = e.clock
            expected = (expected + 1) & 0xFF
    if sess is not None and sess.blocks:
        sessions.append(sess)
    return sessions


def apply_blocks(sessions: List[Session], ram: Optional[bytearray] = None) -> bytearray:
    ram = ram if ram is not None else bytearray(0x10000)
    for s in sessions:
        for b in s.blocks:
            for i, v in enumerate(b.data):
                ram[(b.addr + i) & 0xFFFF] = v
    return ram
