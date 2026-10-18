/**
 *
 * @file
 *
 * @brief  SNSF chiptune factory implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "formats/chiptune/emulation/supernessoundformat.h"
#include "module/players/platforms.h"
#include "module/players/properties_helper.h"
#include "module/players/streaming.h"
#include "module/players/xsf/all.h"
#include "module/players/xsf/snsf_emu.h"
#include "module/players/xsf/xsf.h"

#include "binary/compression/zlib.h"
#include "core/plugins_parameters.h"
#include "module/voices_scope.h"
#include "parameters/tracking_helper.h"
#include "sound/resampler.h"
#include "strings/casing.h"

#include "contract.h"
#include "make_ptr.h"
#include "string_view.h"

#include "3rdparty/snesspc/snes_spc/SPC_Filter.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <optional>

namespace Module::SNSF
{
  struct ModuleData
  {
    using Ptr = std::shared_ptr<const ModuleData>;
    using RWPtr = std::shared_ptr<ModuleData>;

    ModuleData() = default;
    ModuleData(const ModuleData&) = delete;

    Cartridge Cart;
    XSF::MetaInformation::Ptr Meta;
  };

  class Engine
  {
  public:
    using Ptr = std::unique_ptr<Engine>;

    static const uint_t VOICES = ::SPC_DSP::voice_count;
    // state snapshots period in DSP samples, ~1ms
    static const uint_t STATE_PERIOD = Emulator::SAMPLE_RATE / 1000;

    struct VoicesFrame
    {
      // interleaved [count][VOICES] at DSP samplerate
      std::vector<int16_t> Samples;
      std::vector<std::array<VoiceState, VOICES>> States;
      std::vector<uint_t> StatesPos;

      void Clear()
      {
        Samples.clear();
        States.clear();
        StatesPos.clear();
      }
    };

    explicit Engine(const Cartridge& cart)
      : Emu(cart)
    {
      Reset();
    }

    void Reset()
    {
      Emu.Reset();
      Filter.clear();
      Filter.set_gain(::SPC_Filter::gain_unit * 14 / 10);  // the same as for SPC files
      Emu.GetSpc().mute_voices(Mask);
      Pending = 0;
    }

    void SetChannelsMask(int mask)
    {
      Mask = mask;
      Emu.GetSpc().mute_voices(mask);
    }

    void SetVoicesTarget(VoicesFrame* target)
    {
      Voices = target;
      SinceState = 0;
      Emu.GetSpc().get_dsp().set_voices_func(target ? &OnVoices : nullptr, this);
    }

    // Whole frames are rendered to keep voices output in sync, so result size may differ from requested a bit.
    // The difference is compensated on the next call.
    Sound::Chunk Render(uint_t samples)
    {
      static_assert(Sound::Sample::CHANNELS == 2, "Incompatible sound channels count");
      static_assert(Sound::Sample::BITS == 16, "Incompatible sound bits count");
      Sound::Chunk result;
      for (Pending += samples; Pending > 0;)
      {
        const auto done = result.size();
        result.resize(done + Emulator::MAX_FRAME_SAMPLES);
        auto* const buffer = safe_ptr_cast<::SNES_SPC::sample_t*>(result.data() + done);
        const auto count = Emu.RunFrame(buffer, Emulator::MAX_FRAME_SAMPLES);
        Filter.run(buffer, static_cast<int>(count * Sound::Sample::CHANNELS));
        result.resize(done + count);
        Pending -= count;
      }
      return result;
    }

    void Skip(uint_t samples)
    {
      auto* const voices = Voices;
      SetVoicesTarget(nullptr);
      std::array<::SNES_SPC::sample_t, Emulator::MAX_FRAME_SAMPLES * Sound::Sample::CHANNELS> buffer;
      for (Pending += samples; Pending > 0;)
      {
        Pending -= Emu.RunFrame(buffer.data(), Emulator::MAX_FRAME_SAMPLES);
      }
      SetVoicesTarget(voices);
    }

  private:
    // the same as for SPC player
    static void OnVoices(void* data, const int* voices)
    {
      auto* const self = static_cast<Engine*>(data);
      auto& frame = *self->Voices;
      for (uint_t idx = 0; idx < VOICES; ++idx)
      {
        frame.Samples.push_back(static_cast<int16_t>(std::clamp(voices[idx], -32768, 32767)));
      }
      if (++self->SinceState >= STATE_PERIOD)
      {
        self->SinceState = 0;
        frame.StatesPos.push_back(static_cast<uint_t>(frame.Samples.size() / VOICES));
        auto& states = frame.States.emplace_back();
        for (uint_t idx = 0; idx < VOICES; ++idx)
        {
          ::SPC_DSP::voice_state_t in;
          self->Emu.GetSpc().get_dsp().get_voice_state(idx, &in);
          auto& out = states[idx];
          const auto vol = std::max(std::abs(in.vol_l), std::abs(in.vol_r));
          const auto level = (in.env / 2047.0f) * (vol / 128.0f);
          out.Level = level > 0.0000158f ? 20 * std::log10(level) : -96.0f;
          out.Flags = VoiceState::HAS_LEVEL;
          if (!in.released && in.env > 0)
          {
            out.Flags |= VoiceState::KEY_ON;
          }
          if (in.noise)
          {
            out.Flags |= VoiceState::NOISE;
          }
          out.Kind = VoiceState::SPC_DSP;
          auto& f = out.Fields;
          f[0] = static_cast<uint8_t>(in.env >> 4);
          f[1] = static_cast<uint8_t>(!in.released && !(in.adsr0 & 0x80) ? 4 : in.env_mode);
          f[2] = static_cast<uint8_t>(in.adsr0);
          f[3] = static_cast<uint8_t>(in.adsr1);
          f[4] = static_cast<uint8_t>(in.gain);
          f[5] = static_cast<uint8_t>(in.srcn);
          f[6] = static_cast<uint8_t>(in.vol_l);
          f[7] = static_cast<uint8_t>(in.vol_r);
          f[8] = static_cast<uint8_t>(in.pitch & 0xff);
          f[9] = static_cast<uint8_t>(in.pitch >> 8);
          f[10] = static_cast<uint8_t>(in.echo | (in.pmod << 1) | (in.noise << 2));
        }
      }
    }

  private:
    Emulator Emu;
    ::SPC_Filter Filter;
    int Mask = 0;
    // samples to render (positive) or already rendered in advance (negative)
    int Pending = 0;
    VoicesFrame* Voices = nullptr;
    uint_t SinceState = 0;
  };

  const auto FRAME_DURATION = Time::Milliseconds(100);

  uint_t GetSamples(Time::Microseconds period)
  {
    return static_cast<uint_t>(period.Get() * Emulator::SAMPLE_RATE / period.PER_SECOND);
  }

  class Renderer
    : public Module::Renderer
    , public VoicesScopeSource
  {
  public:
    Renderer(ModuleData::Ptr tune, Parameters::Accessor::Ptr params, uint_t samplerate)
      : Tune(std::move(tune))
      , Params(std::move(params))
      , Core(MakePtr<Engine>(Tune->Cart))
      , State(Tune->Meta->Duration)
      , Samplerate(samplerate)
      , Target(Sound::CreateResampler(Emulator::SAMPLE_RATE, samplerate))
    {}

    bool SetVoicesScope(VoicesScope::Ptr scope) override
    {
      Scope = std::move(scope);
      if (Scope)
      {
        VoicesGroup group;
        group.Name = "S-DSP";
        for (uint_t voice = 0; voice < Engine::VOICES; ++voice)
        {
          group.Voices.emplace_back("Voice " + std::to_string(voice + 1));
        }
        Scope->SetVoicesGroups({group}, false);
        Scope->SetDescription("S-DSP 32kHz, 65816 + snes_spc");
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
      auto result = Target->Apply(Core->Render(GetSamples(avail)));
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
        Core->Skip(GetSamples(toSkip));
      }
    }

  private:
    void ResetEngine()
    {
      Core->Reset();
      Params.Reset();
    }

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
          resampler = Sound::CreateResampler(Emulator::SAMPLE_RATE, Samplerate);
        }
        VoicesTarget = &Frame;
      }
      else
      {
        VoicesTarget = nullptr;
      }
      Core->SetVoicesTarget(VoicesTarget);
    }

    // voices are resampled the same way as main output, by pairs
    void FeedVoices(uint_t outSamples)
    {
      const auto voices = Engine::VOICES;
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
        Core->SetChannelsMask(static_cast<int>(val));
      }
    }

  private:
    const ModuleData::Ptr Tune;
    Parameters::TrackingHelper<Parameters::Accessor> Params;
    const Engine::Ptr Core;
    TimedState State;
    const uint_t Samplerate;
    const Sound::Converter::Ptr Target;
    VoicesScope::Ptr Scope;
    Engine::VoicesFrame Frame;
    Engine::VoicesFrame* VoicesTarget = nullptr;
    std::array<Sound::Converter::Ptr, Engine::VOICES / 2> VoicesResamplers;
    std::vector<int16_t> Output;
    std::array<VoiceState, Engine::VOICES> LastStates;
  };

  class Holder : public Module::Holder
  {
  public:
    Holder(ModuleData::Ptr tune, Parameters::Accessor::Ptr props)
      : Tune(std::move(tune))
      , Properties(std::move(props))
    {}

    Module::Information GetModuleInformation() const override
    {
      return CreateTimedInfo(Tune->Meta->Duration);
    }

    Parameters::Accessor::Ptr GetModuleProperties() const override
    {
      return Properties;
    }

    Renderer::Ptr CreateRenderer(uint_t samplerate, Parameters::Accessor::Ptr params) const override
    {
      return MakePtr<Renderer>(Tune, std::move(params), samplerate);
    }

    static Ptr Create(ModuleData::Ptr tune, Parameters::Container::Ptr properties)
    {
      PropertiesHelper props(*properties);
      if (tune->Meta)
      {
        tune->Meta->Dump(props);
      }
      props.SetPlatform(Platforms::SUPER_NINTENDO_ENTERTAINMENT_SYSTEM);
      // as for SPC
      props.SetChannels("DSP"sv, Engine::VOICES);
      return MakePtr<Holder>(std::move(tune), std::move(properties));
    }

  private:
    const ModuleData::Ptr Tune;
    const Parameters::Accessor::Ptr Properties;
  };

  class ModuleDataBuilder
    : public XSF::MergeTarget
    , private Formats::Chiptune::SuperNESSoundFormat::Builder
  {
  public:
    ModuleDataBuilder()
      : Result(MakeRWPtr<ModuleData>())
    {}

    void AddProgramSection(Binary::Container::Ptr program) override
    {
      if (program)
      {
        const auto unpacked = Binary::Compression::Zlib::Decompress(*program);
        Formats::Chiptune::SuperNESSoundFormat::ParseRom(*unpacked, *this);
      }
    }

    void AddReservedSection(Binary::Container::Ptr reserved) override
    {
      if (reserved)
      {
        Formats::Chiptune::SuperNESSoundFormat::ParseReserved(*reserved, *this);
      }
    }

    void AddMeta(const XSF::MetaInformation& meta) override
    {
      if (!Meta)
      {
        Result->Meta = Meta = MakeRWPtr<XSF::MetaInformation>(meta);
      }
      else
      {
        Meta->Merge(meta);
      }
    }

    ModuleData::Ptr CaptureResult()
    {
      auto& rom = Result->Cart.Rom;
      Require(!rom.empty());
      // emulator maps memory by 8k pages
      rom.resize((rom.size() + 0x1fff) & ~std::size_t(0x1fff));
      if (Meta)
      {
        ApplyTags(*Meta);
      }
      return Result;
    }

  private:
    // As in snsf9x-based players, the first loaded program section (usually from snsflib) sets the base offset
    // and the next ones are relative to it
    void SetRom(uint32_t offset, Binary::View content) override
    {
      if (!RomBase)
      {
        RomBase = offset;
      }
      else
      {
        offset += *RomBase;
      }
      UpdateRom(offset, content);
    }

    void SetSram(uint32_t offset, Binary::View content) override
    {
      // spec limit
      const std::size_t LIMIT = 0x20000;
      if (offset < LIMIT)
      {
        const auto* data = content.As<uint8_t>();
        const auto size = std::min<std::size_t>(content.Size(), LIMIT - offset);
        Result->Cart.Sram.push_back({offset, {data, data + size}});
      }
    }

    void UpdateRom(uint32_t offset, Binary::View content)
    {
      auto& dst = Result->Cart.Rom;
      // limit to ExHiROM size to protect from broken files
      const std::size_t LIMIT = 0x800000;
      const auto end = std::min<std::size_t>(std::size_t(offset) + content.Size(), LIMIT);
      if (offset >= end)
      {
        return;
      }
      if (dst.size() < end)
      {
        dst.resize(end);
      }
      std::memcpy(dst.data() + offset, content.Start(), end - offset);
    }

    void ApplyTags(const XSF::MetaInformation& meta)
    {
      // later files are merged at the end and take priority
      for (const auto& tag : meta.Tags)
      {
        const auto name = Strings::ToLowerAscii(tag.first);
        const auto value = Strings::ToLowerAscii(tag.second);
        auto& cart = Result->Cart;
        if (name == "_video"sv && (value == "pal"sv || value == "ntsc"sv))
        {
          cart.Video = value == "pal"sv ? Cartridge::VideoMode::PAL : Cartridge::VideoMode::NTSC;
        }
        else if (name == "_memory"sv && (value == "hirom"sv || value == "lorom"sv))
        {
          cart.Memory = value == "hirom"sv ? Cartridge::MemoryMode::HIROM : Cartridge::MemoryMode::LOROM;
        }
        else if (name == "_sramfill"sv)
        {
          cart.SramFill = static_cast<uint8_t>(std::strtoul(value.c_str(), nullptr, 0));
        }
      }
    }

  private:
    const ModuleData::RWPtr Result;
    XSF::MetaInformation::RWPtr Meta;
    std::optional<uint32_t> RomBase;
  };
}  // namespace Module::SNSF

namespace Module::XSF
{
  Holder::Ptr CreateSNSFModule(const File& file, const FilesMap& additionalFiles, Parameters::Container::Ptr properties)
  {
    SNSF::ModuleDataBuilder builder;
    MergeProgramSectionsMultiLibrary(file, additionalFiles, builder);
    MergeReservedSectionsDepsFirst(file, additionalFiles, builder);
    MergeMeta(file, additionalFiles, builder);
    return SNSF::Holder::Create(builder.CaptureResult(), std::move(properties));
  }
}  // namespace Module::XSF
