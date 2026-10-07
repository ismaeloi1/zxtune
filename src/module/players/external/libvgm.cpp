/**
 *
 * @file
 *
 * @brief  libvgm-based formats support
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "module/players/external/libvgm.h"

#include "formats/chiptune/multidevice/sound98.h"
#include "formats/chiptune/multidevice/videogamemusic.h"
#include "module/players/duration.h"
#include "module/players/external/videogamemusic.h"
#include "module/players/properties_helper.h"
#include "module/players/properties_meta.h"
#include "module/players/streaming.h"

#include "binary/container_factories.h"
#include "core/core_parameters.h"
#include "debug/log.h"
#include "module/voices_scope.h"
#include "parameters/tracking_helper.h"
#include "tools/xrange.h"

#include "contract.h"
#include "error_tools.h"
#include "make_ptr.h"

#include "3rdparty/vgm/emu/SoundEmu.h"
#include "3rdparty/vgm/player/helper.h"
#include "3rdparty/vgm/player/playera.hpp"
#include "3rdparty/vgm/player/s98player.hpp"
#include "3rdparty/vgm/player/vgmplayer.hpp"
#include "3rdparty/vgm/utils/DataLoader.h"

#include <algorithm>
#include <cmath>
#include <map>

namespace Module::LibVGM
{
  const Debug::Stream Dbg("Module::LibVGM");

  using PlayerPtr = std::unique_ptr<::PlayerA>;

  using PlayerCreator = PlayerPtr (*)();

  template<class PlayerType>
  PlayerPtr Create()
  {
    auto player = PlayerPtr(new PlayerA());
    player->RegisterPlayerEngine(new PlayerType());
    return player;
  }

  struct Model
  {
    using Ptr = std::shared_ptr<Model>;

    Model(PlayerCreator create, Binary::View data)
      : CreatePlayer(create)
      , Data(Binary::CreateContainer(data))
    {}

    const PlayerCreator CreatePlayer;
    const Binary::Data::Ptr Data;
  };

  class LoaderAdapter
  {
  public:
    explicit LoaderAdapter(Binary::View raw)
      : Raw(raw)
    {
      std::memset(&Delegate, 0, sizeof(Delegate));
      static const DATA_LOADER_CALLBACKS CALLBACKS = {0xdeadbeef, "",    &Open,   &Read, nullptr,
                                                      &Close,     &Tell, &Length, &Eof,  nullptr};
      ::DataLoader_Setup(&Delegate, &CALLBACKS, this);
      Require(0 == ::DataLoader_Load(Get()));
    }

    ~LoaderAdapter()
    {
      ::DataLoader_Reset(&Delegate);
    }

    DATA_LOADER* Get()
    {
      return &Delegate;
    }

  private:
    static LoaderAdapter* Cast(void* ctx)
    {
      return static_cast<LoaderAdapter*>(ctx);
    }

    static UINT8 Open(void* ctx)
    {
      Cast(ctx)->Position = 0;
      return 0;
    }

    static UINT32 Read(void* ctx, UINT8* buf, UINT32 size)
    {
      auto* self = Cast(ctx);
      if (const auto sub = self->Raw.SubView(self->Position, size))
      {
        std::memcpy(buf, sub.Start(), sub.Size());
        self->Position += sub.Size();
        return sub.Size();
      }
      else
      {
        return 0;
      }
    }

    static UINT8 Close(void* /*ctx*/)
    {
      return 0;
    }

    static INT32 Tell(void* ctx)
    {
      return Cast(ctx)->Position;
    }

    static UINT32 Length(void* ctx)
    {
      return Cast(ctx)->Raw.Size();
    }

    static UINT8 Eof(void* ctx)
    {
      const auto* self = Cast(ctx);
      return self->Position >= self->Raw.Size();
    }

  private:
    const Binary::View Raw;
    DATA_LOADER Delegate;
    std::size_t Position;
  };

  class ChannelsLayout
  {
    ChannelsLayout(const ChannelsLayout&) = delete;
    ChannelsLayout(ChannelsLayout&&) = default;

  public:
    using Ptr = std::shared_ptr<const ChannelsLayout>;

    struct Device
    {
      // as PLR_DEV_INFO::id
      UINT32 Id = 0;
      // -1 for main device, index in PLR_DEV_INFO::devLink otherwise
      int LinkIndex = -1;
      DEV_ID Type = 0;
      String Name;
      Strings::Array ChannelsNames;
      uint_t MuteMaskShift = 0;

      uint_t ChannelsCount() const
      {
        return static_cast<uint_t>(ChannelsNames.size());
      }
    };

    explicit ChannelsLayout(const Model& tune)
    {
      LoaderAdapter loader(*tune.Data);
      const auto player = tune.CreatePlayer();
      Require(0 == player->SetOutputSettings(44100, Sound::Sample::CHANNELS, Sound::Sample::BITS, 44100 / 100));
      Require(0 == player->LoadFile(loader.Get()));
      player->Start();  // required so that FindDeviceCore() works
      std::vector<PLR_DEV_INFO> devices;
      player->GetPlayer()->GetSongDeviceInfo(devices);
      // TODO: use DEVID_MAXIMUM when available
      std::array<uint8_t, 0x100> types = {};
      for (const auto& dev : devices)
      {
        ++types[dev.type];
        for (const auto& ldev : dev.devLink)
        {
          ++types[ldev.type];
        }
      }
      auto addDevice = [&](const PLR_DEV_INFO& info, UINT32 id, int linkIndex) {
        auto& dev = Devices.emplace_back();
        dev.Id = id;
        dev.LinkIndex = linkIndex;
        dev.Type = info.type;
        dev.Name = info.devDecl->name(info.devCfg);
        // See PropertiesHelper::SetChannels for generic contract
        if (types[info.type] > 1)
        {
          dev.Name += '/' + std::to_string(int(info.instance) + 1);
        }
        const auto channels = info.devDecl->channelCount(info.devCfg);
        const auto* names = info.devDecl->channelNames ? info.devDecl->channelNames(info.devCfg) : nullptr;
        for (uint_t ch : xrange(channels))
        {
          dev.ChannelsNames.emplace_back(names ? String(names[ch]) : std::to_string(ch + 1));
        }
        dev.MuteMaskShift = TotalChannels;
        TotalChannels += channels;
      };
      for (const auto& dev : devices)
      {
        addDevice(dev, dev.id, -1);
        for (uint_t lidx = 0; lidx < dev.devLink.size(); ++lidx)
        {
          addDevice(dev.devLink[lidx], dev.id, static_cast<int>(lidx));
        }
      }
    }

    const std::vector<Device>& GetDevices() const
    {
      return Devices;
    }

    Strings::Array GetChannelsNames() const
    {
      if (TotalChannels > 63)
      {
        return {};
      }
      Strings::Array result;
      result.reserve(TotalChannels);
      for (const auto& dev : Devices)
      {
        // See PropertiesHelper::SetChannels for generic contract
        const auto slash = dev.Name.find('/');
        const auto name = dev.Name.substr(0, slash);
        const auto suffix = slash != String::npos ? dev.Name.substr(slash) : String{};
        if (dev.ChannelsCount() > 1)
        {
          for (const auto& ch : dev.ChannelsNames)
          {
            result.emplace_back(name + ' ' + ch + suffix);
          }
        }
        else
        {
          result.emplace_back(name + suffix);
        }
      }
      return result;
    }

    void ApplyMuteMask(int64_t mask, ::PlayerA& player) const
    {
      for (const auto& opt : MakeMuteOptions(mask))
      {
        player.GetPlayer()->SetDeviceMuting(opt.first, opt.second);
      }
    }

    //! @return device id -> muting options
    std::map<UINT32, PLR_MUTE_OPTS> MakeMuteOptions(int64_t mask) const
    {
      std::map<UINT32, PLR_MUTE_OPTS> result;
      for (const auto& dev : Devices)
      {
        auto& opt = result[dev.Id];
        const auto slot = dev.LinkIndex + 1;
        if (slot < static_cast<int>(std::size(opt.chnMute)))
        {
          opt.chnMute[slot] = static_cast<UINT32>(mask >> dev.MuteMaskShift);
        }
      }
      return result;
    }

    uint_t GetTotalChannels() const
    {
      return TotalChannels;
    }

  private:
    std::vector<Device> Devices;
    uint_t TotalChannels = 0;
  };

  const Time::Milliseconds FRAME_DURATION(20);

  void SetupPlayer(::PlayerA& player, uint_t samplerate)
  {
    {
      ::PlayerA::Config pCfg = player.GetConfiguration();
      pCfg.masterVol = 0x10000;  // volume 1.0
      pCfg.loopCount = 0;        // infinite looping
      pCfg.fadeSmpls = 0;        // no fade out
      pCfg.endSilenceSmpls = 0;  // no silence at the end
      pCfg.pbSpeed = 1.0;        // normal playback speed
      player.SetConfiguration(pCfg);
    }
    {
      const auto frame_samples = uint_t(uint64_t(FRAME_DURATION.Get()) * samplerate / FRAME_DURATION.PER_SECOND);
      Require(0 == player.SetOutputSettings(samplerate, Sound::Sample::CHANNELS, Sound::Sample::BITS, frame_samples));
    }
  }

  static_assert(VoiceState::OPN_FM == DEVVOICE_KIND_OPN_FM && VoiceState::OPN_DAC == DEVVOICE_KIND_OPN_DAC
                    && VoiceState::PSG_TONE == DEVVOICE_KIND_PSG_TONE
                    && VoiceState::PSG_NOISE == DEVVOICE_KIND_PSG_NOISE && VoiceState::OPL_2OP == DEVVOICE_KIND_OPL_2OP
                    && VoiceState::OPL_4OP == DEVVOICE_KIND_OPL_4OP
                    && VoiceState::OPL_RHYTHM == DEVVOICE_KIND_OPL_RHYTHM,
                "Voice kinds mismatch");
  static_assert(VoiceState::FIELDS == sizeof(DEV_VOICE_STATE::fields), "Voice fields mismatch");

  // Separate voices output for oscilloscope.
  // Chips with per-channel output support in emulation core (see RWF_VOICES) are tapped directly (as Furnace does).
  // Others are rendered by solo players with all the other channels muted (as MultiDumper does).
  class VoicesTap
  {
  public:
    VoicesTap(const Model& tune, const ChannelsLayout& layout, uint_t samplerate, VoicesScope::Ptr scope)
      : Tune(tune)
      , Layout(layout)
      , Samplerate(samplerate)
      , Scope(std::move(scope))
    {}

    ~VoicesTap()
    {
      Detach();
    }

    //! @brief Build voices layout using running main player
    //! @return false if no voices can be provided
    bool Init(::PlayerA& main)
    {
      std::vector<VoicesGroup> groups;
      String description;
      uint_t soloChannels = 0;
      std::vector<PLR_DEV_INFO> infos;
      main.GetPlayer()->GetSongDeviceInfo(infos);
      for (const auto& dev : Layout.GetDevices())
      {
        const auto channels = dev.ChannelsCount();
        if (!channels)
        {
          continue;
        }
        const auto* base = FindDevice(main, dev);
        if (!base || !base->defInf.devDef)
        {
          continue;
        }
        const auto* devDef = base->defInf.devDef;
        Source src;
        src.Device = &dev;
        src.FirstVoice = TotalVoices;
        void* setCb = nullptr;
        void* getState = nullptr;
        const bool hasTap = 0 == ::SndEmu_GetDeviceFunc(devDef, RWF_VOICES | RWF_WRITE, DEVRW_ALL, 0, &setCb)
                            && 0 == ::SndEmu_GetDeviceFunc(devDef, RWF_VOICES | RWF_READ, DEVRW_ALL, 0, &getState);
        if (hasTap)
        {
          src.Native = true;
          src.SetCallback = reinterpret_cast<DEVFUNC_SET_VOICES_CB>(setCb);
          src.GetState = reinterpret_cast<DEVFUNC_GET_VOICES_STATE>(getState);
        }
        else if (soloChannels + channels > MAX_SOLO_CHANNELS)
        {
          Dbg("Skip voices of {}: too many solo players", dev.Name);
          continue;
        }
        else
        {
          soloChannels += channels;
        }
        src.Voices = std::min(channels, MAX_VOICES - TotalVoices);
        if (!src.Voices)
        {
          break;
        }
        TotalVoices += src.Voices;
        auto& group = groups.emplace_back();
        group.Name = dev.Name;
        group.Voices.assign(dev.ChannelsNames.begin(), dev.ChannelsNames.begin() + src.Voices);
        Sources.push_back(std::move(src));

        if (!description.empty())
        {
          description += ", ";
        }
        description += dev.Name;
        description += ' ';
        description += devDef->author ? devDef->author : "?";
        if (const auto clock = GetClock(infos, dev))
        {
          description += ' ' + FormatClock(clock);
        }
        if (!hasTap)
        {
          description += " (solo)";
        }
      }
      if (!TotalVoices)
      {
        return false;
      }
      Scope->SetVoicesGroups(groups, false);
      Scope->SetDescription(description);
      return true;
    }

    bool IsActive() const
    {
      return Scope->IsActive();
    }

    bool IsAttached() const
    {
      return Attached;
    }

    void Attach(::PlayerA& main, int64_t muteMask)
    {
      Detach();
      const auto pos = main.GetCurPos(PLAYPOS_SAMPLE);
      for (auto& src : Sources)
      {
        if (src.Native)
        {
          const auto* base = FindDevice(main, *src.Device);
          Require(base != nullptr);
          src.Chip = base->defInf.dataPtr;
          src.StateEvery = std::max<uint_t>(base->defInf.sampleRate / STATES_PER_SECOND, 1);
          src.ClearNative();
          src.SetCallback(src.Chip, &Source::OnVoices, &src);
        }
        else
        {
          src.Solos.clear();
          for (uint_t ch = 0; ch < src.Voices; ++ch)
          {
            src.Solos.emplace_back(new Solo(Tune, Layout, *src.Device, ch, Samplerate));
            src.Solos.back()->Seek(pos);
          }
        }
      }
      ApplyMuteMask(muteMask);
      Attached = true;
    }

    void Detach()
    {
      for (auto& src : Sources)
      {
        if (src.Native && src.Chip)
        {
          src.SetCallback(src.Chip, nullptr, nullptr);
          src.Chip = nullptr;
        }
        src.Solos.clear();
      }
      Attached = false;
    }

    void ApplyMuteMask(int64_t mask)
    {
      for (auto& src : Sources)
      {
        for (uint_t ch = 0; ch < src.Solos.size(); ++ch)
        {
          src.Solos[ch]->SetEnabled(0 == (mask & (int64_t(1) << (src.Device->MuteMaskShift + ch))));
        }
      }
    }

    //! @brief Called after main player rendered samples
    void Rendered(uint_t samples)
    {
      Output.assign(std::size_t(samples) * TotalVoices, 0);
      for (auto& src : Sources)
      {
        if (src.Native)
        {
          src.Resample(samples, TotalVoices, Output.data());
        }
        else
        {
          for (uint_t ch = 0; ch < src.Solos.size(); ++ch)
          {
            src.Solos[ch]->Render(samples, TotalVoices, Output.data() + src.FirstVoice + ch);
          }
        }
      }
      // voices state snapshots are interleaved with samples blocks
      const auto period = std::max<uint_t>(Scope->GetStatePeriod(), 1);
      States.assign(TotalVoices, VoiceState());
      for (uint_t done = 0; done < samples;)
      {
        const auto part = std::min(period, samples - done);
        Scope->Feed(0, TotalVoices, Output.data() + std::size_t(done) * TotalVoices, part);
        done += part;
        for (auto& src : Sources)
        {
          if (src.Native)
          {
            src.ReadState(done, samples, States.data());
          }
        }
        Scope->FeedVoices(0, States.data(), TotalVoices);
      }
      for (auto& src : Sources)
      {
        src.ClearNative();
      }
    }

    //! @brief Called before main player seeks/resets, data generated during seek is ignored
    void BeforeSeek()
    {
      for (auto& src : Sources)
      {
        src.Seeking = true;
      }
    }

    void AfterSeek(::PlayerA& main)
    {
      const auto pos = main.GetCurPos(PLAYPOS_SAMPLE);
      for (auto& src : Sources)
      {
        src.Seeking = false;
        src.ClearNative();
        if (src.Native)
        {
          // devices are kept by libvgm players on seek, but rebind for safety
          if (const auto* base = FindDevice(main, *src.Device); base && base->defInf.dataPtr != src.Chip)
          {
            src.Chip = base->defInf.dataPtr;
            src.SetCallback(src.Chip, &Source::OnVoices, &src);
          }
        }
        for (auto& solo : src.Solos)
        {
          solo->Seek(pos);
        }
      }
    }

  private:
    static const VGM_BASEDEV* FindDevice(::PlayerA& main, const ChannelsLayout::Device& dev)
    {
      const auto* base = main.GetPlayer()->GetDeviceBase(dev.Id);
      for (int idx = 0; base && idx <= dev.LinkIndex; ++idx)
      {
        base = base->linkDev;
      }
      return base;
    }

    static UINT32 GetClock(const std::vector<PLR_DEV_INFO>& infos, const ChannelsLayout::Device& dev)
    {
      for (const auto& info : infos)
      {
        if (info.id == dev.Id)
        {
          const auto* target = &info;
          if (dev.LinkIndex >= 0)
          {
            target = std::size_t(dev.LinkIndex) < info.devLink.size() ? &info.devLink[dev.LinkIndex] : nullptr;
          }
          return target && target->devCfg ? target->devCfg->clock & 0x3fffffff : 0;
        }
      }
      return 0;
    }

    static String FormatClock(UINT32 clock)
    {
      const auto tenthsKHz = (clock + 5000) / 10000;
      return std::to_string(tenthsKHz / 100) + '.' + std::to_string(tenthsKHz / 10 % 10)
             + std::to_string(tenthsKHz % 10) + "MHz";
    }

    static int16_t Clamp(int32_t val)
    {
      return static_cast<int16_t>(std::clamp<int32_t>(val, -32768, 32767));
    }

    class Solo
    {
    public:
      Solo(const Model& tune, const ChannelsLayout& layout, const ChannelsLayout::Device& dev, uint_t channel,
           uint_t samplerate)
        : Loader(*tune.Data)
        , Player(tune.CreatePlayer())
      {
        SetupPlayer(*Player, samplerate);
        Require(0 == Player->LoadFile(Loader.Get()));
        Player->Start();
        // mute everything except requested channel, disable emulation of unused devices
        auto opts = layout.MakeMuteOptions(~int64_t(0));
        for (auto& opt : opts)
        {
          opt.second.disable = opt.first == dev.Id ? 0 : 0xff;
        }
        auto& target = opts[dev.Id];
        target.chnMute[dev.LinkIndex + 1] = ~(UINT32(1) << channel);
        if (dev.LinkIndex < 0)
        {
          // linked devices are not required
          target.disable = 0x02;
        }
        for (const auto& opt : opts)
        {
          Player->GetPlayer()->SetDeviceMuting(opt.first, opt.second);
        }
      }

      void SetEnabled(bool enabled)
      {
        Enabled = enabled;
      }

      void Seek(UINT32 pos)
      {
        Player->Seek(PLAYPOS_SAMPLE, pos);
      }

      void Render(uint_t samples, uint_t stride, int16_t* target)
      {
        Buffer.resize(samples);
        const auto bytes = Player->Render(samples * sizeof(Buffer.front()), Buffer.data());
        const auto done = std::min<uint_t>(samples, bytes / sizeof(Buffer.front()));
        if (0 != (Player->GetState() & PLAYSTATE_END))
        {
          Player->Seek(PLAYPOS_TICK, 0);
        }
        if (!Enabled)
        {
          return;
        }
        for (uint_t idx = 0; idx < done; ++idx, target += stride)
        {
          // channel may be panned, take louder side as non-panned output
          const auto left = Buffer[idx].Left();
          const auto right = Buffer[idx].Right();
          *target = std::abs(int(left)) >= std::abs(int(right)) ? left : right;
        }
      }

    private:
      LoaderAdapter Loader;
      PlayerPtr Player;
      Sound::Chunk Buffer;
      bool Enabled = true;
    };

    struct Source
    {
      const ChannelsLayout::Device* Device = nullptr;
      uint_t FirstVoice = 0;
      uint_t Voices = 0;
      bool Native = false;
      // native
      DEVFUNC_SET_VOICES_CB SetCallback = nullptr;
      DEVFUNC_GET_VOICES_STATE GetState = nullptr;
      void* Chip = nullptr;
      bool Seeking = false;
      // interleaved [count][Voices] samples at chip's native rate
      std::vector<int32_t> NativeSamples;
      // state snapshots taken each StateEvery native samples, interleaved [count][Voices]
      uint_t StateEvery = 1;
      uint_t SinceState = 0;
      std::vector<DEV_VOICE_STATE> NativeStates;
      // native sample index of each snapshot
      std::vector<uint_t> NativeStatesPos;
      std::vector<DEV_VOICE_STATE> LastState;
      // solo
      std::vector<std::unique_ptr<Solo>> Solos;

      static void OnVoices(void* param, UINT32 count, const INT32* values)
      {
        auto* self = static_cast<Source*>(param);
        // protect from unbounded growth, e.g. when nobody renders
        if (self->Seeking || self->NativeSamples.size() >= MAX_NATIVE_SAMPLES)
        {
          return;
        }
        const auto voices = self->Voices;
        for (uint_t idx = 0; idx < voices; ++idx)
        {
          self->NativeSamples.push_back(idx < count ? values[idx] : 0);
        }
        // chip state is consistent between samples, so it can be read right from the emulation loop
        if (++self->SinceState >= self->StateEvery)
        {
          self->SinceState = 0;
          const auto offset = self->NativeStates.size();
          self->NativeStates.resize(offset + voices);
          const auto filled = self->GetState(self->Chip, voices, self->NativeStates.data() + offset);
          for (auto idx = filled; idx < voices; ++idx)
          {
            self->NativeStates[offset + idx] = DEV_VOICE_STATE();
          }
          self->NativeStatesPos.push_back(static_cast<uint_t>(self->NativeSamples.size() / voices));
        }
      }

      void ClearNative()
      {
        NativeSamples.clear();
        NativeStates.clear();
        NativeStatesPos.clear();
      }

      // map native rate samples to output rate
      void Resample(uint_t samples, uint_t stride, int16_t* target)
      {
        const auto native = static_cast<uint_t>(NativeSamples.size() / Voices);
        if (native)
        {
          for (uint_t out = 0; out < samples; ++out)
          {
            auto* dst = target + std::size_t(out) * stride + FirstVoice;
            const auto from = uint_t(uint64_t(out) * native / samples);
            const auto to = std::max(from + 1, uint_t(uint64_t(out + 1) * native / samples));
            // box filter when decimating
            for (uint_t voice = 0; voice < Voices; ++voice)
            {
              int64_t sum = 0;
              for (auto idx = from; idx < to; ++idx)
              {
                sum += NativeSamples[std::size_t(idx) * Voices + voice];
              }
              dst[voice] = Clamp(static_cast<int32_t>(sum / int64_t(to - from)));
            }
          }
        }
      }

      // state at output sample 'pos' of 'samples' rendered
      void ReadState(uint_t pos, uint_t samples, VoiceState* states)
      {
        const auto native = static_cast<uint_t>(NativeSamples.size() / Voices);
        const auto nativePos = uint_t(uint64_t(pos) * native / samples);
        const auto it = std::upper_bound(NativeStatesPos.begin(), NativeStatesPos.end(), nativePos);
        if (it != NativeStatesPos.begin())
        {
          const auto idx = std::size_t(it - NativeStatesPos.begin() - 1);
          LastState.assign(NativeStates.begin() + idx * Voices, NativeStates.begin() + (idx + 1) * Voices);
        }
        for (uint_t voice = 0; voice < LastState.size(); ++voice)
        {
          const auto& in = LastState[voice];
          auto& out = states[FirstVoice + voice];
          out.Frequency = in.freq;
          out.Level = in.level;
          out.Flags = (in.flags & DEVVOICE_KEYON ? VoiceState::KEY_ON : 0)
                      | (in.flags & DEVVOICE_FREQ ? VoiceState::HAS_FREQUENCY : 0)
                      | (in.flags & DEVVOICE_LEVEL ? VoiceState::HAS_LEVEL : 0)
                      | (in.flags & DEVVOICE_NOISE ? VoiceState::NOISE : 0);
          // libvgm kinds are mirrored
          out.Kind = in.kind;
          std::copy(std::begin(in.fields), std::end(in.fields), out.Fields.begin());
        }
      }
    };

    static const uint_t MAX_VOICES = 32;
    static const uint_t MAX_SOLO_CHANNELS = 16;
    static const std::size_t MAX_NATIVE_SAMPLES = 1 << 20;
    static const uint_t STATES_PER_SECOND = 1000;

    const Model& Tune;
    const ChannelsLayout& Layout;
    const uint_t Samplerate;
    const VoicesScope::Ptr Scope;
    std::vector<Source> Sources;
    uint_t TotalVoices = 0;
    bool Attached = false;
    std::vector<int16_t> Output;
    std::vector<VoiceState> States;
  };

  class VGMEngine
  {
  public:
    using RWPtr = std::shared_ptr<VGMEngine>;

    VGMEngine(Model::Ptr tune, const Information& info, ChannelsLayout::Ptr channels, uint_t samplerate)
      : Tune(std::move(tune))
      , Channels(std::move(channels))
      , Samplerate(samplerate)
      , Loader(*Tune->Data)
      , Delegate(Tune->CreatePlayer())
    {
      SetupPlayer(*Delegate, samplerate);
      Require(0 == Delegate->LoadFile(Loader.Get()));
      Delegate->Start();
      TotalTicks = ToTicks(info.Duration);
      LoopTicks = ToTicks(info.LoopDuration);
    }

    Time::AtMillisecond At() const
    {
      // time position in file
      const auto curtime = Delegate->GetCurTime(PLAYTIME_LOOP_EXCL | PLAYTIME_TIME_FILE);
      return Time::AtMillisecond() + Time::Milliseconds(curtime * 1000);
    }

    Time::Milliseconds Total() const
    {
      // total played time
      const auto curtime = Delegate->GetCurTime(PLAYTIME_LOOP_INCL | PLAYTIME_TIME_PBK);
      return Time::Milliseconds(curtime * 1000);
    }

    uint_t LoopCount() const
    {
      // Tracks can specify LoopTicks == 0 to indicate no loop.
      // In this case, we want to loop the whole song.
      return std::max(WholeLoopCount, Delegate->GetCurLoop());
    }

    void Reset()
    {
      BeforeSeek();
      Delegate->Reset();
      AfterSeek();
      WholeLoopCount = 0;
    }

    Sound::Chunk Render()
    {
      static_assert(Sound::Sample::CHANNELS == 2, "Incompatible sound channels count");
      static_assert(Sound::Sample::BITS == 16, "Incompatible sound bits count");
      static_assert(Sound::Sample::MID == 0, "Incompatible sound sample type");

      const auto samples = ToSample(FRAME_DURATION);
      Sound::Chunk result(samples);
      UpdateVoicesTap();
      const auto outBytes = Delegate->Render(samples * sizeof(result.front()), result.data());
      result.resize(outBytes / sizeof(result.front()));
      if (Voices && Voices->IsAttached())
      {
        Voices->Rendered(static_cast<uint_t>(result.size()));
      }
      CheckForWholeLoop();
      return result;
    }

    void Seek(Time::AtMillisecond request)
    {
      const auto samples = ToSample(request);
      BeforeSeek();
      Require(0 == Delegate->Seek(PLAYPOS_SAMPLE, samples));
      AfterSeek();
      WholeLoopCount = 0;
    }

    void MuteChannels(int64_t mask)
    {
      MuteMask = mask;
      Channels->ApplyMuteMask(mask, *Delegate);
      if (Voices)
      {
        Voices->ApplyMuteMask(mask);
      }
    }

    bool SetVoicesScope(VoicesScope::Ptr scope)
    {
      Voices.reset();
      if (scope)
      {
        auto voices = std::make_unique<VoicesTap>(*Tune, *Channels, Samplerate, std::move(scope));
        if (!voices->Init(*Delegate))
        {
          return false;
        }
        Voices = std::move(voices);
      }
      return true;
    }

  private:
    void UpdateVoicesTap()
    {
      if (!Voices)
      {
        return;
      }
      const auto active = Voices->IsActive();
      if (active != Voices->IsAttached())
      {
        if (active)
        {
          Voices->Attach(*Delegate, MuteMask);
        }
        else
        {
          Voices->Detach();
        }
      }
    }

    void BeforeSeek()
    {
      if (Voices && Voices->IsAttached())
      {
        Voices->BeforeSeek();
      }
    }

    void AfterSeek()
    {
      if (Voices && Voices->IsAttached())
      {
        Voices->AfterSeek(*Delegate);
      }
    }

    template<class Duration>
    uint_t ToTicks(Duration dur) const
    {
      const auto sample = ToSample(dur);
      return Delegate->GetPlayer()->Sample2Tick(sample);
    }

    template<class Item>
    uint_t ToSample(Item it, uint_t samplerate) const
    {
      return uint_t(uint64_t(it.Get()) * samplerate / Item::PER_SECOND);
    }

    template<class Item>
    uint_t ToSample(Item it) const
    {
      return ToSample(it, Delegate->GetSampleRate());
    }

    void CheckForWholeLoop()
    {
      if (0 != (Delegate->GetState() & PLAYSTATE_END))
      {
        ++WholeLoopCount;
        BeforeSeek();
        Require(0 == Delegate->Seek(PLAYPOS_TICK, 0));
        AfterSeek();
      }
    }

  private:
    const Model::Ptr Tune;
    const ChannelsLayout::Ptr Channels;
    const uint_t Samplerate;
    LoaderAdapter Loader;
    PlayerPtr Delegate;
    uint_t TotalTicks = 0;
    uint_t LoopTicks = 0;
    uint_t WholeLoopCount = 0;
    int64_t MuteMask = 0;
    std::unique_ptr<VoicesTap> Voices;
  };

  class Renderer
    : public Module::Renderer
    , public VoicesScopeSource
  {
  public:
    Renderer(Model::Ptr tune, const Information& info, ChannelsLayout::Ptr channels, uint_t samplerate,
             Parameters::Accessor::Ptr params)
      : Engine(std::move(tune), info, std::move(channels), samplerate)
      , Params(std::move(params))
    {}

    State GetState() const override
    {
      return {.At = Engine.At(), .Total = Engine.Total(), .LoopCount = Engine.LoopCount()};
    }

    Sound::Chunk Render() override
    {
      ApplyParameters();
      return Engine.Render();
    }

    void Reset() override
    {
      try
      {
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
        Engine.Seek(request);
      }
      catch (const std::exception& e)
      {
        Dbg(e.what());
      }
    }

    bool SetVoicesScope(VoicesScope::Ptr scope) override
    {
      try
      {
        return Engine.SetVoicesScope(std::move(scope));
      }
      catch (const std::exception& e)
      {
        Dbg(e.what());
        return false;
      }
    }

  private:
    void ResetEngine()
    {
      Engine.Reset();
      Params.Reset();
    }

    void ApplyParameters()
    {
      if (Params.IsChanged())
      {
        using namespace Parameters::ZXTune::Core;
        const auto mask = Parameters::GetInteger(*Params, CHANNELS_MASK, CHANNELS_MASK_DEFAULT);
        Engine.MuteChannels(mask);
      }
    }

  private:
    VGMEngine Engine;
    Parameters::TrackingHelper<Parameters::Accessor> Params;
  };

  class Holder : public Module::Holder
  {
  public:
    Holder(Model::Ptr tune, Information info, ChannelsLayout::Ptr channels, Parameters::Accessor::Ptr props)
      : Tune(std::move(tune))
      , Info(std::move(info))
      , Channels(std::move(channels))
      , Properties(std::move(props))
    {}

    Information GetModuleInformation() const override
    {
      return Info;
    }

    Parameters::Accessor::Ptr GetModuleProperties() const override
    {
      return Properties;
    }

    Renderer::Ptr CreateRenderer(uint_t samplerate, Parameters::Accessor::Ptr params) const override
    {
      try
      {
        return MakePtr<Renderer>(Tune, Info, Channels, samplerate, std::move(params));
      }
      catch (const std::exception& e)
      {
        throw Error(THIS_LINE, e.what());
      }
    }

  private:
    const Model::Ptr Tune;
    const Information Info;
    const ChannelsLayout::Ptr Channels;
    const Parameters::Accessor::Ptr Properties;
  };
}  // namespace Module::LibVGM

namespace Module::VideoGameMusic
{
  class DataBuilder : public Formats::Chiptune::VideoGameMusic::Builder
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

    void SetTimings(Time::Milliseconds total, Time::Milliseconds loop) override
    {
      if (total)
      {
        Info = CreateTimedInfo(total, loop);
      }
    }

    Information CaptureResult(const Parameters::Accessor& props)
    {
      if (Info)
      {
        return std::move(*Info);
      }
      else
      {
        const auto duration = GetDefaultDuration(props);
        return CreateTimedInfo(duration, duration);
      }
    }

  private:
    PropertiesHelper& Properties;
    MetaProperties Meta;
    std::optional<Information> Info;
  };

  class Factory : public Module::Factory
  {
  public:
    Holder::Ptr CreateModule(const Parameters::Accessor& /*params*/, const Binary::Container& rawData,
                             Parameters::Container::Ptr properties) const override
    {
      try
      {
        PropertiesHelper props(*properties);
        DataBuilder dataBuilder(props);
        if (const auto container = Formats::Chiptune::VideoGameMusic::Parse(rawData, dataBuilder))
        {
          auto tune = MakePtr<LibVGM::Model>(&LibVGM::Create<::VGMPlayer>, *container);
          // TODO: move to builder
          props.SetPlatform(DetectPlatform(*tune->Data));

          props.SetSource(*container);
          auto layout = MakePtr<LibVGM::ChannelsLayout>(*tune);
          props.SetChannels(layout->GetChannelsNames());
          auto info = dataBuilder.CaptureResult(*properties);

          return MakePtr<LibVGM::Holder>(std::move(tune), std::move(info), std::move(layout), std::move(properties));
        }
      }
      catch (const std::exception& e)
      {
        LibVGM::Dbg("Failed to create VGM: {}", e.what());
      }
      return {};
    }
  };

  Factory::Ptr CreateFactory()
  {
    return MakePtr<Factory>();
  }
}  // namespace Module::VideoGameMusic

namespace Module::Sound98
{
  class DataBuilder : public Formats::Chiptune::Sound98::Builder
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

    void SetTimings(Time::Milliseconds total, Time::Milliseconds loop) override
    {
      Info = CreateTimedInfo(total, loop);
    }

    Information CaptureResult() const
    {
      return Info;
    }

  private:
    PropertiesHelper& Properties;
    MetaProperties Meta;
    Information Info;
  };

  class Factory : public Module::Factory
  {
  public:
    Holder::Ptr CreateModule(const Parameters::Accessor& /*params*/, const Binary::Container& rawData,
                             Parameters::Container::Ptr properties) const override
    {
      try
      {
        PropertiesHelper props(*properties);
        DataBuilder dataBuilder(props);
        if (const auto container = Formats::Chiptune::Sound98::Parse(rawData, dataBuilder))
        {
          auto tune = MakePtr<LibVGM::Model>(&LibVGM::Create<::S98Player>, *container);

          props.SetSource(*container);
          auto layout = MakePtr<LibVGM::ChannelsLayout>(*tune);
          props.SetChannels(layout->GetChannelsNames());

          return MakePtr<LibVGM::Holder>(std::move(tune), dataBuilder.CaptureResult(), std::move(layout),
                                         std::move(properties));
        }
      }
      catch (const std::exception& e)
      {
        LibVGM::Dbg("Failed to create S98: {}", e.what());
      }
      return {};
    }
  };

  Factory::Ptr CreateFactory()
  {
    return MakePtr<Factory>();
  }
}  // namespace Module::Sound98
