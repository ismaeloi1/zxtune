# SPC700 driver and 65816 ↔ SPC700 protocol

## Upload

At boot the 65816 uploads **one** block through the standard IPL protocol: 1276 bytes at $0380,
then a jump to $0380. The driver is never re-uploaded. Everything after that goes through the
driver's own protocol on $2140-$2143. This includes the samples, which are re-sent whenever the
game changes music. (Confirmed with `snesfifa.ipl` on the trace. ARAM after the upload matches the block byte for byte.)

## Is the sequencer on the 65816? Yes (confirmed)

The SPC700 program is only a register server. Its main loop writes 0 to $F4, then keeps calling
an envelope routine (timer-0 driven) until $F4 (the 65816's $2140) is non-zero and stable. It then
jumps through a 20-entry table indexed by that value. No handler reads note data from ARAM, and no
handler has a tempo counter or per-track state. All timing comes from the 65816, which sends commands
from its NMI handler at the video frame rate (50 Hz on PAL). Even unchanged pitches are re-sent
every frame. So a static SPC snapshot holds the samples and the driver but nothing that would
ever key a note. This is why SPC dumps are impossible and SNSF is needed.

## Handshake

The 65816 waits for $2140 = 0 (driver idle). It writes the arguments to $2141-$2143 and the command
number to $2140. The driver reads the arguments and acknowledges by writing a value to $F4.
Multi-phase commands acknowledge each phase. The 65816 then writes the next phase's arguments with
a new $2140 value. After the final acknowledgement the 65816 writes $2140 = 0, and the driver
returns to idle.

## Commands

V = voice 0-7. "regs" are S-DSP registers (voice registers are at V·$10 + n).

| # | Name (ours) | Arguments | Effect |
|---|---|---|---|
| 1 | SAMPLE_RESET | – | upload pointer = $087C, slot = 0, KOFF all |
| 2 | SAMPLE_QUERY | – | replies F5 = next slot, F6/F7 = upload pointer |
| 3 | SAMPLE_UPLOAD | F6/F7 = loop offset | writes DIR[slot] = (ptr, ptr+loop). Then a stream of 3 bytes per handshake (F5-F7, counter $84,$85,…,$FF,$80,…). Ends with $2140 = 0 and F6/F7 = signed length correction. Then slot++. |
| 4 | SAMPLE_FREE | F5 = n | slot -= n, pointer = that slot's start |
| 5 | VOICE_CUT | F5 = V | GAIN = $BF (fast exponential decrease), ADSR2 = ADSR1 = 0, envelope idle |
| 6 | KEY_ON | F5 = V, F6/F7 = pitch | P(L,H) = pitch, KOFF = 0, KON = 1<<V |
| 7 | VOICE_SETUP | ph1: F5 = V, F6/F7 = VOL L/R. ph2: F5 = SRCN, F6/F7 = ADSR1/2 | sets VOL, SRCN, ADSR, GAIN = 0 |
| 8 | GLOBAL_SETUP | ph1: MVOL L/R, PMON. ph2: NON, EON, FLG (OR'd with the driver flags, $20 at start) | |
| 9 | ECHO_SETUP | ph1: EVOL L/R, EFB. ph2: ESA, EDL | also enables echo writes (FLG = 0). Not used by the music. |
| 10, 15-17 | – | – | no-op |
| 11 | PITCH | F5 = V, F6/F7 = pitch | P(L,H) |
| 12 | TIMER | F7 | timer-0 target (software-envelope tick) |
| 13 | KEY_OFF_ALL | – | KOFF = $FF |
| 14 | VOICE_VOLUME | ph1: F5 = V. ph2: F5 = scale s, F6/F7 = L/R | VOL = (L·((2s) mod 256)) >> 8, same for R |
| 18 | MAIN_VOLUME | F5/F6 | MVOL L/R |
| 19 | ENVELOPE | 3 phases: level, attack rate, decay rate, sustain fraction | arms a software envelope (GAIN direct mode), stepped on each timer-0 tick |
| 20 | RELEASE | F5 = V, F6/F7 = rate | software release. Replies with a duration estimate in F5/F6. |

`snesfifa/protocol.py` decodes this from a port log and `snesfifa/dsplog.py` turns it into the
resulting DSP register writes. The reassembled SPC player (below) checks that mapping: driving the
DSP from it reproduces the emulator capture.

## What is sent per note (from the traces)

A note is VOICE_SETUP (volume/pan, sample, ADSR) followed by KEY_ON (pitch). Each frame the 65816
re-sends PITCH for every sounding voice and sends VOICE_VOLUME when a volume changes. Notes end with
VOICE_CUT. Voices are allocated dynamically: a pattern played twice can use different voices.
Per song: about 600-1200 notes, plus 8000-13000 PITCH commands every 6 minutes. No music uses
commands 9, 19 or 20.

## Sample handling per song

`PLAY_MUSIC` → SAMPLE_RESET, then 16 SAMPLE_UPLOADs: 61.5 KB for song 1, 61.8 KB for songs 2-5
(they share a set), and 51-54 KB for songs 6-8. Nothing is uploaded once notes start, so there is
no streaming during playback.
