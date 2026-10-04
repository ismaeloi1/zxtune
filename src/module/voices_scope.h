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

#include "string_type.h"
#include "types.h"

#include <memory>

namespace Module
{
  //! @brief Receiver of separate voices waveforms (e.g. for oscilloscope)
  //! @note Called from rendering thread
  class VoicesScope
  {
  public:
    using Ptr = std::shared_ptr<VoicesScope>;
    virtual ~VoicesScope() = default;

    //! @brief Voices layout, called before any Feed call and on its change
    //! @param count total voices count
    //! @param perChip voices count of each chip, voices are enumerated chip by chip
    virtual void SetVoicesCount(uint_t count, uint_t perChip) = 0;

    //! @brief Check if data is consumed now, so separate voices rendering may be skipped to save resources
    virtual bool IsActive() const = 0;

    //! @brief Chip state snapshots period in samples
    virtual uint_t GetStatePeriod() const = 0;

    //! @brief Store chip state snapshot, taken each GetStatePeriod() samples of chip's voices
    //! @param registers last values written to registers of SID-like chip (0x00..0x18)
    //! @param osc oscillator outputs of each voice (as OSC3)
    //! @param env envelope outputs of each voice (as ENV3)
    virtual void FeedState(uint_t chip, const uint8_t* registers, const uint8_t* osc, const uint8_t* env) = 0;

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
