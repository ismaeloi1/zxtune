// Usage: module_test_mt32
// Host check of MIDI playback via MT-32 emulation using fake ROMs (as Munt tests do) and instrument uploaded by SysEx
// test real implementation including internal classes
#include "parameters/container.h"

#include "3rdparty/mt32emu/src/Structures.h"

#include <cstdio>
#include <cstring>

#include "module/players/external/mt32.cpp"

namespace
{
  const uint_t FREQ = 48000;
  int Failures = 0;

  void Check(bool ok, const char* what)
  {
    std::printf("%s: %s\n", ok ? "OK  " : "FAIL", what);
    Failures += !ok;
  }

  const MT32Emu::ROMInfo* FindRom(const char* name)
  {
    for (const auto* const* info = MT32Emu::ROMInfo::getAllROMInfos(); *info; ++info)
    {
      if (0 == std::strcmp(name, (*info)->shortName))
      {
        return *info;
      }
    }
    return nullptr;
  }

  // zero images with known digests, see munt/mt32emu/src/test/FakeROMs.cpp
  Module::MT32::RomSet::Ptr CreateFakeRoms()
  {
    const auto* ctrl = FindRom("ctrl_mt32_1_07");
    const auto* pcm = FindRom("pcm_mt32");
    // maximal values tables are filled to accept any parameter in SysEx
    auto* ctrlData = new MT32Emu::Bit8u[ctrl->fileSize];
    std::memset(ctrlData, 0x7f, ctrl->fileSize);
    auto* pcmData = new MT32Emu::Bit8u[pcm->fileSize]();
    const auto* ctrlImage =
        MT32Emu::ROMImage::makeROMImage(new MT32Emu::ArrayFile(ctrlData, ctrl->fileSize, ctrl->sha1Digest));
    const auto* pcmImage =
        MT32Emu::ROMImage::makeROMImage(new MT32Emu::ArrayFile(pcmData, pcm->fileSize, pcm->sha1Digest));
    return Module::MT32::RomSet::Create(ctrlImage, pcmImage);
  }

  // Roland DT1 message
  std::vector<uint8_t> MakeSysex(uint_t addr, const void* data, std::size_t size)
  {
    std::vector<uint8_t> msg = {
        0xf0, 0x41, 0x10, 0x16, 0x12, uint8_t(addr >> 16), uint8_t((addr >> 8) & 0x7f), uint8_t(addr & 0x7f)};
    const auto* bytes = static_cast<const uint8_t*>(data);
    msg.insert(msg.end(), bytes, bytes + size);
    uint_t sum = 0;
    for (std::size_t idx = 5; idx < msg.size(); ++idx)
    {
      sum += msg[idx];
    }
    msg.push_back(uint8_t((128 - sum % 128) % 128));
    msg.push_back(0xf7);
    return msg;
  }

  class SmfBuilder
  {
  public:
    void Delta(uint_t ticks)
    {
      std::vector<uint8_t> tmp;
      tmp.push_back(ticks & 0x7f);
      while (ticks >>= 7)
      {
        tmp.push_back(0x80 | (ticks & 0x7f));
      }
      Track.insert(Track.end(), tmp.rbegin(), tmp.rend());
    }

    void Bytes(std::initializer_list<uint8_t> bytes)
    {
      Track.insert(Track.end(), bytes);
    }

    void Sysex(const std::vector<uint8_t>& msg)
    {
      Track.push_back(0xf0);
      Delta(static_cast<uint_t>(msg.size() - 1));
      Track.insert(Track.end(), msg.begin() + 1, msg.end());
    }

    std::vector<uint8_t> Build(uint_t division) const
    {
      std::vector<uint8_t> res = {'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, uint8_t(division >> 8), uint8_t(division),
                                  'M', 'T', 'r', 'k'};
      const auto size = Track.size() + 4;
      res.insert(res.end(), {uint8_t(size >> 24), uint8_t(size >> 16), uint8_t(size >> 8), uint8_t(size)});
      res.insert(res.end(), Track.begin(), Track.end());
      res.insert(res.end(), {0, 0xff, 0x2f, 0});
      return res;
    }

  private:
    std::vector<uint8_t> Track;
  };

  // square wave with sustained full level on first partial
  MT32Emu::TimbreParam MakeTimbre()
  {
    MT32Emu::TimbreParam timbre;
    std::memset(&timbre, 0, sizeof(timbre));
    std::memcpy(timbre.common.name, "SQUARE    ", 10);
    timbre.common.partialMute = 1;
    auto& p = timbre.partial[0];
    p.wg.pitchCoarse = 36;
    p.wg.pitchFine = 50;
    p.wg.pitchKeyfollow = 11;
    p.wg.pitchBenderEnabled = 1;
    p.wg.pulseWidthVeloSensitivity = 7;
    for (auto& lvl : p.pitchEnv.level)
    {
      lvl = 50;
    }
    p.tvf.cutoff = 100;
    p.tvf.keyfollow = 0;
    p.tvf.biasPoint = 64;
    p.tvf.biasLevel = 7;
    for (auto& lvl : p.tvf.envLevel)
    {
      lvl = 100;
    }
    p.tva.level = 100;
    p.tva.biasPoint1 = 64;
    p.tva.biasLevel1 = 12;
    p.tva.biasPoint2 = 64;
    p.tva.biasLevel2 = 12;
    p.tva.envTime[4] = 30;
    for (auto& lvl : p.tva.envLevel)
    {
      lvl = 100;
    }
    return timbre;
  }

  // 120bpm 4 notes of quarter at 96 ppqn, tempo doubles after second note
  std::vector<uint8_t> MakeSong()
  {
    SmfBuilder smf;
    // system: master tune, reverb off-ish, reserve all partials to part 1, parts 1-8 on channels 2-9, volume 100
    const uint8_t system[23] = {64, 0, 0, 0, 32, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 100};
    smf.Delta(0);
    smf.Sysex(MakeSysex(0x100000, system, sizeof(system)));
    // patch temp of part 1: memory timbre 0, no key shift, center fine tune, bender 12, poly1, reverb off,
    // full output level, center pan
    const uint8_t patch[16] = {2, 0, 24, 50, 12, 0, 0, 0, 100, 7, 0, 0, 0, 0, 0, 0};
    smf.Delta(0);
    smf.Sysex(MakeSysex(0x030000, patch, sizeof(patch)));
    const auto timbre = MakeTimbre();
    smf.Delta(0);
    smf.Sysex(MakeSysex(0x040000, &timbre, sizeof(timbre)));
    // C4, E4 at 120bpm (0.5s each), then 240bpm G4, C5 (0.25s each)
    const uint8_t keys[4] = {60, 64, 67, 72};
    for (uint_t idx = 0; idx < 4; ++idx)
    {
      smf.Delta(idx == 0 ? 96 : 0);  // starts at 0.5s
      smf.Bytes({0x91, keys[idx], 100});
      smf.Delta(90);
      smf.Bytes({0x81, keys[idx], 0});
      if (idx == 1)
      {
        smf.Delta(6);
        // 250000us per quarter
        smf.Bytes({0xff, 0x51, 3, 0x03, 0xd0, 0x90});
      }
      else
      {
        smf.Delta(6);
        smf.Bytes({0xb1, 7, 127});  // filler event to keep timing
      }
    }
    return smf.Build(96);
  }

  class TestScope : public Module::VoicesScope
  {
  public:
    void SetVoicesGroups(const std::vector<Module::VoicesGroup>& groups, bool /*hasRegisters*/) override
    {
      Count = 0;
      for (const auto& g : groups)
      {
        Count += static_cast<uint_t>(g.Voices.size());
      }
      Names = groups.front().Name + " - " + groups.front().Voices.front();
      Samples.assign(Count, {});
      States.assign(Count, {});
    }

    bool IsActive() const override
    {
      return true;
    }

    uint_t GetStatePeriod() const override
    {
      return FREQ / 1000;
    }

    void FeedState(uint_t, const uint8_t*, const uint8_t*, const uint8_t*) override {}
    void FeedChip(uint_t, const uint8_t*, uint_t) override {}

    void FeedVoices(uint_t first, const Module::VoiceState* states, uint_t count) override
    {
      for (uint_t idx = 0; idx < count; ++idx)
      {
        States[first + idx].push_back(states[idx]);
      }
    }

    void SetDescription(const String& description) override
    {
      Description = description;
    }

    void Feed(uint_t first, uint_t voices, const int16_t* samples, uint_t count) override
    {
      for (uint_t smp = 0; smp < count; ++smp)
      {
        for (uint_t idx = 0; idx < voices; ++idx)
        {
          Samples[first + idx].push_back(samples[smp * voices + idx]);
        }
      }
    }

    uint_t Count = 0;
    String Names;
    String Description;
    std::vector<std::vector<int16_t>> Samples;
    std::vector<std::vector<Module::VoiceState>> States;
  };

  std::vector<int> RenderAll(Module::Renderer& renderer, std::size_t limit)
  {
    std::vector<int> result;
    while (result.size() < limit)
    {
      const auto chunk = renderer.Render();
      if (chunk.empty())
      {
        break;
      }
      for (const auto& smp : chunk)
      {
        result.push_back(smp.Left() + smp.Right());
      }
    }
    return result;
  }

  double Rms(const std::vector<int>& data, std::size_t from, std::size_t to)
  {
    double sum = 0;
    for (auto idx = from; idx < to && idx < data.size(); ++idx)
    {
      sum += double(data[idx]) * data[idx];
    }
    return std::sqrt(sum / std::max<std::size_t>(to - from, 1));
  }

  // pressed key of any voice
  float NoteAt(const std::vector<std::vector<Module::VoiceState>>& voices, double seconds)
  {
    for (const auto& states : voices)
    {
      const auto& st = states.at(std::size_t(seconds * 1000));
      if (st.Flags & Module::VoiceState::KEY_ON)
      {
        return st.Frequency;
      }
    }
    return 0.0f;
  }
}  // namespace

int main()
{
  using namespace Module::MT32;
  using namespace Parameters::ZXTune::Core::MT32;
  const auto song = MakeSong();
  const auto tune = SmfParser::Parse(Binary::View(song.data(), song.size()));
  // 0.5s + 2 * 0.5s + 2 * 0.25s = 2s + tail 2s
  std::printf("events=%zu duration=%ums\n", tune->Events.size(), tune->GetDuration().Get());
  Check(tune->GetDuration().Get() == 4000, "duration with tempo change");
  std::vector<uint64_t> noteOns;
  for (const auto& evt : tune->Events)
  {
    if ((evt.Message & 0xf0) == 0x90)
    {
      noteOns.push_back(evt.Sample);
    }
  }
  Check(noteOns == std::vector<uint64_t>{16000, 32000, 48000, 56000}, "note on times at 32kHz");

  const auto roms = CreateFakeRoms();
  const auto params = Parameters::Container::Create();
  const std::size_t total = FREQ * 4;
  // plain rendering
  Renderer plain(tune, roms, FREQ, params);
  const auto plainOut = RenderAll(plain, total);
  Check(Rms(plainOut, 0, FREQ / 2) == 0, "silence before first note");
  Check(Rms(plainOut, FREQ * 6 / 10, FREQ) > 100, "audible first note");

  for (const auto mode : {VOICES_PARTS, VOICES_PARTIALS})
  {
    const auto modeParams = Parameters::Container::Create();
    modeParams->SetValue(Parameters::ZXTune::Core::MT32::VOICES, mode);
    Renderer tapped(tune, roms, FREQ, modeParams);
    auto scope = std::make_shared<TestScope>();
    tapped.SetVoicesScope(scope);
    const auto tappedOut = RenderAll(tapped, total);
    std::printf("mode %s: %u voices, first is '%s', %s\n", mode == VOICES_PARTS ? "parts" : "partials", scope->Count,
                scope->Names.c_str(), scope->Description.c_str());
    Check(tappedOut == plainOut, "output is not changed by voices tap");
    Check(scope->Samples[0].size() == tappedOut.size(), "voices samples count");
    // single part is playing, partials are allocated one by one
    const auto sumOfVoices = mode == VOICES_PARTIALS;
    double sxy = 0, sxx = 0, syy = 0;
    for (std::size_t idx = 0; idx < tappedOut.size(); ++idx)
    {
      double v = scope->Samples[0][idx];
      for (uint_t voice = 1; sumOfVoices && voice < scope->Count; ++voice)
      {
        v += scope->Samples[voice][idx];
      }
      sxy += v * tappedOut[idx];
      sxx += v * v;
      syy += double(tappedOut[idx]) * tappedOut[idx];
    }
    const auto corr = sxy / std::sqrt(sxx * syy + 1);
    std::printf("correlation(%s, output) = %.4f\n", sumOfVoices ? "sum of voices" : "voice 1", corr);
    Check(corr > 0.9, "voices carry output");
    double other = 0;
    for (uint_t voice = sumOfVoices ? 2 : 1; voice < scope->Count; ++voice)
    {
      for (const auto smp : scope->Samples[voice])
      {
        other += std::abs(smp);
      }
    }
    Check(other == 0, sumOfVoices ? "only 2 partials are used" : "other voices are silent");
    const auto& states = scope->States;
    std::printf("notes: %.1f %.1f %.1f %.1f %.1f Hz\n", NoteAt(states, 0.3), NoteAt(states, 0.75), NoteAt(states, 1.25),
                NoteAt(states, 1.6), NoteAt(states, 1.85));
    Check(NoteAt(states, 0.3) == 0 && std::abs(NoteAt(states, 0.75) - 261.63f) < 0.1f
              && std::abs(NoteAt(states, 1.25) - 329.63f) < 0.1f && std::abs(NoteAt(states, 1.6) - 392.0f) < 0.1f
              && std::abs(NoteAt(states, 1.85) - 523.25f) < 0.1f,
          "notes C4 E4 G4 C5 at expected times");
  }

  // seek forward into the third note: sysex instrument must be restored by chasing
  {
    Renderer seeked(tune, roms, FREQ, params);
    seeked.SetPosition(Time::AtMillisecond() + Time::Milliseconds(1400));
    const auto out = RenderAll(seeked, FREQ);
    const auto ref = Rms(plainOut, FREQ * 1550 / 1000, FREQ * 1700 / 1000);
    const auto got = Rms(out, FREQ * 150 / 1000, FREQ * 300 / 1000);
    std::printf("seek forward: rms %.0f vs %.0f\n", got, ref);
    Check(got > ref * 0.8 && got < ref * 1.25, "seek forward plays third note with uploaded instrument");
    // and back to the start
    seeked.SetPosition(Time::AtMillisecond());
    const auto again = RenderAll(seeked, FREQ);
    Check(Rms(again, 0, FREQ / 2) == 0 && Rms(again, FREQ * 6 / 10, FREQ) > 100, "seek back restarts");
  }
  // settings changed while playing
  {
    const auto liveParams = Parameters::Container::Create();
    Renderer live(tune, roms, FREQ, liveParams);
    auto scope = std::make_shared<TestScope>();
    live.SetVoicesScope(scope);
    auto out = RenderAll(live, FREQ);
    const auto partsCount = scope->Count;
    liveParams->SetValue(VOICES, VOICES_PARTIALS);
    // ROMs from unusable location should not break playback
    liveParams->SetValue(ROMS_PATH, StringView("/nonexistent"));
    const auto tail = RenderAll(live, total - FREQ);
    out.insert(out.end(), tail.begin(), tail.end());
    std::printf("voices switched while playing: %u -> %u\n", partsCount, scope->Count);
    Check(partsCount == 9 && scope->Count > partsCount, "voices mode is applied while playing");
    Check(out == plainOut, "output is not changed by settings switch");
  }
  {
    // directory without ROMs
    const String dir = "/tmp/zxtune_mt32_test_roms";
    std::system(("mkdir -p " + dir).c_str());
    std::ofstream(dir + "/junk.rom", std::ios::binary) << String(65536, 'x');
    std::ofstream(dir + "/MT32_PCM.ROM", std::ios::binary) << String(524288, 'y');
    Check(!RomSet::Load(dir, MODEL_AUTO), "unknown files are not used as ROMs");
    Check(!RomSet::Load("/nonexistent", MODEL_AUTO), "missing directory");
    std::system(("rm -rf " + dir).c_str());
  }
  std::printf("%s\n", Failures ? "FAILED" : "PASSED");
  return Failures ? 1 : 0;
}
