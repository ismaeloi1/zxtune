"""SPC700 disassembler (and a tiny table-driven assembler used for test programs).

Operand tokens in templates:
  {d}  direct page byte (1st operand byte)       {D} direct page byte (2nd operand byte)
  {i}  immediate (1st operand byte)              {w} 16-bit absolute (operand bytes 1-2)
  {r}  relative branch (last operand byte)       {m} mem.bit (13-bit address + 3-bit bit no.)
  {u}  PCALL upage ($FF00+byte)
Syntax: direct page `$12`, absolute `!$1234`, indirect `[$12+x]` / `[$12]+y`.
"""
import re
from typing import Dict, List, Optional, Tuple

_ALU = ["or", "and", "eor", "cmp", "adc", "sbc"]


def _build() -> List[Tuple[str, int]]:
    t: List[Optional[str]] = [None] * 256
    # columns 4-9 of rows 0..b: ALU ops on A
    for k, op in enumerate(_ALU):
        even, odd = k * 0x20, k * 0x20 + 0x10
        t[even + 4] = f"{op} a,{{d}}"
        t[even + 5] = f"{op} a,!{{w}}"
        t[even + 6] = f"{op} a,(x)"
        t[even + 7] = f"{op} a,[{{d}}+x]"
        t[even + 8] = f"{op} a,{{i}}"
        t[even + 9] = f"{op} {{D}},{{d}}"
        t[odd + 4] = f"{op} a,{{d}}+x"
        t[odd + 5] = f"{op} a,!{{w}}+x"
        t[odd + 6] = f"{op} a,!{{w}}+y"
        t[odd + 7] = f"{op} a,[{{d}}]+y"
        t[odd + 8] = f"{op} {{D}},{{i}}"
        t[odd + 9] = f"{op} (x),(y)"
    for n in range(16):
        t[n * 0x10 + 1] = f"tcall {n}"
    for n in range(8):
        t[n * 0x20 + 2] = f"set1 {{d}}.{n}"
        t[n * 0x20 + 0x12] = f"clr1 {{d}}.{n}"
        t[n * 0x20 + 3] = f"bbs {{d}}.{n},{{r}}"
        t[n * 0x20 + 0x13] = f"bbc {{d}}.{n},{{r}}"
    branches = ["bpl", "bmi", "bvc", "bvs", "bcc", "bcs", "bne", "beq"]
    for k, b in enumerate(branches):
        t[0x10 + k * 0x20] = f"{b} {{r}}"
    rest = {
        0x00: "nop", 0x0A: "or1 c,{m}", 0x0B: "asl {d}", 0x0C: "asl !{w}", 0x0D: "push psw",
        0x0E: "tset1 !{w}", 0x0F: "brk", 0x1A: "decw {d}", 0x1B: "asl {d}+x", 0x1C: "asl a",
        0x1D: "dec x", 0x1E: "cmp x,!{w}", 0x1F: "jmp [!{w}+x]",
        0x20: "clrp", 0x2A: "or1 c,/{m}", 0x2B: "rol {d}", 0x2C: "rol !{w}", 0x2D: "push a",
        0x2E: "cbne {d},{r}", 0x2F: "bra {r}", 0x3A: "incw {d}", 0x3B: "rol {d}+x", 0x3C: "rol a",
        0x3D: "inc x", 0x3E: "cmp x,{d}", 0x3F: "call !{w}",
        0x40: "setp", 0x4A: "and1 c,{m}", 0x4B: "lsr {d}", 0x4C: "lsr !{w}", 0x4D: "push x",
        0x4E: "tclr1 !{w}", 0x4F: "pcall {u}", 0x5A: "cmpw ya,{d}", 0x5B: "lsr {d}+x", 0x5C: "lsr a",
        0x5D: "mov x,a", 0x5E: "cmp y,!{w}", 0x5F: "jmp !{w}",
        0x60: "clrc", 0x6A: "and1 c,/{m}", 0x6B: "ror {d}", 0x6C: "ror !{w}", 0x6D: "push y",
        0x6E: "dbnz {d},{r}", 0x6F: "ret", 0x7A: "addw ya,{d}", 0x7B: "ror {d}+x", 0x7C: "ror a",
        0x7D: "mov a,x", 0x7E: "cmp y,{d}", 0x7F: "reti",
        0x80: "setc", 0x84: "adc a,{d}", 0x8A: "eor1 c,{m}", 0x8B: "dec {d}", 0x8C: "dec !{w}",
        0x8D: "mov y,{i}", 0x8E: "pop psw", 0x8F: "mov {D},{i}",
        0x9A: "subw ya,{d}", 0x9B: "dec {d}+x", 0x9C: "dec a", 0x9D: "mov x,sp", 0x9E: "div ya,x",
        0x9F: "xcn a",
        0xA0: "ei", 0xAA: "mov1 c,{m}", 0xAB: "inc {d}", 0xAC: "inc !{w}", 0xAD: "cmp y,{i}",
        0xAE: "pop a", 0xAF: "mov (x)+,a", 0xBA: "movw ya,{d}", 0xBB: "inc {d}+x", 0xBC: "inc a",
        0xBD: "mov sp,x", 0xBE: "das a", 0xBF: "mov a,(x)+",
        0xC0: "di", 0xC4: "mov {d},a", 0xC5: "mov !{w},a", 0xC6: "mov (x),a", 0xC7: "mov [{d}+x],a",
        0xC8: "cmp x,{i}", 0xC9: "mov !{w},x", 0xCA: "mov1 {m},c", 0xCB: "mov {d},y", 0xCC: "mov !{w},y",
        0xCD: "mov x,{i}", 0xCE: "pop x", 0xCF: "mul ya",
        0xD4: "mov {d}+x,a", 0xD5: "mov !{w}+x,a", 0xD6: "mov !{w}+y,a", 0xD7: "mov [{d}]+y,a",
        0xD8: "mov {d},x", 0xD9: "mov {d}+y,x", 0xDA: "movw {d},ya", 0xDB: "mov {d}+x,y", 0xDC: "dec y",
        0xDD: "mov a,y", 0xDE: "cbne {d}+x,{r}", 0xDF: "daa a",
        0xE0: "clrv", 0xE4: "mov a,{d}", 0xE5: "mov a,!{w}", 0xE6: "mov a,(x)", 0xE7: "mov a,[{d}+x]",
        0xE8: "mov a,{i}", 0xE9: "mov x,!{w}", 0xEA: "not1 {m}", 0xEB: "mov y,{d}", 0xEC: "mov y,!{w}",
        0xED: "notc", 0xEE: "pop y", 0xEF: "sleep",
        0xF4: "mov a,{d}+x", 0xF5: "mov a,!{w}+x", 0xF6: "mov a,!{w}+y", 0xF7: "mov a,[{d}]+y",
        0xF8: "mov x,{d}", 0xF9: "mov x,{d}+y", 0xFA: "mov {D},{d}", 0xFB: "mov y,{d}+x", 0xFC: "inc y",
        0xFD: "mov y,a", 0xFE: "dbnz y,{r}", 0xFF: "stop",
    }
    # columns 4-9 on rows 8/9 are adc/sbc (handled by ALU loop) except where overridden by
    # MOV opcodes in rows C-F which are outside the ALU rows.
    for k, v in rest.items():
        t[k] = v
    out = []
    for op, tpl in enumerate(t):
        assert tpl is not None, hex(op)
        length = 1
        for tok, n in (("{w}", 2), ("{m}", 2)):
            if tok in tpl:
                length += n
        for tok in ("{d}", "{D}", "{i}", "{r}", "{u}"):
            if tok in tpl:
                length += 1
        out.append((tpl, length))
    return out


OPCODES: List[Tuple[str, int]] = _build()


def length(op: int) -> int:
    return OPCODES[op][1]


def format_insn(mem: bytes, pc: int, offset: int = 0) -> Tuple[str, int]:
    """Disassemble one instruction at mem[offset] located at address pc."""
    op = mem[offset]
    tpl, n = OPCODES[op]
    b1 = mem[offset + 1] if n > 1 and offset + 1 < len(mem) else 0
    b2 = mem[offset + 2] if n > 2 and offset + 2 < len(mem) else 0
    rel = b2 if n == 3 else b1
    target = (pc + n + (rel - 256 if rel >= 128 else rel)) & 0xFFFF
    word = b1 | b2 << 8
    s = (tpl.replace("{d}", f"${b1:02x}").replace("{D}", f"${b2:02x}")
         .replace("{i}", f"#${b1:02x}").replace("{w}", f"${word:04x}")
         .replace("{r}", f"${target:04x}").replace("{u}", f"$ff{b1:02x}")
         .replace("{m}", f"${word & 0x1fff:04x}.{word >> 13}"))
    return s, n


def disassemble(mem: bytes, start: int, end: int, base: int = 0, labels: Optional[Dict[int, str]] = None) -> List[str]:
    """Linear disassembly of mem[start-base:end-base]; addresses are SPC addresses."""
    out = []
    pc = start
    while pc < end:
        off = pc - base
        s, n = format_insn(mem, pc, off)
        raw = " ".join(f"{mem[off + k]:02x}" for k in range(n) if off + k < len(mem))
        lab = labels.get(pc) if labels else None
        if lab:
            out.append(f"{lab}:")
        out.append(f"{pc:04x}  {raw:<9} {s}")
        pc += n
    return out


# ---------------------------------------------------------------- assembler
_TOKRE = {
    "{d}": r"\$([0-9a-f]{1,2})", "{D}": r"\$([0-9a-f]{1,2})", "{i}": r"#\$([0-9a-f]{1,2})",
    "{w}": r"\$([0-9a-f]{1,4})", "{r}": r"\$([0-9a-f]{1,4})", "{u}": r"\$ff([0-9a-f]{2})",
    "{m}": r"\$([0-9a-f]{1,4})\.([0-7])",
}


def _compile():
    pats = []
    for op, (tpl, n) in enumerate(OPCODES):
        rx = ""
        toks = []
        i = 0
        while i < len(tpl):
            if tpl[i] == "{":
                tok = tpl[i:i + 3]
                rx += _TOKRE[tok]
                toks.append(tok)
                i += 3
            else:
                rx += re.escape(tpl[i])
                i += 1
        pats.append((re.compile("^" + rx + "$"), op, toks, n))
    # prefer direct page forms (shorter) when both match: order by length
    pats.sort(key=lambda p: p[3])
    return pats


_PATS = None


def assemble(src: str, org: int) -> bytes:
    """Two-pass assembler. Supports `label:`, `.db b,b,..`, `.dw w,..`, and
    instructions in disassembler syntax where any `$hex` may be replaced by a label."""
    global _PATS
    if _PATS is None:
        _PATS = _compile()
    lines = []
    for raw in src.splitlines():
        line = raw.split(";")[0].strip().lower()
        if line:
            lines.append(line)

    def encode(line, pc, labels, final):
        if line.startswith(".db"):
            return bytes(int(_sub(x.strip(), labels, final), 0) & 0xFF for x in line[3:].split(","))
        if line.startswith(".dw"):
            out = b""
            for x in line[3:].split(","):
                v = int(_sub(x.strip(), labels, final), 0)
                out += bytes([v & 0xFF, v >> 8 & 0xFF])
            return out
        text = re.sub(r"\s+", " ", line)
        text = re.sub(r"(?<![$0-9a-z_])([a-z_][a-z0-9_]*)", lambda m: _lab(m, labels, final), text)
        # normalise 1-space after mnemonic and no spaces elsewhere
        parts = text.split(" ", 1)
        text = parts[0] + (" " + parts[1].replace(" ", "") if len(parts) > 1 else "")
        for rx, op, toks, n in _PATS:
            m = rx.match(text)
            if not m:
                continue
            vals = list(m.groups())
            b = [op] + [0] * (n - 1)
            gi = 0
            ok = True
            for tok in toks:
                v = int(vals[gi], 16)
                gi += 1
                if tok == "{d}":
                    if v > 0xFF:
                        ok = False
                    b[1] = v
                elif tok == "{D}":
                    b[2] = v
                elif tok == "{i}":
                    b[1] = v
                elif tok == "{w}":
                    b[1], b[2] = v & 0xFF, v >> 8
                elif tok == "{u}":
                    b[1] = v
                elif tok == "{m}":
                    bit = int(vals[gi])
                    gi += 1
                    w = (v & 0x1FFF) | bit << 13
                    b[1], b[2] = w & 0xFF, w >> 8
                elif tok == "{r}":
                    d = v - (pc + n)
                    if final and not -128 <= d <= 127:
                        raise ValueError(f"branch out of range: {line}")
                    b[n - 1] = d & 0xFF
            if ok:
                return bytes(b)
        raise ValueError(f"cannot assemble: {line!r} ({text!r})")

    labels: Dict[str, int] = {}
    for final in (False, True):
        pc = org
        out = bytearray()
        for line in lines:
            if line.endswith(":"):
                labels[line[:-1]] = pc
                continue
            if line.startswith(".org"):
                pc = int(line[4:].strip(), 0)
                continue
            data = encode(line, pc, labels, final)
            out += data
            pc += len(data)
    return bytes(out)


_REGS = {"a", "x", "y", "ya", "sp", "psw", "c"}
_MNEMS = None


def _lab(m, labels, final):
    global _MNEMS
    if _MNEMS is None:
        _MNEMS = {tpl.split(" ")[0] for tpl, _ in OPCODES}
    name = m.group(1)
    if name in _REGS or name in _MNEMS:
        return name
    if name in labels:
        return f"${labels[name]:04x}"
    if final:
        raise ValueError(f"undefined label {name}")
    return "$0000"


def _sub(x, labels, final):
    if x in labels:
        return str(labels[x])
    if x.startswith("$"):
        return "0x" + x[1:]
    if re.match(r"^[a-z_]", x):
        if final:
            raise ValueError(f"undefined label {x}")
        return "0"
    return x
