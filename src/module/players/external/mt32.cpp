/**
 *
 * @file
 *
 * @brief  MIDI files support via MT-32 emulation (Munt)
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "module/players/external/mt32.h"

#include "formats/chiptune/music/midi.h"
#include "module/players/properties_helper.h"
#include "module/players/properties_meta.h"
#include "module/players/streaming.h"

#include "binary/container_factories.h"
#include "binary/input_stream.h"
#include "core/core_parameters.h"
#include "debug/log.h"
#include "module/holder.h"
#include "module/renderer.h"
#include "module/voices_scope.h"
#include "parameters/tracking_helper.h"
#include "sound/resampler.h"

#include "contract.h"
#include "error_tools.h"
#include "make_ptr.h"

#include "3rdparty/mt32emu/src/File.h"
#include "3rdparty/mt32emu/src/ROMInfo.h"
#include "3rdparty/mt32emu/src/Synth.h"

#include <dirent.h>
#include <sys/stat.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>

namespace Module::MT32
{
  const Debug::Stream Dbg("Module::MT32");

  const uint_t SAMPLERATE = MT32Emu::SAMPLE_RATE;
  // release and reverb tails after the last event
  const uint_t TAIL_SAMPLES = SAMPLERATE * 2;

  struct Event
  {
    uint64_t Sample = 0;
    // short message (status | data1 << 8 | data2 << 16) if SysexSize is 0
    uint32_t Message = 0;
    uint32_t SysexOffset = 0;
    uint32_t SysexSize = 0;

    bool IsNote() const
    {
      const auto cmd = Message & 0xf0;
      return SysexSize == 0 && (cmd == 0x80 || cmd == 0x90 || cmd == 0xa0);
    }
  };

  struct Model
  {
    using Ptr = std::shared_ptr<const Model>;

    std::vector<Event> Events;
    std::vector<uint8_t> Sysex;
    uint64_t TotalSamples = 0;

    Time::Milliseconds GetDuration() const
    {
      return Time::Milliseconds(static_cast<uint_t>(TotalSamples * 1000 / SAMPLERATE));
    }
  };

  // Standard MIDI File events in playback order with absolute time
  class SmfParser
  {
  public:
    static Model::Ptr Parse(Binary::View data)
    {
      SmfParser parser;
      parser.ParseFile(data);
      return parser.Build();
    }

  private:
    struct RawEvent
    {
      uint64_t Tick = 0;
      // order of events at the same tick: track then position in track
      uint32_t Order = 0;
      // 0 for tempo change
      uint32_t Message = 0;
      uint32_t Tempo = 0;
      uint32_t SysexOffset = 0;
      uint32_t SysexSize = 0;
    };

    void ParseFile(Binary::View data)
    {
      Binary::DataInputStream stream(data);
      Require(stream.Read<be_uint32_t>() == 0x4d546864);  // MThd
      const std::size_t headerSize = stream.Read<be_uint32_t>();
      stream.Read<be_uint16_t>();  // format
      const uint_t tracks = stream.Read<be_uint16_t>();
      Division = stream.Read<be_uint16_t>();
      Require(headerSize >= 6 && Division != 0);
      stream.Skip(headerSize - 6);
      for (uint_t track = 0; track < tracks && stream.GetRestSize() >= 8;)
      {
        const uint_t id = stream.Read<be_uint32_t>();
        const std::size_t size = stream.Read<be_uint32_t>();
        const auto chunk = stream.ReadData(std::min(size, stream.GetRestSize()));
        if (id == 0x4d54726b)  // MTrk
        {
          ParseTrack(chunk);
          ++track;
        }
      }
    }

    static uint_t ReadVarLen(Binary::DataInputStream& stream)
    {
      uint_t result = 0;
      for (uint_t idx = 0; idx < 4; ++idx)
      {
        const auto byte = stream.ReadByte();
        result = (result << 7) | (byte & 0x7f);
        if (!(byte & 0x80))
        {
          break;
        }
      }
      return result;
    }

    void ParseTrack(Binary::View data)
    {
      Binary::DataInputStream stream(data);
      uint64_t tick = 0;
      uint_t running = 0;
      // sysex divided into packets (F0 ... then F7 ...)
      std::size_t pendingSysex = 0;
      bool hasPendingSysex = false;
      try
      {
        while (stream.GetRestSize())
        {
          tick += ReadVarLen(stream);
          uint_t status = stream.ReadByte();
          if (status < 0x80)
          {
            // running status, first data byte is already read
            Require(running != 0);
            AddChannelMessage(tick, running, status, stream);
            continue;
          }
          if (status == 0xff)
          {
            const auto type = stream.ReadByte();
            const auto size = ReadVarLen(stream);
            const auto meta = stream.ReadData(std::min<std::size_t>(size, stream.GetRestSize()));
            if (type == 0x51 && meta.Size() == 3)
            {
              const auto* tempo = meta.As<uint8_t>();
              RawEvent evt;
              evt.Tick = tick;
              evt.Order = Order++;
              evt.Tempo = (uint_t(tempo[0]) << 16) | (uint_t(tempo[1]) << 8) | tempo[2];
              Raw.push_back(evt);
            }
            else if (type == 0x2f)
            {
              break;
            }
          }
          else if (status == 0xf0 || status == 0xf7)
          {
            running = 0;
            const auto size = ReadVarLen(stream);
            const auto body = stream.ReadData(std::min<std::size_t>(size, stream.GetRestSize()));
            const auto* bytes = body.As<uint8_t>();
            if (status == 0xf0)
            {
              pendingSysex = Sysex.size();
              Sysex.push_back(0xf0);
            }
            else if (!hasPendingSysex)
            {
              // escaped raw data, only complete sysex messages are supported
              if (body.Size() < 2 || bytes[0] != 0xf0)
              {
                continue;
              }
              pendingSysex = Sysex.size();
            }
            Sysex.insert(Sysex.end(), bytes, bytes + body.Size());
            hasPendingSysex = Sysex.back() != 0xf7;
            if (!hasPendingSysex)
            {
              RawEvent evt;
              evt.Tick = tick;
              evt.Order = Order++;
              evt.SysexOffset = static_cast<uint32_t>(pendingSysex);
              evt.SysexSize = static_cast<uint32_t>(Sysex.size() - pendingSysex);
              Raw.push_back(evt);
            }
          }
          else if (status >= 0xf1)
          {
            // system common/realtime messages are not expected in files
            static const uint8_t SIZES[16] = {0, 1, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            stream.Skip(SIZES[status & 15]);
          }
          else
          {
            running = status;
            AddChannelMessage(tick, status, stream.ReadByte(), stream);
          }
        }
      }
      catch (const std::exception&)
      {
        // truncated track, use parsed part
      }
    }

    void AddChannelMessage(uint64_t tick, uint_t status, uint_t data1, Binary::DataInputStream& stream)
    {
      const auto cmd = status & 0xf0;
      const uint_t data2 = (cmd == 0xc0 || cmd == 0xd0) ? 0 : stream.ReadByte();
      RawEvent evt;
      evt.Tick = tick;
      evt.Order = Order++;
      evt.Message = status | ((data1 & 0x7f) << 8) | ((data2 & 0x7f) << 16);
      Raw.push_back(evt);
    }

    Model::Ptr Build()
    {
      std::stable_sort(Raw.begin(), Raw.end(), [](const RawEvent& lh, const RawEvent& rh) {
        return lh.Tick != rh.Tick ? lh.Tick < rh.Tick : lh.Order < rh.Order;
      });
      auto result = std::make_shared<Model>();
      result->Sysex = std::move(Sysex);
      // time is accumulated in microseconds multiplied by ticks per quarter to avoid rounding errors
      uint64_t tempo = 500000;
      uint64_t lastTick = 0;
      uint64_t scaledTime = 0;
      const bool smpte = 0 != (Division & 0x8000);
      const uint64_t ticksPerQuarter = Division & 0x7fff;
      // SMPTE: -frames per second in high byte (29 is 29.97), ticks per frame in low byte
      const auto fps = smpte ? -int(int8_t(Division >> 8)) : 0;
      const uint64_t ticksPerSecond = smpte ? uint64_t(fps == 29 ? 29.97 * (Division & 0xff) : fps * (Division & 0xff))
                                            : 0;
      for (const auto& raw : Raw)
      {
        const auto delta = raw.Tick - lastTick;
        lastTick = raw.Tick;
        scaledTime += smpte ? delta * 1000000 : delta * tempo;
        if (raw.Tempo)
        {
          tempo = raw.Tempo;
          continue;
        }
        const auto us = scaledTime / (smpte ? std::max<uint64_t>(ticksPerSecond, 1) : ticksPerQuarter);
        Event evt;
        evt.Sample = us * SAMPLERATE / 1000000;
        evt.Message = raw.Message;
        evt.SysexOffset = raw.SysexOffset;
        evt.SysexSize = raw.SysexSize;
        result->Events.push_back(evt);
      }
      Require(!result->Events.empty());
      result->TotalSamples = result->Events.back().Sample + TAIL_SAMPLES;
      return result;
    }

  private:
    uint_t Division = 0;
    uint32_t Order = 0;
    std::vector<RawEvent> Raw;
    std::vector<uint8_t> Sysex;
  };

  // Control and PCM ROMs found in directory, identified by contents
  class RomSet
  {
  public:
    using Ptr = std::shared_ptr<const RomSet>;

    ~RomSet()
    {
      for (const auto* image : Images)
      {
        MT32Emu::ROMImage::freeROMImage(image);
      }
    }

    static Ptr Load(const String& dir, Parameters::IntType model)
    {
      static std::mutex lock;
      static std::map<String, Ptr> cache;
      const auto signature = GetSignature(dir) + '#' + std::to_string(model);
      const std::scoped_lock guard(lock);
      auto& cached = cache[dir];
      if (!cached || cached->Signature != signature)
      {
        cached.reset();
        auto set = std::unique_ptr<RomSet>(new RomSet());
        set->Signature = signature;
        if (set->Scan(dir, model))
        {
          cached = std::move(set);
        }
      }
      return cached;
    }

    //! @brief Adopts already identified images (e.g. for testing)
    static Ptr Create(const MT32Emu::ROMImage* control, const MT32Emu::ROMImage* pcm)
    {
      auto set = std::unique_ptr<RomSet>(new RomSet());
      set->Images = {control, pcm};
      set->Control = control;
      set->Pcm = pcm;
      set->Description = String(control->getROMInfo()->description) + " + " + pcm->getROMInfo()->description;
      return set;
    }

    const MT32Emu::ROMImage* Control = nullptr;
    const MT32Emu::ROMImage* Pcm = nullptr;
    String Description;

  private:
    RomSet() = default;

    static std::vector<String> ListFiles(const String& dir)
    {
      std::vector<String> result;
      if (auto* const handle = ::opendir(dir.c_str()))
      {
        while (const auto* entry = ::readdir(handle))
        {
          const String name = entry->d_name;
          if (name != "." && name != "..")
          {
            result.push_back(dir + '/' + name);
          }
        }
        ::closedir(handle);
      }
      std::sort(result.begin(), result.end());
      return result;
    }

    // changes on files replacement
    static String GetSignature(const String& dir)
    {
      String result;
      for (const auto& file : ListFiles(dir))
      {
        struct stat st;
        if (0 == ::stat(file.c_str(), &st))
        {
          result += file + ':' + std::to_string(st.st_size) + ':' + std::to_string(st.st_mtime) + ';';
        }
      }
      return result;
    }

    bool Scan(const String& dir, Parameters::IntType model)
    {
      // largest known ROM is CM-32L PCM (1Mb)
      const std::size_t MAX_SIZE = 1 << 20;
      std::vector<MT32Emu::File*> partials;
      std::vector<const MT32Emu::ROMImage*> full;
      for (const auto& path : ListFiles(dir))
      {
        struct stat st;
        if (0 != ::stat(path.c_str(), &st) || !S_ISREG(st.st_mode) || st.st_size <= 0
            || std::size_t(st.st_size) > MAX_SIZE)
        {
          continue;
        }
        auto& buffer = Buffers.emplace_back(std::size_t(st.st_size));
        std::ifstream stream(path, std::ios::binary);
        if (!stream.read(safe_ptr_cast<char*>(buffer.data()), buffer.size()))
        {
          Buffers.pop_back();
          continue;
        }
        auto* file = Files.emplace_back(new MT32Emu::ArrayFile(buffer.data(), buffer.size())).get();
        const auto* info = MT32Emu::ROMInfo::getROMInfo(file);
        if (info && info->pairType == MT32Emu::ROMInfo::Full)
        {
          if (const auto* image = MT32Emu::ROMImage::makeROMImage(file))
          {
            Images.push_back(image);
            full.push_back(image);
          }
        }
        // CM-32L PCM lower half is identical to full MT-32 PCM, so it's a partial candidate as well
        if (buffer.size() <= MAX_SIZE / 2)
        {
          partials.push_back(file);
        }
      }
      for (std::size_t first = 0; first < partials.size(); ++first)
      {
        for (auto second = first + 1; second < partials.size(); ++second)
        {
          if (const auto* image = MT32Emu::ROMImage::makeROMImage(partials[first], partials[second]))
          {
            Images.push_back(image);
            full.push_back(image);
          }
        }
      }
      for (const auto* machine : GetMachinesOrder(model))
      {
        const auto* const* compatible = machine->getCompatibleROMInfos();
        auto find = [&](MT32Emu::ROMInfo::Type type) -> const MT32Emu::ROMImage* {
          for (const auto* image : full)
          {
            const auto* info = image->getROMInfo();
            for (const auto* const* it = compatible; *it; ++it)
            {
              if (*it == info && info->type == type)
              {
                return image;
              }
            }
          }
          return nullptr;
        };
        Control = find(MT32Emu::ROMInfo::Control);
        Pcm = find(MT32Emu::ROMInfo::PCM);
        if (Control && Pcm)
        {
          Description = String(Control->getROMInfo()->description) + " + " + Pcm->getROMInfo()->description;
          Dbg("Use {} ({})", machine->getMachineID(), Description);
          return true;
        }
      }
      Dbg("No complete ROM set found in {}", dir);
      return false;
    }

    static std::vector<const MT32Emu::MachineConfiguration*> GetMachinesOrder(Parameters::IntType model)
    {
      using namespace Parameters::ZXTune::Core::MT32;
      // newer revisions first
      static const std::array<std::string_view, 9> MT32 = {"mt32_2_07", "mt32_2_06", "mt32_2_04",
                                                           "mt32_2_03", "mt32_1_07", "mt32_1_06",
                                                           "mt32_1_05", "mt32_1_04", "mt32_bluer"};
      static const std::array<std::string_view, 3> CM32L = {"cm32l_1_02", "cm32l_1_00", "cm32ln_1_00"};
      std::vector<std::string_view> ids;
      if (model == MODEL_CM32L)
      {
        ids.assign(CM32L.begin(), CM32L.end());
        ids.insert(ids.end(), MT32.begin(), MT32.end());
      }
      else
      {
        ids.assign(MT32.begin(), MT32.end());
        ids.insert(ids.end(), CM32L.begin(), CM32L.end());
      }
      std::vector<const MT32Emu::MachineConfiguration*> result;
      const auto* const* all = MT32Emu::MachineConfiguration::getAllMachineConfigurations();
      for (const auto id : ids)
      {
        for (const auto* const* it = all; *it; ++it)
        {
          if (id == (*it)->getMachineID())
          {
            result.push_back(*it);
          }
        }
      }
      return result;
    }

  private:
    String Signature;
    std::vector<std::vector<uint8_t>> Buffers;
    std::vector<std::unique_ptr<MT32Emu::ArrayFile>> Files;
    std::vector<const MT32Emu::ROMImage*> Images;
  };

  // Separate voices of synth: 9 parts (8 melodic + rhythm) or all the partials
  class VoicesTap : public MT32Emu::PartialsListener
  {
  public:
    static const uint_t PARTS = 9;

    VoicesTap(MT32Emu::Synth& synth, bool partials)
      : Synth(synth)
      , Partials(partials)
      , Voices(partials ? synth.getPartialCount() : PARTS)
    {}

    uint_t GetVoices() const
    {
      return Voices;
    }

    std::vector<VoicesGroup> GetGroups() const
    {
      std::vector<VoicesGroup> result;
      if (Partials)
      {
        for (uint_t first = 0; first < Voices; first += PARTIALS_PER_GROUP)
        {
          auto& group = result.emplace_back();
          const auto last = std::min(first + PARTIALS_PER_GROUP, Voices);
          group.Name = "Partials " + std::to_string(first + 1) + '-' + std::to_string(last);
          for (auto idx = first; idx < last; ++idx)
          {
            group.Voices.emplace_back(std::to_string(idx + 1));
          }
        }
      }
      else
      {
        auto& group = result.emplace_back();
        group.Name = "MT-32";
        for (uint_t part = 0; part < PARTS - 1; ++part)
        {
          group.Voices.emplace_back("Part " + std::to_string(part + 1));
        }
        group.Voices.emplace_back("Rhythm");
      }
      return result;
    }

    void onPartialsRendered(const MT32Emu::Bit16s* samples, MT32Emu::Bit32u partialCount,
                            MT32Emu::Bit32u length) override
    {
      const auto offset = Samples.size();
      Samples.resize(offset + std::size_t(length) * Voices);
      auto* target = Samples.data() + offset;
      if (Partials)
      {
        for (uint_t smp = 0; smp < length; ++smp, samples += partialCount, target += Voices)
        {
          std::copy(samples, samples + std::min<uint_t>(partialCount, Voices), target);
        }
        return;
      }
      // partials ownership is stable during rendering pass
      Owners.resize(partialCount);
      for (uint_t idx = 0; idx < partialCount; ++idx)
      {
        Owners[idx] = Synth.getPartialOwnerPart(idx);
      }
      for (uint_t smp = 0; smp < length; ++smp, samples += partialCount, target += Voices)
      {
        std::array<int, PARTS> mix = {};
        for (uint_t idx = 0; idx < partialCount; ++idx)
        {
          if (const auto owner = Owners[idx]; owner >= 0 && owner < int(PARTS))
          {
            mix[owner] += samples[idx];
          }
        }
        for (uint_t part = 0; part < PARTS; ++part)
        {
          target[part] = static_cast<int16_t>(std::clamp(mix[part], -32768, 32767));
        }
      }
    }

    // take snapshot of voices state at current position of samples stream
    void TakeState()
    {
      StatesPos.push_back(static_cast<uint_t>(Samples.size() / Voices));
      const auto offset = States.size();
      States.resize(offset + Voices);
      auto* states = States.data() + offset;
      if (Partials)
      {
        PartialStates.resize(Synth.getPartialCount());
        Synth.getPartialStates(PartialStates.data());
        for (uint_t idx = 0; idx < Voices; ++idx)
        {
          auto& out = states[idx];
          const auto st = PartialStates[idx];
          if (st == MT32Emu::PartialState_ATTACK || st == MT32Emu::PartialState_SUSTAIN)
          {
            out.Flags |= VoiceState::KEY_ON;
          }
          out.Kind = VoiceState::MT32_PARTIAL;
          out.Fields[0] = static_cast<uint8_t>(st);
          if (st != MT32Emu::PartialState_INACTIVE)
          {
            const auto owner = Synth.getPartialOwnerPart(idx);
            const auto key = Synth.getPartialKey(idx);
            SetKey(owner, key, out);
            out.Fields[1] = static_cast<uint8_t>(owner + 1);
            out.Fields[2] = static_cast<uint8_t>(std::max(key, 0));
          }
        }
        return;
      }
      // partials used by each part
      std::array<uint8_t, PARTS> partials = {};
      for (uint_t idx = 0, lim = Synth.getPartialCount(); idx < lim; ++idx)
      {
        if (const auto owner = Synth.getPartialOwnerPart(idx); owner >= 0 && owner < int(PARTS))
        {
          ++partials[owner];
        }
      }
      for (uint_t part = 0; part < PARTS; ++part)
      {
        std::array<MT32Emu::Bit8u, 256> keys;
        std::array<MT32Emu::Bit8u, 256> velocities;
        const auto notes = Synth.getPlayingNotes(static_cast<MT32Emu::Bit8u>(part), keys.data(), velocities.data());
        auto& out = states[part];
        out.Kind = VoiceState::MT32_PART;
        if (notes)
        {
          out.Flags |= VoiceState::KEY_ON;
          // the most recently started note
          SetKey(part, keys[notes - 1], out);
        }
        else if (part == PARTS - 1)
        {
          out.Flags |= VoiceState::NOISE;
        }
        // the latest notes
        const auto shown = std::min<uint_t>(notes, 8);
        out.Fields[0] = static_cast<uint8_t>(std::min<uint_t>(notes, 255));
        for (uint_t idx = 0; idx < shown; ++idx)
        {
          out.Fields[1 + idx] = keys[notes - shown + idx];
        }
        out.Fields[9] = partials[part];
        GetPatchName(part, out.Text);
      }
    }

    // patch names are rarely changed, so cached
    void GetPatchName(uint_t part, std::array<char, VoiceState::TEXT>& target)
    {
      const auto* name = Synth.getPatchName(static_cast<MT32Emu::Bit8u>(part));
      auto& cached = PatchNames[part];
      if (name && cached.Source != name)
      {
        cached.Source = name;
        cached.Name.fill(0);
        // patch names are space padded
        const auto len = std::min<std::size_t>(std::strlen(name), cached.Name.size() - 1);
        std::copy_n(name, len, cached.Name.begin());
        for (auto idx = len; idx > 0 && cached.Name[idx - 1] == ' '; --idx)
        {
          cached.Name[idx - 1] = 0;
        }
      }
      target = cached.Name;
    }

    // interleaved [count][Voices] at internal samplerate
    std::vector<int16_t> Samples;
    std::vector<VoiceState> States;
    std::vector<uint_t> StatesPos;

    void Clear()
    {
      Samples.clear();
      States.clear();
      StatesPos.clear();
    }

  private:
    // nominal frequency of played note, rhythm keys are instruments
    static void SetKey(int part, int key, VoiceState& out)
    {
      if (part == int(PARTS - 1))
      {
        out.Flags |= VoiceState::NOISE;
      }
      else if (key >= 0)
      {
        out.Frequency = 440.0f * std::exp2((key - 69) / 12.0f);
        out.Flags |= VoiceState::HAS_FREQUENCY;
      }
    }

  private:
    static const uint_t PARTIALS_PER_GROUP = 8;
    MT32Emu::Synth& Synth;
    const bool Partials;
    const uint_t Voices;
    std::vector<int> Owners;
    struct PatchName
    {
      String Source;
      std::array<char, VoiceState::TEXT> Name = {};
    };
    std::array<PatchName, PARTS> PatchNames;
    std::vector<MT32Emu::PartialState> PartialStates;
  };

  class Engine
  {
  public:
    using Ptr = std::unique_ptr<Engine>;

    Engine(Model::Ptr tune, RomSet::Ptr roms)
      : Tune(std::move(tune))
      , Roms(std::move(roms))
    {
      Open();
    }

    ~Engine()
    {
      Synth.setPartialsListener(nullptr);
      Synth.close();
    }

    const RomSet& GetRoms() const
    {
      return *Roms;
    }

    MT32Emu::Synth& GetSynth()
    {
      return Synth;
    }

    void SetVoicesTap(VoicesTap* tap)
    {
      Tap = tap;
      Synth.setPartialsListener(tap);
    }

    void Reset()
    {
      Synth.setPartialsListener(nullptr);
      Synth.close();
      Open();
      Synth.setPartialsListener(Tap);
    }

    Sound::Chunk Render(uint_t samples)
    {
      Sound::Chunk result(samples);
      // rendered by small parts to take voices state snapshots, independently of visualization to keep output the same
      for (uint_t done = 0; done < samples;)
      {
        const auto part = std::min(STATE_PERIOD, samples - done);
        QueueEvents(Position + part);
        Synth.render(safe_ptr_cast<MT32Emu::Bit16s*>(result.data() + done), part);
        done += part;
        Position += part;
        if (Tap)
        {
          Tap->TakeState();
        }
      }
      return result;
    }

    // Controllers, programs and sysex messages are applied immediately up to requested position
    void Seek(uint64_t sample)
    {
      if (sample < Position)
      {
        Reset();
      }
      else
      {
        for (uint_t chan = 0; chan < 16; ++chan)
        {
          // all notes off
          Synth.playMsgNow(0x7bb0 | chan);
        }
      }
      const auto& events = Tune->Events;
      for (; NextEvent < events.size() && events[NextEvent].Sample < sample; ++NextEvent)
      {
        const auto& evt = events[NextEvent];
        if (evt.SysexSize)
        {
          Synth.playSysexNow(Tune->Sysex.data() + evt.SysexOffset, evt.SysexSize);
        }
        else if (!evt.IsNote())
        {
          Synth.playMsgNow(evt.Message);
        }
      }
      Position = sample;
    }

  private:
    void Open()
    {
      Require(Synth.open(*Roms->Control, *Roms->Pcm, MT32Emu::DEFAULT_MAX_PARTIALS, MT32Emu::AnalogOutputMode_COARSE));
      Require(Synth.getStereoOutputSampleRate() == SAMPLERATE);
      Position = 0;
      NextEvent = 0;
    }

    void QueueEvents(uint64_t end)
    {
      const auto& events = Tune->Events;
      const auto rendered = Synth.getInternalRenderedSampleCount();
      for (; NextEvent < events.size() && events[NextEvent].Sample < end; ++NextEvent)
      {
        const auto& evt = events[NextEvent];
        const auto timestamp =
            rendered + static_cast<MT32Emu::Bit32u>(evt.Sample > Position ? evt.Sample - Position : 0);
        const bool queued = evt.SysexSize
                                ? Synth.playSysex(Tune->Sysex.data() + evt.SysexOffset, evt.SysexSize, timestamp)
                                : Synth.playMsg(evt.Message, timestamp);
        if (!queued)
        {
          // queue is full, retry after rendering
          break;
        }
      }
    }

  private:
    // ~1ms
    static const uint_t STATE_PERIOD = SAMPLERATE / 1000;
    const Model::Ptr Tune;
    const RomSet::Ptr Roms;
    MT32Emu::Synth Synth;
    uint64_t Position = 0;
    std::size_t NextEvent = 0;
    VoicesTap* Tap = nullptr;
  };

  const auto FRAME_DURATION = Time::Milliseconds(20);

  uint_t GetSamples(Time::Microseconds period)
  {
    return static_cast<uint_t>(uint64_t(period.Get()) * SAMPLERATE / period.PER_SECOND);
  }

  class Renderer
    : public Module::Renderer
    , public VoicesScopeSource
  {
  public:
    Renderer(Model::Ptr tune, RomSet::Ptr roms, uint_t samplerate, Parameters::Accessor::Ptr params)
      : Tune(std::move(tune))
      , Params(std::move(params))
      , Delegate(MakePtr<Engine>(Tune, std::move(roms)))
      , State(Tune->GetDuration())
      , Samplerate(samplerate)
      , Target(Sound::CreateResampler(SAMPLERATE, samplerate))
    {
      using namespace Parameters::ZXTune::Core::MT32;
      // ROMs are already loaded by holder using the same parameters
      RomsPath = Parameters::GetString(*Params, ROMS_PATH);
      RomsModel = Parameters::GetInteger(*Params, MODEL, MODEL_DEFAULT);
      Voices = Parameters::GetInteger(*Params, VOICES, VOICES_DEFAULT);
      Tap = std::make_unique<VoicesTap>(Delegate->GetSynth(), Voices == VOICES_PARTIALS);
    }

    ~Renderer() override
    {
      Delegate->SetVoicesTap(nullptr);
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
      auto result = Target->Apply(Delegate->Render(GetSamples(avail)));
      if (TapActive)
      {
        FeedVoices(static_cast<uint_t>(result.size()));
      }
      return result;
    }

    void Reset() override
    {
      State.Reset();
      Delegate->Reset();
      Tap->Clear();
    }

    void SetPosition(Time::AtMillisecond request) override
    {
      State.Seek(request);
      Delegate->Seek(uint64_t(request.Get()) * SAMPLERATE / request.PER_SECOND);
      Tap->Clear();
    }

    bool SetVoicesScope(VoicesScope::Ptr scope) override
    {
      Scope = std::move(scope);
      DescribeVoices();
      UpdateVoicesTap();
      return true;
    }

  private:
    void DescribeVoices()
    {
      if (Scope)
      {
        Scope->SetVoicesGroups(Tap->GetGroups(), false);
        Scope->SetDescription(Delegate->GetRoms().Description + ", Munt " + MT32Emu::Synth::getLibraryVersionString());
      }
    }

    // Settings are applied while playing by recreating synth or voices tap at the current position
    void ApplyParameters()
    {
      if (!Params.IsChanged())
      {
        return;
      }
      using namespace Parameters::ZXTune::Core::MT32;
      const auto path = Parameters::GetString(*Params, ROMS_PATH);
      const auto model = Parameters::GetInteger(*Params, MODEL, MODEL_DEFAULT);
      const auto voices = Parameters::GetInteger(*Params, VOICES, VOICES_DEFAULT);
      bool reloaded = false;
      if (path != RomsPath || model != RomsModel)
      {
        RomsPath = path;
        RomsModel = model;
        reloaded = ReloadEngine();
      }
      // tap is bound to synth instance
      if (reloaded || voices != Voices)
      {
        Voices = voices;
        RecreateVoicesTap();
      }
    }

    bool ReloadEngine()
    {
      try
      {
        // missing or broken ROMs should not interrupt playback with the current ones
        auto roms = RomsPath.empty() ? RomSet::Ptr() : RomSet::Load(RomsPath, RomsModel);
        if (!roms || roms.get() == &Delegate->GetRoms())
        {
          return false;
        }
        auto engine = MakePtr<Engine>(Tune, std::move(roms));
        const auto pos = State.Get().At;
        engine->Seek(uint64_t(pos.Get()) * SAMPLERATE / pos.PER_SECOND);
        Delegate = std::move(engine);
        return true;
      }
      catch (const std::exception&)
      {
        return false;
      }
    }

    void RecreateVoicesTap()
    {
      Delegate->SetVoicesTap(nullptr);
      Tap =
          std::make_unique<VoicesTap>(Delegate->GetSynth(), Voices == Parameters::ZXTune::Core::MT32::VOICES_PARTIALS);
      TapActive = false;
      DescribeVoices();
    }

    // separate voices are rendered only while consumed
    void UpdateVoicesTap()
    {
      const bool active = Scope && Scope->IsActive();
      if (active == TapActive)
      {
        return;
      }
      TapActive = active;
      Tap->Clear();
      if (active)
      {
        Resamplers.clear();
        for (uint_t pair = 0; pair < (Tap->GetVoices() + 1) / 2; ++pair)
        {
          Resamplers.push_back(Sound::CreateResampler(SAMPLERATE, Samplerate));
        }
      }
      Delegate->SetVoicesTap(active ? Tap.get() : nullptr);
    }

    // voices are resampled the same way as main output, by pairs
    void FeedVoices(uint_t outSamples)
    {
      const auto voices = Tap->GetVoices();
      const auto& samples = Tap->Samples;
      const auto inSamples = static_cast<uint_t>(samples.size() / voices);
      Output.assign(std::size_t(outSamples) * voices, 0);
      for (uint_t pair = 0; pair < Resamplers.size(); ++pair)
      {
        const auto first = pair * 2;
        const bool hasSecond = first + 1 < voices;
        Sound::Chunk in(inSamples);
        for (uint_t idx = 0; idx < inSamples; ++idx)
        {
          const auto* src = samples.data() + std::size_t(idx) * voices + first;
          in[idx] = Sound::Sample(src[0], hasSecond ? src[1] : int16_t(0));
        }
        const auto out = Resamplers[pair]->Apply(std::move(in));
        for (uint_t idx = 0, lim = std::min<uint_t>(outSamples, static_cast<uint_t>(out.size())); idx < lim; ++idx)
        {
          Output[std::size_t(idx) * voices + first] = static_cast<int16_t>(out[idx].Left());
          if (hasSecond)
          {
            Output[std::size_t(idx) * voices + first + 1] = static_cast<int16_t>(out[idx].Right());
          }
        }
      }
      // states are interleaved with samples blocks
      const auto period = std::max<uint_t>(Scope->GetStatePeriod(), 1);
      LastStates.resize(voices);
      for (uint_t done = 0; done < outSamples;)
      {
        const auto part = std::min(period, outSamples - done);
        Scope->Feed(0, voices, Output.data() + std::size_t(done) * voices, part);
        done += part;
        const auto inPos = inSamples ? uint_t(uint64_t(done) * inSamples / outSamples) : 0;
        const auto it = std::upper_bound(Tap->StatesPos.begin(), Tap->StatesPos.end(), inPos);
        if (it != Tap->StatesPos.begin())
        {
          const auto idx = std::size_t(it - Tap->StatesPos.begin() - 1);
          std::copy_n(Tap->States.begin() + idx * voices, voices, LastStates.begin());
        }
        Scope->FeedVoices(0, LastStates.data(), voices);
      }
      Tap->Clear();
    }

  private:
    const Model::Ptr Tune;
    Parameters::TrackingHelper<Parameters::Accessor> Params;
    String RomsPath;
    Parameters::IntType RomsModel = 0;
    Parameters::IntType Voices = 0;
    Engine::Ptr Delegate;
    TimedState State;
    const uint_t Samplerate;
    const Sound::Converter::Ptr Target;
    std::unique_ptr<VoicesTap> Tap;
    bool TapActive = false;
    VoicesScope::Ptr Scope;
    std::vector<Sound::Converter::Ptr> Resamplers;
    std::vector<int16_t> Output;
    std::vector<VoiceState> LastStates;
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
      using namespace Parameters::ZXTune::Core::MT32;
      const auto path = Parameters::GetString(*params, ROMS_PATH);
      const auto model = Parameters::GetInteger(*params, MODEL, MODEL_DEFAULT);
      auto roms = path.empty() ? RomSet::Ptr() : RomSet::Load(path, model);
      if (!roms)
      {
        throw Error(THIS_LINE, "MT-32 ROMs are not found. Select directory with control and PCM ROMs in settings.");
      }
      try
      {
        return MakePtr<Renderer>(Tune, std::move(roms), samplerate, std::move(params));
      }
      catch (const std::exception& e)
      {
        throw Error(THIS_LINE, e.what());
      }
    }

  private:
    const Model::Ptr Tune;
    const Parameters::Accessor::Ptr Properties;
  };

  class DataBuilder : public Formats::Chiptune::Midi::Builder
  {
  public:
    explicit DataBuilder(PropertiesHelper& props)
      : Meta(props)
    {}

    Formats::Chiptune::MetaBuilder& GetMetaBuilder() override
    {
      return Meta;
    }

  private:
    MetaProperties Meta;
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
        if (const auto container = Formats::Chiptune::Midi::Parse(rawData, dataBuilder))
        {
          auto tune = SmfParser::Parse(*container);
          props.SetSource(*container);
          props.SetChannels(
              Strings::Array{"Part 1", "Part 2", "Part 3", "Part 4", "Part 5", "Part 6", "Part 7", "Part 8", "Rhythm"});
          return MakePtr<Holder>(std::move(tune), std::move(properties));
        }
      }
      catch (const std::exception& e)
      {
        Dbg("Failed to create MIDI: {}", e.what());
      }
      return {};
    }
  };

  Factory::Ptr CreateFactory()
  {
    return MakePtr<Factory>();
  }
}  // namespace Module::MT32
