# Status: results, validation, confirmed facts and hypotheses

Measured on the user's dump (EUR, sha1 f9967028…) with `scripts/run_all.sh` on 2026-10-17.

## Plan steps

| Step | State |
|---|---|
| 1. ROM identification, headless emulator, disassemblers | done: LoROM/FastROM 8 Mbit PAL, checksum OK. snes9x + hook patch → `snestrace`. SPC700/65816 disassemblers and assemblers are checked against snes9x's own tables (tests). |
| 2. IPL trace, blocks, streaming? | done: a single 1276-byte block at $0380, uploaded once at boot. Samples are sent per song through the driver protocol before playback. **No streaming during playback.** |
| 3. Driver disassembly and protocol | done: 20 commands documented (driver_protocol.md). The decoder is `protocol.py`. |
| 4. 65816 routines, song pointers, instrument tables, BRR directory | done for the entry points, song table, sample sets, instrument records and sample map (memory_map.md). **The song byte format is not decoded.** |
| 5. BRR samples, MIDI | samples: done (exact, with loops). MIDI: transcription of the command stream, not a decode of the song data. |
| 6. CLI: command stream, WAV render, SNSF | done (`python3 -m snesfifa.cli`) |
| 7. Validation by cross-correlation | done (tables below) |
| Extra: reassembled standalone SPC | done for 7 of 8 songs. Song 1 cannot fit (explained below). |

## Songs

Intro and loop come from the note stream (`dsplog.find_loop`). Correlations are median / 10th
percentile of 1-second windows, after re-aligning each window locally (±50 ms) to absorb tempo
drift. The reference is the snes9x capture of the SNSF ROM (`emu_capture.wav`).

| Song | Intro s | Loop s | Notes/loop | Samples B | snes_spc replay vs emu | Reassembled SPC fits? | Reassembled vs emu (1st pass) |
|---|---|---|---|---|---|---|---|
| 1 | 44.8 | 112.0 | 1572 | 61461 | 0.999 / 0.990 | no (17476 B needed, 3858 B free) | – |
| 2 | 22.5 | 44.1 | 584 | 61794 | 0.971 / 0.930 | yes (12075 of 15531 B, sub-frame 256 clk) | 0.959 / 0.926 |
| 3 | 0.0 | 90.1 | 1359 | 61794 | 0.964 / 0.862 | yes (15421 of 21849 B, 256 clk) | 0.935 / 0.835 |
| 4 | 12.2 | 85.8 | 1678 | 61794 | 0.974 / 0.926 | yes (15070 of 15630 B, 1024 clk) | 0.902 / 0.856 |
| 5 | 15.9 | 50.3 | 691 | 61794 | 0.953 / 0.899 | yes (11322 of 18060 B, 256 clk) | 0.958 / 0.916 |
| 6 | 1.2 | 65.8 | 1017 | 53685 | 0.973 / 0.928 | yes (14278 of 43998 B, 256 clk) | 0.954 / 0.925 |
| 7 | 0.0 | 48.4 | 551 | 51273 | 0.981 / 0.957 | yes (6129 of 42315 B, 256 clk) | 0.972 / 0.939 |
| 8 | 0.5 | 41.0 | 852 | 52029 | 0.955 / 0.921 | yes (8826 of 40614 B, 256 clk) | 0.962 / 0.918 |

SNSF set: `fifa.snsflib` is 147582 bytes (the minimised ROM, 21 of 32 banks zeroed, plus our
198-byte stub). The eight `fifa_NN.minisnsf` files (≈93 bytes each) each override one byte, the song
number. Each minisnsf resolved through its `_lib` is byte-identical to the stub ROM the captures were made with
(`snsf_rom_matches_stub`). The minimised ROM gives the same port stream as the full ROM for all
songs over 4 minutes.

**SNSF vs the real game.** We booted the untouched ROM and let it reach the title screen. Song 1
started there via the game's own code. The command stream of the game and of the SNSF were
identical for the first 11 624 commands (56 s), until the game left the title screen. The audio
correlates at 0.958 median (0.85 p10).

**Homebrew test ROM** (`testrom.py`, no game data): both IPL blocks are recovered byte-exact, and
the replay correlates with the emulator at 0.997.

### Why the replay is not 1.000

`spcreplay` uses blargg's `snes_spc` (zxtune's copy) and snes9x uses its own SMP core. The two
differ in small timing details: handshake waits shift writes by up to ~370 SPC clocks, and the DSP
output is resampled differently. Song 1, whose voices are mostly slow pads, reaches 0.999. Songs with
sharp percussion are around 0.95-0.97.

### Reassembled SPCs: how and limits

The reassembled SPC keeps the samples a song really uses, packed and with the DIR rewritten. It
drops the original driver (only a command server) and installs our 412-byte SPC700 player, which
writes the DSP registers from a compact token stream. The stream covers the intro plus one loop,
then jumps back to the loop start. It uses a note dictionary, LZ-style back references and
sub-frame wait tokens that keep each write's position inside the frame. Timer 1 has an 8-bit
fractional accumulator, so the frame rate matches the trace (20502.6 SPC clocks per frame).

* After the loop point the reassembled SPC repeats pass 1 literally. The game does not: its tempo
  accumulator shifts notes by ±1 frame between passes and it allocates voices differently. Windows
  after the loop point therefore correlate lower (0.5-0.9) although the music is the same. The table
  reports the first pass.
* **Song 1 does not fit.** It uses 61038 of its 61461 sample bytes (15 of 16 samples). That leaves 3858 bytes
  of ARAM, even with the driver area reclaimed and no echo buffer. The smallest encoding we built
  (frame-exact timing, no sub-frame tokens) needs 13060 bytes of stream, 3976 bytes of note dictionary
  and 412 bytes of player, 17476 bytes in all. Re-implementing the 65816 sequencer on the SPC700 would
  not help either. Song 1's data alone takes up to 8.5 KB in ROM (the distance from its pointer $97:8000
  to the next song's $97:A13A), more than twice the free ARAM. Only lossy changes could make it fit:
  resampling or dropping samples, or shortening the song.
* Song 4 fits only with coarser sub-frame timing (1024 clocks), hence its lower 0.902.

## Confirmed vs hypothesis

Confirmed (seen in traces or proven by a test):
* LoROM/FastROM/PAL header and checksum; SPC700 driver = one IPL block of 1276 bytes at $0380, never re-uploaded.
* The SPC700 driver has no sequencer: all 20 command handlers are register/sample servers. The 65816 sends per-frame commands from NMI. → SPC dumps are impossible and SNSF is required (VGMPF's statement holds, and the hypothesis in the task is confirmed).
* Command semantics as listed in driver_protocol.md. Confirmed by DSP register state comparisons (64/64 voice registers identical between the replayed original and our re-implementation at sampled times) and by the reassembled SPCs' audio.
* Samples are uploaded per song before the first note, 16 slots. There is no streaming during playback.
* 8 songs, song table $80:DA34, play entry $80:DBAC. The SNSF made from it matches the title-screen music of the unmodified game.
* Sample-id → slot map at `[$7A]`. Without setting it, every note plays sample 0, so the SNSF stub sets the front-end map.
* Loops: songs 1-8 repeat after the intro with the lengths above (≥0.93 of notes match between passes).

Hypotheses or not verified:
* The game uses the match sample map ($80:D8E8) for songs heard during a match. Many instrument ids
  then map to $FF, so in-match renditions may lack instruments. We rip the front-end versions.
* Song 1 is the title theme (verified). The roles of songs 2-8 (menus, match intro, half-time, etc.) were not checked in game. $80:DBDD rotating songs 2-4 suggests in-match or menu rotation.
* Record fields other than $14/$15/$16 and the song data byte format are unknown.
* The MIDI note numbers assume each sample's root key is C-4 at pitch $1000. The real tuning per sample is unknown.
* Composer/driver credits are taken from VGMPF and could not be checked from the ROM.

## Not done / blocked

* Decoding the sequence bytes, which would give a "native" MIDI. The entry points are documented.
* No test in a real SNSF player (snsf9x / Highly Competitive are Windows plug-ins and are not available
  here). The SNSF set was checked by resolving `_lib` with our loader and running the result in
  snes9x. It follows CaitSith2's v0.03 spec: LoROM header kept, `_video=PAL` tag, snsflib at offset 0.
* zxtune has no SNSF player, so these files will not play in zxtune. The reassembled `.spc` files will.
