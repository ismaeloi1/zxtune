/**
 *
 * @file
 *
 * @brief  TFM-based chiptunes common functionality implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "module/players/tfm/tfm_base.h"

#include "module/players/tfm/tfm_parameters.h"

#include "module/voices_scope.h"

#include "make_ptr.h"

#include <algorithm>
#include <array>
#include <utility>

namespace Module
{
  class TFMRenderer
    : public Renderer
    , public VoicesScopeSource
  {
  public:
    TFMRenderer(Time::Microseconds frameDuration, TFM::DataIterator::Ptr iterator, Devices::TFM::Chip::Ptr device)
      : Iterator(std::move(iterator))
      , Device(std::move(device))
      , FrameDuration(frameDuration)
    {}

    // Channels outputs are taken right from chip emulation, as Furnace does
    bool SetVoicesScope(VoicesScope::Ptr scope) override
    {
      Scope = std::move(scope);
      Active = false;
      Device->SetChannelsOutput(false);
      if (Scope)
      {
        std::vector<VoicesGroup> groups(Devices::TFM::CHIPS);
        for (auto& group : groups)
        {
          group.Name = "YM2203";
          group.Voices = {"FM 1", "FM 2", "FM 3"};
        }
        Scope->SetVoicesGroups(groups, false);
        Scope->SetDescription("TurboFM, 2xYM2203");
      }
      return true;
    }

    State GetState() const override
    {
      return Iterator->GetState();
    }

    Sound::Chunk Render() override
    {
      const bool active = Scope && Scope->IsActive();
      if (active != Active)
      {
        Active = active;
        Device->SetChannelsOutput(active);
      }
      TransferChunk();
      Iterator->NextFrame();
      LastChunk.TimeStamp += FrameDuration;
      auto result = Device->RenderTill(LastChunk.TimeStamp);
      if (Active)
      {
        FeedVoices();
      }
      return result;
    }

    void Reset() override
    {
      Iterator->Reset();
      Device->Reset();
      LastChunk.TimeStamp = {};
    }

    void SetPosition(Time::AtMillisecond request) override
    {
      if (request < Iterator->GetState().At)
      {
        Iterator->Reset();
        Device->Reset();
        LastChunk.TimeStamp = {};
      }
      while (Iterator->GetState().At < request)
      {
        TransferChunk();
        Iterator->NextFrame();
      }
    }

  private:
    void TransferChunk()
    {
      Iterator->GetData(LastChunk.Data);
      Device->RenderData(LastChunk);
    }

    void FeedVoices()
    {
      const auto& samples = Device->GetChannelsOutput();
      const auto voices = Devices::TFM::VOICES;
      const auto count = static_cast<uint_t>(samples.size() / voices);
      // registers are changed per frame (rendered block), so state is the same for the whole block
      std::array<Devices::FM::ChannelState, Devices::TFM::VOICES> channels;
      Device->GetChannelsState(channels.data());
      std::array<VoiceState, Devices::TFM::VOICES> states;
      for (uint_t idx = 0; idx < voices; ++idx)
      {
        const auto& in = channels[idx];
        auto& out = states[idx];
        out.Frequency = in.Frequency;
        out.Level = in.Level;
        out.Flags = VoiceState::HAS_LEVEL | (in.Frequency > 0 ? VoiceState::HAS_FREQUENCY | VoiceState::KEY_ON : 0);
        FillFmFields(in, out);
      }
      const auto period = std::max<uint_t>(Scope->GetStatePeriod(), 1);
      for (uint_t done = 0; done < count;)
      {
        const auto part = std::min(period, count - done);
        Scope->Feed(0, voices, samples.data() + std::size_t(done) * voices, part);
        done += part;
        Scope->FeedVoices(0, states.data(), voices);
      }
    }

    // Same layout as reported by libvgm cores for YM2203 (see Module::VoiceState::OPN_FM)
    static void FillFmFields(const Devices::FM::ChannelState& in, VoiceState& out)
    {
      // carriers mask per algorithm in OP1..OP4 order
      static const uint8_t CARRIERS[8] = {0x08, 0x08, 0x08, 0x08, 0x0a, 0x0e, 0x0e, 0x0f};
      auto& fields = out.Fields;
      const auto alg = in.AlgorithmFeedback & 7;
      const uint_t fnum = in.BlockFnum & 0x7ff;
      fields[0] = alg;
      fields[1] = (in.AlgorithmFeedback >> 3) & 7;
      fields[6] = CARRIERS[alg];
      // mono chip
      fields[7] = 3;
      fields[11] = (in.BlockFnum >> 11) & 7;
      for (uint_t idx = 0; idx < 4; ++idx)
      {
        const auto& op = in.Operators[idx];
        const auto& regs = op.Registers;
        auto* dst = fields.data() + VoiceState::FM_OPS + idx * VoiceState::FM_OP_SIZE;
        fields[2 + idx] = op.Level;
        fields[10] |= op.Key ? 1 << idx : 0;
        fields[VoiceState::FM_EG + idx] = op.Envelope;
        dst[0] = regs[0] & 0x0f;
        dst[1] = regs[1] & 0x7f;
        dst[2] = regs[2] & 0x1f;
        dst[3] = regs[3] & 0x1f;
        dst[4] = (regs[4] & 0x1f) | ((regs[2] >> 6) << 5);
        dst[5] = (regs[5] >> 4) | ((regs[5] & 0x0f) << 4);
        // YM2203 has no LFO, so no AM flag
        dst[6] = op.Stage;
        dst[7] = ((regs[0] >> 4) & 7) | ((regs[6] & 0x0f) << 3);
      }
      fields[VoiceState::FM_FNUM] = fnum & 0xff;
      fields[VoiceState::FM_FNUM + 1] = fnum >> 8;
      const auto ch3 = in.Mode & 0xc0;
      fields[VoiceState::FM_GLOBAL] = ch3 == 0x40 ? 0x10 : (ch3 == 0x80 ? 0x20 : 0);
      fields[VoiceState::FM_CHIP] = VoiceState::FM_CHIP_OPN;
      fields[VoiceState::FM_EG_RATE] = in.EnvelopeRate & 0xff;
      fields[VoiceState::FM_EG_RATE + 1] = in.EnvelopeRate >> 8;
      fields[VoiceState::FM_TIMER_A] = in.TimerA & 0xff;
      fields[VoiceState::FM_TIMER_A + 1] = in.TimerA >> 8;
      out.Kind = VoiceState::OPN_FM;
    }

  private:
    const TFM::DataIterator::Ptr Iterator;
    const Devices::TFM::Chip::Ptr Device;
    const Time::Duration<Devices::TFM::TimeUnit> FrameDuration;
    Devices::TFM::DataChunk LastChunk;
    VoicesScope::Ptr Scope;
    bool Active = false;
  };

  class TFMHolder : public Holder
  {
  public:
    explicit TFMHolder(TFM::Chiptune::Ptr chiptune)
      : Tune(std::move(chiptune))
    {}

    Information GetModuleInformation() const override
    {
      return Tune->GetInformation();
    }

    Parameters::Accessor::Ptr GetModuleProperties() const override
    {
      return Tune->GetProperties();
    }

    Renderer::Ptr CreateRenderer(uint_t samplerate, Parameters::Accessor::Ptr params) const override
    {
      auto chipParams = TFM::CreateChipParameters(samplerate, std::move(params));
      auto chip = Devices::TFM::CreateChip(std::move(chipParams));
      auto iterator = Tune->CreateDataIterator();
      return TFM::CreateRenderer(Tune->GetFrameDuration() /*TODO: speed variation*/, std::move(iterator),
                                 std::move(chip));
    }

  private:
    const TFM::Chiptune::Ptr Tune;
  };
}  // namespace Module

namespace Module::TFM
{
  Renderer::Ptr CreateRenderer(Time::Microseconds frameDuration, DataIterator::Ptr iterator,
                               Devices::TFM::Chip::Ptr device)
  {
    return MakePtr<TFMRenderer>(frameDuration, std::move(iterator), std::move(device));
  }

  Holder::Ptr CreateHolder(Chiptune::Ptr chiptune)
  {
    return MakePtr<TFMHolder>(std::move(chiptune));
  }
}  // namespace Module::TFM
