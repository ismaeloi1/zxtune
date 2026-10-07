/**
 *
 * @file
 *
 * @brief  Game Music Emu-based formats support implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "module/players/external/gme.h"

#include "module/players/duration.h"
#include "module/players/external/gym.h"
#include "module/players/external/kss.h"
#include "module/players/platforms.h"
#include "module/players/properties_helper.h"
#include "module/players/streaming.h"

#include "core/core_parameters.h"
#include "debug/log.h"
#include "formats/multitrack/container.h"
#include "math/numeric.h"
#include "module/attributes.h"
#include "module/holder.h"
#include "module/renderer.h"
#include "module/voices_scope.h"
#include "parameters/tracking_helper.h"
#include "strings/optimize.h"
#include "tools/xrange.h"

#include "contract.h"
#include "error.h"
#include "make_ptr.h"
#include "string_view.h"

#include "3rdparty/gme/gme/Classic_Emu.h"
#include "3rdparty/gme/gme/Gbs_Emu.h"
#include "3rdparty/gme/gme/Gme_File.h"
#include "3rdparty/gme/gme/Gym_Emu.h"
#include "3rdparty/gme/gme/Hes_Emu.h"
#include "3rdparty/gme/gme/Kss_Emu.h"
#include "3rdparty/gme/gme/Nsf_Emu.h"
#include "3rdparty/gme/gme/Nsfe_Emu.h"

#include <algorithm>
#include <cmath>
#include <deque>
#include <functional>

namespace Module::GME
{
  const Debug::Stream Dbg("Module::GME");

  using EmuPtr = std::unique_ptr<::Music_Emu>;

  inline void CheckError(::blargg_err_t err)
  {
    if (err)
    {
      throw std::runtime_error(err);
    }
  }

  using TimeBase = Time::Millisecond;

  using DataCreator = Binary::Data::Ptr (*)(const Binary::Container&);
  using PlatformDetector = StringView (*)(Binary::View);

  struct TuneInfo : ::track_info_t
  {
    Strings::Array Channels;
  };

  struct GMETune
  {
    using Ptr = std::shared_ptr<GMETune>;

    GMETune(::gme_type_t type, Binary::Data::Ptr data, uint_t track)
      : Type(type)
      , Data(std::move(data))
      , Track(track)
    {}

    const ::gme_type_t Type;
    const Binary::Data::Ptr Data;
    const uint_t Track;
    Time::Milliseconds Duration;

    TuneInfo GetInfo() const
    {
      const EmuPtr emu(Type->new_info());
      CheckError(emu->load_mem(Data->Start(), Data->Size()));
      TuneInfo info;
      CheckError(emu->track_info(&info, Track));
      if (auto chans = emu->voice_count())
      {
        info.Channels.resize(chans);
        for (auto i : xrange(chans))
        {
          info.Channels[i] = emu->voice_name(i);
        }
      }
      return info;
    }

    void SetDuration(const ::track_info_t& info, const Parameters::Accessor& params)
    {
      if (info.length > 0)
      {
        Duration = Time::Duration<TimeBase>(info.length);
      }
      else if (info.loop_length > 0)
      {
        Duration = Time::Duration<TimeBase>(info.intro_length + info.loop_length);
      }
      else
      {
        Duration = GetDefaultDuration(params);
      }
    }
  };

  // Separate Blip buffers for each voice. Their deltas are summed and mixed exactly as Stereo_Buffer does
  // (including silence tracking), so the output is the same, while each voice is integrated separately as well.
  class VoicesBuffer : public ::Multi_Buffer
  {
  public:
    VoicesBuffer()
      : ::Multi_Buffer(2)
    {}

    // emulated frame end callback with position (in samples since start) the current state corresponds to
    std::function<void(uint64_t)> OnFrame;
    // interleaved [frames][voices] outputs of voices collected while reading
    std::vector<int16_t> Staging;
    bool Collect = false;

    uint_t GetVoices() const
    {
      return static_cast<uint_t>(Voices.size());
    }

    ::blargg_err_t set_channel_count(int count, int const* types) override
    {
      Voices.clear();
      for (int idx = 0; idx < count; ++idx)
      {
        auto& voice = *Voices.emplace_back(new Voice());
        for (auto& buf : voice.Bufs)
        {
          if (const auto* err = buf.set_sample_rate(Rate, Length))
          {
            return err;
          }
          buf.clock_rate(Clock);
          buf.bass_freq(Bass);
        }
      }
      clear();
      channels_changed();
      return ::Multi_Buffer::set_channel_count(count, types);
    }

    channel_t channel(int idx) override
    {
      auto& bufs = Voices[idx]->Bufs;
      channel_t result;
      result.left = &bufs[LEFT];
      result.right = &bufs[RIGHT];
      result.center = &bufs[CENTER];
      return result;
    }

    ::blargg_err_t set_sample_rate(int rate, int /*msec*/) override
    {
      Rate = rate;
      // shorter emulation slices give finer chip state snapshots
      Length = FRAME_MS;
      for (auto& voice : Voices)
      {
        for (auto& buf : voice->Bufs)
        {
          if (const auto* err = buf.set_sample_rate(Rate, Length))
          {
            return err;
          }
        }
      }
      ::Blip_Buffer probe;
      if (const auto* err = probe.set_sample_rate(Rate, Length))
      {
        return err;
      }
      return ::Multi_Buffer::set_sample_rate(probe.sample_rate(), probe.length());
    }

    uint64_t GetTotalRead() const
    {
      return TotalRead;
    }

    void clock_rate(int rate) override
    {
      Clock = rate;
      ForEachBuffer([rate](::Blip_Buffer& buf) { buf.clock_rate(rate); });
    }

    void bass_freq(int freq) override
    {
      Bass = freq;
      ForEachBuffer([freq](::Blip_Buffer& buf) { buf.bass_freq(freq); });
    }

    void clear() override
    {
      ForEachBuffer([](::Blip_Buffer& buf) { buf.clear(); });
      SamplesRead = 0;
      TotalRead = 0;
      Sums = {};
      LastNonSilence = {};
    }

    void end_frame(::blip_time_t time) override
    {
      std::array<bool, 3> modified = {};
      for (auto& voice : Voices)
      {
        for (uint_t idx = 0; idx < 3; ++idx)
        {
          auto& buf = voice->Bufs[idx];
          buf.end_frame(time);
          if (buf.modified())
          {
            buf.clear_modified();
            modified[idx] = true;
          }
        }
      }
      const auto avail = AvailFrames();
      for (uint_t idx = 0; idx < 3; ++idx)
      {
        if (modified[idx])
        {
          LastNonSilence[idx] = avail + BLIP_BUFFER_EXTRA;
        }
      }
      if (OnFrame)
      {
        OnFrame(TotalRead + avail);
      }
    }

    int samples_avail() const override
    {
      return AvailFrames() * 2;
    }

    int read_samples(::blip_sample_t* out, int size) override
    {
      const auto pairs = std::min(size, samples_avail()) / 2;
      if (pairs <= 0 || Voices.empty())
      {
        return 0;
      }
      const auto bass = Voices.front()->Bufs[CENTER].highpass_shift();
      for (auto& sum : Deltas)
      {
        sum.assign(pairs, 0);
      }
      for (const auto& voice : Voices)
      {
        for (uint_t idx = 0; idx < 3; ++idx)
        {
          const auto* src = voice->Bufs[idx].read_pos() + SamplesRead;
          auto* dst = Deltas[idx].data();
          for (int smp = 0; smp < pairs; ++smp)
          {
            dst[smp] += src[smp];
          }
        }
      }
      // see Stereo_Mixer::mix_stereo/mix_mono
      auto& center = Sums[CENTER];
      if (NonSilent(LEFT) | NonSilent(RIGHT))
      {
        auto& left = Sums[LEFT];
        auto& right = Sums[RIGHT];
        for (int smp = 0; smp < pairs; ++smp)
        {
          const auto sl = Clamp((center + left) >> ::Blip_Buffer::delta_bits);
          const auto sr = Clamp((center + right) >> ::Blip_Buffer::delta_bits);
          left -= left >> bass;
          right -= right >> bass;
          center -= center >> bass;
          left += Deltas[LEFT][smp];
          right += Deltas[RIGHT][smp];
          center += Deltas[CENTER][smp];
          out[smp * 2] = sl;
          out[smp * 2 + 1] = sr;
        }
      }
      else
      {
        for (int smp = 0; smp < pairs; ++smp)
        {
          const auto s = Clamp(center >> ::Blip_Buffer::delta_bits);
          center -= center >> bass;
          center += Deltas[CENTER][smp];
          out[smp * 2] = out[smp * 2 + 1] = s;
        }
      }
      if (Collect)
      {
        CollectVoices(pairs, bass);
      }
      SamplesRead += pairs;
      TotalRead += pairs;
      if (AvailFrames() <= 0 || immediate_removal())
      {
        ForEachBuffer([this](::Blip_Buffer& buf) { buf.remove_samples(SamplesRead); });
        for (auto& last : LastNonSilence)
        {
          last = std::max(last - SamplesRead, 0);
        }
        SamplesRead = 0;
      }
      return pairs * 2;
    }

  private:
    enum
    {
      LEFT = 0,
      RIGHT = 1,
      CENTER = 2
    };

    struct Voice
    {
      std::array<::Blip_Buffer, 3> Bufs;
    };

    template<class F>
    void ForEachBuffer(F&& func)
    {
      for (auto& voice : Voices)
      {
        for (auto& buf : voice->Bufs)
        {
          func(buf);
        }
      }
    }

    int AvailFrames() const
    {
      return Voices.empty() ? 0 : Voices.front()->Bufs[0].samples_avail() - SamplesRead;
    }

    bool NonSilent(uint_t idx) const
    {
      return LastNonSilence[idx] != 0 || (Sums[idx] >> ::Blip_Buffer::delta_bits) != 0;
    }

    static ::blip_sample_t Clamp(int val)
    {
      return static_cast<::blip_sample_t>(std::clamp(val, -32768, 32767));
    }

    // each voice is integrated using its own buffers integrators, louder side is used as non-panned output
    void CollectVoices(int pairs, int bass)
    {
      const auto voices = Voices.size();
      const auto offset = Staging.size();
      Staging.resize(offset + std::size_t(pairs) * voices);
      for (std::size_t idx = 0; idx < voices; ++idx)
      {
        auto& bufs = Voices[idx]->Bufs;
        const auto* dl = bufs[LEFT].read_pos() + SamplesRead;
        const auto* dr = bufs[RIGHT].read_pos() + SamplesRead;
        const auto* dc = bufs[CENTER].read_pos() + SamplesRead;
        int left = bufs[LEFT].integrator();
        int right = bufs[RIGHT].integrator();
        int center = bufs[CENTER].integrator();
        auto* target = Staging.data() + offset + idx;
        for (int smp = 0; smp < pairs; ++smp, target += voices)
        {
          const auto sl = (center + left) >> ::Blip_Buffer::delta_bits;
          const auto sr = (center + right) >> ::Blip_Buffer::delta_bits;
          *target = Clamp(std::abs(sl) >= std::abs(sr) ? sl : sr);
          left -= left >> bass;
          right -= right >> bass;
          center -= center >> bass;
          left += dl[smp];
          right += dr[smp];
          center += dc[smp];
        }
        bufs[LEFT].set_integrator(left);
        bufs[RIGHT].set_integrator(right);
        bufs[CENTER].set_integrator(center);
      }
    }

  private:
    static const int FRAME_MS = 10;
    // see Multi_Buffer.cpp
    static const int BLIP_BUFFER_EXTRA = 32;
    std::vector<std::unique_ptr<Voice>> Voices;
    int Rate = 0;
    int Length = FRAME_MS;
    int Clock = 0;
    int Bass = 16;
    int SamplesRead = 0;
    uint64_t TotalRead = 0;
    std::array<int, 3> Sums = {};
    std::array<int, 3> LastNonSilence = {};
    std::array<std::vector<int>, 3> Deltas;
  };

  class GME
  {
  public:
    GME(const GMETune& tune, uint_t samplerate, VoicesBuffer* voices = nullptr)
      : Emu(tune.Type->new_emu())
      , SoundFreq(samplerate)
      , Track(tune.Track)
    {
      if (voices)
      {
        // should be set before samplerate
        auto* const classic = dynamic_cast<::Classic_Emu*>(Emu.get());
        Require(classic != nullptr);
        classic->set_buffer(voices);
      }
      // TODO: effects_buffer
      CheckError(Emu->set_sample_rate(samplerate));
      CheckError(Emu->load_mem(tune.Data->Start(), tune.Data->Size()));
      Reset();
    }

    void Reset()
    {
      CheckError(Emu->start_track(Track));
    }

    Sound::Chunk Render(uint_t samples)
    {
      static_assert(Sound::Sample::CHANNELS == 2, "Incompatible sound channels count");
      static_assert(Sound::Sample::BITS == 16, "Incompatible sound bits count");
      Sound::Chunk result(samples);
      auto* const buffer = safe_ptr_cast<::Music_Emu::sample_t*>(result.data());
      CheckError(Emu->play(static_cast<int>(samples * Sound::Sample::CHANNELS), buffer));
      return result;
    }

    void Skip(uint_t samples)
    {
      CheckError(Emu->skip(samples));
    }

    void SetChannelsMask(int mask)
    {
      Emu->mute_voices(mask);
    }

    uint_t GetSoundFreq() const
    {
      return SoundFreq;
    }

    ::Music_Emu& GetEmu()
    {
      return *Emu;
    }

    static bool SupportsVoices(const GMETune& tune)
    {
      const EmuPtr emu(tune.Type->new_emu());
      return dynamic_cast<::Classic_Emu*>(emu.get()) != nullptr;
    }

  private:
    const EmuPtr Emu;
    const uint_t SoundFreq;
    const uint_t Track;
  };

  const auto FRAME_DURATION = Time::Milliseconds(100);

  // NES 2A03 registers snapshot for visualization
  void FillNesState(const ::Nes_Apu& apu, bool pal, VoiceState* states)
  {
    const float clock = pal ? 1662607.0f : 1789773.0f;
    for (int osc = 0; osc < ::Nes_Apu::osc_count; ++osc)
    {
      ::Nes_Apu::osc_state_t in;
      apu.get_osc_state(osc, &in);
      auto& out = states[osc];
      auto& f = out.Fields;
      const uint_t period = in.regs[2] | ((in.regs[3] & 7) << 8);
      const bool active = in.enabled && in.length_counter > 0;
      auto setLevel = [&out](int volume) {
        // linear 4 bit DAC
        out.Level = volume ? 20 * std::log10(volume / 15.0f) : -96.0f;
        out.Flags |= VoiceState::HAS_LEVEL;
      };
      switch (osc)
      {
      case 0:
      case 1:
        out.Kind = VoiceState::NES_PULSE;
        f[0] = in.regs[0] >> 6;
        f[1] = static_cast<uint8_t>(active ? in.volume : 0);
        f[2] = (in.regs[0] >> 4) & 1;
        f[3] = period & 0xff;
        f[4] = period >> 8;
        f[5] = active;
        f[6] = in.regs[1] >> 7;
        setLevel(active && period >= 8 ? in.volume : 0);
        if (period >= 8)
        {
          out.Frequency = clock / (16 * (period + 1));
          out.Flags |= VoiceState::HAS_FREQUENCY;
        }
        break;
      case 2:
        out.Kind = VoiceState::NES_TRIANGLE;
        f[0] = in.linear_counter > 0;
        f[1] = period & 0xff;
        f[2] = period >> 8;
        f[3] = active;
        setLevel(active && in.linear_counter > 0 && period >= 2 ? 15 : 0);
        if (period >= 2)
        {
          out.Frequency = clock / (32 * (period + 1));
          out.Flags |= VoiceState::HAS_FREQUENCY;
        }
        break;
      case 3:
        out.Kind = VoiceState::NES_NOISE;
        f[0] = static_cast<uint8_t>(active ? in.volume : 0);
        f[1] = in.regs[2] >> 7;
        f[2] = in.regs[2] & 15;
        f[3] = active;
        out.Flags |= VoiceState::NOISE;
        setLevel(active ? in.volume : 0);
        break;
      default:
        out.Kind = VoiceState::NES_DMC;
        f[0] = static_cast<uint8_t>(in.dac);
        f[1] = static_cast<uint8_t>(in.dmc_period);
        f[2] = in.enabled;
        f[3] = (in.regs[0] >> 6) & 1;
        break;
      }
      if (active && (out.Flags & VoiceState::HAS_LEVEL) && out.Level > -96.0f)
      {
        out.Flags |= VoiceState::KEY_ON;
      }
    }
  }

  class Renderer
    : public Module::Renderer
    , public VoicesScopeSource
  {
  public:
    Renderer(GMETune::Ptr tune, uint_t samplerate, Parameters::Accessor::Ptr params)
      : Tune(std::move(tune))
      , State(Tune->Duration)
      , Params(std::move(params))
      , Samplerate(samplerate)
      , Engine(std::make_unique<GME>(*Tune, samplerate))
    {}

    bool SetVoicesScope(VoicesScope::Ptr scope) override
    {
      if (!scope || !GME::SupportsVoices(*Tune))
      {
        return false;
      }
      // emulator is recreated with separate voices buffers at current position
      auto buffer = std::make_unique<VoicesBuffer>();
      auto engine = std::make_unique<GME>(*Tune, Samplerate, buffer.get());
      const auto position = State.At();
      Engine = std::move(engine);
      Voices = std::move(buffer);
      Voices->OnFrame = [this](uint64_t pos) { TakeState(pos); };
      State.Reset();
      Params.Reset();
      SeekTune(position);
      Scope = std::move(scope);
      auto& emu = Engine->GetEmu();
      VoicesGroup group;
      group.Name = Tune->Type->system;
      for (int voice = 0, lim = std::min<int>(emu.voice_count(), MAX_VOICES); voice < lim; ++voice)
      {
        group.Voices.emplace_back(emu.voice_name(voice));
      }
      Scope->SetVoicesGroups({group}, false);
      Scope->SetDescription(group.Name + ", Game_Music_Emu");
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
      auto result = Engine->Render(GetSamples(avail));
      if (Voices && Voices->Collect)
      {
        FeedVoices(static_cast<uint_t>(result.size()));
      }
      return result;
    }

    void Reset() override
    {
      try
      {
        State.Reset();
        ResetEngine();
      }
      catch (const std::exception& e)
      {
        Dbg(e.what());
      }
    }

    void SetPosition(Time::AtMillisecond request) override
    {
      try
      {
        SeekTune(request);
      }
      catch (const std::exception& e)
      {
        Dbg(e.what());
      }
    }

  private:
    void ResetEngine()
    {
      Engine->Reset();
      Params.Reset();
      if (Voices)
      {
        Voices->Staging.clear();
        Snapshots.clear();
      }
    }

    // separate voices are collected only while consumed
    void UpdateVoicesTap()
    {
      if (!Voices)
      {
        return;
      }
      const bool active = Scope->IsActive();
      if (active != Voices->Collect)
      {
        Voices->Collect = active;
        Voices->Staging.clear();
        Snapshots.clear();
      }
    }

    void TakeState(uint64_t pos)
    {
      if (!Voices->Collect)
      {
        return;
      }
      auto& snapshot = Snapshots.emplace_back();
      snapshot.Position = pos;
      snapshot.States.resize(Voices->GetVoices());
      if (auto* const nsf = dynamic_cast<::Nsf_Emu*>(&Engine->GetEmu()))
      {
        if (snapshot.States.size() >= ::Nes_Apu::osc_count)
        {
          FillNesState(*nsf->core().nes_apu(), nsf->header().pal_only(), snapshot.States.data());
        }
      }
    }

    // Emulator runs ahead of output to detect silence (see Track_Filter), so voices are taken by output position
    void FeedVoices(uint_t frames)
    {
      const auto voices = Voices->GetVoices();
      auto& staging = Voices->Staging;
      const auto totalRead = Voices->GetTotalRead();
      const auto stagingStart = totalRead - staging.size() / voices;
      // samples_ahead is in single channel samples
      const auto ahead = std::min<uint64_t>(Engine->GetEmu().samples_ahead() / 2, totalRead);
      const auto output = totalRead - ahead - std::min<uint64_t>(frames, totalRead - ahead);
      Block.assign(std::size_t(frames) * voices, 0);
      for (uint_t frame = 0; frame < frames; ++frame)
      {
        const auto pos = output + frame;
        if (pos >= stagingStart && (pos - stagingStart) * voices < staging.size())
        {
          const auto* src = staging.data() + (pos - stagingStart) * voices;
          std::copy_n(src, voices, Block.data() + std::size_t(frame) * voices);
        }
      }
      // consumed part
      const auto end = output + frames;
      if (end > stagingStart)
      {
        staging.erase(staging.begin(),
                      staging.begin() + std::min<std::size_t>((end - stagingStart) * voices, staging.size()));
      }
      const auto period = std::max<uint_t>(Scope->GetStatePeriod(), 1);
      LastStates.resize(voices);
      for (uint_t done = 0; done < frames;)
      {
        const auto part = std::min(period, frames - done);
        Scope->Feed(0, voices, Block.data() + std::size_t(done) * voices, part);
        done += part;
        // the latest snapshot taken not after the end of block
        const auto pos = output + done;
        while (!Snapshots.empty() && Snapshots.front().Position <= pos)
        {
          LastStates = std::move(Snapshots.front().States);
          Snapshots.pop_front();
        }
        Scope->FeedVoices(0, LastStates.data(), voices);
      }
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

    uint_t GetSamples(Time::Microseconds period) const
    {
      return period.Get() * Engine->GetSoundFreq() / period.PER_SECOND;
    }

    void SeekTune(Time::AtMillisecond request)
    {
      if (request < State.At())
      {
        ResetEngine();
      }
      if (const auto toSkip = State.Seek(request))
      {
        // skipped output is not visualized
        const bool collect = Voices && Voices->Collect;
        if (collect)
        {
          Voices->Collect = false;
        }
        Engine->Skip(GetSamples(toSkip));
        if (collect)
        {
          Voices->Collect = true;
          Voices->Staging.clear();
          Snapshots.clear();
        }
      }
    }

  private:
    static const int MAX_VOICES = 32;
    const GMETune::Ptr Tune;
    TimedState State;
    Parameters::TrackingHelper<Parameters::Accessor> Params;
    const uint_t Samplerate;
    // should outlive emulator
    std::unique_ptr<VoicesBuffer> Voices;
    std::unique_ptr<GME> Engine;
    VoicesScope::Ptr Scope;
    struct Snapshot
    {
      uint64_t Position = 0;
      std::vector<VoiceState> States;
    };
    std::deque<Snapshot> Snapshots;
    std::vector<VoiceState> LastStates;
    std::vector<int16_t> Block;
  };

  class Holder : public Module::Holder
  {
  public:
    Holder(GMETune::Ptr tune, Parameters::Accessor::Ptr props)
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
      try
      {
        return MakePtr<Renderer>(Tune, samplerate, std::move(params));
      }
      catch (const std::exception& e)
      {
        throw Error(THIS_LINE, e.what());
      }
    }

  private:
    const GMETune::Ptr Tune;
    const Parameters::Accessor::Ptr Properties;
  };

  Binary::Data::Ptr DefaultDataCreator(const Binary::Container& data)
  {
    return data.GetSubcontainer(0, data.Size());
  }

  void GetProperties(const TuneInfo& info, PropertiesHelper& props)
  {
    const auto system = Strings::OptimizeAscii(info.system);
    const auto song = Strings::OptimizeAscii(info.song);
    const auto game = Strings::OptimizeAscii(info.game);
    const auto author = Strings::OptimizeAscii(info.author);
    const auto comment = Strings::OptimizeAscii(info.comment);
    const auto copyright = Strings::OptimizeAscii(info.copyright);
    const auto dumper = Strings::OptimizeAscii(info.dumper);

    props.SetComputer(system);
    props.SetTitle(game);
    props.SetTitle(song);
    props.SetProgram(game);
    props.SetAuthor(dumper);
    props.SetAuthor(author);
    props.SetComment(copyright);
    props.SetComment(comment);
    props.SetChannels(info.Channels);
  }

  class MultitrackFactory : public Module::MultitrackFactory
  {
  public:
    MultitrackFactory(::gme_type_t type, PlatformDetector detectPlatform)
      : Type(type)
      , DetectPlatform(detectPlatform)
    {}

    Holder::Ptr CreateModule(const Parameters::Accessor& params, const Formats::Multitrack::Container& container,
                             Parameters::Container::Ptr properties) const override
    {
      try
      {
        PropertiesHelper props(*properties);
        auto data = DefaultDataCreator(container);
        props.SetPlatform(DetectPlatform(*data));
        auto tune = MakePtr<GMETune>(Type, std::move(data), container.StartTrackIndex());

        const auto info = tune->GetInfo();
        GetProperties(info, props);
        tune->SetDuration(info, params);

        return MakePtr<Holder>(std::move(tune), std::move(properties));
      }
      catch (const std::exception& e)
      {
        Dbg("Failed to create {}: {}", Type->extension_, e.what());
      }
      return {};
    }

  private:
    const ::gme_type_t Type;
    const PlatformDetector DetectPlatform;
  };

  class SingletrackFactory : public ExternalParsingFactory
  {
  public:
    SingletrackFactory(::gme_type_t type, DataCreator createData, PlatformDetector detectPlatform)
      : Type(type)
      , CreateData(createData)
      , DetectPlatform(detectPlatform)
    {}

    Holder::Ptr CreateModule(const Parameters::Accessor& params, const Formats::Chiptune::Container& container,
                             Parameters::Container::Ptr properties) const override
    {
      try
      {
        PropertiesHelper props(*properties);
        auto data = CreateData(container);
        props.SetPlatform(DetectPlatform(*data));
        auto tune = MakePtr<GMETune>(Type, std::move(data), 0);
        const auto info = tune->GetInfo();
        GetProperties(info, props);
        tune->SetDuration(info, params);

        return MakePtr<Holder>(std::move(tune), std::move(properties));
      }
      catch (const std::exception& e)
      {
        Dbg("Failed to create {}: {}", Type->extension_, e.what());
      }
      catch (const Error& e)
      {
        Dbg("Failed to create {}: {}", Type->extension_, e.ToString());
      }
      return {};
    }

  private:
    const ::gme_type_t Type;
    const DataCreator CreateData;
    const PlatformDetector DetectPlatform;
  };

  MultitrackFactory::Ptr CreateNsfFactory()
  {
    return MakePtr<MultitrackFactory>(
        ::Nsf_Emu::static_type(), [](Binary::View) -> StringView { return Platforms::NINTENDO_ENTERTAINMENT_SYSTEM; });
  }

  MultitrackFactory::Ptr CreateNsfeFactory()
  {
    return MakePtr<MultitrackFactory>(
        ::Nsfe_Emu::static_type(), [](Binary::View) -> StringView { return Platforms::NINTENDO_ENTERTAINMENT_SYSTEM; });
  }

  MultitrackFactory::Ptr CreateGbsFactory()
  {
    return MakePtr<MultitrackFactory>(::Gbs_Emu::static_type(),
                                      [](Binary::View) -> StringView { return Platforms::GAME_BOY; });
  }

  MultitrackFactory::Ptr CreateKssxFactory()
  {
    return MakePtr<MultitrackFactory>(::Kss_Emu::static_type(), &KSS::DetectPlatform);
  }

  MultitrackFactory::Ptr CreateHesFactory()
  {
    return MakePtr<MultitrackFactory>(::Hes_Emu::static_type(),
                                      [](Binary::View) -> StringView { return Platforms::PC_ENGINE; });
  }

  ExternalParsingFactory::Ptr CreateGymFactory()
  {
    return MakePtr<SingletrackFactory>(::Gym_Emu::static_type(), &GYM::CreateData,
                                       [](Binary::View) -> StringView { return Platforms::SEGA_GENESIS; });
  }

  ExternalParsingFactory::Ptr CreateKssFactory()
  {
    return MakePtr<SingletrackFactory>(::Kss_Emu::static_type(), &DefaultDataCreator, &KSS::DetectPlatform);
  }
}  // namespace Module::GME
