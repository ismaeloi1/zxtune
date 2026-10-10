/**
 *
 * @file
 *
 * @brief  AY/YM sound chip interface
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "devices/aym.h"
#include "sound/mixer.h"

#include <vector>

// supporting for AY/YM-based modules
namespace Devices::AYM
{
  //! @brief Single sound channel state for visualization
  struct ChannelState
  {
    //! Hz of tone or periodic envelope, 0 if not periodic
    float Frequency = 0;
    //! Amplitude relative to maximal channel output, 0..1
    float Level = 0;
    bool Tone = false;
    bool Noise = false;
    bool Envelope = false;
  };

  //! @brief Separate sound channels output for visualization (e.g. oscilloscope)
  class ChannelsSource
  {
  public:
    virtual ~ChannelsSource() = default;

    //! @brief Disabled by default to save resources
    virtual void SetChannelsOutput(bool enabled) = 0;

    //! @return interleaved [samples][SOUND_CHANNELS * chips] outputs of separate channels for the last RenderTill call,
    //!         as real channels DACs levels (0..32767)
    virtual const std::vector<int16_t>& GetChannelsOutput() const = 0;

    //! @param target [SOUND_CHANNELS * chips] current states
    virtual void GetChannelsState(ChannelState* target) const = 0;
  };

  // Describes real device
  class Chip
    : public Device
    , public ChannelsSource
  {
  public:
    using Ptr = std::unique_ptr<Chip>;

    virtual Sound::Chunk RenderTill(Stamp till) = 0;
  };

  enum ChannelMasks
  {
    CHANNEL_MASK_A = 1,
    CHANNEL_MASK_B = 2,
    CHANNEL_MASK_C = 4,
    CHANNEL_MASK_N = 8,
    CHANNEL_MASK_E = 16,
  };

  enum LayoutType
  {
    LAYOUT_ABC = 0,
    LAYOUT_ACB = 1,
    LAYOUT_BAC = 2,
    LAYOUT_BCA = 3,
    LAYOUT_CAB = 4,
    LAYOUT_CBA = 5,
    LAYOUT_MONO = 6,

    LAYOUT_LAST
  };

  enum InterpolationType
  {
    INTERPOLATION_NONE = 0,
    INTERPOLATION_LQ = 1,
    INTERPOLATION_HQ = 2
  };

  enum ChipType
  {
    TYPE_AY38910 = 0,
    TYPE_YM2149F = 1
  };

  class ChipParameters
  {
  public:
    using Ptr = std::unique_ptr<const ChipParameters>;

    virtual ~ChipParameters() = default;

    virtual uint_t Version() const = 0;

    virtual uint64_t ClockFreq() const = 0;
    virtual uint_t SoundFreq() const = 0;
    virtual ChipType Type() const = 0;
    virtual InterpolationType Interpolation() const = 0;
    virtual uint_t DutyCycleValue() const = 0;
    virtual uint_t DutyCycleMask() const = 0;
    virtual LayoutType Layout() const = 0;
    virtual uint_t MuteMask() const = 0;
  };

  const uint_t SOUND_CHANNELS = 3;
  using MixerType = Sound::ThreeChannelsMixer;

  /// Virtual constructors
  Chip::Ptr CreateChip(ChipParameters::Ptr params, MixerType::Ptr mixer);
}  // namespace Devices::AYM
