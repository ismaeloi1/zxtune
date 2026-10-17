# snes-fifa: music extraction for *FIFA International Soccer* (SNES, 1994)

These are tools to rip the music of the SNES game from **your own cartridge dump** into formats you can play back:

| Output (per song) | What it is | Fidelity |
|---|---|---|
| `fifa.snsflib` + `fifa_NN.minisnsf` | SNSF: the game's own 65816 sound code and data, plus a 198-byte stub of ours that starts song NN. Non-music ROM banks are zeroed. | exact (the game's code runs) |
| `songNN/commands.tsv`, `songNN/ports.tsv` | decoded driver commands and the raw 65816↔SPC700 port log, timestamped in SPC700 clocks | exact recording |
| `songNN/initial_state.spc` | ARAM/DSP snapshot just before the first note (driver + samples) | silent on its own (see below) |
| `songNN/replay_snes_spc.wav` | the port log replayed into `snes_spc` (zxtune's `3rdparty/snesspc`) | ~0.95-1.0 correlation with the emulator |
| `songNN/songNN_reassembled.spc` | standalone SPC: our own SPC700 player feeds the recorded stream to the DSP | 7 of 8 songs fit in 64 KB (not song 1) |
| `songNN/samples/` | every BRR sample as `.brr` and `.wav` (with a `smpl` loop chunk), plus `samples.json` | exact |
| `songNN/songNN.mid` | MIDI transcription of the command stream | approximate (see docs) |
| `songNN/emu_capture.wav` | snes9x audio capture of the SNSF ROM (the reference) | reference |
| `summary.json`, `songNN/result.json` | loop points, sizes, validation numbers | – |

Why SNSF: the SPC700 program in this game is only a command server. The sequencer runs on the
65816 and sends DSP-level commands every frame, so an SPC snapshot never plays a note. See
[docs/driver_protocol.md](docs/driver_protocol.md).

## Legal

Use only a ROM you dumped from a cartridge you own. Nothing from the game is stored in this
directory: no ROM, no disassembly listing, no samples, no audio. The scripts read the ROM from
`$FIFA_ROM`, and everything they produce stays in your output directory (`.gitignore` covers ROM
and audio formats). snes9x is downloaded and built at a pinned commit (it has its own non-commercial
licence) and is never vendored.

## Quick start (Linux)

```sh
export FIFA_ROM=/path/to/your/fifa.sfc        # European cartridge, sha1 f9967028…
tools/snes-fifa/scripts/run_all.sh /tmp/fifa-out
```

Requirements: `g++`, `zlib`, `git`, Python 3.9+, `numpy`. The script runs `emu/build.sh`, which fetches snes9x and
builds the headless tracer `build/snestrace`. It runs `replay/build.sh`, which builds `build/spcreplay` against zxtune's
`3rdparty/snesspc`. Then it runs the tests and does the extraction. A full run takes about 15 minutes, of which the one-off
ROM minimisation is 12 minutes. The minimisation is cached in `OUT/minimize.json`.

Single steps:

```sh
cd tools/snes-fifa
python3 -m snesfifa.cli identify                 # header, mapping, region, checksum
python3 -m snesfifa.cli driver   --out OUT       # SPC700 driver block(s) from the IPL trace + disassembly
python3 -m snesfifa.cli minimize --out OUT       # find the ROM regions playback needs
python3 -m snesfifa.cli snsf     --out OUT       # SNSF set
python3 -m snesfifa.cli track    --out OUT -t 3  # everything for song 3
build/snestrace --rom X.sfc --frames 3000 --log ports.tsv --wav out.wav --spc 300:s.spc --aram 300:a.bin \
                --exec-trace t.txt --exec-range 100-101 --coverage cov.txt --input 600:START
build/spcreplay --spc s.spc --log ports.tsv --start-clock C --out replay.wav
python3 -m snesfifa.xcorr reference.wav test.wav [--expected-lag S]
python3 -m snesfifa.calltree X.sfc t.txt --frame 100 --depth 6
```

## Layout

```
emu/       snes9x hook patch, headless tracer (snestrace.cpp), build script
replay/    spcreplay.cpp: port-log → snes_spc → WAV (handshake-aware)
snesfifa/  Python package
  portlog, ipl, protocol, dsplog   trace parsing: IPL uploads, driver commands, DSP writes, loops
  spc700, w65816                   disassemblers + small assemblers (checked against snes9x's tables)
  brr, spcfile, snsf, midi         formats
  fifa                             game addresses + SNSF stub
  minimize, rip, reassemble, cli   pipeline, ROM minimisation, standalone-SPC player
  xcorr, calltree, testrom         validation, call trees from exec traces, homebrew test ROM
tests/     unit + integration tests (homebrew ROM always; FIFA tests only if $FIFA_ROM is set)
docs/      research.md, memory_map.md, driver_protocol.md, music_format.md, status.md
```

zxtune itself does not play SNSF (`src/module/players/xsf` has 2SF/GSF/NCSF/PSF/SSF/DSF/USF only). To
play the rips, use an SNSF player (snsf9x, Highly Competitive, foobar2000 + vgmstream/xSF). The
reassembled `.spc` files play in zxtune and any SPC player.

The results, validation numbers and the list of confirmed facts versus hypotheses are in [docs/status.md](docs/status.md).
