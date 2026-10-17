"""End-to-end tests: homebrew ROM (always, if the tools are built) and the user's own FIFA dump
(only when $FIFA_ROM is set)."""
import os
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from snesfifa import fifa, ipl, portlog, protocol, reassemble, rip, snsf, testrom, xcorr  # noqa: E402
from snesfifa.protocol import Command  # noqa: E402

BUILD = rip.BUILD
HAVE_TOOLS = os.path.exists(os.path.join(BUILD, "snestrace")) and os.path.exists(os.path.join(BUILD, "spcreplay"))
FIFA_ROM = os.environ.get("FIFA_ROM")


@unittest.skipUnless(HAVE_TOOLS, "run emu/build.sh and replay/build.sh first")
class HomebrewTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.mkdtemp(prefix="snesfifa_test")
        cls.rom, cls.blocks = testrom.build()
        cls.rom_path = os.path.join(cls.dir, "test.sfc")
        with open(cls.rom_path, "wb") as f:
            f.write(cls.rom)
        cls.log = os.path.join(cls.dir, "test.tsv")
        cls.wav = os.path.join(cls.dir, "emu.wav")
        cls.spc = os.path.join(cls.dir, "snap.spc")
        rip.snestrace(cls.rom_path, 300, "--log", cls.log, "--wav", cls.wav, "--spc", f"60:{cls.spc}",
                      "--aram", f"60:{cls.dir}/aram.bin")

    def test_header(self):
        info = fifa.identify(self.rom)
        self.assertTrue(info["checksum_ok"])
        self.assertEqual(info["mapping"], "LoROM")

    def test_ipl_blocks_recovered(self):
        sessions = ipl.parse_ipl(list(portlog.read_events(self.log)))
        self.assertEqual(len(sessions), 1)
        self.assertEqual([(b.addr, bytes(b.data)) for b in sessions[0].blocks], self.blocks)
        self.assertEqual(sessions[0].jump, 0x0200)
        with open(os.path.join(self.dir, "aram.bin"), "rb") as f:
            aram = f.read()
        for addr, data in self.blocks:
            self.assertEqual(aram[addr:addr + len(data)], data)

    def test_replay_matches_emulator(self):
        clock = next(int(l.split("smp_clock")[1]) for l in open(self.log) if "action spc" in l)
        out = os.path.join(self.dir, "replay.wav")
        subprocess.run([os.path.join(BUILD, "spcreplay"), "--spc", self.spc, "--log", self.log,
                        "--start-clock", str(clock), "--out", out, "--seconds", "4"], check=True,
                       stderr=subprocess.DEVNULL)
        r = xcorr.compare(self.wav, out, expected_lag_s=clock / 32 / 32040)
        self.assertGreater(r["local_corr_median"], 0.95)
        self.assertGreater(r["global_corr"], 0.95)

    def test_snsf_container_plays(self):
        lib = os.path.join(self.dir, "t.snsflib")
        mini = os.path.join(self.dir, "t.minisnsf")
        with open(lib, "wb") as f:
            f.write(snsf.pack_snsf(0, self.rom))
        with open(mini, "wb") as f:
            f.write(snsf.pack_snsf(0x7F00, b"\xff", {"_lib": "t.snsflib"}))
        rom, _ = snsf.load_snsf(mini)
        self.assertEqual(bytes(rom), self.rom)


class ReassembleEncoderTest(unittest.TestCase):
    def _cmd(self, frame, cmd, *phases):
        return Command(cmd, list(phases), frame * 20000 + 100, frame, 0)

    def test_tokens_and_lz(self):
        cmds = []
        for rep in range(6):
            base = 10 + rep * 8
            for k in range(4):
                f = base + 2 * k
                cmds.append(self._cmd(f, 7, (7, k % 2, 0x40, 0x40), (0x81, 3, 0x8F, 0xE0)))
                cmds.append(self._cmd(f, 6, (6, k % 2, 0x00, 0x08 + k)))
                cmds.append(self._cmd(f + 1, 5, (5, k % 2, 0, 0)))
        toks, entries, loop_tok, stats = reassemble.tokenize(cmds, 10, 18, 58, bytes(128))
        self.assertEqual(len(entries), 4)
        self.assertEqual(stats["generic"], 0)
        items = reassemble.lz_items(toks, loop_tok)
        self.assertTrue(any(k == "ref" for k, _, _ in items))
        # expanding the references gives back the token list
        out = []
        for k, i, n in items:
            out += [toks[i]] if k == "lit" else toks[i:i + n]
        self.assertEqual(out, toks)
        placed = reassemble.layout(items, toks, [(0x1000, 0x1100), (0x8000, 0x9000)], loop_tok)
        self.assertIsNotNone(placed)


@unittest.skipUnless(FIFA_ROM and HAVE_TOOLS, "set FIFA_ROM to your own dump to run")
class FifaRomTest(unittest.TestCase):
    def test_identify_and_stub(self):
        rom = open(FIFA_ROM, "rb").read()
        info = fifa.identify(rom)
        self.assertTrue(info["checksum_ok"])
        self.assertEqual(info["mapping"], "LoROM")
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "s.sfc")
            with open(p, "wb") as f:
                f.write(fifa.build_rom(rom, 7))
            log = os.path.join(d, "s.tsv")
            rip.snestrace(p, 400, "--log", log)
            ev, sessions, cmds = rip.decode_log(log)
            self.assertEqual(len(sessions[0].blocks), 1)
            self.assertEqual(sessions[0].blocks[0].addr, 0x0380)
            self.assertEqual(sum(1 for c in cmds if c.cmd == 3), 16)
            srcn = {c.phases[1][1] for c in cmds if c.cmd == 7 and len(c.phases) > 1}
            self.assertTrue(len(srcn) > 3 and max(srcn) < 16)


if __name__ == "__main__":
    unittest.main()
