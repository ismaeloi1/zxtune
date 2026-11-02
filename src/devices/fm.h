/**
 *
 * @file
 *
 * @brief  FM support
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "sound/chunk.h"
#include "time/instant.h"
#include "tools/data_streaming.h"

#include "types.h"

#include <array>
#include <memory>
#include <vector>

namespace Devices::FM
{
  const uint_t VOICES = 3;

  using TimeUnit = Time::Microsecond;
  using Stamp = Time::Instant<TimeUnit>;

  class Register
  {
  public:
    Register() = default;

    Register(uint_t idx, uint_t val)
      : Val((idx << 8) | val)
    {}

    uint_t Index() const
    {
      return (Val >> 8) & 0xff;
    }

    uint_t Value() const
    {
      return Val & 0xff;
    }

  protected:
    uint_t Val = 0;
  };

  using Registers = std::vector<Register>;

  struct DataChunk
  {
    DataChunk() = default;

    Stamp TimeStamp;
    Registers Data;
  };

  class Device
  {
  public:
    using Ptr = std::unique_ptr<Device>;
    virtual ~Device() = default;

    /// render single data chunk
    virtual void RenderData(const DataChunk& src) = 0;

    /// reset internal state to initial
    virtual void Reset() = 0;
  };

  //! @brief Single FM channel state for visualization
  struct ChannelState
  {
    //! Hz, 0 if silent
    float Frequency = 0;
    //! dB of carriers' envelope attenuation, 0 is maximal level
    float Level = -96.0f;

    //! Operators in connection order (OP1..OP4), not registers order
    struct Operator
    {
      //! registers 0x30 (DT/MUL), 0x40 (TL), 0x50 (KS/AR), 0x60 (D1R), 0x70 (D2R), 0x80 (SL/RR), 0x90 (SSG-EG)
      std::array<uint8_t, 7> Registers = {};
      //! 0..255 (maximal) output level including TL
      uint8_t Level = 0;
      //! 0..255 (maximal) envelope generator output
      uint8_t Envelope = 0;
      //! 0 off, 1 attack, 2 decay, 3 sustain, 4 release
      uint8_t Stage = 0;
      bool Key = false;
    };
    std::array<Operator, 4> Operators;
    //! register 0xb0
    uint8_t AlgorithmFeedback = 0;
    //! 3 bits of block and 11 bits of fnum
    uint16_t BlockFnum = 0;
    //! register 0x27 of chip
    uint8_t Mode = 0;
    uint16_t TimerA = 0;
    //! Hz
    uint16_t EnvelopeRate = 0;
  };

  //! @brief Separate channels output for visualization (e.g. oscilloscope)
  class ChannelsSource
  {
  public:
    virtual ~ChannelsSource() = default;

    //! @brief Disabled by default to save resources
    virtual void SetChannelsOutput(bool enabled) = 0;

    //! @return interleaved [samples][channels] outputs of separate channels (before mixing) for the last RenderTill call
    virtual const std::vector<int16_t>& GetChannelsOutput() const = 0;

    //! @param target [channels] current states
    virtual void GetChannelsState(ChannelState* target) const = 0;
  };

  // Describes real device
  class Chip : public Device
  {
  public:
    using Ptr = std::unique_ptr<Chip>;

    /// Render rest data and return result
    virtual Sound::Chunk RenderTill(Stamp stamp) = 0;
  };

  class ChipParameters
  {
  public:
    using Ptr = std::unique_ptr<const ChipParameters>;

    virtual ~ChipParameters() = default;

    virtual uint_t Version() const = 0;
    virtual uint64_t ClockFreq() const = 0;
    virtual uint_t SoundFreq() const = 0;
    virtual uint_t MuteMask() const = 0;
  };

  /// Virtual constructors
  Chip::Ptr CreateChip(ChipParameters::Ptr params);
}  // namespace Devices::FM
