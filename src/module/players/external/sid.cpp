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
#include "module/players/seek_snapshots.h"
#include "module/players/streaming.h"

#include "core/core_parameters.h"
#include "core/plugins_parameters.h"
#include "debug/log.h"
#include "formats/multitrack/container.h"
#include "module/attributes.h"
#include "module/seek_interruption.h"
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
#include <optional>
#include <thread>
#include <vector>

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

  // Snapshots of chip state are taken with ~1ms period
  const uint_t STATE_RATE = 1000;

  // Fast skipping leaves analog part of chips (filters, resampler) stale. Regular emulation settles it quickly,
  // except of slow drift of DC level due to output high-pass filter (RC=0.1s), which is not audible
  const auto SKIP_SETTLE_DURATION = Time::Milliseconds(250);
  // Skipping is split to check for interruption, few tens of milliseconds of work on slow devices
  const auto SKIP_CHUNK_DURATION = Time::Milliseconds(250);
  // Snapshot takes 150..220kB (mostly C64 memory), each one costs settling, so do not take them too often on
  // short tunes
  const auto SNAPSHOTS_PERIOD = Time::Seconds(1);

  class VoicesSinkAdapter : public SidVoicesSink
  {
  public:
    void voices(unsigned int chip, const short* samples, unsigned int count) override
    {
      // split voices outputs and chip data
      VoicesData.resize(count * VOICES);
      ChipData.resize(count * 4);
      for (unsigned int idx = 0; idx < count; ++idx, samples += SidVoicesSink::STRIDE)
      {
        std::copy_n(samples, VOICES, VoicesData.data() + idx * VOICES);
        for (unsigned int val = 0; val < 4; ++val)
        {
          ChipData[idx * 4 + val] = static_cast<uint8_t>(samples[VOICES + val]);
        }
      }
      Scope->Feed(chip * VOICES, VOICES, VoicesData.data(), count);
      Scope->FeedChip(chip, ChipData.data(), count);
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

  private:
    std::vector<int16_t> VoicesData;
    std::vector<uint8_t> ChipData;
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
      // libsidplayfp rejects (and stops tune!) buffers shorter than 10ms of mono samples
      MinSkipSamples = (samplerate + 99) / 100;
      MinRenderSamples = (samplerate + 199) / 200;
      SettleSamples = std::max<uint_t>(GetSamples(SKIP_SETTLE_DURATION), MinRenderSamples);
      SkipChunkSamples = std::max<uint_t>(GetSamples(SKIP_CHUNK_DURATION), MinSkipSamples);
    }

    // Byte image of emulation objects, see sidplayfp::saveState
    using Snapshot = std::vector<uint8_t>;

    enum class Change
    {
      NONE,
      // emulation is changed in place, so snapshots are not valid anymore
      STATE,
      // tune should be reloaded to apply new configuration
      RELOAD,
    };

    void Load(SidTune& tune)
    {
      CheckSidplayError(Player.load(&tune));
      Tune = &tune;
      // new chips are created, so voices are not muted
      MuteMask = 0;
      UpdateDescription();
    }

    //! @return 0 if snapshots are not supported by current emulation
    std::size_t GetStateSize() const
    {
      return Player.stateSize();
    }

    std::unique_ptr<Snapshot> SaveState() const
    {
      auto result = std::make_unique<Snapshot>(Player.stateSize());
      Player.saveState(result->data());
      return result;
    }

    void LoadState(const Snapshot& state)
    {
      Player.loadState(state.data());
      // voices muting is a part of state
      for (uint_t chan = 0; chan < ChipsCount * VOICES; ++chan)
      {
        Player.mute(chan / VOICES, chan % VOICES, MuteMask & (1 << chan));
      }
    }

    Change ApplyParameters(const SidParameters& sidParams)
    {
      auto change = Change::NONE;
      const bool useResidFp = sidParams.GetUseResidFp();
      sidbuilder* const builder = useResidFp ? static_cast<sidbuilder*>(&GetResidFp()) : &GetResid();
      const bool useFilter = sidParams.GetUseFilter();
      FilterSettings newFilter{.UseResidFp = useResidFp, .UseFilter = useFilter};
      if (useResidFp)
      {
        newFilter.Curve6581 = sidParams.GetFilter6581Curve();
        newFilter.Range6581 = sidParams.GetFilter6581Range();
        newFilter.Curve8580 = sidParams.GetFilter8580Curve();
        newFilter.CombinedWaveforms = sidParams.GetCombinedWaveforms();
      }
      else
      {
        newFilter.Bias = sidParams.GetFilterBias();
      }
      // filters tuning reallocates emulation tables, so apply only real changes
      if (!Filter || !(*Filter == newFilter))
      {
        if (useResidFp)
        {
          auto& fp = GetResidFp();
          fp.filter(useFilter);
          fp.filter6581Curve(newFilter.Curve6581);
          fp.filter6581Range(newFilter.Range6581);
          fp.filter8580Curve(newFilter.Curve8580);
          fp.combinedWaveformsStrength(newFilter.CombinedWaveforms);
        }
        else
        {
          auto& resid = GetResid();
          resid.filter(useFilter);
          resid.bias(newFilter.Bias);
        }
        Filter = newFilter;
        change = Change::STATE;
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
        // models and engine are applied by sidplayfp only on tune loading
        if (IsConfigured && Tune != nullptr)
        {
          change = Change::RELOAD;
        }
        CheckSidplayError(Player.config(Config));
        IsConfigured = true;
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
      return change;
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
      // too short buffer (e.g. at the end of track) stops the tune, so render a bit more
      Sound::Chunk result(std::max(samples, MinRenderSamples));
      Player.play(safe_ptr_cast<short*>(result.data()), result.size() * Sound::Sample::CHANNELS);
      return result;
    }

    //! @return samples skipped, less than requested if interrupted, may be slightly more due to engine limitations
    uint_t Skip(uint_t samples, const InterruptibleSeek::Predicate& interrupted)
    {
      const auto fastEnd = samples - std::min(samples, SettleSamples);
      uint_t done = 0;
      bool stopped = false;
      while (done + MinSkipSamples <= fastEnd)
      {
        if (interrupted && interrupted())
        {
          stopped = true;
          break;
        }
        // stereo mixer requires even count, which is mono samples (not frames!) count without output buffer
        const auto part = std::max(std::min(fastEnd - done, SkipChunkSamples) & ~1u, MinSkipSamples + 1) & ~1u;
        Player.play(nullptr, part);
        done += part;
      }
      const auto settleEnd = stopped ? std::min(samples, done + SettleSamples) : samples;
      done += Settle(settleEnd - done);
      return done;
    }

    void SetVoicesScope(VoicesScope::Ptr scope, uint_t chips)
    {
      Sink.Scope = std::move(scope);
      if (Sink.Scope)
      {
        std::vector<VoicesGroup> groups(chips);
        for (uint_t chip = 0; chip < chips; ++chip)
        {
          groups[chip].Name = "SID " + std::to_string(chip + 1);
          for (uint_t voice = 0; voice < VOICES; ++voice)
          {
            groups[chip].Voices.emplace_back("Voice " + std::to_string(voice + 1));
          }
        }
        Sink.Scope->SetVoicesGroups(groups, true);
        UpdateDescription();
      }
      SetSink(Config.sidEmulation, nullptr);
    }

  private:
    uint_t GetSamples(Time::Milliseconds period) const
    {
      return static_cast<uint_t>(uint64_t(period.Get()) * Config.frequency / period.PER_SECOND);
    }

    // Regular emulation with output discarded to make state after skipping the same as after continuous playback
    uint_t Settle(uint_t samples)
    {
      if (!samples)
      {
        return 0;
      }
      samples = std::max(samples, MinRenderSamples);
      SetSink(Config.sidEmulation, nullptr);
      SettleBuffer.resize(samples * Sound::Sample::CHANNELS);
      Player.play(SettleBuffer.data(), SettleBuffer.size());
      return samples;
    }

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

    uint_t MinSkipSamples = 1;
    uint_t MinRenderSamples = 1;
    uint_t SettleSamples = 0;
    uint_t SkipChunkSamples = 1;
    std::vector<short> SettleBuffer;

    struct FilterSettings
    {
      bool UseResidFp = false;
      bool UseFilter = false;
      double Curve6581 = 0;
      double Range6581 = 0;
      double Curve8580 = 0;
      SidConfig::sid_cw_t CombinedWaveforms = SidConfig::AVERAGE;
      double Bias = 0;

      bool operator==(const FilterSettings&) const = default;
    };
    std::optional<FilterSettings> Filter;
  };

  const auto FRAME_DURATION = Time::Milliseconds(100);

  class Renderer
    : public Module::Renderer
    , public VoicesScopeSource
    , public InterruptibleSeek
  {
  public:
    Renderer(Model::Ptr tune, uint_t samplerate, const Parameters::Accessor::Ptr& params)
      : Tune(std::move(tune))
      , State(Tune->GetDuration())
      , Engine(MakePtr<SidEngine>())
      , SidParams(MakePtr<SidParameters>(params))
      , SnapshotsMemory(GetSeekSnapshotsMemoryLimit(*params))
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
      const auto loops = State.LoopCount();
      const auto avail = State.ConsumeUpTo(FRAME_DURATION);
      auto result = Engine->Render(GetSamples(avail));
      Samples += result.size();
      // emulation is not restarted on looping, so position does not correspond to emulated samples anymore
      InSync = InSync && loops == State.LoopCount();
      TakeSnapshot();
      return result;
    }

    void Reset() override
    {
      State.Reset();
      ResetEngine();
    }

    void SetPosition(Time::AtMillisecond request) override
    {
      // position is wrapped by state, so detect backward seeking by already wrapped one
      const auto duration = Tune->GetDuration().Get();
      if (duration && request.Get() >= duration)
      {
        request = Time::AtMillisecond(request.Get() % duration);
      }
      SeekTo(request, SeekInterrupted);
    }

    bool SetSeekInterruption(Predicate interrupted) override
    {
      SeekInterrupted = std::move(interrupted);
      return true;
    }

    bool SetVoicesScope(VoicesScope::Ptr scope) override
    {
      const uint_t chips = std::max<uint_t>(Tune->getInfo()->sidChips(), 1);
      Engine->SetVoicesScope(std::move(scope), chips);
      return true;
    }

  private:
    // Emulation from the very beginning is replaced by initial state restoring, so tune is loaded only once
    // per emulation configuration
    void ResetEngine()
    {
      if (Initial)
      {
        Engine->LoadState(*Initial);
      }
      else
      {
        Engine->Load(*Tune);
        SidParams.Reset();
        Snapshots.reset();
        const auto stateSize = Engine->GetStateSize();
        Dbg("State size {} bytes", stateSize);
        if (stateSize)
        {
          Initial = Engine->SaveState();
          Snapshots.emplace(GetSamples(SNAPSHOTS_PERIOD), GetSamples(Tune->GetDuration()), stateSize, SnapshotsMemory);
          Snapshots->Restart();
        }
      }
      Samples = 0;
      InSync = true;
    }

    // Snapshots are byte images of objects allocated on configuration, see sidplayfp::saveState
    void InvalidateSnapshots()
    {
      Initial.reset();
      if (Snapshots)
      {
        Snapshots->Invalidate();
      }
    }

    void TakeSnapshot()
    {
      if (InSync && Snapshots && Snapshots->IsDue(Samples))
      {
        Snapshots->Add(Samples, Engine->SaveState());
      }
    }

    //! @return true if restored
    bool RestoreSnapshot(uint64_t after, uint64_t upTo)
    {
      if (const auto* snapshot = Snapshots ? Snapshots->Find(after, upTo) : nullptr)
      {
        Engine->LoadState(*snapshot->State);
        Samples = snapshot->Position;
        InSync = true;
        return true;
      }
      return false;
    }

    uint_t GetSamples(Time::Microseconds period) const
    {
      return period.Get() * Engine->GetSoundFreq() / period.PER_SECOND;
    }

    Time::AtMicrosecond GetPosition(uint64_t samples) const
    {
      return Time::AtMicrosecond() + Time::Microseconds::FromRatio(samples, Engine->GetSoundFreq());
    }

    void SeekTo(Time::AtMillisecond request, const Predicate& interrupted)
    {
      if (request < State.At())
      {
        if (!RestoreSnapshot(0, GetSamples(request - Time::AtMillisecond())))
        {
          ResetEngine();
        }
        // state backward seeking just sets position
        State.Seek(GetPosition(Samples));
      }
      else if (InSync && RestoreSnapshot(Samples, GetSamples(request - Time::AtMillisecond())))
      {
        State.Seek(GetPosition(Samples));
      }
      const auto from = State.PreciseAt();
      const auto toSkip = GetSamples(State.Seek(request));
      const auto done = Skip(toSkip, interrupted);
      if (done < toSkip)
      {
        State.Seek(from + Time::Microseconds::FromRatio(done, Engine->GetSoundFreq()));
      }
    }

    //! @return skipped samples count
    uint64_t Skip(uint64_t samples, const Predicate& interrupted)
    {
      const auto start = Samples;
      for (const auto end = Samples + samples; Samples < end;)
      {
        // split to take snapshots on the way
        const auto next = InSync && Snapshots ? std::min(end, Snapshots->NextAt(Samples)) : end;
        const auto part = static_cast<uint_t>(next - Samples);
        const auto done = Engine->Skip(part, interrupted);
        Samples += done;
        if (done < part)
        {
          break;
        }
        TakeSnapshot();
      }
      return Samples - start;
    }

    void ApplyParameters()
    {
      if (!SidParams.IsChanged())
      {
        return;
      }
      switch (Engine->ApplyParameters(*SidParams))
      {
      case SidEngine::Change::RELOAD:
        Reload();
        break;
      case SidEngine::Change::STATE:
        InvalidateSnapshots();
        break;
      default:
        break;
      }
    }

    // Apply new emulation configuration keeping current position
    void Reload()
    {
      const auto pos = State.At();
      InvalidateSnapshots();
      State.Reset();
      ResetEngine();
      // restore muted voices
      Engine->ApplyParameters(*SidParams);
      SeekTo(pos, {});
    }

  private:
    const Model::Ptr Tune;
    TimedState State;
    const SidEngine::Ptr Engine;
    Parameters::TrackingHelper<SidParameters> SidParams;
    const uint64_t SnapshotsMemory;
    Predicate SeekInterrupted;
    // emulated since reset (or initial state restoring)
    uint64_t Samples = 0;
    bool InSync = true;
    std::unique_ptr<SidEngine::Snapshot> Initial;
    std::optional<SeekSnapshots<SidEngine::Snapshot>> Snapshots;
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

  // reSIDfp filter models tables are built on first use (thread-safe) and take noticeable time. Every chip
  // emulation requires tables of both models, so prepare them in parallel to make the first tune start faster
  void WarmUpEngines()
  {
    std::thread([]() { ReSIDfpBuilder::prepareModel(SidConfig::MOS6581); }).detach();
    std::thread([]() { ReSIDfpBuilder::prepareModel(SidConfig::MOS8580); }).detach();
  }

  MultitrackFactory::Ptr CreateFactory()
  {
    WarmUpEngines();
    return MakePtr<Factory>();
  }
}  // namespace Module::Sid
