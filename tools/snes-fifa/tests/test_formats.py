import math
import os
import struct
import sys
import tempfile
import unittest
import wave

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from snesfifa import brr, ipl, midi, portlog, protocol, snsf, spcfile, xcorr  # noqa: E402
from snesfifa.portlog import PortEvent  # noqa: E402


class BrrTest(unittest.TestCase):
    def test_filter0_exact(self):
        # shift 12, filter 0: nibble n -> ((n << 12) >> 1), stored doubled (SPC_DSP scale)
        block = bytes([0xC0 | 1, 0x17, 0x80] + [0] * 6)
        out, _, _ = brr.decode_block(block, 0, 0)
        self.assertEqual(out[:4], [4096, 7 * 4096, -8 * 4096, 0])

    def test_roundtrip_sine(self):
        pcm = [int(6000 * math.sin(i / 5.0)) for i in range(256)]
        data = brr.encode(pcm, loop_start=0)
        self.assertEqual(len(data), 9 * 16)
        self.assertTrue(data[-9] & 1 and data[-9] & 2)
        dec = brr.decode(data)
        err = max(abs(a - b) for a, b in zip(dec, pcm))
        self.assertLess(err, 1500)
        self.assertEqual(brr.block_count(data), 16)

    def test_loop_decode(self):
        data = brr.encode([1000] * 32, loop_start=0)
        self.assertEqual(len(brr.decode(data, loops=2, loop_block=1)), 32 + 2 * 16)

    def test_directory_and_export(self):
        ram = bytearray(0x10000)
        s0 = brr.encode([3000] * 32, loop_start=16)
        s1 = brr.encode([-3000] * 16)
        ram[0x1000:0x1000 + len(s0)] = s0
        ram[0x2000:0x2000 + len(s1)] = s1
        ram[0x0200:0x0208] = bytes([0x00, 0x10, 0x09, 0x10, 0x00, 0x20, 0x00, 0x20])
        smp = brr.directory(bytes(ram), 2, 2)
        self.assertEqual([s.blocks for s in smp], [2, 1])
        self.assertEqual(smp[0].loop_block, 1)
        self.assertFalse(smp[1].looped)
        with tempfile.TemporaryDirectory() as d:
            meta = brr.export(smp, d)
            self.assertEqual(meta[0]["loop_sample"], 16)
            with open(os.path.join(d, "sample00.wav"), "rb") as f:
                raw = f.read()
            self.assertIn(b"smpl", raw)
            with wave.open(os.path.join(d, "sample00.wav")) as w:
                self.assertEqual(w.getnframes(), 32)


class SnsfTest(unittest.TestCase):
    def test_psf_roundtrip(self):
        blob = snsf.pack_snsf(0x1234, b"hello", {"title": "x", "_lib": "a.snsflib"})
        self.assertEqual(blob[:4], b"PSF\x23")
        v, prog, res, tags = snsf.unpack_psf(blob)
        self.assertEqual(v, 0x23)
        self.assertEqual(prog, struct.pack("<II", 0x1234, 5) + b"hello")
        self.assertEqual(tags["title"], "x")

    def test_crc_checked(self):
        blob = bytearray(snsf.pack_snsf(0, b"abc"))
        blob[-1] ^= 0xFF
        with self.assertRaises(Exception):
            snsf.unpack_psf(bytes(blob))

    def test_lib_resolution(self):
        with tempfile.TemporaryDirectory() as d:
            open(os.path.join(d, "x.snsflib"), "wb").write(snsf.pack_snsf(0, b"\x00" * 16, {"game": "g"}))
            open(os.path.join(d, "x01.minisnsf"), "wb").write(
                snsf.pack_snsf(4, b"\x07", {"_lib": "x.snsflib", "title": "t"}))
            rom, tags = snsf.load_snsf(os.path.join(d, "x01.minisnsf"))
            self.assertEqual(bytes(rom), b"\x00" * 4 + b"\x07" + b"\x00" * 11)
            self.assertEqual(tags, {"game": "g", "title": "t"})

    def test_length_tag(self):
        self.assertEqual(snsf.length_tag(75.5), "1:15.500")


def _ev(kind, port, value, clock):
    return PortEvent(kind, port, value, clock, 0, 0, 0)


def ipl_events(blocks, jump):
    """Simulate a 65816 IPL upload + an SPC700 IPL ROM answering it."""
    ev = [_ev("SW", 0, 0xAA, 1), _ev("SW", 1, 0xBB, 1)]
    clock = 10
    kick = 0xCC
    for addr, data in blocks:
        ev += [_ev("CW", 2, addr & 0xFF, clock), _ev("CW", 3, addr >> 8, clock), _ev("CW", 1, 1, clock),
               _ev("CW", 0, kick, clock), _ev("SW", 0, kick, clock + 5)]
        clock += 10
        for i, b in enumerate(data):
            # 16-bit store straddling two SPC clocks: index first, data one clock later
            ev += [_ev("CW", 0, i & 0xFF, clock), _ev("CW", 1, b, clock + 1), _ev("SW", 0, i & 0xFF, clock + 6)]
            clock += 10
        kick = (len(data) + 1) & 0xFF or 1
    ev += [_ev("CW", 2, jump & 0xFF, clock), _ev("CW", 3, jump >> 8, clock), _ev("CW", 1, 0, clock),
           _ev("CW", 0, kick, clock), _ev("SW", 0, kick, clock + 5)]
    return ev


class IplTest(unittest.TestCase):
    def test_reconstruct(self):
        blocks = [(0x0200, bytes(range(40))), (0x1000, bytes([0xAB] * 300))]
        sessions = ipl.parse_ipl(ipl_events(blocks, 0x0200))
        self.assertEqual(len(sessions), 1)
        self.assertEqual([(b.addr, bytes(b.data)) for b in sessions[0].blocks], blocks)
        self.assertEqual(sessions[0].jump, 0x0200)
        ram = ipl.apply_blocks(sessions)
        self.assertEqual(ram[0x1000:0x1000 + 300], bytes([0xAB] * 300))

    def test_portlog_roundtrip(self):
        ev = ipl_events([(0x0300, b"\x01\x02")], 0x0300)
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "x.tsv")
            portlog.write_events(p, ev)
            self.assertEqual(list(portlog.read_events(p)), ev)


class ProtocolTest(unittest.TestCase):
    def test_keyon_and_setup(self):
        ev = [
            # VOICE_SETUP v2 vol 0x40/0x30, srcn 5 adsr 8f e0
            _ev("CW", 1, 2, 100), _ev("CW", 2, 0x40, 100), _ev("CW", 3, 0x30, 100), _ev("CW", 0, 7, 100),
            _ev("SW", 0, 7, 110),
            _ev("CW", 1, 5, 120), _ev("CW", 2, 0x8F, 120), _ev("CW", 3, 0xE0, 120), _ev("CW", 0, 0x81, 120),
            _ev("SW", 0, 0x81, 130), _ev("CW", 0, 0, 140), _ev("SW", 0, 0, 150),
            # KEY_ON v2 pitch 0x0800
            _ev("CW", 1, 2, 200), _ev("CW", 2, 0x00, 200), _ev("CW", 3, 0x08, 200), _ev("CW", 0, 6, 200),
            _ev("SW", 0, 6, 210), _ev("CW", 0, 0, 220), _ev("SW", 0, 0, 230),
        ]
        cmds = protocol.decode(ev)
        self.assertEqual([c.name for c in cmds], ["VOICE_SETUP", "KEY_ON"])
        self.assertEqual(cmds[0].describe(), "VOICE_SETUP v2 vol=64,48 srcn=5 adsr=8f,e0")
        self.assertEqual(cmds[1].describe(), "KEY_ON v2 pitch=0x0800")
        from snesfifa import dsplog
        writes, _ = dsplog.dsp_writes(cmds)
        regs = [(w.reg, w.value) for w in writes]
        self.assertIn((0x24, 5), regs)
        self.assertEqual(regs[-1], (0x4C, 0x04))
        data = midi.transcribe(cmds, 0)
        self.assertTrue(data.startswith(b"MThd"))
        self.assertIn(bytes([0x90, 60 - 12, 0])[:2], data)  # note-on, channel 0, pitch $0800 -> note 48


class XcorrTest(unittest.TestCase):
    def test_known_lag(self):
        import numpy as np
        rate = 32040
        rng = np.random.default_rng(1)
        sig = rng.normal(0, 3000, rate * 6)
        ref = np.concatenate([np.zeros(12345), sig])
        with tempfile.TemporaryDirectory() as d:
            xcorr.write_wav(os.path.join(d, "r.wav"), np.stack([ref, ref], 1), rate)
            xcorr.write_wav(os.path.join(d, "t.wav"), np.stack([sig, sig], 1) * 0.5, rate)
            r = xcorr.compare(os.path.join(d, "r.wav"), os.path.join(d, "t.wav"), max_lag_s=1.0)
            self.assertEqual(r["lag_samples"], 12345)
            self.assertGreater(r["global_corr"], 0.999)
            self.assertAlmostEqual(r["gain"], 2.0, places=2)
            r2 = xcorr.compare(os.path.join(d, "r.wav"), os.path.join(d, "t.wav"), expected_lag_s=0.4)
            self.assertEqual(r2["lag_samples"], 12345)


class SpcFileTest(unittest.TestCase):
    def test_build_and_tags(self):
        s = spcfile.Spc.build(bytes(0x10000), bytes(128), 0x0400)
        s.set_tags(title="t", game="g", seconds=90, fade_ms=10000)
        self.assertEqual(s.pc, 0x0400)
        self.assertEqual(bytes(s.raw[0xA9:0xAB]), b"90")
        spcfile.fix_timer_registers(s, 0x01, t0=0x10)
        self.assertEqual(s.ram[0xFA], 0x10)


if __name__ == "__main__":
    unittest.main()
