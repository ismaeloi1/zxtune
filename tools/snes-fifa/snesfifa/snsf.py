"""PSF/SNSF container: pack, unpack, and resolve _lib chains into a ROM image.

PSF layout (Neill Corlett's PSF spec): "PSF" + version byte, u32 reserved size, u32 compressed
program size, u32 CRC-32 of the compressed program, reserved area, zlib program, then optional
"[TAG]" + "name=value\n" lines.  SNSF (version 0x23, CaitSith2's spec v0.03): the program is
u32 load offset + u32 size + ROM bytes; the reserved area may hold SRAM blocks.
Library order (psflib): _lib is loaded first, then the file itself, then _lib2, _lib3, ...
"""
import os
import struct
import zlib
from typing import Dict, List, Optional, Tuple

SNSF_VERSION = 0x23


def pack_psf(version: int, program: bytes, reserved: bytes = b"", tags: Optional[Dict[str, str]] = None,
             level: int = 9) -> bytes:
    comp = zlib.compress(program, level)
    out = b"PSF" + bytes([version]) + struct.pack("<III", len(reserved), len(comp), zlib.crc32(comp) & 0xFFFFFFFF)
    out += reserved + comp
    if tags:
        out += b"[TAG]"
        for k, v in tags.items():
            for line in str(v).split("\n"):
                out += f"{k}={line}\n".encode("utf-8")
    return out


def unpack_psf(data: bytes) -> Tuple[int, bytes, bytes, Dict[str, str]]:
    if data[:3] != b"PSF":
        raise ValueError("not a PSF file")
    version = data[3]
    rsize, psize, crc = struct.unpack_from("<III", data, 4)
    reserved = data[16:16 + rsize]
    comp = data[16 + rsize:16 + rsize + psize]
    if comp and (zlib.crc32(comp) & 0xFFFFFFFF) != crc:
        raise ValueError("PSF program CRC mismatch")
    program = zlib.decompress(comp) if comp else b""
    tags: Dict[str, str] = {}
    rest = data[16 + rsize + psize:]
    if rest.startswith(b"[TAG]"):
        for line in rest[5:].decode("utf-8", "replace").split("\n"):
            if "=" in line:
                k, v = line.split("=", 1)
                k = k.strip().lower()
                tags[k] = (tags[k] + "\n" + v.strip()) if k in tags else v.strip()
    return version, program, reserved, tags


def snsf_program(offset: int, rom: bytes) -> bytes:
    return struct.pack("<II", offset, len(rom)) + rom


def pack_snsf(offset: int, rom: bytes, tags: Optional[Dict[str, str]] = None, reserved: bytes = b"") -> bytes:
    return pack_psf(SNSF_VERSION, snsf_program(offset, rom), reserved, tags)


def _apply(image: bytearray, program: bytes) -> bytearray:
    off, size = struct.unpack_from("<II", program, 0)
    data = program[8:8 + size]
    if len(image) < off + len(data):
        image.extend(b"\x00" * (off + len(data) - len(image)))
    image[off:off + len(data)] = data
    return image


def load_snsf(path: str, depth: int = 0) -> Tuple[bytearray, Dict[str, str]]:
    """Resolve an SNSF (and its _lib chain) into a ROM image. Returns (rom, merged tags)."""
    if depth > 10:
        raise ValueError("_lib nesting too deep")
    data = open(path, "rb").read()
    version, program, _reserved, tags = unpack_psf(data)
    if version != SNSF_VERSION:
        raise ValueError(f"not an SNSF (version {version:#x})")
    base = os.path.dirname(path)
    image = bytearray()
    merged: Dict[str, str] = {}
    if "_lib" in tags:
        image, libtags = load_snsf(os.path.join(base, tags["_lib"]), depth + 1)
        merged.update(libtags)
    _apply(image, program)
    n = 2
    while f"_lib{n}" in tags:
        lib_img, _ = load_snsf(os.path.join(base, tags[f"_lib{n}"]), depth + 1)
        # additional libs overlay only their own region
        lib_prog = unpack_psf(open(os.path.join(base, tags[f"_lib{n}"]), "rb").read())[1]
        _apply(image, lib_prog)
        n += 1
    merged.update({k: v for k, v in tags.items() if not k.startswith("_lib")})
    return image, merged


def length_tag(seconds: float) -> str:
    m, s = divmod(seconds, 60)
    return f"{int(m)}:{s:06.3f}" if m else f"{s:.3f}"
