/**
 *
 * @file
 *
 * @brief  AY/YM-based chips base implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "devices/aym/src/renderers.h"
#include "devices/aym/src/volume_table.h"

#include "parameters/tracking_helper.h"

#include <vector>

namespace Devices::AYM
{
  template<class Traits>
  class SoundChip : public Traits::ChipBaseType
  {
  public:
    SoundChip(ChipParameters::Ptr params, MixerType::Ptr mixer)
      : Params(std::move(params))
      , Mixer(std::move(mixer))
      , PSG(VolTable)
      , Renderers(Clock, PSG)
    {
      SoundChip::Reset();
    }

    void RenderData(const typename Traits::DataChunkType& src) override
    {
      if (Clock.HasSamplesBefore(src.TimeStamp))
      {
        RenderTill(src.TimeStamp);
      }
      PSG.SetNewData(src.Data);
    }

    void RenderData(const std::vector<typename Traits::DataChunkType>& src) override
    {
      if (src.empty())
      {
        return;
      }
      const Stamp end = src.back().TimeStamp;
      if (Clock.HasSamplesBefore(end))
      {
        RenderedData.reserve(RenderedData.size() + Clock.SamplesTill(end));
        for (const auto& chunk : src)
        {
          Renderers.Render(chunk.TimeStamp, &RenderedData);
          PSG.SetNewData(chunk.Data);
        }
      }
      else
      {
        for (const auto& chunk : src)
        {
          PSG.SetNewData(chunk.Data);
        }
      }
    }

    void Reset() override
    {
      Params.Reset();
      PSG.Reset();
      Renderers.Reset();
      Levels.clear();
      SynchronizeParameters();
    }

    void SetChannelsOutput(bool enabled) override
    {
      Levels.clear();
      ChannelsOutput.clear();
      ChannelsEnabled = enabled;
      PSG.SetLevelsRecord(enabled ? &Levels : nullptr);
    }

    const std::vector<int16_t>& GetChannelsOutput() const override
    {
      return ChannelsOutput;
    }

    void GetChannelsState(ChannelState* target) const override
    {
      PSG.GetChannelsState(PsgClock, target);
    }

    Sound::Chunk RenderTill(Stamp stamp) override
    {
      Sound::Chunk result;
      if (RenderedData.empty())
      {
        result = Renderers.Render(stamp, Clock.SamplesTill(stamp));
      }
      else
      {
        Renderers.Render(stamp, &RenderedData);
        result.swap(RenderedData);
        RenderedData.reserve(result.size());
      }
      if (ChannelsEnabled)
      {
        ConvertChannels(result.size());
      }
      SynchronizeParameters();
      return result;
    }

  private:
    // Levels are recorded at the rate of mixed output calculation (PSG clock for HQ renderer, sound frequency for
    // others), so averaging of each output sample's period acts the same as decimation of mixed output does
    void ConvertChannels(std::size_t samples)
    {
      static const uint_t CHANNELS = SOUND_CHANNELS * Traits::CHIPS;
      ChannelsOutput.assign(samples * CHANNELS, 0);
      const auto native = Levels.size() / Traits::CHIPS;
      if (native && samples)
      {
        std::array<int_t, HIGH_LEVEL_A + 1> table;
        for (uint_t idx = 0; idx < table.size(); ++idx)
        {
          table[idx] = VolTable.GetChannelLevel(idx);
        }
        const auto muted = Params->MuteMask();
        auto* out = ChannelsOutput.data();
        for (std::size_t smp = 0; smp < samples; ++smp, out += CHANNELS)
        {
          const auto from = smp * native / samples;
          const auto to = std::max(from + 1, (smp + 1) * native / samples);
          for (uint_t chip = 0; chip < Traits::CHIPS; ++chip)
          {
            int_t sums[SOUND_CHANNELS] = {};
            for (auto idx = from; idx < to; ++idx)
            {
              const uint_t levels = Levels[idx * Traits::CHIPS + chip];
              sums[0] += table[levels & HIGH_LEVEL_A];
              sums[1] += table[(levels >> BITS_PER_LEVEL) & HIGH_LEVEL_A];
              sums[2] += table[levels >> 2 * BITS_PER_LEVEL];
            }
            for (uint_t chan = 0; chan < SOUND_CHANNELS; ++chan)
            {
              const bool isMuted = 0 != (muted & (1 << (chip * VOICES + chan)));
              out[chip * SOUND_CHANNELS + chan] = isMuted ? 0 : static_cast<int16_t>(sums[chan] / int_t(to - from));
            }
          }
        }
      }
      Levels.clear();
    }

    void SynchronizeParameters()
    {
      const uint_t AYM_CLOCK_DIVISOR = 8;

      if (Params.IsChanged())
      {
        PSG.SetMuteMask(Params->MuteMask());
        PSG.SetDutyCycle(Params->DutyCycleValue(), Params->DutyCycleMask());
        const uint64_t clock = Params->ClockFreq() / AYM_CLOCK_DIVISOR;
        PsgClock = clock;
        const uint_t sndFreq = Params->SoundFreq();
        Renderers.SetFrequency(clock, sndFreq);
        Renderers.SetInterpolation(Params->Interpolation());
        VolTable.SetParameters(Params->Type(), Params->Layout(), *Mixer);
      }
    }

  private:
    Parameters::TrackingHelper<ChipParameters> Params;
    const MixerType::Ptr Mixer;
    MultiVolumeTable VolTable;
    typename Traits::PSGType PSG;
    ClockSource Clock;
    RenderersSet<typename Traits::PSGType> Renderers;
    Sound::Chunk RenderedData;
    uint64_t PsgClock = 0;
    bool ChannelsEnabled = false;
    // packed channels levels, interleaved [count][Traits::CHIPS]
    std::vector<uint16_t> Levels;
    std::vector<int16_t> ChannelsOutput;
  };
}  // namespace Devices::AYM
