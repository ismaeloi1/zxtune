/**
 *
 * @file
 *
 * @brief  Separate voices output interface for visualization
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "strings/array.h"

#include "string_type.h"
#include "types.h"

#include <array>
#include <memory>
#include <vector>

namespace Module
{
  //! @brief Voices of single chip (device)
  struct VoicesGroup
  {
    String Name;
    Strings::Array Voices;
  };

  //! @brief State of single voice of chip without SID-like registers
  struct VoiceState
  {
    enum : uint8_t
    {
      KEY_ON = 1,
      HAS_FREQUENCY = 2,
      HAS_LEVEL = 4,
      NOISE = 8,
    };

    //! Chip specific voice kinds, define meaning of Fields
    enum Kind : uint8_t
    {
      GENERIC = 0,
      //! algorithm, feedback, op1..4 levels, carriers mask, pan (L=2, R=1), AMS, PMS, keys mask, block
      OPN_FM = 1,
      //! enabled, last value (unsigned)
      OPN_DAC = 2,
      //! attenuation 0..15, period low, period high, stereo (L=2, R=1)
      PSG_TONE = 3,
      //! attenuation 0..15, white, rate 0..3 (3 - tone 3 period), stereo
      PSG_NOISE = 4,
      //! connection (1 - additive), feedback, op1..2 levels, op1..2 waveforms, key, pan, block
      OPL_2OP = 5,
      //! algorithm 0..3, feedback, op1..4 levels, op1..4 waveforms, key, pan
      OPL_4OP = 6,
      //! instrument (0 BD, 1 SD, 2 TT, 3 CY, 4 HH), level, key, rhythm mode enabled
      OPL_RHYTHM = 7,
      //! ENVX 0..127, envelope mode (0 release, 1 attack, 2 decay, 3 sustain, 4 gain), ADSR1, ADSR2, GAIN, SRCN,
      //! volume left, volume right (signed), pitch low, pitch high (14 bit), flags (1 echo, 2 pitch modulation, 4 noise)
      SPC_DSP = 8,
      //! active notes count, up to 8 keys (0 - none), partials used; Text is patch name
      MT32_PART = 9,
      //! state (0 inactive, 1 attack, 2 sustain, 3 release), owner part + 1, key
      MT32_PARTIAL = 10,
      //! duty 0..3, volume 0..15, constant volume, period low, period high (11 bit), length counter active, sweep
      NES_PULSE = 11,
      //! linear counter active, period low, period high, length counter active
      NES_TRIANGLE = 12,
      //! volume 0..15, short mode, period index 0..15, length counter active
      NES_NOISE = 13,
      //! output level 0..127, rate index 0..15, active, loop
      NES_DMC = 14,
    };

    static const std::size_t FIELDS = 12;
    static const std::size_t TEXT = 16;

    //! Hz
    float Frequency = 0;
    //! dB relative to maximal voice level (0 or negative)
    float Level = 0;
    uint8_t Flags = 0;
    uint8_t Kind = GENERIC;
    //! chip specific, operator levels are 0..255 (255 is maximal)
    std::array<uint8_t, FIELDS> Fields = {};
    //! zero terminated if shorter
    std::array<char, TEXT> Text = {};
  };

  //! @brief Receiver of separate voices waveforms (e.g. for oscilloscope)
  //! @note Called from rendering thread
  class VoicesScope
  {
  public:
    using Ptr = std::shared_ptr<VoicesScope>;
    virtual ~VoicesScope() = default;

    //! @brief Voices layout, called before any Feed call and on its change
    //! @param groups voices are enumerated group by group
    //! @param hasRegisters SID-like chips state is provided via FeedState/FeedChip
    virtual void SetVoicesGroups(const std::vector<VoicesGroup>& groups, bool hasRegisters) = 0;

    //! @brief Check if data is consumed now, so separate voices rendering may be skipped to save resources
    virtual bool IsActive() const = 0;

    //! @brief Chip state snapshots period in samples
    virtual uint_t GetStatePeriod() const = 0;

    //! @brief Store chip state snapshot, taken each GetStatePeriod() samples of chip's voices
    //! @param registers last values written to registers of SID-like chip (0x00..0x18)
    //! @param osc oscillator outputs of each voice (as OSC3)
    //! @param env envelope outputs of each voice (as ENV3)
    virtual void FeedState(uint_t chip, const uint8_t* registers, const uint8_t* osc, const uint8_t* env) = 0;

    //! @brief Store high resolution chip data aligned with voices samples
    //! @param data interleaved [count][4]: oscillator outputs of 3 voices (as OSC3), master volume (0..15)
    virtual void FeedChip(uint_t chip, const uint8_t* data, uint_t count) = 0;

    //! @brief Store voices state snapshot (for chips without registers), taken each GetStatePeriod() samples
    //! @param firstVoice index of the first voice in block
    virtual void FeedVoices(uint_t firstVoice, const VoiceState* states, uint_t count) = 0;

    //! @brief Human readable description of emulation parameters, e.g. "PAL, MOS8580, reSIDfp"
    virtual void SetDescription(const String& description) = 0;

    //! @brief Store part of rendered voices
    //! @param firstVoice index of the first voice in block
    //! @param voices voices count in block
    //! @param samples interleaved samples [count][voices] at render samplerate
    virtual void Feed(uint_t firstVoice, uint_t voices, const int16_t* samples, uint_t count) = 0;
  };

  //! @brief Optional Renderer extension, query via dynamic_cast
  class VoicesScopeSource
  {
  public:
    virtual ~VoicesScopeSource() = default;

    //! @param scope receiver or nullptr to disable
    //! @return false if separate voices output is not supported
    virtual bool SetVoicesScope(VoicesScope::Ptr scope) = 0;
  };
}  // namespace Module
