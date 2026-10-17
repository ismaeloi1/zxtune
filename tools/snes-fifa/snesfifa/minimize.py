"""Find which ROM regions the music playback needs (empirically), to build a minimal SNSF.

A region is 'unneeded' if zero-filling it leaves the 65816->SPC700 port write stream of every
song byte-identical over the test window.  Regions are tested independently first (coarse,
then finer inside the needed ones), and the union of unneeded regions is verified at the end.
"""
import concurrent.futures as cf
import hashlib
import os
import subprocess
import tempfile
from typing import Callable, Dict, List, Sequence, Tuple


def port_stream_hash(snestrace: str, rom: bytes, frames: int, workdir: str, tag: str) -> str:
    rp = os.path.join(workdir, f"{tag}.sfc")
    lp = os.path.join(workdir, f"{tag}.tsv")
    with open(rp, "wb") as f:
        f.write(rom)
    subprocess.run([snestrace, "--rom", rp, "--frames", str(frames), "--log", lp, "--reads", "none",
                    "--no-smp-log"], check=True, stderr=subprocess.DEVNULL, stdout=subprocess.DEVNULL)
    h = hashlib.sha1()
    with open(lp) as f:
        for line in f:
            if line.startswith("CW"):
                p = line.split("\t")
                h.update(f"{p[1]}{p[2]}{p[3]}".encode())  # port, value, SPC clock
    os.remove(rp)
    os.remove(lp)
    return h.hexdigest()


def find_unneeded(snestrace: str, roms: Dict[int, bytes], regions: Sequence[Tuple[int, int]], frames: int,
                  keep: Callable[[int, int], bool], jobs: int = 4) -> List[Tuple[int, int]]:
    """roms: track -> ROM image with stub. Returns the list of regions safe to zero."""
    work = tempfile.mkdtemp(prefix="snsfmin")
    ref = {t: port_stream_hash(snestrace, r, frames, work, f"ref{t}") for t, r in roms.items()}

    def test(region):
        a, b = region
        if keep(a, b):
            return region, False
        for t, r in roms.items():
            z = bytearray(r)
            z[a:b] = b"\x00" * (b - a)
            if port_stream_hash(snestrace, bytes(z), frames, work, f"t{t}_{a:x}") != ref[t]:
                return region, False
        return region, True

    with cf.ThreadPoolExecutor(jobs) as ex:
        res = list(ex.map(test, regions))
    return [r for r, ok in res if ok]


def zero_regions(rom: bytes, regions: Sequence[Tuple[int, int]]) -> bytes:
    z = bytearray(rom)
    for a, b in regions:
        z[a:b] = b"\x00" * (b - a)
    return bytes(z)


def verify(snestrace: str, full: Dict[int, bytes], reduced: Dict[int, bytes], frames: int) -> List[int]:
    """Songs whose port write stream differs between the full and the reduced ROM."""
    work = tempfile.mkdtemp(prefix="snsfver")
    bad = []
    for t in full:
        if port_stream_hash(snestrace, full[t], frames, work, f"f{t}") != \
                port_stream_hash(snestrace, reduced[t], frames, work, f"r{t}"):
            bad.append(t)
    return bad
