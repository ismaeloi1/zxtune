"""Cross-check our disassembler tables against snes9x's independent tables.

Only used when a snes9x checkout is available (emu/build.sh puts one in build/snes9x)."""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
SNES9X = os.environ.get("SNES9X_SRC", os.path.join(HERE, "..", "build", "snes9x"))


def bapu_spc700_reference():
    """Yield (opcode, reference_text, uses_third_byte) using bapu's disassembler.cpp."""
    path = os.path.join(SNES9X, "apu", "bapu", "smp", "debugger", "disassembler.cpp")
    if not os.path.exists(path):
        return None
    with open(path) as f:
        src = f.read()
    entries = re.findall(r'case 0x([0-9a-f]{2}): sprintf\(t, "([^"]*)"(.*?)\);\s*break;', src)
    out = []
    b1, b2, pc = 0x34, 0x92, 0x1000
    env = dict(op0=b1, op1=b2, opw=b1 | b2 << 8, opdp0=b1, opdp1=b2)
    for op, fmt, raw in entries:
        vals = []
        for part in re.findall(r"\s*(relb\([^)]*\)|[^,]+)", raw):
            part = part.strip()
            if not part:
                continue
            m = re.match(r"relb\((op\d), (\d)\)", part)
            if m:
                v = env[m.group(1)]
                n = int(m.group(2))
                vals.append((pc + n + (v - 256 if v > 127 else v)) & 0xFFFF)
            else:
                vals.append(eval(part, {}, dict(env)))
        out.append((int(op, 16), fmt % tuple(vals) if vals else fmt, ("op1" in raw) or ("opw" in raw) or ("opdp1" in raw)))
    return out, (b1, b2, pc)


def snes9x_65816_reference():
    """Return (mnemonics[256], addrmodes[256]) parsed from snes9x debug.cpp."""
    path = os.path.join(SNES9X, "debug.cpp")
    if not os.path.exists(path):
        return None
    with open(path) as f:
        src = f.read()
    mn = re.search(r"S9xMnemonics\[256\]\s*=\s*\{(.*?)\};", src, re.S).group(1)
    mnem = re.findall(r'"([A-Z]{3})"', mn)
    am = re.search(r"AddrModes\[256\]\s*=\s*\{(.*?)\};", src, re.S).group(1)
    am = re.sub(r"//[^\n]*", "", am)
    modes = [int(x) for x in re.findall(r"\b(\d+)\b", am)]
    return mnem, modes
