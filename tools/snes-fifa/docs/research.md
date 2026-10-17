# Background research

Collected 2026-10-17. Quotes are short; see the sources for the full text.

## What the community says about this game

| Claim | Source | Status after our analysis |
|---|---|---|
| The SNES sound driver of *FIFA International Soccer* is by Kris Hatlelid and Alan Stewart (Distinctive Software / EA Canada). The same driver family is listed for John Madden '93, NBA Live 95/96, NBA Showdown, NHL '94, Test Drive II, the two Carmen Sandiego games. | VGMPF, *Super Famicom/Super NES Sound Driver List* (section "Distinctive Software/Electronic Arts Canada (Kris Hatlelid, Alan Stewart)"), <https://www.vgmpf.com/Wiki/index.php?title=Super_Famicom/Super_NES_Sound_Driver_List> | Not checkable from the ROM (no credits in the code). Alan Stewart's VGMPF page credits him with "Sound Technology" on this game. |
| "By the way the engine was programmed, SPC files are undumpable and instead must be ripped as SNSF files"; music "written in assembly". | VGMPF, *Kris Hatlelid*, section SNES, <https://www.vgmpf.com/Wiki/index.php?title=Kris_Hatlelid> | **Confirmed**: the SPC700 program has no sequencer (see driver_protocol.md). Whether the music was written in assembly or converted from MIDI cannot be told from the ROM. The plan's "MIDI converter per VGMPF" does not match these pages: VGMPF says the Eastridge driver converted MIDI, and says the Hatlelid/Stewart music was written in assembly. |
| Listed under "Unsupported Games" (SPC impossible) together with Eastridge, Heineman, etc. | VGMPF, *SPC*, section "Unsupported Games", <https://www.vgmpf.com/Wiki/index.php?title=SPC> | Confirmed (same reason as above). |
| No SNSF rips existed for these games. | VGMPF *SNSF* page (list of games with SNSF rips does not include it), <https://www.vgmpf.com/Wiki/index.php?title=SNSF>; CaitSith2's SNSF site lists only Blackthorne, Chrono Trigger, LotR, Lost Vikings, Noah's Ark, Star Ocean, Tales of Phantasia, Wolfenstein 3D, <http://snsf.caitsith2.net/> | — |
| snesmusic.org has no set for *FIFA International Soccer*. Its "F" list has sets for FIFA 96, FIFA 97 and FIFA 98 only. | <https://snesmusic.org/v2/select.php?view=sets&char=F&limit=0> (and the next pages). We only looked at the set list and downloaded no rips. | Consistent with "undumpable". |
| VGMPF has no page for the SNES game itself. A search shows only links to it, from the Hatlelid/Stewart pages and the SPC "unsupported" list. | VGMPF search | — |
| The comparable "Eastridge" driver is MIDI-converted and SPC-undumpable. KungFuFurby rewrote such drivers to make SPCs. | VGMPF, *Eastridge Technology* | Background only. |

We could not reach the snesmusic.org wiki page "Undumpable" (404 at the URLs we tried). snes.nesdev.org returned HTTP 403 to our client. The SPC700/IPL facts we use are textbook ones: the IPL handshake, DSP register map and BRR format, as also documented on <https://snes.nesdev.org/wiki/> and <https://sneslab.net/>. Every one of them was checked against what the emulator showed.

## SNSF / PSF format

* PSF container (Neill Corlett): `"PSF"` plus a version byte, then three u32 values (reserved size, compressed program size, CRC-32 of the compressed program). Then come the reserved area, the zlib-compressed program, and optional `[TAG]name=value` lines. In the psflib loading order, `_lib` comes first, then the file itself, then `_lib2`, `_lib3`, and so on.
* SNSF v0.03 (CaitSith2, <http://snsf.caitsith2.net/snsf%20spec.txt>) uses version byte `0x23`. The program is u32 load offset, u32 size, then a ROM image "patched to only play back music". The reserved area may hold SRAM blocks. The tags are `_memory` (HiROM/LoROM), `_video` (NTSC/PAL) and `_sramfill`. A snsflib may not load past 0x7F00 (LoROM), but minisnsf files may.
* Players: Highly Competitive / snsf9x (both based on snes9x).
* **zxtune does not play SNSF**. `src/module/players/xsf` implements 2SF, GSF, NCSF, PSF/PSF2, SSF/DSF and USF only. `grep -ri snsf src` finds nothing.

## Emulator choice

We use snes9x (pinned commit, fetched at build time and never vendored), with a 100-line patch adding four hooks. The hooks fire on 65816 port writes/reads, SPC700 port writes and 65816 instruction fetch. A cumulative SMP-clock counter is also added. The snes9x libretro frontend code is linked straight into a small headless runner (`emu/snestrace.cpp`). Mesen2 and bsnes would also work, but snes9x builds in seconds with only `g++` and `zlib`. Its APU core (byuu's SMP + blargg's DSP) is close to blargg's `snes_spc`, which zxtune ships in `3rdparty/snesspc` and which we use for replays.
