"""SNES BRR sample codec (decoder mirrors blargg's SPC_DSP; encoder is a simple brute-force one
used for tests), sample-directory extraction from ARAM, and WAV export with loop points."""
import json
import struct
import wave
from dataclasses import dataclass
from typing import List, Optional, Tuple


def _clamp16(v: int) -> int:
    return -0x8000 if v < -0x8000 else 0x7FFF if v > 0x7FFF else v


def decode_block(block: bytes, p1: int, p2: int) -> Tuple[List[int], int, int]:
    """Decode one 9-byte block. p1/p2 are the previous two output samples (DSP scale,
    i.e. 16-bit values with the low bit clear). Returns (16 samples, p1, p2)."""
    header = block[0]
    shift = header >> 4
    flt = (header >> 2) & 3
    out = []
    for i in range(16):
        byte = block[1 + i // 2]
        nib = (byte >> 4) if i % 2 == 0 else (byte & 0x0F)
        s = nib - 16 if nib >= 8 else nib
        s = (s << shift) >> 1
        if shift >= 0xD:
            s = (s >> 25) << 11 if False else (-2048 if nib >= 8 else 0)
        if flt >= 8:  # pragma: no cover (impossible)
            pass
        # filters operate on the halved previous samples, as in SPC_DSP
        q1 = p1 >> 1
        q2 = p2 >> 1
        if flt == 1:
            s += q1 + ((-q1) >> 4)
        elif flt == 2:
            s += (q1 << 1) + ((-((q1 << 1) + q1)) >> 5) - q2 + (q2 >> 4)
        elif flt == 3:
            s += (q1 << 1) + ((-(q1 + (q1 << 2) + (q1 << 3))) >> 6) - q2 + (((q2 << 1) + q2) >> 4)
        s = _clamp16(s)
        s = ((s * 2) & 0xFFFF)
        s = s - 0x10000 if s >= 0x8000 else s
        p2, p1 = p1, s
        out.append(s)
    return out, p1, p2


def block_count(data: bytes, start: int = 0) -> int:
    """Number of blocks up to and including the one with the END flag."""
    n = 0
    while start + 9 * n + 9 <= len(data):
        n += 1
        if data[start + 9 * (n - 1)] & 1:
            break
    return n


def decode(data: bytes, loops: int = 0, loop_block: Optional[int] = None) -> List[int]:
    """Decode a BRR stream until the END block. If loops>0 and loop_block is given, the
    loop section is decoded `loops` more times (filter history continues, as on hardware)."""
    out: List[int] = []
    p1 = p2 = 0
    n = block_count(data)
    for b in range(n):
        s, p1, p2 = decode_block(data[9 * b:9 * b + 9], p1, p2)
        out += s
    if loops and loop_block is not None and data[9 * (n - 1)] & 2:
        for _ in range(loops):
            for b in range(loop_block, n):
                s, p1, p2 = decode_block(data[9 * b:9 * b + 9], p1, p2)
                out += s
    return out


def encode(samples: List[int], loop_start: Optional[int] = None) -> bytes:
    """Brute-force encoder (all shifts/filters per block, minimum squared error), input
    16-bit PCM, length padded to a multiple of 16. Sets END (and LOOP if loop_start)."""
    pcm = list(samples) + [0] * (-len(samples) % 16)
    out = bytearray()
    p1 = p2 = 0
    nblocks = len(pcm) // 16
    for b in range(nblocks):
        target = pcm[16 * b:16 * b + 16]
        best = None
        for flt in range(4):
            if b == 0 and flt:
                continue
            for shift in range(13):
                nibs = []
                q1, q2 = p1, p2
                err = 0
                ok = True
                for t in target:
                    # choose nibble minimising error given prediction
                    bestn = None
                    for n in range(-8, 8):
                        blk = bytes([shift << 4 | flt << 2, ((n & 15) << 4)]) + b"\x00" * 7
                        s, _, _ = decode_block(blk, q1, q2)
                        e = (s[0] - (t & ~1)) ** 2
                        if bestn is None or e < bestn[0]:
                            bestn = (e, n, s[0])
                    err += bestn[0]
                    nibs.append(bestn[1])
                    q2, q1 = q1, bestn[2]
                    if best is not None and err >= best[0]:
                        ok = False
                        break
                if ok and (best is None or err < best[0]):
                    best = (err, shift, flt, nibs, q1, q2)
        _, shift, flt, nibs, p1, p2 = best
        hdr = shift << 4 | flt << 2
        if b == nblocks - 1:
            hdr |= 1 | (2 if loop_start is not None else 0)
        out.append(hdr)
        for i in range(0, 16, 2):
            out.append(((nibs[i] & 15) << 4) | (nibs[i + 1] & 15))
    return bytes(out)


@dataclass
class Sample:
    index: int
    start: int
    loop: int
    data: bytes          # BRR blocks through the END block

    @property
    def blocks(self) -> int:
        return len(self.data) // 9

    @property
    def looped(self) -> bool:
        return bool(self.data and self.data[-9] & 2)

    @property
    def loop_block(self) -> Optional[int]:
        off = self.loop - self.start
        if not self.looped or off < 0 or off % 9 or off // 9 >= self.blocks:
            return None
        return off // 9


def directory(aram: bytes, dir_page: int, count: int) -> List[Sample]:
    """Read `count` entries of the sample directory at dir_page*$100."""
    out = []
    for i in range(count):
        e = dir_page * 0x100 + 4 * i
        start = aram[e] | aram[e + 1] << 8
        loop = aram[e + 2] | aram[e + 3] << 8
        n = block_count(aram, start)
        out.append(Sample(i, start, loop, bytes(aram[start:start + 9 * n])))
    return out


def write_wav(path: str, pcm: List[int], rate: int, loop: Optional[Tuple[int, int]] = None) -> None:
    """Mono 16-bit WAV; if loop=(start,end) (sample frames, end exclusive) add a 'smpl' chunk."""
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(struct.pack(f"<{len(pcm)}h", *pcm))
    if loop:
        smpl = struct.pack("<9I", 0, 0, 1000000000 // rate, 60, 0, 0, 0, 1, 0)
        smpl += struct.pack("<6I", 0, 0, loop[0], loop[1] - 1, 0, 0)
        with open(path, "r+b") as f:
            f.seek(0, 2)
            f.write(b"smpl" + struct.pack("<I", len(smpl)) + smpl)
            size = f.tell()
            f.seek(4)
            f.write(struct.pack("<I", size - 8))


def export(samples: List[Sample], outdir: str, prefix: str = "sample", rate: int = 32000) -> List[dict]:
    """Write <prefix>NN.brr (raw blocks), <prefix>NN.wav (+smpl loop) and return metadata."""
    import os
    meta = []
    for s in samples:
        base = os.path.join(outdir, f"{prefix}{s.index:02d}")
        with open(base + ".brr", "wb") as f:
            f.write(s.data)
        pcm = decode(s.data)
        lb = s.loop_block
        write_wav(base + ".wav", pcm, rate, (lb * 16, len(pcm)) if lb is not None else None)
        meta.append({"index": s.index, "aram_start": s.start, "aram_loop": s.loop, "blocks": s.blocks,
                     "bytes": len(s.data), "looped": s.looped, "loop_block": lb,
                     "loop_sample": lb * 16 if lb is not None else None, "samples": len(pcm),
                     "wav_rate": rate})
    with open(os.path.join(outdir, f"{prefix}s.json"), "w") as f:
        json.dump(meta, f, indent=1)
    return meta
