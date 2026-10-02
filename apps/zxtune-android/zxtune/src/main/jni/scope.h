/**
 *
 * @file
 *
 * @brief Oscilloscope data provider interface
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "module/voices_scope.h"
#include "sound/chunk.h"

#include "types.h"

#include <memory>

namespace Player
{
  //! @brief Collects separate voices (if supported) or master output and provides triggered waveforms
  class Scope : public Module::VoicesScope
  {
  public:
    using Ptr = std::shared_ptr<Scope>;

    //! @brief Called after each rendered chunk of module, flushes collected voices data
    virtual void Commit(const Sound::Chunk& master) = 0;

    //! @brief Called on each output buffer request
    //! @param start index of first sample in buffer (in samples since start)
    //! @param samples output buffer size
    virtual void Played(uint64_t start, uint_t samples) = 0;

    //! @brief Seek happened, reset all the state
    virtual void Reset() = 0;

    struct Layout
    {
      //! channels count actually stored
      uint_t Channels = 0;
      //! channels count of each chip (0 for single master channel)
      uint_t PerChip = 0;
    };

    //! @param maxChannels maximum channels count to get
    //! @param points points per channel to get
    //! @param target [channels][points] array of normalized samples
    virtual Layout Get(uint_t maxChannels, uint_t points, int16_t* target) = 0;

    static Ptr Create(uint_t samplerate);
  };
}  // namespace Player
