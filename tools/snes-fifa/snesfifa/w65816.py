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


def load_coverage(path: str) -> Dict[int, Tuple[bool, bool]]:
    """Read a snestrace --coverage file: 'pc m x count' (m/x = 1 means 8-bit)."""
    flags: Dict[int, Tuple[bool, bool]] = {}
    with open(path) as f:
        for line in f:
            p = line.split()
            if len(p) >= 4:
                flags[int(p[0], 16)] = (p[1] == "0", p[2] == "0")  # (m16, x16)
    return flags


def disassemble_traced(read: Callable[[int], int], start: int, end: int,
                       flags: Dict[int, Tuple[bool, bool]]) -> List[str]:
    """Disassemble using M/X flags observed at run time; unexecuted code falls back to
    REP/SEP tracking from the last executed instruction. Unexecuted lines are marked '?'."""
    out = []
    pc = start
    m16 = x16 = False
    while pc < end:
        seen = pc in flags
        if seen:
            m16, x16 = flags[pc]
        op = read(pc)
        text, n, _ = format_insn(read, pc, m16, x16)
        raw = " ".join(f"{read((pc & 0xFF0000) | ((pc + k) & 0xFFFF)):02x}" for k in range(n))
        out.append(f"{' ' if seen else '?'}{pc >> 16:02x}:{pc & 0xFFFF:04x}  {raw:<12} {text}")
        if op == 0xC2:
            v = read(pc + 1)
            m16 = m16 or bool(v & 0x20)
            x16 = x16 or bool(v & 0x10)
        elif op == 0xE2:
            v = read(pc + 1)
            m16 = m16 and not (v & 0x20)
            x16 = x16 and not (v & 0x10)
        pc += n
    return out


# ---------------------------------------------------------------- assembler
_BRANCH8 = {"bpl", "bmi", "bvc", "bvs", "bcc", "bcs", "bne", "beq", "bra"}
_BRANCH16 = {"brl", "per"}


def _operand_mode(mn: str, opnd: str):
    """Classify operand syntax -> (mode candidates, value string(s))."""
    import re
    o = opnd.replace(" ", "")
    if o == "":
        return ["imp"], []
    if o == "a":
        return ["acc"], []
    m = re.fullmatch(r"#\$([0-9a-f]+)", o)
    if m:
        return ["immm", "immx", "imm8"], [m.group(1)]
    if mn in _BRANCH8 and re.fullmatch(r"\$[0-9a-f]+", o):
        return ["rel"], [o[1:]]
    if mn in _BRANCH16 and re.fullmatch(r"\$[0-9a-f]+", o):
        return ["rell"], [o[1:]]
    m = re.fullmatch(r"\$([0-9a-f]+),\$([0-9a-f]+)", o)
    if m and mn in ("mvn", "mvp"):
        return ["move"], [m.group(1), m.group(2)]
    pats = [
        (r"\$([0-9a-f]+)", {2: "dp", 4: "abs", 6: "long"}),
        (r"\$([0-9a-f]+),x", {2: "dpx", 4: "absx", 6: "longx"}),
        (r"\$([0-9a-f]+),y", {2: "dpy", 4: "absy"}),
        (r"\$([0-9a-f]+),s", {2: "sr"}),
        (r"\(\$([0-9a-f]+)\)", {2: "idp", 4: "iabs"}),
        (r"\(\$([0-9a-f]+),x\)", {2: "idpx", 4: "iabsx"}),
        (r"\(\$([0-9a-f]+)\),y", {2: "idpy"}),
        (r"\(\$([0-9a-f]+),s\),y", {2: "isry"}),
        (r"\[\$([0-9a-f]+)\]", {2: "ildp", 4: "ilabs"}),
        (r"\[\$([0-9a-f]+)\],y", {2: "ildpy"}),
    ]
    for rx, sizes in pats:
        m = re.fullmatch(rx, o)
        if m:
            mode = sizes.get(len(m.group(1)))
            if mode is None:
                raise ValueError(f"bad operand width: {mn} {opnd}")
            return [mode], [m.group(1)]
    raise ValueError(f"cannot parse operand: {mn} {opnd}")


def assemble(src: str, org: int, symbols: Optional[Dict[str, int]] = None) -> bytes:
    """Two-pass 65816 assembler for small stubs. Syntax as produced by the disassembler
    (lowercase, `$hex`, width of the hex literal selects dp/abs/long and imm8/imm16).
    Labels: `name:`; references: `name` (16-bit), `<name` (low byte), `>name` (24-bit),
    `^name` (bank byte). Directives: `.db`, `.dw`, `.dl`."""
    import re
    index = {}
    for op, (mn, mode) in enumerate(OPCODES):
        index.setdefault((mn, mode), op)
    lines = [l.split(";")[0].strip().lower() for l in src.splitlines()]
    lines = [l for l in lines if l]
    labels: Dict[str, int] = {}

    def sub(text, final):
        def rep(m):
            pre, name = m.group(1), m.group(2)
            if name in labels:
                v = labels[name]
            elif final:
                raise ValueError(f"undefined label {name}")
            else:
                v = 0
            if pre == "<":
                return f"${v & 0xFF:02x}"
            if pre == ">":
                return f"${v & 0xFFFFFF:06x}"
            if pre == "^":
                return f"${v >> 16 & 0xFF:02x}"
            return f"${v & 0xFFFF:04x}"
        return re.sub(r"([<>^]?)(?<![$0-9a-z_])([a-z_][a-z0-9_]*)\b", lambda m: rep(m) if m.group(2) not in ("a", "x", "y", "s") else m.group(0), text)

    for final in (False, True):
        pc = org
        out = bytearray()
        for line in lines:
            if line.endswith(":"):
                labels[line[:-1]] = pc
                continue
            mn, _, opnd = line.partition(" ")
            if mn in (".db", ".dw", ".dl"):
                width = {".db": 1, ".dw": 2, ".dl": 3}[mn]
                for item in opnd.split(","):
                    item = sub(item.strip(), final)
                    v = int(item[1:], 16) if item.startswith("$") else int(item, 0)
                    out += v.to_bytes(width, "little")
                    pc += width
                continue
            opnd = sub(opnd, final) if opnd else ""
            modes, vals = _operand_mode(mn, opnd)
            code = None
            for md in modes:
                if (mn, md) in index:
                    code, mode = index[(mn, md)], md
                    break
            if code is None and modes[0] == "dp" and (mn, "abs") in index:
                code, mode = index[(mn, "abs")], "abs"
            if code is None:
                raise ValueError(f"no opcode for {line}")
            b = bytearray([code])
            if mode in ("rel", "rell"):
                tgt = int(vals[0], 16)
                n = 2 if mode == "rel" else 3
                d = (tgt & 0xFFFF) - ((pc + n) & 0xFFFF)
                if mode == "rel":
                    if final and not -128 <= d <= 127:
                        raise ValueError(f"branch out of range: {line}")
                    b.append(d & 0xFF)
                else:
                    b += (d & 0xFFFF).to_bytes(2, "little")
            elif mode == "move":
                b += bytes([int(vals[1], 16), int(vals[0], 16)])
            elif vals:
                v = int(vals[0], 16)
                width = len(vals[0]) // 2 if mode in ("immm", "immx", "imm8") else {
                    "dp": 1, "dpx": 1, "dpy": 1, "sr": 1, "idp": 1, "idpx": 1, "idpy": 1, "isry": 1,
                    "ildp": 1, "ildpy": 1, "abs": 2, "absx": 2, "absy": 2, "iabs": 2, "iabsx": 2,
                    "ilabs": 2, "long": 3, "longx": 3}[mode]
                if mode == "imm8":
                    width = 1
                b += v.to_bytes(width, "little")
            out += b
            pc += len(b)
    if symbols is not None:
        symbols.update(labels)
    return bytes(out)
