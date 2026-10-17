"""Game-specific knowledge for FIFA International Soccer (SNES, EUR, LoROM 8 Mbit).

All addresses were found by tracing the user's own cartridge dump (see docs/memory_map.md).
Nothing from the ROM is stored here: only addresses and the stub we wrote ourselves.
"""
import hashlib
from typing import Dict, List

from . import w65816

KNOWN_DUMPS = {
    "f9967028eca3be77cba97bf46705fe0b96bb79b6": "FIFA International Soccer (Europe) [cartridge dump, 1 MiB]",
}

# 65816 side ------------------------------------------------------------------------------
DRIVER_UPLOAD = 0x80A937      # JSL; IPL uploader, data pointer in [$0C] (list of len,addr,data; len=0 -> jump)
DRIVER_IMAGE = 0x00BDE0       # long pointer of the IPL block list holding the SPC700 driver
SOUND_RESET = 0x809CE4        # JSL; reset sound engine state ($74 <- $44 data bank)
SOUND_TICK = 0x809F09         # JSR (bank $80); per-frame tick, called from the NMI handler
SOUND_DEFS = 0x80EB9D         # [$42]: table of 16-bit offsets to 48-byte sound/instrument records
PLAY_MUSIC = 0x80DBAC         # JSL; X = song number: loads its sample set, then starts it if enabled
MUSIC_ENABLE = 0x7E47FF       # byte; non-zero = music enabled (PLAY_MUSIC only loads samples otherwise)
SONG_TABLE = 0x80DA34         # 4 bytes per song: 24-bit pointer to song data (song 0 = none)
SAMPLESET_OF_SONG = 0x80DA04  # 2 bytes per song: sample-set id
SAMPLESET_TABLE = 0x80D91C    # 4 bytes per sample set: 24-bit pointer to the set descriptor
SONGS = list(range(1, 9))     # songs 1..8 (see docs)

# free ROM space used for the SNSF stub: $80:F6B7-$80:FEFF is $FF-filled in this dump
STUB_ORG = 0x80F6C0
TRACK_BYTE = 0x80F6BC         # the minisnsf files override this one byte


def identify(rom: bytes) -> Dict[str, object]:
    """Parse the internal header (LoROM at $7FC0 / HiROM at $FFC0) and verify the checksum."""
    info: Dict[str, object] = {"size": len(rom), "sha1": hashlib.sha1(rom).hexdigest()}
    copier = len(rom) % 0x8000 == 512
    if copier:
        rom = rom[512:]
    info["copier_header"] = copier
    best = None
    for name, base in (("LoROM", 0x7FC0), ("HiROM", 0xFFC0)):
        if base + 0x40 > len(rom):
            continue
        h = rom[base:base + 0x40]
        cmp_, chk = h[0x1C] | h[0x1D] << 8, h[0x1E] | h[0x1F] << 8
        score = (cmp_ ^ chk) == 0xFFFF
        if best is None or score:
            best = (name, base, h, cmp_, chk, score)
            if score:
                break
    name, base, h, cmp_, chk, ok = best
    regions = {0: "Japan", 1: "USA", 2: "Europe", 3: "Sweden", 6: "France", 8: "Spain", 9: "Germany",
               10: "Italy", 0xD: "Netherlands"}
    total = sum(rom) & 0xFFFF
    info.update({
        "title": h[:21].decode("ascii", "replace").rstrip(),
        "mapping": name, "map_mode": h[0x15], "fastrom": bool(h[0x15] & 0x10),
        "rom_size_kb": 1 << h[0x17], "sram_kb": (1 << h[0x18]) if h[0x18] else 0,
        "region": regions.get(h[0x19], f"0x{h[0x19]:02x}"), "video": "PAL" if h[0x19] >= 2 else "NTSC",
        "licensee": f"0x{h[0x1A]:02x}", "version": h[0x1B],
        "checksum": f"0x{chk:04x}", "complement": f"0x{cmp_:04x}", "computed_checksum": f"0x{total:04x}",
        "checksum_ok": ok and total == chk,
        "reset_vector": f"0x{h[0x3C] | h[0x3D] << 8:04x}", "nmi_vector": f"0x{h[0x2A] | h[0x2B] << 8:04x}",
        "known_dump": KNOWN_DUMPS.get(info["sha1"], None),
    })
    return info


def stub_source() -> str:
    """SNSF driver stub: init hardware + sound, start song (byte at TRACK_BYTE), tick from NMI."""
    return f"""
track:
 .db $01
 .db $ff,$ff,$ff
reset:
 sei
 clc
 xce
 rep #$30
 ldx #$1fff
 txs
 lda #$0000
 tcd
 jml >reset_fast
reset_fast:
 phk
 plb
 sep #$20
 stz $4200
 lda #$8f
 sta $2100
 stz $420c
 stz $2181
 stz $2182
 stz $2183
 lda #$08
 sta $4300
 lda #$80
 sta $4301
 ldx #zero
 stx $4302
 lda #^zero
 sta $4304
 ldx #$0000
 stx $4305
 lda #$01
 sta $420b
 ldx #$0000
 stx $4305
 lda #$01
 sta $420b
 rep #$30
 lda #${DRIVER_IMAGE & 0xFFFF:04x}
 sta $0c
 lda #${DRIVER_IMAGE >> 16:04x}
 sta $0e
 jsl ${DRIVER_UPLOAD:06x}
 rep #$30
 lda #${SOUND_DEFS >> 16:04x}
 sta $44
 lda #${SOUND_DEFS & 0xFFFF:04x}
 sta $42
 jsl ${SOUND_RESET:06x}
 sep #$20
 lda #$01
 sta ${MUSIC_ENABLE:06x}
 rep #$30
 lda track
 and #$00ff
 tax
 jsl ${PLAY_MUSIC:06x}
 sep #$20
 lda #$80
 sta $4200
 cli
idle:
 wai
 bra idle
nmi:
 jml >nmi_fast
nmi_fast:
 rep #$30
 phd
 phb
 pha
 phx
 phy
 phk
 plb
 lda #$0000
 tcd
 sep #$20
 lda $4210
 rep #$30
 jsr ${SOUND_TICK & 0xFFFF:04x}
 rep #$30
 ply
 plx
 pla
 plb
 pld
irq:
 rti
zero:
 .db $00
"""


def build_rom(rom: bytes, track: int = 1) -> bytes:
    """Return a copy of the ROM with the SNSF stub installed and vectors redirected."""
    out = bytearray(rom)
    org = TRACK_BYTE
    code = w65816.assemble(stub_source(), org)
    labels = _labels(stub_source(), org)
    start = w65816.lorom_offset(org)
    end = start + len(code)
    if end > w65816.lorom_offset(0x80FF00) or any(b != 0xFF for b in rom[start:end]):
        raise ValueError("stub area is not free in this ROM")
    out[start:end] = code
    out[w65816.lorom_offset(TRACK_BYTE)] = track & 0xFF

    def vec(off, addr):
        out[off] = addr & 0xFF
        out[off + 1] = addr >> 8 & 0xFF
    vec(0x7FFC, labels["reset"])   # emulation-mode RESET
    vec(0x7FEA, labels["nmi"])     # native NMI
    vec(0x7FEE, labels["irq"])     # native IRQ
    vec(0x7FE6, labels["irq"])     # native BRK
    vec(0x7FE4, labels["irq"])     # native COP
    vec(0x7FFA, labels["nmi"])     # emulation NMI
    vec(0x7FFE, labels["irq"])     # emulation IRQ/BRK
    return bytes(out)


def _labels(src: str, org: int) -> Dict[str, int]:
    syms: Dict[str, int] = {}
    w65816.assemble(src, org, syms)
    return syms
