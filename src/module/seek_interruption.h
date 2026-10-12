/**
 *
 * @file
 *
 * @brief  Interruption of long seeking
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <functional>

namespace Module
{
  //! @brief Optional renderer interface. Some engines can seek only by emulation (e.g. backward seeking restarts
  //!        from the very beginning), so seeking takes seconds and should be abandoned once the result is not needed
  //!        anymore (track change, another seek request, playback stop).
  class InterruptibleSeek
  {
  public:
    //! @return true to stop seeking as soon as possible
    using Predicate = std::function<bool()>;

    virtual ~InterruptibleSeek() = default;

    //! @param interrupted is polled from SetPosition's thread, empty to disable
    //! @return false if not supported
    //! @note Interrupted renderer stays at some position between initial and requested, reported by GetState()
    virtual bool SetSeekInterruption(Predicate interrupted) = 0;
  };
}  // namespace Module
