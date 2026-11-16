// SPC files player based on ares S-SMP and S-DSP emulation (cycle accurate), standalone and without ares/nall
// dependencies. See README.zxtune for the details.
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

class AresSpc
{
public:
  enum
  {
    sample_rate = 32000,
    voice_count = 8,
    // registers dump at the end is required
    min_file_size = 0x10180,
  };

  AresSpc();
  ~AresSpc();
  AresSpc(const AresSpc&) = delete;
  AresSpc& operator=(const AresSpc&) = delete;

  // Resets emulation and loads SPC dump, returns false if it's truncated
  bool load_spc(const void* data, std::size_t size);

  // Console mode: power on with boot ROM, main CPU talks via ports. Time is in DSP clocks (32 per sample)
  // since the start of frame.
  enum
  {
    clocks_per_sample = 32,
  };
  void reset();
  int read_port(int time, int port);
  void write_port(int time, int port, int data);
  // Runs till the time and starts the next frame
  void end_frame(int time);
  // Moves up to maxFrames rendered stereo frames to out, returns their count
  std::size_t take(int16_t* out, std::size_t maxFrames);

  // Clears echo region of RAM if echo is enabled, to avoid playing garbage of dump
  void clear_echo();

  // Muted voices are mixed neither to output nor to echo, bit per voice
  void mute_voices(int mask);

  // Called for each sample with outputs of all voices
  using voices_func_t = void (*)(void* data, const int* voices);
  void set_voices_func(voices_func_t func, void* data);

  // Renders stereo frames at sample_rate, out may be null to skip
  void play(std::size_t frames, int16_t* out);

  // Emulation state only, mute mask and voices function are kept
  void copy_state_from(const AresSpc& rh);

  // DSP state, the same interface as of SPC_DSP
  struct voice_state_t
  {
    int env;         // 0..0x7FF
    int released;    // envelope is in release mode
    int pitch;       // 0x1000 is 32kHz sample rate
    int vol_l, vol_r;
    int srcn;
    int noise;       // voice outputs noise
    int noise_rate;  // noise generator rate index 0..0x1F
    int env_mode;    // 0 - release, 1 - attack, 2 - decay, 3 - sustain
    int adsr0, adsr1, gain;
    int echo;      // voice is sent to echo
    int pmod;      // pitch is modulated by previous voice
    int brr_addr;  // address of the current BRR block
  };
  void get_voice_state(int voice, voice_state_t* out) const;
  int read(int reg) const;
  const uint8_t* ram() const;

private:
  struct Machine;
  const std::unique_ptr<Machine> M;
};
