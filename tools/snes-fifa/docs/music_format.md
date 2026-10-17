# Music data (65816 side)

## What is known

* **Songs**: 8 songs, numbered 1-8 by the game (`PLAY_MUSIC`, X = song). The song table at $80:DA34 holds
  one 24-bit pointer per song. Song data lives in banks $92-$9C. Song 0 means "none".
* **Song start** ($80:DAE3 → $80:9D5D): the second byte of the song header is a tempo, scaled by a
  PAL or NTSC factor before use. The 65816 advances a fractional tempo accumulator each frame. That is
  why repeated bars are ±1 frame apart in the traces, and why loop lengths are not whole numbers of
  frames (e.g. song 1: 5599.5 frames).
* **Instruments**: 48-byte records reached through `[$42]` = $80:EB9D (157 slots, many empty). The
  music and the sound effects share them. Byte $14 is a *sample id*, bytes $15/$16 are the hardware
  ADSR. Other bytes (volume, pitch base, flags at $05/$06/$2F…) are read by the per-frame code; we did
  not map them.
* **Sample id → DIR slot**: a map table at `[$7A]`. In the front end (title/menus) it is $80:D8B4: every
  id maps to one of the 16 slots the song set occupies. In a match the game uses $80:D8E8: ids map to
  slots 16+ (after the match sound effects), and many ids map to $FF (not loaded). **Hypothesis**:
  songs heard during a match therefore use fewer instruments than in the front end. Our rips use the
  front-end map. With it, every note of all 8 songs points at a loaded sample, and song 1 matches the
  game's title screen command for command.
* **Samples**: sample sets are listed at $80:D91C. `PLAY_MUSIC` loads the common list $80:D9E4 (empty
  in practice), then the song's set `[$80:DA04 + 2·song]`: set $13 for song 1, $14 for songs 2-5, and
  others for 6-8. Each sample is sent as raw BRR with its loop offset (SAMPLE_UPLOAD). The extracted
  BRR files are written from ARAM after the upload. Their loop points come from the DIR and their END/LOOP flags.

## What is not decoded

The byte format of the song data, meaning the event codes inside the sequences, was **not** reverse
engineered. Instead we capture what the sequencer *does*, and that is complete and exact:

* the command stream per song (`commands.tsv`), timestamped in SPC700 clocks;
* the DSP register writes it produces (`snesfifa.dsplog`);
* a MIDI transcription (`songNN.mid`): one channel per sample, note numbers relative to
  pitch $1000 = C-4 (the samples' real root keys are unknown), velocity and pan from the voice volumes,
  pitch bends for in-note pitch changes;
* the loop: found by matching the note stream against a delayed copy of itself
  (`dsplog.find_loop`). It is reported as intro + loop length, and the SNSF length tags use it.

Decoding the sequence bytes would be the next step toward a "native" MIDI export (real note
lengths, instrument numbers, tempo). The entry points are listed in memory_map.md.
