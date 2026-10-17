"""SPC file helpers: parse, patch registers, write ID666 text tags."""
import struct
from dataclasses import dataclass
from typing import Optional

SPC_SIZE = 0x10200


@dataclass
class Spc:
    raw: bytearray

    @classmethod
    def load(cls, path: str) -> "Spc":
        d = bytearray(open(path, "rb").read())
        if not d.startswith(b"SNES-SPC700 Sound File Data") or len(d) < 0x10180:
            raise ValueError("not an SPC file")
        if len(d) < SPC_SIZE:
            d += b"\x00" * (SPC_SIZE - len(d))
        return cls(d)

    @classmethod
    def build(cls, ram: bytes, dsp: bytes, pc: int, a: int = 0, x: int = 0, y: int = 0, psw: int = 0x02,
              sp: int = 0xEF) -> "Spc":
        d = bytearray(SPC_SIZE)
        d[0:33] = b"SNES-SPC700 Sound File Data v0.30"
        d[0x21:0x24] = b"\x1a\x1a\x1a"
        d[0x24] = 30
        d[0x25:0x27] = struct.pack("<H", pc)
        d[0x27], d[0x28], d[0x29], d[0x2A], d[0x2B] = a, x, y, psw, sp
        d[0x100:0x10100] = ram
        d[0x10100:0x10180] = dsp
        return cls(d)

    @property
    def ram(self) -> memoryview:
        return memoryview(self.raw)[0x100:0x10100]

    @property
    def dsp(self) -> memoryview:
        return memoryview(self.raw)[0x10100:0x10180]

    @property
    def pc(self) -> int:
        return self.raw[0x25] | self.raw[0x26] << 8

    def set_tags(self, title: str = "", game: str = "", dumper: str = "", comment: str = "",
                 seconds: int = 0, fade_ms: int = 0, artist: str = "") -> None:
        d = self.raw
        d[0x23] = 26  # ID666 present
        def put(off, size, text):
            b = text.encode("latin-1", "replace")[:size]
            d[off:off + size] = b + b"\x00" * (size - len(b))
        put(0x2E, 32, title)
        put(0x4E, 32, game)
        put(0x6E, 16, dumper)
        put(0x7E, 32, comment)
        put(0x9E, 11, "")
        put(0xA9, 3, str(min(seconds, 999)))
        put(0xAC, 5, str(min(fade_ms, 99999)))
        put(0xB1, 32, artist)

    def save(self, path: str) -> None:
        with open(path, "wb") as f:
            f.write(self.raw)


def fix_timer_registers(spc: Spc, control: int, t0: Optional[int] = None, t1: Optional[int] = None,
                        t2: Optional[int] = None) -> None:
    """Write-only timer targets are not readable on hardware, so emulator snapshots often
    store 0 there; restore known values ($F1 control, $FA-$FC targets)."""
    r = spc.raw
    r[0x100 + 0xF1] = control
    for off, v in ((0xFA, t0), (0xFB, t1), (0xFC, t2)):
        if v is not None:
            r[0x100 + off] = v
