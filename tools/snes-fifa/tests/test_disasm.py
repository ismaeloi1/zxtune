import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.dirname(__file__))

import refcheck  # noqa: E402
from snesfifa import spc700, w65816  # noqa: E402

import re  # noqa: E402


class Spc700Test(unittest.TestCase):
    def test_table_complete(self):
        self.assertEqual(len(spc700.OPCODES), 256)
        lengths = [n for _, n in spc700.OPCODES]
        self.assertEqual(lengths.count(1) + lengths.count(2) + lengths.count(3), 256)

    def test_known_encodings(self):
        cases = {
            b"\x8f\x6c\xf2": "mov $f2,#$6c", b"\xfa\xf5\xf3": "mov $f3,$f5",
            b"\x3f\x8f\x04": "call !$048f", b"\x1f\x65\x04": "jmp [!$0465+x]",
            b"\xd7\x06": "mov [$06]+y,a", b"\xcf": "mul ya", b"\x9e": "div ya,x",
            b"\x2e\x10\xfd": "cbne $10,$0000", b"\x13\x07\x16": "bbc $07.0,$0019",
            b"\xaa\x34\x52": "mov1 c,$1234.2",
        }
        for raw, text in cases.items():
            self.assertEqual(spc700.format_insn(raw, 0x0000)[0], text, raw.hex())

    def test_against_snes9x_bapu(self):
        ref = refcheck.bapu_spc700_reference()
        if ref is None:
            self.skipTest("snes9x checkout not available (run emu/build.sh)")
        entries, (b1, b2, pc) = ref
        nums = lambda s: [int(x, 16) for x in re.findall(r"\$([0-9a-f]+)", s)]
        for op, text, uses3 in entries:
            mine, n = spc700.format_insn(bytes([op, b1, b2]), pc)
            self.assertEqual(nums(mine), nums(text), f"{op:02x}: {mine} vs {text}")
            self.assertEqual(n == 3, uses3, f"{op:02x} length")

    def test_assembler_roundtrip_all_opcodes(self):
        for op in range(256):
            raw = bytes([op, 0x12, 0x34])
            text, n = spc700.format_insn(raw, 0x1000)
            again = spc700.assemble(text, 0x1000)
            # direct-page and absolute forms with equal text cannot collide, so bytes must match
            self.assertEqual(again, raw[:n], f"{op:02x} {text}")

    def test_labels(self):
        code = spc700.assemble("start:\n mov a,#$01\n bne start\n call !start\n", 0x0400)
        self.assertEqual(code, bytes([0xE8, 0x01, 0xD0, 0xFC, 0x3F, 0x00, 0x04]))


class W65816Test(unittest.TestCase):
    MODE_IDS = {"imp": 0, "immm": 1, "immx": 2, "imm8": 3, "rel": 4, "rell": 5, "dp": 6, "dpx": 7, "dpy": 8,
                "idp": 9, "idpx": 10, "idpy": 11, "ildp": 12, "ildpy": 13, "abs": 14, "absx": 15, "absy": 16,
                "long": 17, "longx": 18, "sr": 19, "isry": 20, "iabs": 21, "ilabs": 22, "iabsx": 23, "acc": 24,
                "move": 25}
    # snes9x differences that are naming choices (or a known slip in its table), not errors
    KNOWN = {0x5C: ("jml", "JMP"), 0xD3: None, 0xD4: None, 0xF4: None}

    def test_against_snes9x(self):
        ref = refcheck.snes9x_65816_reference()
        if ref is None:
            self.skipTest("snes9x checkout not available (run emu/build.sh)")
        mnem, modes = ref
        for op in range(256):
            m, mode = w65816.OPCODES[op]
            if op in self.KNOWN:
                continue
            self.assertEqual(m.upper(), mnem[op], f"{op:02x}")
            self.assertEqual(self.MODE_IDS[mode], modes[op], f"{op:02x} {m} {mode}")

    def test_lengths(self):
        self.assertEqual(w65816.insn_length(0xA9, False, False), 2)
        self.assertEqual(w65816.insn_length(0xA9, True, False), 3)
        self.assertEqual(w65816.insn_length(0xA2, True, True), 3)
        self.assertEqual(w65816.insn_length(0x22, False, False), 4)
        self.assertEqual(w65816.insn_length(0x54, False, False), 3)

    def test_assembler_roundtrip_all_opcodes(self):
        for m16 in (False, True):
            for op in range(256):
                n = w65816.insn_length(op, m16, m16)
                raw = bytes([op, 0x12, 0x34, 0x56])[:n]
                mem = {0x808000 + i: b for i, b in enumerate(raw)}
                text, n2, _ = w65816.format_insn(lambda a: mem.get(a, 0), 0x808000, m16, m16)
                self.assertEqual(w65816.assemble(text, 0x808000), raw, f"{op:02x} {text}")

    def test_rep_sep_tracking(self):
        code = bytes([0xC2, 0x20, 0xA9, 0x34, 0x12, 0xE2, 0x20, 0xA9, 0x56])
        rd = lambda a: code[(a & 0xFFFF) - 0x8000] if 0 <= (a & 0xFFFF) - 0x8000 < len(code) else 0
        lines = w65816.disassemble(rd, 0x808000, 0x808000 + len(code))
        self.assertIn("lda #$1234", lines[1])
        self.assertIn("lda #$56", lines[3])

    def test_lorom_mapping(self):
        self.assertEqual(w65816.lorom_offset(0x80EB9D), 0x6B9D)
        self.assertEqual(w65816.lorom_offset(0x978000), 0xB8000)
        self.assertEqual(w65816.lorom_address(0x6B9D), 0x80EB9D)


if __name__ == "__main__":
    unittest.main()
