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

    //! @brief Total voices count, called before any Feed call and on its change
    virtual void SetVoicesCount(uint_t count) = 0;

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
