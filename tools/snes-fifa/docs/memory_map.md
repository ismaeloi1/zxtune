# Memory map (European cartridge, sha1 f9967028eca3be77cba97bf46705fe0b96bb79b6)

Everything below was found by tracing the user's own dump with `snestrace`. "Confirmed" means we
saw it happen in the trace. "Inferred" means we read it from the code but did not test it on its own.

## Cartridge

| Field | Value |
|---|---|
| Internal title | `FIFA SOCCER` |
| Mapping | LoROM, FastROM (map mode $30), 8 Mbit (1 MiB), no SRAM, no copier header |
| Region | $02 Europe → PAL (snes9x runs it at 50.007 Hz) |
| Licensee byte / version | $67 / 0 |
| Checksum / complement | $22E9 / $DD16, matches the computed sum (good dump) |
| Vectors | RESET $8034, NMI $818B (native) |
| Free space | $80:F6B7-$80:FEFF is $FF-filled. The SNSF stub lives there (`fifa.STUB_ORG`). |

## 65816 side (bank $80 unless stated)

| Address | What | Status |
|---|---|---|
| $80:A937 | IPL uploader (JSL). Reads a list `{u16 length, u16 ARAM address, data}` from `[$0C]`. Length 0 means jump to that address. | confirmed |
| $00:BDE0 | IPL list holding the SPC700 driver: one 1276-byte block at ARAM $0380, entry point $0380 | confirmed |
| $80:9CE4 | sound engine reset (copies the data bank `$44` to `$74`, clears the WRAM state $0913-$0AB5, cuts all voices) | confirmed |
| $80:9F09 | per-frame sound tick, called (JSR) from the NMI handler at $80:84B7 | confirmed |
| $80:9E3B | "play sound record A", records reached through `[$42]` | confirmed |
| `[$42]` = $80:EB9D | table of u16 offsets (relative to $EB9D) to 48-byte sound/instrument records. 157 entries; offset $013A is the empty record. | confirmed |
| record byte $14 | sample id. The driver SRCN is `[$7A][id]`. | confirmed |
| record bytes $15/$16 | ADSR1/ADSR2 sent with VOICE_SETUP. If bit 7 of $15 is clear, another path sends a software envelope (cmd 19). | confirmed / inferred |
| `$7A` (dp, long) | sample-id → DIR-slot map. Front end: $80:D8B4 (set by $80:DB54). In a match: $80:D8E8 (set by $80:DB2D). | confirmed |
| $80:DBAC | **play music**, X = song (1-8). Calls $80:DAB2 (sample sets) and, if `$7E:47FF` ≠ 0, $80:DAE3 (start song) | confirmed |
| $80:DAB2 | loads the common sample list $80:D9E4, then the song's set `SET[$80:DA04 + 2·song]` through $80:DA6A | confirmed |
| $80:D91C | sample-set table: 4 bytes per set → descriptor, uploaded by $80:9462 / $80:93A5 (SAMPLE_QUERY + SAMPLE_UPLOAD) | confirmed |
| $80:DA34 | song table: 4 bytes per song → 24-bit pointer. Song 0 is null. Songs 1..8 point to $97:8000, $99:A4CB, $96:8000, $92:BCC5, $98:8000, $97:A13A, $9C:966F, $9A:8000. | confirmed (pointers), contents not decoded |
| $80:DAE3 → $80:9D5D | start song: sound reset, then reads the song header at `[$0C]`. The tempo is scaled by a PAL/NTSC factor ($FF78 / $D4E4, chosen from $213F bit 4). | inferred |
| $80:DBDD | rotates songs 2, 3, 4 (counter $0AE4), probably during matches | inferred |
| $80:9342 / $80:9354 | TIMER command (target 13); called when $213F says PAL | confirmed |
| $80:9364 / $80:9371 | SAMPLE_RESET | confirmed |
| $80:9425 | SAMPLE_FREE (X = slots) | confirmed |
| $80:A6F5..$80:A8AA | voice-level command senders (VOICE_SETUP $A792, KEY_ON $A841, VOICE_CUT $A888, PITCH $A8AA, VOICE_VOLUME $A739..) | confirmed |
| `$6D` | engine lock (decremented around API calls; the tick does nothing while it is non-zero) | confirmed |

ROM regions needed by music playback (found by zero-filling regions and checking that every song's
port stream stays identical over 4 minutes): all of bank $80 (code), plus 44 of the 4 KiB blocks in
banks $92-$9D. That covers sequences and BRR data. 21 of the 32 banks are not needed. The list is in
`out/minimize.json`.

## SPC700 side (ARAM)

| Range | Use |
|---|---|
| $0000-$008B | driver variables (cleared at start) |
| $0100-$01FF | stack (SP=$FF) |
| $0200-$02FF | sample directory (DSP DIR = $02), 4 bytes per slot, filled by SAMPLE_UPLOAD |
| $0300-$037F | cleared by the driver, unused |
| $0380-$087B | driver code (1276 bytes, uploaded once at boot, never re-uploaded) |
| $087C-… | BRR samples, appended in upload order. 16 slots per song, 51-62 KB. |
| top | free. No echo buffer is used (FLG keeps echo writes disabled). |

Timer 0 runs at target $10 (or 13 = 1.625 ms after the TIMER command). It only drives the software
envelopes (commands 19/20), which the music does not use.
