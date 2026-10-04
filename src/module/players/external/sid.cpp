/**
 *
 * @file
 *
 * @brief  SID support plugin implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "module/players/external/sid.h"

#include "module/players/duration.h"
#include "module/players/external/sid/roms.h"
#include "module/players/external/sid/songlengths.h"
#include "module/players/platforms.h"
#include "module/players/properties_helper.h"
#include "module/players/streaming.h"

#include "core/core_parameters.h"
#include "core/plugins_parameters.h"
#include "debug/log.h"
#include "formats/multitrack/container.h"
#include "module/attributes.h"
#include "module/voices_scope.h"
#include "parameters/tracking_helper.h"
#include "strings/sanitize.h"

#include "contract.h"
#include "make_ptr.h"

#include "3rdparty/sidplayfp/src/builders/resid-builder/resid.h"
#include "3rdparty/sidplayfp/src/builders/residfp-builder/residfp.h"
#include "3rdparty/sidplayfp/src/config.h"
#include "3rdparty/sidplayfp/src/sidmd5.h"
#include "3rdparty/sidplayfp/src/sidplayfp/SidInfo.h"
#include "3rdparty/sidplayfp/src/sidplayfp/SidTune.h"
#include "3rdparty/sidplayfp/src/sidplayfp/SidTuneInfo.h"
#include "3rdparty/sidplayfp/src/sidplayfp/sidplayfp.h"

#include <algorithm>
#include <memory>

namespace Module::Sid
{
  const Debug::Stream Dbg("Module::SID");

  const uint_t VOICES = 3;

  void CheckSidplayError(bool ok)
  {
    Require(ok);  // TODO
  }

  class Model : public SidTune
  {
  public:
    using Ptr = std::shared_ptr<Model>;

    Model(Binary::View data, uint_t idx)
      : SidTune(data.As<uint_least8_t>(), data.Size())
      , Index(selectSong(idx + 1))
      , MD5(GetMD5(data))
    {
      CheckSidplayError(getStatus());
    }

    void FillDuration(const Parameters::Accessor& params)
    {
      Duration = GetSongLength(MD5, Index - 1);
      if (!Duration)
      {
        Duration = GetDefaultDuration(params);
      }
      Dbg("Duration for {}/{} is {}ms", MD5, Index, Duration.Get());
    }

    Time::Milliseconds GetDuration() const
    {
      return Duration;
    }

  private:
    static std::string GetMD5(Binary::View data)
    {
      libsidplayfp::sidmd5 md5;
      md5.append(data.Start(), data.Size());
      md5.finish();
      return md5.getDigest();
    }

  private:
    const uint_t Index = 0;
    const std::string MD5;
    Time::Milliseconds Duration;
  };

  inline const uint8_t* GetData(const Binary::Data::Ptr& data, const uint8_t* defVal)
  {
    return !data || !data->Size() ? defVal : static_cast<const uint8_t*>(data->Start());
  }

  /*
   * Interpolation modes
   * 0 - fast sampling+interpolate
   * 1 - regular sampling+interpolate
   * 2 - regular sampling+interpolate+resample
   */

  class SidParameters
  {
  public:
    using Ptr = std::unique_ptr<const SidParameters>;

    explicit SidParameters(Parameters::Accessor::Ptr params)
      : Params(std::move(params))
    {}

    uint_t Version() const
    {
      return Params->Version();
    }

    bool GetFastSampling() const
    {
      return Parameters::ZXTune::Core::SID::INTERPOLATION_NONE == GetInterpolation();
    }

    SidConfig::sampling_method_t GetSamplingMethod() const
    {
      return Parameters::ZXTune::Core::SID::INTERPOLATION_HQ == GetInterpolation() ? SidConfig::RESAMPLE_INTERPOLATE
                                                                                   : SidConfig::INTERPOLATE;
    }

    bool GetUseFilter() const
    {
      using namespace Parameters::ZXTune::Core::SID;
      return 0 != Get(FILTER, FILTER_DEFAULT);
    }

    uint_t GetMuteMask() const
    {
      using namespace Parameters::ZXTune::Core;
      return Parameters::GetInteger<uint_t>(*Params, CHANNELS_MASK, CHANNELS_MASK_DEFAULT);
    }

    bool GetUseResidFp() const
    {
      using namespace Parameters::ZXTune::Core::SID;
      return ENGINE_RESIDFP == Get(ENGINE, ENGINE_DEFAULT);
    }

    SidConfig::sid_model_t GetModel() const
    {
      using namespace Parameters::ZXTune::Core::SID;
      return MODEL_8580 == Get(MODEL, MODEL_DEFAULT) ? SidConfig::MOS8580 : SidConfig::MOS6581;
    }

    bool GetForceModel() const
    {
      using namespace Parameters::ZXTune::Core::SID;
      return 0 != Get(MODEL_FORCE, MODEL_FORCE_DEFAULT);
    }

    SidConfig::c64_model_t GetClock() const
    {
      using namespace Parameters::ZXTune::Core::SID;
      switch (Get(CLOCK, CLOCK_DEFAULT))
      {
      case CLOCK_NTSC:
        return SidConfig::NTSC;
      case CLOCK_OLD_NTSC:
        return SidConfig::OLD_NTSC;
      case CLOCK_DREAN:
        return SidConfig::DREAN;
      case CLOCK_PAL_M:
        return SidConfig::PAL_M;
      default:
        return SidConfig::PAL;
      }
    }

    bool GetForceClock() const
    {
      using namespace Parameters::ZXTune::Core::SID;
      return 0 != Get(CLOCK_FORCE, CLOCK_FORCE_DEFAULT);
    }

    bool GetDigiBoost() const
    {
      using namespace Parameters::ZXTune::Core::SID;
      return 0 != Get(DIGIBOOST, DIGIBOOST_DEFAULT);
    }

    double GetFilterBias() const
    {
      using namespace Parameters::ZXTune::Core::SID;
      return std::clamp(Get(FILTER_BIAS, FILTER_BIAS_DEFAULT), FILTER_BIAS_MIN, FILTER_BIAS_MAX);
    }

    double GetFilter6581Curve() const
    {
      using namespace Parameters::ZXTune::Core::SID;
      return GetPercent(FILTER_6581_CURVE, FILTER_6581_CURVE_DEFAULT);
    }

    double GetFilter6581Range() const
    {
      using namespace Parameters::ZXTune::Core::SID;
      return GetPercent(FILTER_6581_RANGE, FILTER_6581_RANGE_DEFAULT);
    }

    double GetFilter8580Curve() const
    {
      using namespace Parameters::ZXTune::Core::SID;
      return GetPercent(FILTER_8580_CURVE, FILTER_8580_CURVE_DEFAULT);
    }

    SidConfig::sid_cw_t GetCombinedWaveforms() const
    {
      using namespace Parameters::ZXTune::Core::SID;
      switch (Get(COMBINED_WAVEFORMS, COMBINED_WAVEFORMS_DEFAULT))
      {
      case COMBINED_WAVEFORMS_WEAK:
        return SidConfig::WEAK;
      case COMBINED_WAVEFORMS_STRONG:
        return SidConfig::STRONG;
      default:
        return SidConfig::AVERAGE;
      }
    }

  private:
    Parameters::IntType GetInterpolation() const
    {
      using namespace Parameters::ZXTune::Core::SID;
      return Get(INTERPOLATION, INTERPOLATION_DEFAULT);
    }

    Parameters::IntType Get(Parameters::Identifier name, Parameters::IntType def) const
    {
      return Parameters::GetInteger(*Params, name, def);
    }

    double GetPercent(Parameters::Identifier name, Parameters::IntType def) const
    {
      return std::clamp<Parameters::IntType>(Get(name, def), 0, 100) / 100.0;
    }

  private:
    const Parameters::Accessor::Ptr Params;
  };

  // Snapshots of chip state are taken with ~4ms period
  const uint_t STATE_RATE = 250;

  class VoicesSinkAdapter : public SidVoicesSink
  {
  public:
    void voices(unsigned int chip, const short* samples, unsigned int count) override
    {
      Scope->Feed(chip * VOICES, VOICES, samples, count);
    }

    void state(unsigned int chip, const unsigned char* regs, const unsigned char* osc,
               const unsigned char* env) override
    {
      Scope->FeedState(chip, regs, osc, env);
    }

    unsigned int statePeriod() const override
    {
      return StatePeriod;
    }

    VoicesScope::Ptr Scope;
    uint_t StatePeriod = 1;
  };

  class SidEngine
  {
  public:
    using Ptr = std::unique_ptr<SidEngine>;

    SidEngine()
      : Config(Player.config())
    {}

    void Init(uint_t samplerate, const Parameters::Accessor& params)
    {
      const auto kernal = params.FindData(Parameters::ZXTune::Core::Plugins::SID::KERNAL);
      const auto basic = params.FindData(Parameters::ZXTune::Core::Plugins::SID::BASIC);
      const auto chargen = params.FindData(Parameters::ZXTune::Core::Plugins::SID::CHARGEN);
      Player.setRoms(GetData(kernal, GetKernalROM()), GetData(basic, GetBasicROM()), GetData(chargen, GetChargenROM()));
      ChipsCount = Player.info().maxsids();
      Config.frequency = samplerate;
      Config.powerOnDelay = SidConfig::MAX_POWER_ON_DELAY - 1;
      Config.playback = Sound::Sample::CHANNELS == 1 ? SidConfig::MONO : SidConfig::STEREO;
      Sink.StatePeriod = std::max<uint_t>(samplerate / STATE_RATE, 1);
    }

    void Load(SidTune& tune)
    {
      CheckSidplayError(Player.load(&tune));
      Tune = &tune;
      UpdateDescription();
    }

    void ApplyParameters(const SidParameters& sidParams)
    {
      const bool useResidFp = sidParams.GetUseResidFp();
      sidbuilder* const builder = useResidFp ? static_cast<sidbuilder*>(&GetResidFp()) : &GetResid();
      const bool useFilter = sidParams.GetUseFilter();
      if (useResidFp)
      {
        auto& fp = GetResidFp();
        fp.filter(useFilter);
        fp.filter6581Curve(sidParams.GetFilter6581Curve());
        fp.filter6581Range(sidParams.GetFilter6581Range());
        fp.filter8580Curve(sidParams.GetFilter8580Curve());
        fp.combinedWaveformsStrength(sidParams.GetCombinedWaveforms());
      }
      else
      {
        auto& resid = GetResid();
        resid.filter(useFilter);
        resid.bias(sidParams.GetFilterBias());
      }

      auto newConfig = Config;
      newConfig.sidEmulation = builder;
      newConfig.fastSampling = sidParams.GetFastSampling();
      newConfig.samplingMethod = sidParams.GetSamplingMethod();
      newConfig.defaultSidModel = sidParams.GetModel();
      newConfig.forceSidModel = sidParams.GetForceModel();
      newConfig.defaultC64Model = sidParams.GetClock();
      newConfig.forceC64Model = sidParams.GetForceClock();
      newConfig.digiBoost = sidParams.GetDigiBoost();
      if (!IsConfigured || IsChanged(newConfig))
      {
        if (Config.sidEmulation && Config.sidEmulation != builder)
        {
          SetSink(Config.sidEmulation, nullptr);
        }
        Config = newConfig;
        CheckSidplayError(Player.config(Config));
        IsConfigured = true;
        // voices of new chips are not muted
        MuteMask = 0;
      }
      const auto newMuteMask = sidParams.GetMuteMask();
      for (uint_t chan = 0, diff = MuteMask ^ newMuteMask; diff != 0; ++chan, diff >>= 1)
      {
        if (diff & 1)
        {
          const auto chip = chan / VOICES;
          const auto voice = chan % VOICES;
          Player.mute(chip, voice, newMuteMask & (1 << chan));
        }
      }
      MuteMask = newMuteMask;
      UseFilter = useFilter;
      UpdateDescription();
    }

    uint_t GetSoundFreq() const
    {
      return Config.frequency;
    }

    Sound::Chunk Render(uint_t samples)
    {
      static_assert(Sound::Sample::BITS == 16, "Incompatible sound bits count");
      // separate voices rendering is expensive, so do it only if required
      SetSink(Config.sidEmulation, Sink.Scope && Sink.Scope->IsActive() ? &Sink : nullptr);
      Sound::Chunk result(samples);
      Player.play(safe_ptr_cast<short*>(result.data()), samples * Sound::Sample::CHANNELS);
      return result;
    }

    void Skip(uint_t samples)
    {
      Player.play(nullptr, samples * Sound::Sample::CHANNELS);
    }

    void SetVoicesScope(VoicesScope::Ptr scope, uint_t chips)
    {
      Sink.Scope = std::move(scope);
      if (Sink.Scope)
      {
        Sink.Scope->SetVoicesCount(chips * VOICES, VOICES);
        UpdateDescription();
      }
      SetSink(Config.sidEmulation, nullptr);
    }

  private:
    ReSIDBuilder& GetResid()
    {
      if (!Resid)
      {
        Resid = std::make_unique<ReSIDBuilder>("reSID");
        Resid->create(ChipsCount);
      }
      return *Resid;
    }

    ReSIDfpBuilder& GetResidFp()
    {
      if (!ResidFp)
      {
        ResidFp = std::make_unique<ReSIDfpBuilder>("reSIDfp");
        ResidFp->create(ChipsCount);
      }
      return *ResidFp;
    }

    void SetSink(sidbuilder* builder, SidVoicesSink* sink)
    {
      if (builder && builder == Resid.get())
      {
        Resid->voicesSink(sink);
      }
      else if (builder && builder == ResidFp.get())
      {
        ResidFp->voicesSink(sink);
      }
    }

    bool IsChanged(const SidConfig& cfg) const
    {
      return cfg.sidEmulation != Config.sidEmulation || cfg.fastSampling != Config.fastSampling
             || cfg.samplingMethod != Config.samplingMethod || cfg.defaultSidModel != Config.defaultSidModel
             || cfg.forceSidModel != Config.forceSidModel || cfg.defaultC64Model != Config.defaultC64Model
             || cfg.forceC64Model != Config.forceC64Model || cfg.digiBoost != Config.digiBoost;
    }

    static const char* GetClockName(SidConfig::c64_model_t model)
    {
      switch (model)
      {
      case SidConfig::NTSC:
        return "NTSC";
      case SidConfig::OLD_NTSC:
        return "NTSC (old)";
      case SidConfig::DREAN:
        return "Drean";
      case SidConfig::PAL_M:
        return "PAL-M";
      default:
        return "PAL";
      }
    }

    void UpdateDescription()
    {
      if (!Sink.Scope || !Tune)
      {
        return;
      }
      const auto& info = *Tune->getInfo();
      String clock;
      if (Config.forceC64Model || info.clockSpeed() == SidTuneInfo::CLOCK_UNKNOWN
          || info.clockSpeed() == SidTuneInfo::CLOCK_ANY)
      {
        clock = GetClockName(Config.defaultC64Model);
      }
      else
      {
        clock = info.clockSpeed() == SidTuneInfo::CLOCK_NTSC ? "NTSC" : "PAL";
      }
      const auto chips = std::max(info.sidChips(), 1);
      String models;
      for (int chip = 0; chip < chips; ++chip)
      {
        const auto tuneModel = info.sidModel(chip);
        const bool is8580 = Config.forceSidModel || tuneModel == SidTuneInfo::SIDMODEL_UNKNOWN
                                    || tuneModel == SidTuneInfo::SIDMODEL_ANY
                                ? Config.defaultSidModel == SidConfig::MOS8580
                                : tuneModel == SidTuneInfo::SIDMODEL_8580;
        models += (chip ? "+" : "");
        models += is8580 ? "MOS8580" : "MOS6581";
      }
      const auto* engine = Config.sidEmulation == ResidFp.get() ? "reSIDfp" : "reSID";
      const auto* sampling = Config.samplingMethod == SidConfig::RESAMPLE_INTERPOLATE
                                 ? "resample"
                                 : (Config.fastSampling ? "fast" : "interpolate");
      String speed = Player.info().speedString() ? Player.info().speedString() : "";
      Sink.Scope->SetDescription(clock + ", " + models + ", " + engine + ", " + sampling
                                 + (UseFilter ? "" : ", no filter") + "\n" + info.formatString() + ", " + speed + ", "
                                 + std::to_string(info.sidChips()) + " SID, song " + std::to_string(info.currentSong())
                                 + "/" + std::to_string(info.songs()));
    }

  private:
    sidplayfp Player;
    std::unique_ptr<ReSIDBuilder> Resid;
    std::unique_ptr<ReSIDfpBuilder> ResidFp;
    SidConfig Config;
    VoicesSinkAdapter Sink;
    uint_t ChipsCount = 1;
    bool IsConfigured = false;
    const SidTune* Tune = nullptr;

    // cache filter flag
    bool UseFilter = false;
    uint_t MuteMask = 0;
  };

  const auto FRAME_DURATION = Time::Milliseconds(100);

  class Renderer
    : public Module::Renderer
    , public VoicesScopeSource
  {
  public:
    Renderer(Model::Ptr tune, uint_t samplerate, const Parameters::Accessor::Ptr& params)
      : Tune(std::move(tune))
      , State(Tune->GetDuration())
      , Engine(MakePtr<SidEngine>())
      , SidParams(MakePtr<SidParameters>(params))
    {
      Engine->Init(samplerate, *params);
      ApplyParameters();
      Reset();
    }

    Module::State GetState() const override
    {
      return State.Get();
    }

    Sound::Chunk Render() override
    {
      ApplyParameters();
      const auto avail = State.ConsumeUpTo(FRAME_DURATION);
      return Engine->Render(GetSamples(avail));
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

    bool SetVoicesScope(VoicesScope::Ptr scope) override
    {
      const uint_t chips = std::max<uint_t>(Tune->getInfo()->sidChips(), 1);
      Engine->SetVoicesScope(std::move(scope), chips);
      return true;
    }

  private:
    void ResetEngine()
    {
      Engine->Load(*Tune);
      SidParams.Reset();
    }

    uint_t GetSamples(Time::Microseconds period) const
    {
      return period.Get() * Engine->GetSoundFreq() / period.PER_SECOND;
    }

    void ApplyParameters()
    {
      if (SidParams.IsChanged())
      {
        Engine->ApplyParameters(*SidParams);
      }
    }

  private:
    const Model::Ptr Tune;
    TimedState State;
    const SidEngine::Ptr Engine;
    const StateIterator::Ptr Iterator;
    Parameters::TrackingHelper<SidParameters> SidParams;
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
      return CreateTimedInfo(Tune->GetDuration());
    }

    Parameters::Accessor::Ptr GetModuleProperties() const override
    {
      return Properties;
    }

    Renderer::Ptr CreateRenderer(uint_t samplerate, Parameters::Accessor::Ptr params) const override
    {
      return MakePtr<Renderer>(Tune, samplerate, std::move(params));
    }

  private:
    const Model::Ptr Tune;
    const Parameters::Accessor::Ptr Properties;
  };

  class Factory : public MultitrackFactory
  {
  public:
    Holder::Ptr CreateModule(const Parameters::Accessor& params, const Formats::Multitrack::Container& container,
                             Parameters::Container::Ptr properties) const override
    {
      try
      {
        auto tune = MakePtr<Model>(container, container.StartTrackIndex());

        const auto& tuneInfo = *tune->getInfo();
        Require(container.TracksCount() == tuneInfo.songs());

        PropertiesHelper props(*properties);
        switch (tuneInfo.numberOfInfoStrings())
        {
        default:
        case 3:
          // copyright/publisher really
          props.SetComment(Strings::SanitizeMultiline(tuneInfo.infoString(2)));
          [[fallthrough]];
        case 2:
          props.SetAuthor(Strings::Sanitize(tuneInfo.infoString(1)));
          [[fallthrough]];
        case 1:
          props.SetTitle(Strings::Sanitize(tuneInfo.infoString(0)));
          [[fallthrough]];
        case 0:
          break;
        }

        props.SetPlatform(Platforms::COMMODORE_64);
        props.SetChannels({"Voice 1"s, "Voice 2"s, "Voice 3"s}, tuneInfo.sidChips());

        tune->FillDuration(params);
        return MakePtr<Holder>(std::move(tune), std::move(properties));
      }
      catch (const std::exception&)
      {
        return {};
      }
    }
  };

  MultitrackFactory::Ptr CreateFactory()
  {
    return MakePtr<Factory>();
  }
}  // namespace Module::Sid
