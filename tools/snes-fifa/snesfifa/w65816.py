"""65C816 disassembler with M/X flag tracking (REP/SEP), used to study the game's sound code."""
from typing import Callable, Dict, List, Optional, Tuple

# addressing modes: name -> (operand bytes or None for flag-dependent, format)
MODES = {
    "imp": (0, ""), "acc": (0, "a"), "immm": (None, "#${v}"), "immx": (None, "#${v}"),
    "imm8": (1, "#${v}"), "rel": (1, "${t}"), "rell": (2, "${t}"),
    "dp": (1, "${v}"), "dpx": (1, "${v},x"), "dpy": (1, "${v},y"), "idp": (1, "(${v})"),
    "idpx": (1, "(${v},x)"), "idpy": (1, "(${v}),y"), "ildp": (1, "[${v}]"), "ildpy": (1, "[${v}],y"),
    "abs": (2, "${v}"), "absx": (2, "${v},x"), "absy": (2, "${v},y"), "long": (3, "${v}"),
    "longx": (3, "${v},x"), "sr": (1, "${v},s"), "isry": (1, "(${v},s),y"), "iabs": (2, "(${v})"),
    "ilabs": (2, "[${v}]"), "iabsx": (2, "(${v},x)"), "move": (2, "${s},${d}"),
}

_ALU = ["ora", "and", "eor", "adc", "sta", "lda", "cmp", "sbc"]


def _build() -> List[Tuple[str, str]]:
    t: List[Optional[Tuple[str, str]]] = [None] * 256
    for k, m in enumerate(_ALU):
        r = k * 0x20
        for col, mode in ((1, "idpx"), (3, "sr"), (5, "dp"), (7, "ildp"), (9, "immm"), (0xD, "abs"), (0xF, "long")):
            t[r + col] = (m, mode)
        for col, mode in ((1, "idpy"), (2, "idp"), (3, "isry"), (5, "dpx"), (7, "ildpy"), (9, "absy"),
                          (0xD, "absx"), (0xF, "longx")):
            t[r + 0x10 + col] = (m, mode)
    t[0x89] = ("bit", "immm")
    for k, b in enumerate(["bpl", "bmi", "bvc", "bvs", "bcc", "bcs", "bne", "beq"]):
        t[0x10 + k * 0x20] = (b, "rel")
    rest = {
        0x00: ("brk", "imm8"), 0x02: ("cop", "imm8"), 0x04: ("tsb", "dp"), 0x06: ("asl", "dp"),
        0x08: ("php", "imp"), 0x0A: ("asl", "acc"), 0x0B: ("phd", "imp"), 0x0C: ("tsb", "abs"),
        0x0E: ("asl", "abs"), 0x14: ("trb", "dp"), 0x16: ("asl", "dpx"), 0x18: ("clc", "imp"),
        0x1A: ("inc", "acc"), 0x1B: ("tcs", "imp"), 0x1C: ("trb", "abs"), 0x1E: ("asl", "absx"),
        0x20: ("jsr", "abs"), 0x22: ("jsl", "long"), 0x24: ("bit", "dp"), 0x26: ("rol", "dp"),
        0x28: ("plp", "imp"), 0x2A: ("rol", "acc"), 0x2B: ("pld", "imp"), 0x2C: ("bit", "abs"),
        0x2E: ("rol", "abs"), 0x34: ("bit", "dpx"), 0x36: ("rol", "dpx"), 0x38: ("sec", "imp"),
        0x3A: ("dec", "acc"), 0x3B: ("tsc", "imp"), 0x3C: ("bit", "absx"), 0x3E: ("rol", "absx"),
        0x40: ("rti", "imp"), 0x42: ("wdm", "imm8"), 0x44: ("mvp", "move"), 0x46: ("lsr", "dp"),
        0x48: ("pha", "imp"), 0x4A: ("lsr", "acc"), 0x4B: ("phk", "imp"), 0x4C: ("jmp", "abs"),
        0x4E: ("lsr", "abs"), 0x54: ("mvn", "move"), 0x56: ("lsr", "dpx"), 0x58: ("cli", "imp"),
        0x5A: ("phy", "imp"), 0x5B: ("tcd", "imp"), 0x5C: ("jml", "long"), 0x5E: ("lsr", "absx"),
        0x60: ("rts", "imp"), 0x62: ("per", "rell"), 0x64: ("stz", "dp"), 0x66: ("ror", "dp"),
        0x68: ("pla", "imp"), 0x6A: ("ror", "acc"), 0x6B: ("rtl", "imp"), 0x6C: ("jmp", "iabs"),
        0x6E: ("ror", "abs"), 0x74: ("stz", "dpx"), 0x76: ("ror", "dpx"), 0x78: ("sei", "imp"),
        0x7A: ("ply", "imp"), 0x7B: ("tdc", "imp"), 0x7C: ("jmp", "iabsx"), 0x7E: ("ror", "absx"),
        0x80: ("bra", "rel"), 0x82: ("brl", "rell"), 0x84: ("sty", "dp"), 0x86: ("stx", "dp"),
        0x88: ("dey", "imp"), 0x8A: ("txa", "imp"), 0x8B: ("phb", "imp"), 0x8C: ("sty", "abs"),
        0x8E: ("stx", "abs"), 0x94: ("sty", "dpx"), 0x96: ("stx", "dpy"), 0x98: ("tya", "imp"),
        0x9A: ("txs", "imp"), 0x9B: ("txy", "imp"), 0x9C: ("stz", "abs"), 0x9E: ("stz", "absx"),
        0xA0: ("ldy", "immx"), 0xA2: ("ldx", "immx"), 0xA4: ("ldy", "dp"), 0xA6: ("ldx", "dp"),
        0xA8: ("tay", "imp"), 0xAA: ("tax", "imp"), 0xAB: ("plb", "imp"), 0xAC: ("ldy", "abs"),
        0xAE: ("ldx", "abs"), 0xB4: ("ldy", "dpx"), 0xB6: ("ldx", "dpy"), 0xB8: ("clv", "imp"),
        0xBA: ("tsx", "imp"), 0xBB: ("tyx", "imp"), 0xBC: ("ldy", "absx"), 0xBE: ("ldx", "absy"),
        0xC0: ("cpy", "immx"), 0xC2: ("rep", "imm8"), 0xC4: ("cpy", "dp"), 0xC6: ("dec", "dp"),
        0xC8: ("iny", "imp"), 0xCA: ("dex", "imp"), 0xCB: ("wai", "imp"), 0xCC: ("cpy", "abs"),
        0xCE: ("dec", "abs"), 0xD4: ("pei", "idp"), 0xD6: ("dec", "dpx"), 0xD8: ("cld", "imp"),
        0xDA: ("phx", "imp"), 0xDB: ("stp", "imp"), 0xDC: ("jml", "ilabs"), 0xDE: ("dec", "absx"),
        0xE0: ("cpx", "immx"), 0xE2: ("sep", "imm8"), 0xE4: ("cpx", "dp"), 0xE6: ("inc", "dp"),
        0xE8: ("inx", "imp"), 0xEA: ("nop", "imp"), 0xEB: ("xba", "imp"), 0xEC: ("cpx", "abs"),
        0xEE: ("inc", "abs"), 0xF4: ("pea", "abs"), 0xF6: ("inc", "dpx"), 0xF8: ("sed", "imp"),
        0xFA: ("plx", "imp"), 0xFB: ("xce", "imp"), 0xFC: ("jsr", "iabsx"), 0xFE: ("inc", "absx"),
    }
    for k, v in rest.items():
        t[k] = v
    for i, e in enumerate(t):
        assert e is not None, hex(i)
    return t  # type: ignore


OPCODES: List[Tuple[str, str]] = _build()


def insn_length(op: int, m16: bool, x16: bool) -> int:
    mode = OPCODES[op][1]
    n = MODES[mode][0]
    if n is None:
        n = 2 if (m16 if mode == "immm" else x16) else 1
    return 1 + n


def format_insn(read: Callable[[int], int], addr: int, m16: bool = False, x16: bool = False) -> Tuple[str, int, str]:
    """Disassemble at 24-bit address `addr` using read(addr)->byte. Returns (text, length, mode)."""
    op = read(addr)
    mn, mode = OPCODES[op]
    n = insn_length(op, m16, x16)
    ops = [read((addr & 0xFF0000) | ((addr + 1 + k) & 0xFFFF)) for k in range(n - 1)]
    v = 0
    for k, b in enumerate(ops):
        v |= b << (8 * k)
    fmt = MODES[mode][1]
    width = 2 * (n - 1)
    if mode == "rel":
        t = (addr & 0xFF0000) | ((addr + 2 + (v - 256 if v >= 128 else v)) & 0xFFFF)
        s = fmt.replace("{t}", f"{t & 0xFFFF:04x}")
    elif mode == "rell":
        t = (addr & 0xFF0000) | ((addr + 3 + (v - 65536 if v >= 32768 else v)) & 0xFFFF)
        s = fmt.replace("{t}", f"{t & 0xFFFF:04x}")
    elif mode == "move":
        s = fmt.replace("{s}", f"{ops[1]:02x}").replace("{d}", f"{ops[0]:02x}")
    else:
        s = fmt.replace("{v}", f"{v:0{width}x}") if width else fmt
    return (f"{mn} {s}".strip(), n, mode)


def disassemble(read: Callable[[int], int], start: int, end: int, m16: bool = False, x16: bool = False,
                labels: Optional[Dict[int, str]] = None) -> List[str]:
    """Linear disassembly with REP/SEP tracking. Addresses are 24-bit CPU addresses."""
    out = []
    pc = start
    while pc < end:
        op = read(pc)
        text, n, mode = format_insn(read, pc, m16, x16)
        raw = " ".join(f"{read((pc & 0xFF0000) | ((pc + k) & 0xFFFF)):02x}" for k in range(n))
        if labels and pc in labels:
            out.append(f"{labels[pc]}:")
        out.append(f"{pc >> 16:02x}:{pc & 0xFFFF:04x}  {raw:<12} {text}")
        if op == 0xC2:
            v = read(pc + 1)
            m16 = m16 or bool(v & 0x20)
            x16 = x16 or bool(v & 0x10)
        elif op == 0xE2:
            v = read(pc + 1)
            m16 = m16 and not (v & 0x20)
            x16 = x16 and not (v & 0x10)
        pc = (pc & 0xFF0000) | ((pc + n) & 0xFFFF)
        if (pc & 0xFFFF) < n:  # wrapped
            break
    return out


def lorom_reader(rom: bytes) -> Callable[[int], int]:
    """Return read(addr) for a LoROM image (banks $00-$7D / $80-$FF, $8000-$FFFF)."""
    def read(addr: int) -> int:
        bank = (addr >> 16) & 0x7F
        off = addr & 0xFFFF
        if off < 0x8000:
            return 0
        i = bank * 0x8000 + (off - 0x8000)
        return rom[i % len(rom)]
    return read


def lorom_offset(addr: int) -> int:
    return ((addr >> 16) & 0x7F) * 0x8000 + ((addr & 0xFFFF) - 0x8000)


def lorom_address(offset: int, fast: bool = True) -> int:
    bank = offset // 0x8000
    return ((0x80 if fast else 0) | bank) << 16 | (0x8000 + offset % 0x8000)
