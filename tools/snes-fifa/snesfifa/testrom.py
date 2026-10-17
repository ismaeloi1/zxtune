"""A tiny homebrew SNES test ROM (written from scratch, no game data) used to test the tool
chain without the FIFA cartridge.

The 65816 side uploads two blocks to the SPC700 through the IPL protocol (code at $0200,
sample directory + a synthetic BRR wave at $0300), starts it, then every N frames sends a
'note' command ($2140 = sequence counter, $2141 = pitch high byte).  The SPC700 program keys
voice 0 with that pitch and echoes the counter, exactly like a minimal command-server driver.
"""
import math
from typing import Dict, List, Tuple

from . import brr, spc700, w65816

SPC_CODE = """
 mov $f2,#$6c
 mov $f3,#$20
 mov $f2,#$5d
 mov $f3,#$03
 mov $f2,#$0c
 mov $f3,#$60
 mov $f2,#$1c
 mov $f3,#$60
 mov $f2,#$00
 mov $f3,#$60
 mov $f2,#$01
 mov $f3,#$60
 mov $f2,#$04
 mov $f3,#$00
 mov $f2,#$05
 mov $f3,#$8f
 mov $f2,#$06
 mov $f3,#$f0
 mov $f2,#$02
 mov $f3,#$00
 mov $f2,#$03
 mov $f3,#$10
 mov $f2,#$5c
 mov $f3,#$00
 mov $00,#$00
 mov $f4,#$00
loop:
 mov a,$f4
 cmp a,$00
 beq loop
 mov $00,a
 mov $f2,#$03
 mov $f3,$f5
 mov $f2,#$4c
 mov $f3,#$01
 mov a,$00
 mov $f4,a
 bra loop
"""

NOTES = [0x08, 0x0A, 0x0C, 0x0D, 0x10, 0x0C, 0x0A, 0x09, 0x08, 0x10, 0x14, 0x18]


def spc_blocks() -> List[Tuple[int, bytes]]:
    code = spc700.assemble(SPC_CODE, 0x0200)
    wave = [int(9000 * math.sin(2 * math.pi * i / 32)) for i in range(32)]
    sample = brr.encode(wave * 4, loop_start=0)
    # directory at $0300: one entry (start, loop) pointing right after it
    start = 0x0304
    data = bytes([start & 0xFF, start >> 8, start & 0xFF, start >> 8]) + sample
    return [(0x0200, code), (0x0300, data)]


def cpu_source(blocks: List[Tuple[int, bytes]], frames_per_note: int = 15) -> str:
    lines = [f" .db {','.join(f'${b:02x}' for b in blk[i:i + 16])}" for _, blk in blocks
             for i in range(0, len(blk), 16)]
    data_labels = []
    src = """
reset:
 sei
 clc
 xce
 rep #$10
 sep #$20
 ldx #$1fff
 txs
 lda #$00
 pha
 plb
 stz $4200
 ldx #$bbaa
waitready:
 cpx $2140
 bne waitready
 lda #$cc
 sta $00
"""
    for n, (addr, blk) in enumerate(blocks):
        src += f"""
 ldx #${addr:04x}
 stx $2142
 lda #$01
 sta $2141
 lda $00
 sta $2140
ack{n}:
 cmp $2140
 bne ack{n}
 ldy #$0000
byte{n}:
 lda blk{n},y
 sta $2141
 tya
 sta $2140
wb{n}:
 cmp $2140
 bne wb{n}
 iny
 cpy #${len(blk):04x}
 bne byte{n}
 tya
 clc
 adc #$02
 bne nz{n}
 inc a
nz{n}:
 sta $00
"""
    src += """
 ldx #$0200
 stx $2142
 stz $2141
 lda $00
 sta $2140
ackj:
 cmp $2140
 bne ackj
 stz $2140
 ldy #$0000
 lda #$01
 sta $01
notes:
"""
    src += f"""
 ldx #${frames_per_note:04x}
vbl:
 lda $4212
 bpl vbl
vbl2:
 lda $4212
 bmi vbl2
 dex
 bne vbl
 lda notes_tab,y
 sta $2141
 lda $01
 sta $2140
wn:
 cmp $2140
 bne wn
 inc $01
 bne nzc
 inc $01
nzc:
 iny
 cpy #${len(NOTES):04x}
 bne notes
 ldy #$0000
 bra notes
nmi:
 rti
notes_tab:
 .db {','.join(f'${n:02x}' for n in NOTES)}
"""
    for n, (_, blk) in enumerate(blocks):
        src += f"blk{n}:\n"
        for i in range(0, len(blk), 16):
            src += " .db " + ",".join(f"${b:02x}" for b in blk[i:i + 16]) + "\n"
    return src


def build() -> Tuple[bytes, List[Tuple[int, bytes]]]:
    """Return (32 KiB LoROM image, uploaded SPC700 blocks)."""
    blocks = spc_blocks()
    syms: Dict[str, int] = {}
    code = w65816.assemble(cpu_source(blocks), 0x008000, syms)
    rom = bytearray(b"\xff" * 0x8000)
    rom[:len(code)] = code
    hdr = bytearray(0x40)
    hdr[0:21] = b"SNES-FIFA TOOLS TEST ".ljust(21)[:21]
    hdr[0x15] = 0x20      # LoROM, SlowROM
    hdr[0x16] = 0x00
    hdr[0x17] = 0x05      # 32 KiB
    hdr[0x19] = 0x01      # USA / NTSC
    hdr[0x1A] = 0x00
    for off in (0x24, 0x26, 0x28, 0x2A, 0x2E, 0x34, 0x38, 0x3A, 0x3E):
        hdr[off:off + 2] = (syms["nmi"] & 0xFFFF).to_bytes(2, "little")
    hdr[0x3C:0x3E] = (syms["reset"] & 0xFFFF).to_bytes(2, "little")
    rom[0x7FC0:0x8000] = hdr
    rom[0x7FDC:0x7FE0] = b"\xff\xff\x00\x00"
    chk = sum(rom) & 0xFFFF
    rom[0x7FDC:0x7FE0] = (chk ^ 0xFFFF).to_bytes(2, "little") + chk.to_bytes(2, "little")
    return bytes(rom), blocks
