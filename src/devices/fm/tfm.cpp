/**
 *
 * @file
 *
 * @brief  TurboFM chips implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "devices/tfm.h"

#include "devices/fm/chip.h"

#include "make_ptr.h"

#include <algorithm>
#include <array>
#include <functional>
#include <vector>

namespace Devices::TFM
{
  class ChipAdapter
  {
  public:
    void SetParams(uint64_t clock, uint_t sndFreq, uint_t muteMask)
    {
      if (Helper.SetNewParams(clock, sndFreq))
      {
        if (Chips[0])
        {
          // recreated chips lose registers written once (e.g. instruments), so they are updated in place
          ::YM2203SetClock(Chips[0].get(), clock, sndFreq);
          ::YM2203SetClock(Chips[1].get(), clock, sndFreq);
        }
        else
        {
          Chips[0] = Helper.CreateChip();
          Chips[1] = Helper.CreateChip();
        }
      }
      SetMuteMask(muteMask);
    }

    void Reset()
    {
      if (Chips[0])
      {
        ::YM2203ResetChip(Chips[0].get());
        ::YM2203ResetChip(Chips[1].get());
      }
    }

    void SetMuteMask(uint_t mask)
    {
      if (Chips[0])
      {
        ::YM2203SetMuteMask(Chips[0].get(), mask & 0x7);
        ::YM2203SetMuteMask(Chips[1].get(), (mask >> 3) & 0x7);
      }
    }

    void WriteRegisters(const Devices::TFM::Registers& regs)
    {
      for (const auto& reg : regs)
      {
        ::YM2203WriteRegs(Chips.at(reg.Chip()).get(), reg.Index(), reg.Value());
      }
    }

    //! @param voices storage for separate channels output, [count][TFM::VOICES], may be nullptr
    Sound::Chunk RenderSamples(uint_t count, std::vector<int16_t>* voices)
    {
      Sound::Chunk result(count);
      auto* const outRaw = safe_ptr_cast<FM::Details::YM2203SampleType*>(result.data());
      if (voices)
      {
        voices->resize(std::size_t(count) * TFM::VOICES);
        ::YM2203UpdateOneVoices(Chips[0].get(), outRaw, voices->data(), TFM::VOICES, count);
        ::YM2203UpdateOneVoices(Chips[1].get(), outRaw, voices->data() + FM::VOICES, TFM::VOICES, count);
      }
      else
      {
        ::YM2203UpdateOne(Chips[0].get(), outRaw, count);
        ::YM2203UpdateOne(Chips[1].get(), outRaw, count);
      }
      std::transform(outRaw, outRaw + count, outRaw, [](FM::Details::YM2203SampleType s) { return s / 2; });
      Helper.ConvertSamples(outRaw, outRaw + count, result.data());
      return result;
    }

    void GetChannelsState(FM::ChannelState* target) const
    {
      if (!Chips[0])
      {
        return;
      }
      const auto clock = Helper.GetClock();
      for (uint_t chip = 0; chip < TFM::CHIPS; ++chip)
      {
        std::array<uint_t, FM::VOICES> attenuations = {};
        std::array<uint_t, FM::VOICES> periods = {};
        ::YM2203GetState(Chips[chip].get(), attenuations.data(), periods.data());
        for (uint_t chan = 0; chan < FM::VOICES; ++chan)
        {
          auto& out = target[chip * FM::VOICES + chan];
          // 10-bit envelope attenuation, 96dB range
          const auto att = std::min<uint_t>(attenuations[chan], 1023);
          out.Level = -float(att) * 96.0f / 1024;
          out.Frequency = att < 1023 && periods[chan] ? float(clock) / periods[chan] : 0.0f;
        }
      }
    }

  private:
    FM::Details::ChipAdapterHelper Helper;
    std::array<FM::Details::ChipPtr, TFM::CHIPS> Chips;
  };

  struct Traits
  {
    using BaseClass = Chip;
    using DataChunkType = DataChunk;
    using StampType = Stamp;
    using AdapterType = ChipAdapter;
  };

  using TFMChip = FM::Details::BaseChip<Traits>;

  Chip::Ptr CreateChip(ChipParameters::Ptr params)
  {
    return MakePtr<TFMChip>(std::move(params));
  }
}  // namespace Devices::TFM
