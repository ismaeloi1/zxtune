/**
 *
 * @file
 *
 * @brief  SPC support implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "module/players/external/spc.h"

#include "formats/chiptune/emulation/spc.h"
#include "module/players/duration.h"
#include "module/players/platforms.h"
#include "module/players/properties_meta.h"
#include "module/players/streaming.h"

#include "binary/container_factories.h"
#include "core/core_parameters.h"
#include "core/plugins_parameters.h"
#include "debug/log.h"
#include "math/numeric.h"
#include "module/holder.h"
#include "module/renderer.h"
#include "module/voices_scope.h"
#include "parameters/tracking_helper.h"
#include "sound/resampler.h"

#include "contract.h"
#include "make_ptr.h"
#include "string_view.h"

#include "3rdparty/snesspc/snes_spc/SNES_SPC.h"
#include "3rdparty/snesspc/snes_spc/SPC_Filter.h"

#include <algorithm>
#include <array>
#include <cmath>

namespace Module::SPC
{
  const Debug::Stream Dbg("Module::SPC");

  struct Model
  {
    using Ptr = std::shared_ptr<const Model>;

    const Binary::Data::Ptr Data;
    const Time::Milliseconds Duration;

    Model(Binary::View data, Time::Milliseconds duration)
      : Data(Binary::CreateContainer(data))
      , Duration(duration)
    {}
  };

  class SPC
  {
  public:
    using Ptr = std::unique_ptr<SPC>;

    explicit SPC(Binary::View data)
      : Data(data)
    {
      CheckError(Spc.init());
      Reset();
    }

    void Reset()
    {
      Spc.reset();
      CheckError(Spc.load_spc(Data.Start(), Data.Size()));
      Spc.clear_echo();
      Spc.disable_surround(true);
      Filter.clear();
      Filter.set_gain(static_cast<int>(::SPC_Filter::gain_unit * 1.4));  // as in GME
    }

    void SetChannelsMask(int mask)
    {
      Spc.mute_voices(mask);
    }

    static const uint_t VOICES = ::SPC_DSP::voice_count;
    // state snapshots period in DSP samples, ~1ms
    static const uint_t STATE_PERIOD = ::SNES_SPC::sample_rate / 1000;

    struct VoicesFrame
    {
      // interleaved [count][VOICES] at DSP samplerate
      std::vector<int16_t> Samples;
      // snapshot of all voices each STATE_PERIOD samples, with sample index in frame
      std::vector<std::array<VoiceState, VOICES>> States;
      std::vector<uint_t> StatesPos;

      void Clear()
      {
        Samples.clear();
        States.clear();
        StatesPos.clear();
      }
    };

    //! @param target frame to collect voices into or nullptr to disable
    void SetVoicesTarget(VoicesFrame* target)
    {
      Voices = target;
      SinceState = 0;
      Spc.get_dsp().set_voices_func(target ? &OnVoices : nullptr, this);
    }

    Sound::Chunk Render(uint_t samples)
    {
      static_assert(Sound::Sample::CHANNELS == 2, "Incompatible sound channels count");
      static_assert(Sound::Sample::BITS == 16, "Incompatible sound bits count");
      Sound::Chunk result(samples);
      auto* const buffer = safe_ptr_cast<::SNES_SPC::sample_t*>(result.data());
      const auto dataSize = static_cast<int>(samples * Sound::Sample::CHANNELS);
      CheckError(Spc.play(dataSize, buffer));
      Filter.run(buffer, dataSize);
      return result;
    }

    void Skip(uint_t samples)
    {
      auto* const voices = Voices;
      SetVoicesTarget(nullptr);
      CheckError(Spc.skip(static_cast<int>(samples * Sound::Sample::CHANNELS)));
      SetVoicesTarget(voices);
    }

  private:
    inline static void CheckError(::blargg_err_t err)
    {
      Require(!err);  // TODO: detalize
    }

    static void OnVoices(void* data, const int* voices)
    {
      auto* const self = static_cast<SPC*>(data);
      auto& frame = *self->Voices;
      for (uint_t idx = 0; idx < VOICES; ++idx)
      {
        frame.Samples.push_back(static_cast<int16_t>(std::clamp(voices[idx], -32768, 32767)));
      }
      // DSP state is consistent between samples
      if (++self->SinceState >= STATE_PERIOD)
      {
        self->SinceState = 0;
        frame.StatesPos.push_back(static_cast<uint_t>(frame.Samples.size() / VOICES));
        auto& states = frame.States.emplace_back();
        for (uint_t idx = 0; idx < VOICES; ++idx)
        {
          ::SPC_DSP::voice_state_t in;
          self->Spc.get_dsp().get_voice_state(idx, &in);
          auto& out = states[idx];
          // envelope is 11 bit, volume is signed 8 bit
          const auto vol = std::max(std::abs(in.vol_l), std::abs(in.vol_r));
          const auto level = (in.env / 2047.0f) * (vol / 128.0f);
          out.Level = level > 0.0000158f ? 20 * std::log10(level) : -96.0f;
          out.Flags = VoiceState::HAS_LEVEL;
          if (!in.released && in.env > 0)
          {
            out.Flags |= VoiceState::KEY_ON;
          }
          // pitch depends on sample contents, so frequency is estimated from output
          if (in.noise)
          {
            out.Flags |= VoiceState::NOISE;
          }
        }
      }
    }

  private:
    const Binary::View Data;
    ::SNES_SPC Spc;
    ::SPC_Filter Filter;
    VoicesFrame* Voices = nullptr;
    uint_t SinceState = 0;
  };

  const auto FRAME_DURATION = Time::Milliseconds(100);

  uint_t GetSamples(Time::Microseconds period)
  {
    return period.Get() * ::SNES_SPC::sample_rate / period.PER_SECOND;
  }

  class Renderer
    : public Module::Renderer
    , public VoicesScopeSource
  {
  public:
    Renderer(Model::Ptr tune, Parameters::Accessor::Ptr params, uint_t samplerate)
      : Tune(std::move(tune))
      , Params(std::move(params))
      , Engine(MakePtr<SPC>(*Tune->Data))
      , State(Tune->Duration)
      , Samplerate(samplerate)
      , Target(Sound::CreateResampler(::SNES_SPC::sample_rate, samplerate))
    {}

    bool SetVoicesScope(VoicesScope::Ptr scope) override
    {
      Scope = std::move(scope);
      if (Scope)
      {
        VoicesGroup group;
        group.Name = "S-DSP";
        for (uint_t voice = 0; voice < SPC::VOICES; ++voice)
        {
          group.Voices.emplace_back("Voice " + std::to_string(voice + 1));
        }
        Scope->SetVoicesGroups({group}, false);
        Scope->SetDescription("S-DSP 32kHz, snes_spc");
      }
      UpdateVoicesTap();
      return true;
    }

    Module::State GetState() const override
    {
      return State.Get();
    }

    Sound::Chunk Render() override
    {
      ApplyParameters();
      UpdateVoicesTap();
      const auto avail = State.ConsumeUpTo(FRAME_DURATION);
      auto result = Target->Apply(Engine->Render(GetSamples(avail)));
      if (VoicesTarget)
      {
        FeedVoices(static_cast<uint_t>(result.size()));
      }
      return result;
    }

    void Reset() override
    {
      State.Reset();
      ResetEngine();
    }

    void SetPosition(Time::AtMillisecond request) override
    {
      if (request < State.At())
      {
        ResetEngine();
      }
      if (const auto toSkip = State.Seek(request))
      {
        Engine->Skip(GetSamples(toSkip));
      }
    }

  private:
    void ResetEngine()
    {
      Engine->Reset();
      Params.Reset();
    }

    // separate voices are rendered only while consumed
    void UpdateVoicesTap()
    {
      const bool active = Scope && Scope->IsActive();
      if (active == (VoicesTarget != nullptr))
      {
        return;
      }
      if (active)
      {
        Frame.Clear();
        for (auto& resampler : VoicesResamplers)
        {
          resampler = Sound::CreateResampler(::SNES_SPC::sample_rate, Samplerate);
        }
        VoicesTarget = &Frame;
      }
      else
      {
        VoicesTarget = nullptr;
      }
      Engine->SetVoicesTarget(VoicesTarget);
    }

    // voices are resampled the same way as main output, by pairs
    void FeedVoices(uint_t outSamples)
    {
      const auto voices = SPC::VOICES;
      const auto inSamples = static_cast<uint_t>(Frame.Samples.size() / voices);
      Output.assign(std::size_t(outSamples) * voices, 0);
      for (uint_t pair = 0; pair < voices / 2; ++pair)
      {
        Sound::Chunk in(inSamples);
        for (uint_t idx = 0; idx < inSamples; ++idx)
        {
          in[idx] = Sound::Sample(Frame.Samples[idx * voices + pair * 2], Frame.Samples[idx * voices + pair * 2 + 1]);
        }
        const auto out = VoicesResamplers[pair]->Apply(std::move(in));
        for (uint_t idx = 0, lim = std::min<uint_t>(outSamples, static_cast<uint_t>(out.size())); idx < lim; ++idx)
        {
          Output[idx * voices + pair * 2] = static_cast<int16_t>(out[idx].Left());
          Output[idx * voices + pair * 2 + 1] = static_cast<int16_t>(out[idx].Right());
        }
      }
      // states are interleaved with samples blocks
      const auto period = std::max<uint_t>(Scope->GetStatePeriod(), 1);
      for (uint_t done = 0; done < outSamples;)
      {
        const auto part = std::min(period, outSamples - done);
        Scope->Feed(0, voices, Output.data() + std::size_t(done) * voices, part);
        done += part;
        const auto inPos = inSamples ? uint_t(uint64_t(done) * inSamples / outSamples) : 0;
        const auto it = std::upper_bound(Frame.StatesPos.begin(), Frame.StatesPos.end(), inPos);
        if (it != Frame.StatesPos.begin())
        {
          LastStates = Frame.States[it - Frame.StatesPos.begin() - 1];
        }
        Scope->FeedVoices(0, LastStates.data(), voices);
      }
      Frame.Clear();
    }

    void ApplyParameters()
    {
      if (Params.IsChanged())
      {
        using namespace Parameters::ZXTune::Core;
        const auto val = Parameters::GetInteger(*Params, CHANNELS_MASK, CHANNELS_MASK_DEFAULT);
        Engine->SetChannelsMask(val);
      }
    }

  private:
    const Model::Ptr Tune;
    Parameters::TrackingHelper<Parameters::Accessor> Params;
    const SPC::Ptr Engine;
    TimedState State;
    const uint_t Samplerate;
    const Sound::Converter::Ptr Target;
    VoicesScope::Ptr Scope;
    SPC::VoicesFrame Frame;
    SPC::VoicesFrame* VoicesTarget = nullptr;
    std::array<Sound::Converter::Ptr, SPC::VOICES / 2> VoicesResamplers;
    std::vector<int16_t> Output;
    std::array<VoiceState, SPC::VOICES> LastStates;
  };

  class Holder : public Module::Holder
  {
  public:
    Holder(Model::Ptr tune, Parameters::Accessor::Ptr props)
      : Tune(std::move(tune))
      , Properties(std::move(props))
    {}

    Information GetModuleInformation() const override
    {
      return CreateTimedInfo(Tune->Duration);
    }

    Parameters::Accessor::Ptr GetModuleProperties() const override
    {
      return Properties;
    }

    Renderer::Ptr CreateRenderer(uint_t samplerate, Parameters::Accessor::Ptr params) const override
    {
      return MakePtr<Renderer>(Tune, std::move(params), samplerate);
    }

  private:
    const Model::Ptr Tune;
    const Parameters::Accessor::Ptr Properties;
  };

  class DataBuilder : public Formats::Chiptune::SPC::Builder
  {
  public:
    explicit DataBuilder(PropertiesHelper& props)
      : Properties(props)
      , Meta(props)
    {}

    Formats::Chiptune::MetaBuilder& GetMetaBuilder() override
    {
      return Meta;
    }

    void SetRegisters(uint16_t /*pc*/, uint8_t /*a*/, uint8_t /*x*/, uint8_t /*y*/, uint8_t /*psw*/,
                      uint8_t /*sp*/) override
    {}

    void SetDumper(StringView dumper) override
    {
      Meta.SetComment(dumper);
    }

    void SetDumpDate(StringView date) override
    {
      Properties.SetDate(date);
    }

    void SetIntro(Time::Milliseconds duration) override
    {
      // Some tracks contain specified intro instead of loop
      if (!Loop)
      {
        Intro = duration;
        Properties.SetFadein(Intro);
      }
    }

    void SetLoop(Time::Milliseconds duration) override
    {
      Loop = duration;
    }

    void SetFade(Time::Milliseconds duration) override
    {
      if (duration)
      {
        Fade = duration;
        Properties.SetFadeout(duration);
      }
    }

    void SetRAM(Binary::View /*data*/) override {}

    void SetDSPRegisters(Binary::View /*data*/) override {}

    void SetExtraRAM(Binary::View /*data*/) override {}

    Time::Milliseconds GetDuration() const
    {
      return Intro + Loop + Fade;
    }

  private:
    PropertiesHelper& Properties;
    MetaProperties Meta;
    Time::Milliseconds Intro;
    Time::Milliseconds Loop;
    Time::Milliseconds Fade;
  };

  class Factory : public Module::Factory
  {
  public:
    Holder::Ptr CreateModule(const Parameters::Accessor& params, const Binary::Container& rawData,
                             Parameters::Container::Ptr properties) const override
    {
      try
      {
        PropertiesHelper props(*properties);
        DataBuilder dataBuilder(props);
        if (const auto container = Formats::Chiptune::SPC::Parse(rawData, dataBuilder))
        {
          props.SetSource(*container);
          props.SetPlatform(Platforms::SUPER_NINTENDO_ENTERTAINMENT_SYSTEM);
          // As in GME
          props.SetChannels("DSP", ::SNES_SPC::voice_count);

          auto duration = dataBuilder.GetDuration();
          if (!duration.Get())
          {
            duration = GetDefaultDuration(params);
          }
          auto tune = MakePtr<Model>(*container, duration);

          return MakePtr<Holder>(std::move(tune), std::move(properties));
        }
      }
      catch (const std::exception& e)
      {
        Dbg("Failed to create SPC: {}", e.what());
      }
      return {};
    }
  };

  Factory::Ptr CreateFactory()
  {
    return MakePtr<Factory>();
  }
}  // namespace Module::SPC
