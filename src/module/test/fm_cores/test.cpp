// Usage: module_test_fm_cores [seconds] [file.vgm...]
// Host check of Yamaha FM chips emulation cores of libvgm-based player (see module/players/external/fm_output.h):
// - every core of every family renders non-silent output, speed is reported as realtime factor
// - per-voice taps of native cores sum up to mixed output (correlation), voices state has operators details
// - emulation and output stage settings are applied while playing
// Tunes are given files (e.g. samples/chiptunes/Multi/vgm/*.vgm) and synthetic register dumps of each family.
// Exit code is non-zero on any failed check.
#include "module/players/external/fm_output.h"
#include "module/players/pipeline.h"

#include "binary/container_factories.h"
#include "core/core_parameters.h"
#include "core/service.h"
#include "module/holder.h"
#include "module/voices_scope.h"
#include "parameters/container.h"
#include "sound/sound_parameters.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <numbers>
#include <string>
#include <vector>

namespace FmCoresTest
{
  const uint_t FREQ = 48000;
  namespace FM = Parameters::ZXTune::Core::FM;

  class VgmWriter
  {
  public:
    VgmWriter()
      : Data(0x100)
    {
      Set32(0x00, 0x206d6756);  // "Vgm "
      Set32(0x08, 0x171);
      Set32(0x34, 0x100 - 0x34);
    }

    VgmWriter& Clock(std::size_t offset, uint32_t clock)
    {
      Set32(offset, clock);
      return *this;
    }

    void Write(uint8_t cmd, uint8_t reg, uint8_t val)
    {
      Data.insert(Data.end(), {cmd, reg, val});
    }

    void Wait(uint_t samples)
    {
      Samples += samples;
      while (samples)
      {
        const auto part = std::min<uint_t>(samples, 65535);
        Data.insert(Data.end(), {0x61, uint8_t(part & 0xff), uint8_t(part >> 8)});
        samples -= part;
      }
    }

    void WaitMs(uint_t ms)
    {
      Wait(ms * 44100 / 1000);
    }

    std::vector<uint8_t> Build()
    {
      Data.push_back(0x66);
      Set32(0x04, static_cast<uint32_t>(Data.size() - 4));
      Set32(0x18, Samples);
      return Data;
    }

  private:
    void Set32(std::size_t offset, uint32_t val)
    {
      for (uint_t i = 0; i < 4; ++i)
      {
        Data[offset + i] = uint8_t(val >> (8 * i));
      }
    }

  private:
    std::vector<uint8_t> Data;
    uint32_t Samples = 0;
  };

  // C major arpeggio over channels
  const double NOTES[] = {261.63, 329.63, 392.0, 523.25, 440.0, 349.23, 293.66, 196.0, 246.94};

  std::vector<uint8_t> MakeOpm(uint_t seconds)
  {
    const uint32_t clock = 3579545;
    VgmWriter vgm;
    vgm.Clock(0x30, clock);
    const auto w = [&vgm](uint8_t reg, uint8_t val) { vgm.Write(0x54, reg, val); };
    w(0x18, 0xc0);         // LFO
    w(0x19, 0x80 | 0x10);  // PMD
    w(0x1b, 0x02);         // triangle
    for (uint8_t ch = 0; ch < 8; ++ch)
    {
      // alg 4 (two carriers), fb 5, both outputs and some panned
      const uint8_t rl = ch == 6 ? 0x40 : (ch == 7 ? 0x80 : 0xc0);
      w(0x20 + ch, rl | (5 << 3) | 4);
      w(0x38 + ch, 0x10);  // PMS
      for (uint8_t k = 0; k < 4; ++k)
      {
        const uint8_t slot = ch + 8 * k;
        const bool carrier = k >= 2;
        w(0x40 + slot, carrier ? 0x01 : 0x02);
        w(0x60 + slot, carrier ? 0x08 : 0x20);
        w(0x80 + slot, 0x1f);
        w(0xa0 + slot, 0x06);
        w(0xc0 + slot, 0x03);
        w(0xe0 + slot, 0x27);
      }
    }
    // key codes of semitones in octave
    static const uint8_t KC[12] = {0xe, 0x0, 0x1, 0x2, 0x4, 0x5, 0x6, 0x8, 0x9, 0xa, 0xc, 0xd};
    for (uint_t step = 0; step < seconds * 4; ++step)
    {
      const uint8_t ch = step % 8;
      const uint_t semitone = (step * 7) % 24;
      const uint8_t oct = 3 + semitone / 12 - (semitone % 12 == 0 ? 1 : 0);
      w(0x28 + ch, (oct << 4) | KC[semitone % 12]);
      w(0x30 + ch, uint8_t(step * 4));
      w(0x08, 0x78 | ch);
      vgm.WaitMs(200);
      w(0x08, ch);
      vgm.WaitMs(50);
    }
    return vgm.Build();
  }

  // operators offsets of 2-op channels in register bank
  const uint8_t OPL_SLOTS[9] = {0, 1, 2, 8, 9, 10, 16, 17, 18};

  void OplPatch(VgmWriter& vgm, uint8_t cmd, uint8_t ch, uint8_t feedbackAlg, uint8_t pan, uint8_t wave)
  {
    const uint8_t mod = OPL_SLOTS[ch];
    const uint8_t car = mod + 3;
    vgm.Write(cmd, 0x20 + mod, 0x21);
    vgm.Write(cmd, 0x20 + car, 0x21);
    vgm.Write(cmd, 0x40 + mod, 0x14);
    vgm.Write(cmd, 0x40 + car, 0x00);
    vgm.Write(cmd, 0x60 + mod, 0xf4);
    vgm.Write(cmd, 0x60 + car, 0xf3);
    vgm.Write(cmd, 0x80 + mod, 0x55);
    vgm.Write(cmd, 0x80 + car, 0x46);
    vgm.Write(cmd, 0xe0 + mod, wave);
    vgm.Write(cmd, 0xe0 + car, 0);
    vgm.Write(cmd, 0xc0 + ch, pan | feedbackAlg);
  }

  void OplNote(VgmWriter& vgm, uint8_t cmd, uint8_t ch, double freq, bool key)
  {
    const uint8_t block = 4;
    const auto fnum = static_cast<uint_t>(freq * (1 << 20) / (49716.0 * (1 << block)));
    vgm.Write(cmd, 0xa0 + ch, uint8_t(fnum));
    vgm.Write(cmd, 0xb0 + ch, uint8_t((key ? 0x20 : 0) | (block << 2) | (fnum >> 8)));
  }

  std::vector<uint8_t> MakeOpl2(uint_t seconds)
  {
    VgmWriter vgm;
    vgm.Clock(0x50, 3579545);
    const uint8_t cmd = 0x5a;
    vgm.Write(cmd, 0x01, 0x20);
    for (uint8_t ch = 0; ch < 9; ++ch)
    {
      OplPatch(vgm, cmd, ch, (ch % 2) | (3 << 1), 0x30, ch % 4);
    }
    for (uint_t step = 0; step < seconds * 4; ++step)
    {
      // second half is rhythm mode with 6 melodic channels
      const bool rhythm = step >= seconds * 2;
      const uint8_t ch = step % (rhythm ? 6 : 9);
      OplNote(vgm, cmd, ch, NOTES[step % 9], true);
      if (rhythm)
      {
        OplNote(vgm, cmd, 6, 110, false);
        OplNote(vgm, cmd, 7, 220, false);
        OplNote(vgm, cmd, 8, 330, false);
        vgm.Write(cmd, 0xbd, 0x20 | (1 << (step % 5)));
      }
      vgm.WaitMs(200);
      OplNote(vgm, cmd, ch, NOTES[step % 9], false);
      vgm.Write(cmd, 0xbd, rhythm ? 0x20 : 0);
      vgm.WaitMs(50);
    }
    return vgm.Build();
  }

  std::vector<uint8_t> MakeOpl3(uint_t seconds)
  {
    VgmWriter vgm;
    vgm.Clock(0x5c, 14318180);
    vgm.Write(0x5f, 0x05, 0x01);  // OPL3 mode
    vgm.Write(0x5f, 0x04, 0x01);  // channels 0+3 are 4 operators
    for (uint8_t ch = 0; ch < 9; ++ch)
    {
      // left, right and both outputs
      const uint8_t pan = ch % 3 == 0 ? 0x30 : (ch % 3 == 1 ? 0x10 : 0x20);
      OplPatch(vgm, 0x5e, ch, (ch == 3 ? 1 : 0) | (2 << 1), pan, ch % 8);
      OplPatch(vgm, 0x5f, ch, 1 | (4 << 1), 0x30, (ch + 4) % 8);
    }
    for (uint_t step = 0; step < seconds * 4; ++step)
    {
      // channel 3 is a part of 4 operators channel 0
      static const uint8_t PORT0[] = {0, 1, 2, 4, 5, 6, 7, 8};
      const bool high = step % 2;
      const uint8_t ch = high ? (step / 2) % 9 : PORT0[(step / 2) % 8];
      const uint8_t cmd = high ? 0x5f : 0x5e;
      OplNote(vgm, cmd, ch, NOTES[step % 9], true);
      vgm.WaitMs(200);
      OplNote(vgm, cmd, ch, NOTES[step % 9], false);
      vgm.WaitMs(50);
    }
    return vgm.Build();
  }

  void OpllNote(VgmWriter& vgm, uint8_t ch, double freq, bool key)
  {
    const uint8_t block = 4;
    const auto fnum = static_cast<uint_t>(freq * (1 << 19) / (49716.0 * (1 << block)));
    vgm.Write(0x51, 0x10 + ch, uint8_t(fnum));
    vgm.Write(0x51, 0x20 + ch, uint8_t((key ? 0x10 : 0) | (block << 1) | (fnum >> 8)));
  }

  std::vector<uint8_t> MakeOpll(uint_t seconds)
  {
    VgmWriter vgm;
    vgm.Clock(0x10, 3579545);
    for (uint8_t ch = 0; ch < 9; ++ch)
    {
      vgm.Write(0x51, 0x30 + ch, uint8_t(((ch + 1) << 4) | 0x02));
    }
    for (uint_t step = 0; step < seconds * 4; ++step)
    {
      const bool rhythm = step >= seconds * 2;
      const uint8_t ch = step % (rhythm ? 6 : 9);
      OpllNote(vgm, ch, NOTES[step % 9], true);
      if (rhythm)
      {
        OpllNote(vgm, 6, 110, false);
        OpllNote(vgm, 7, 220, false);
        OpllNote(vgm, 8, 330, false);
        vgm.Write(0x51, 0x36, 0x02);
        vgm.Write(0x51, 0x37, 0x22);
        vgm.Write(0x51, 0x38, 0x22);
        vgm.Write(0x51, 0x0e, 0x20 | (1 << (step % 5)));
      }
      vgm.WaitMs(200);
      OpllNote(vgm, ch, NOTES[step % 9], false);
      vgm.Write(0x51, 0x0e, rhythm ? 0x20 : 0);
      vgm.WaitMs(50);
    }
    return vgm.Build();
  }

  void OpnPatch(VgmWriter& vgm, uint8_t cmd, uint8_t ch, bool stereo)
  {
    // registers order is OP1, OP3, OP2, OP4
    for (uint8_t op = 0; op < 4; ++op)
    {
      const uint8_t r = ch + op * 4;
      const bool carrier = op >= 2;
      vgm.Write(cmd, 0x30 + r, carrier ? 0x01 : 0x02);
      vgm.Write(cmd, 0x40 + r, carrier ? 0x08 : 0x1c);
      vgm.Write(cmd, 0x50 + r, 0x1f);
      vgm.Write(cmd, 0x60 + r, 0x06);
      vgm.Write(cmd, 0x70 + r, 0x03);
      vgm.Write(cmd, 0x80 + r, 0x27);
      vgm.Write(cmd, 0x90 + r, 0);
    }
    // alg 4, feedback 5
    vgm.Write(cmd, 0xb0 + ch, (5 << 3) | 4);
    if (stereo)
    {
      vgm.Write(cmd, 0xb4 + ch, 0xc0);
    }
  }

  void OpnNote(VgmWriter& vgm, uint8_t cmd, uint8_t ch, double freq, double rate)
  {
    const uint8_t block = 4;
    const auto fnum = static_cast<uint_t>(freq * (1 << 20) / (rate * (1 << (block - 1))));
    vgm.Write(cmd, 0xa4 + ch, uint8_t((block << 3) | (fnum >> 8)));
    vgm.Write(cmd, 0xa0 + ch, uint8_t(fnum));
  }

  std::vector<uint8_t> MakeOpn(uint_t seconds, bool opna, bool ssg)
  {
    VgmWriter vgm;
    const uint32_t clock = opna ? 7987200 : 3993600;
    vgm.Clock(opna ? 0x48 : 0x44, clock);
    const uint8_t cmd0 = opna ? 0x56 : 0x55;
    const uint8_t cmd1 = 0x57;
    const double rate = clock / (opna ? 144.0 : 72.0);
    const uint8_t channels = opna ? 6 : 3;
    if (opna)
    {
      vgm.Write(cmd0, 0x29, 0x80);
    }
    for (uint8_t ch = 0; ch < channels; ++ch)
    {
      OpnPatch(vgm, ch < 3 ? cmd0 : cmd1, ch % 3, opna);
    }
    // SSG tone A
    vgm.Write(cmd0, 0x00, 0xfe);
    vgm.Write(cmd0, 0x01, 0x01);
    vgm.Write(cmd0, 0x07, 0x3e);
    for (uint_t step = 0; step < seconds * 4; ++step)
    {
      const uint8_t ch = step % channels;
      const uint8_t keyCh = ch < 3 ? ch : ch + 1;
      OpnNote(vgm, ch < 3 ? cmd0 : cmd1, ch % 3, NOTES[step % 9], rate);
      vgm.Write(cmd0, 0x28, 0xf0 | keyCh);
      vgm.Write(cmd0, 0x08, ssg && step % 2 ? 0x0c : 0x00);
      vgm.WaitMs(200);
      vgm.Write(cmd0, 0x28, keyCh);
      vgm.WaitMs(50);
    }
    return vgm.Build();
  }

  std::vector<uint8_t> MakeOpn2(uint_t seconds)
  {
    VgmWriter vgm;
    const uint32_t clock = 7670453;
    vgm.Clock(0x2c, clock);
    const double rate = clock / 144.0;
    for (uint8_t ch = 0; ch < 6; ++ch)
    {
      OpnPatch(vgm, ch < 3 ? 0x52 : 0x53, ch % 3, true);
    }
    for (uint_t step = 0; step < seconds * 4; ++step)
    {
      const uint8_t ch = step % 6;
      const uint8_t keyCh = ch < 3 ? ch : ch + 1;
      OpnNote(vgm, ch < 3 ? 0x52 : 0x53, ch % 3, NOTES[step % 9], rate);
      vgm.Write(0x52, 0x28, 0xf0 | keyCh);
      vgm.WaitMs(200);
      vgm.Write(0x52, 0x28, keyCh);
      vgm.WaitMs(50);
    }
    return vgm.Build();
  }

  // YM2610 has FM channels 2, 3, 5, 6 only, no ADPCM ROMs here
  std::vector<uint8_t> MakeOpnb(uint_t seconds)
  {
    VgmWriter vgm;
    const uint32_t clock = 8000000;
    vgm.Clock(0x4c, clock);
    const double rate = clock / 144.0;
    static const uint8_t CHANNELS[] = {1, 2, 4, 5};
    for (const auto ch : CHANNELS)
    {
      OpnPatch(vgm, ch < 3 ? 0x58 : 0x59, ch % 3, true);
    }
    for (uint_t step = 0; step < seconds * 4; ++step)
    {
      const uint8_t ch = CHANNELS[step % 4];
      const uint8_t keyCh = ch < 3 ? ch : ch + 1;
      OpnNote(vgm, ch < 3 ? 0x58 : 0x59, ch % 3, NOTES[step % 9], rate);
      vgm.Write(0x58, 0x28, 0xf0 | keyCh);
      vgm.WaitMs(200);
      vgm.Write(0x58, 0x28, keyCh);
      vgm.WaitMs(50);
    }
    return vgm.Build();
  }

  class TestScope : public Module::VoicesScope
  {
  public:
    void SetVoicesGroups(const std::vector<Module::VoicesGroup>& groups, bool) override
    {
      Voices = 0;
      for (const auto& g : groups)
      {
        Voices += static_cast<uint_t>(g.Voices.size());
      }
      Samples.assign(Voices, {});
      Detailed.assign(Voices, 0);
      Keyed.assign(Voices, 0);
      States = 0;
    }

    bool IsActive() const override
    {
      return true;
    }

    uint_t GetStatePeriod() const override
    {
      return FREQ / 100;
    }

    void FeedState(uint_t, const uint8_t*, const uint8_t*, const uint8_t*) override {}
    void FeedChip(uint_t, const uint8_t*, uint_t) override {}

    void FeedVoices(uint_t firstVoice, const Module::VoiceState* states, uint_t count) override
    {
      ++States;
      for (uint_t idx = 0; idx < count; ++idx)
      {
        const auto& st = states[idx];
        const auto voice = firstVoice + idx;
        // FM kinds have chip type field when operators details are provided
        const bool fm = st.Kind == Module::VoiceState::OPN_FM || st.Kind == Module::VoiceState::OPL_2OP
                        || st.Kind == Module::VoiceState::OPL_4OP;
        Detailed[voice] += fm && st.Fields[Module::VoiceState::FM_CHIP] != 0;
        Keyed[voice] += (st.Flags & Module::VoiceState::KEY_ON) != 0;
      }
    }

    void SetDescription(const String& description) override
    {
      Description = description;
    }

    void Feed(uint_t firstVoice, uint_t voices, const int16_t* samples, uint_t count) override
    {
      for (uint_t smp = 0; smp < count; ++smp)
      {
        for (uint_t idx = 0; idx < voices; ++idx)
        {
          Samples[firstVoice + idx].push_back(samples[smp * voices + idx]);
        }
      }
    }

    uint_t Voices = 0;
    String Description;
    std::vector<std::vector<int16_t>> Samples;
    std::vector<uint_t> Detailed;
    std::vector<uint_t> Keyed;
    uint_t States = 0;
  };

  using Setting = std::pair<Parameters::Identifier, Parameters::IntType>;

  struct Config
  {
    String Name;
    std::vector<Setting> Settings;
  };

  struct Tune
  {
    String Name;
    std::vector<uint8_t> Data;
    std::vector<Config> Configs;
  };

  Parameters::Container::Ptr MakeParams(const Config& cfg)
  {
    auto params = Parameters::Container::Create();
    params->SetValue(Parameters::ZXTune::Sound::FREQUENCY, FREQ);
    for (const auto& s : cfg.Settings)
    {
      params->SetValue(s.first, s.second);
    }
    return params;
  }

  std::vector<int32_t> Render(Module::Renderer& r, uint_t samples)
  {
    std::vector<int32_t> mono;
    while (mono.size() < samples)
    {
      const auto chunk = r.Render();
      if (chunk.empty())
      {
        break;
      }
      for (const auto& s : chunk)
      {
        mono.push_back(int(s.Left()) + s.Right());
      }
    }
    return mono;
  }

  double Rms(const std::vector<int32_t>& data, std::size_t from = 0)
  {
    double sum = 0;
    for (std::size_t i = from; i < data.size(); ++i)
    {
      sum += double(data[i]) * data[i];
    }
    return std::sqrt(sum / std::max<std::size_t>(data.size() - from, 1));
  }

  // DC and infrasound are removed by output stage of some chips, so they are excluded from comparison
  std::vector<double> Highpass(std::vector<double> data)
  {
    const double r = 1 - 2 * std::numbers::pi * 30 / FREQ;
    double x1 = 0, y1 = 0;
    for (auto& x : data)
    {
      const auto y = x - x1 + r * y1;
      x1 = x;
      x = y1 = y;
    }
    return data;
  }

  // best correlation of voices sum with output, with small lag search (resampling latency)
  double Correlation(const TestScope& scope, const std::vector<int32_t>& output)
  {
    const auto count = std::min(output.size(), scope.Samples.empty() ? 0 : scope.Samples[0].size());
    std::vector<double> sums(count);
    for (std::size_t i = 0; i < count; ++i)
    {
      for (const auto& v : scope.Samples)
      {
        sums[i] += v[i];
      }
    }
    sums = Highpass(std::move(sums));
    const auto out = Highpass({output.begin(), output.begin() + count});
    double best = -1;
    for (int lag = -16; lag <= 16; ++lag)
    {
      double sx = 0, sy = 0, sxy = 0, sxx = 0, syy = 0, n = 0;
      for (std::size_t i = 16; i + 16 < count; ++i)
      {
        const double x = sums[i];
        const double y = out[i + lag];
        sx += x;
        sy += y;
        sxy += x * y;
        sxx += x * x;
        syy += y * y;
        ++n;
      }
      const auto cov = sxy - sx * sy / n;
      const auto vx = sxx - sx * sx / n;
      const auto vy = syy - sy * sy / n;
      best = std::max(best, cov / std::sqrt(vx * vy + 1));
    }
    return best;
  }

  // voices sum level relative to output, as displayed by oscilloscope
  double Scale(const TestScope& scope, const std::vector<int32_t>& out)
  {
    const auto count = std::min(out.size(), scope.Samples.empty() ? 0 : scope.Samples[0].size());
    double sums = 0, outs = 0;
    for (std::size_t i = 0; i < count; ++i)
    {
      double sum = 0;
      for (const auto& v : scope.Samples)
      {
        sum += v[i];
      }
      sums += sum * sum;
      outs += double(out[i]) * out[i];
    }
    return std::sqrt(sums / (outs + 1));
  }

  uint_t Failures = 0;

  void Check(bool ok, const char* what)
  {
    if (!ok)
    {
      std::printf("  FAIL: %s\n", what);
      ++Failures;
    }
  }

  void Test(const ZXTune::Service& service, const Tune& tune, const Config& cfg, uint_t seconds)
  {
    const auto params = MakeParams(cfg);
    const auto holder = service.OpenModule(Binary::CreateContainer(Binary::View(tune.Data.data(), tune.Data.size())),
                                           "", Parameters::Container::Create());
    const auto samples = seconds * FREQ;
    std::vector<int32_t> plain;
    double ms = 0;
    {
      auto renderer = Module::CreatePipelinedRenderer(*holder, params);
      const auto start = std::chrono::steady_clock::now();
      plain = Render(*renderer, samples);
      ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    }
    auto renderer = Module::CreatePipelinedRenderer(*holder, params);
    auto scope = std::make_shared<TestScope>();
    auto* source = dynamic_cast<Module::VoicesScopeSource*>(renderer.get());
    const auto hasVoices = source && source->SetVoicesScope(scope);
    const auto tapStart = std::chrono::steady_clock::now();
    const auto tapped = Render(*renderer, samples);
    const auto tapMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - tapStart).count();
    const auto corr = hasVoices ? Correlation(*scope, tapped) : 0.0;
    uint_t detailed = 0, keyed = 0;
    for (uint_t v = 0; v < scope->Voices; ++v)
    {
      detailed += scope->Detailed[v] != 0;
      keyed += scope->Keyed[v] != 0;
    }
    const auto rms = Rms(plain);
    const auto scale = hasVoices ? Scale(*scope, tapped) : 0.0;
    std::printf(
        "%-16s %-24s %6.1fx/%6.1fx realtime rms=%6.0f voices=%2u corr=%.4f scale=%.2f keyed=%2u detailed=%2u [%s]\n",
        tune.Name.c_str(), cfg.Name.c_str(), seconds * 1000.0 / ms, seconds * 1000.0 / tapMs, rms, scope->Voices, corr,
        scale, keyed, detailed, scope->Description.c_str());
    Check(plain.size() == samples, "rendered duration");
    Check(rms > 100, "non-silent output");
    Check(plain == tapped, "output is not changed by voices tap");
    Check(hasVoices && scope->Voices != 0, "voices are provided");
    // native taps only, solo players are used for cores without them (and provide no state)
    if (scope->Description.find("(solo)") == String::npos)
    {
      Check(corr > 0.95, "voices sum matches output");
      // oscilloscope shows voices of all the chips in the same scale
      Check(scale > 0.6 && scale < 1.6, "voices sum level matches output");
      Check(keyed != 0, "voices states");
    }
  }

  // Settings change while playing restarts chips at the same position
  void TestLive(const ZXTune::Service& service, const Tune& tune, const Config& from, const Config& to, bool coreChange)
  {
    const auto params = MakeParams(from);
    const auto holder = service.OpenModule(Binary::CreateContainer(Binary::View(tune.Data.data(), tune.Data.size())),
                                           "", Parameters::Container::Create());
    auto renderer = Module::CreatePipelinedRenderer(*holder, params);
    auto scope = std::make_shared<TestScope>();
    dynamic_cast<Module::VoicesScopeSource&>(*renderer).SetVoicesScope(scope);
    const auto before = Render(*renderer, FREQ);
    const auto descBefore = scope->Description;
    for (const auto& s : to.Settings)
    {
      params->SetValue(s.first, s.second);
    }
    const auto after = Render(*renderer, FREQ);
    const auto pos = renderer->GetState().At.Get();
    std::printf("%-12s live %s -> %s: rms %.0f -> %.0f, at %ums, voices %u [%s] -> [%s]\n", tune.Name.c_str(),
                from.Name.c_str(), to.Name.c_str(), Rms(before), Rms(after), uint_t(pos), scope->Voices,
                descBefore.c_str(), scope->Description.c_str());
    Check(Rms(after) > 100, "non-silent after change");
    Check(pos >= 1900 && pos <= 2100, "position is kept");
    Check(scope->Voices != 0 && !scope->Samples.empty() && scope->Samples[0].size() > FREQ / 2, "voices after change");
    Check(coreChange == (descBefore != scope->Description), "emulation core change");
  }

  // Output stage gain of sine at frequency, in dB
  double MeasureGain(const Module::FmOutput::Stage& stage, double freq)
  {
    Module::FmOutput::Filter filter;
    filter.SetStage(stage, FREQ);
    Sound::Chunk chunk(FREQ);
    for (uint_t i = 0; i < FREQ; ++i)
    {
      const auto val = static_cast<int>(10000 * std::sin(2 * std::numbers::pi * freq * i / FREQ));
      chunk[i] = Sound::Sample(val, val);
    }
    filter.Process(chunk);
    double in = 0, out = 0;
    // skip transient
    for (uint_t i = FREQ / 2; i < FREQ; ++i)
    {
      const auto val = 10000 * std::sin(2 * std::numbers::pi * freq * i / FREQ);
      in += val * val;
      out += double(chunk[i].Left()) * chunk[i].Left();
    }
    return 10 * std::log10(out / in);
  }

  void TestOutputStage()
  {
    const Module::FmOutput::Stage md1{3390, 1, 5, 100};
    const Module::FmOutput::Stage md2{2840, 2, 5, 100};
    const Module::FmOutput::Stage sbpro{8000, 1, 0, 100};
    // analog RC/Butterworth magnitudes, 1st order digital filter can't follow them exactly near Nyquist frequency
    const struct
    {
      const char* Name;
      Module::FmOutput::Stage Stage;
      double Freq;
      double Expected;
      double Tolerance;
    } CASES[] = {
        {"MD1 at cutoff", md1, 3390, -3.01, 0.05},      {"MD1 at 2 octaves above", md1, 13560, -12.30, 1.0},
        {"MD1 passband", md1, 200, -0.02, 0.05},        {"MD1 coupling", md1, 5, -3.01, 0.1},
        {"MD2 at cutoff", md2, 2840, -3.01, 0.05},      {"MD2 at octave above", md2, 5680, -12.30, 1.0},
        {"SB Pro at cutoff", sbpro, 8000, -3.01, 0.05}, {"SB Pro at 16kHz", sbpro, 16000, -6.99, 1.0},
    };
    for (const auto& c : CASES)
    {
      Module::FmOutput::Filter filter;
      filter.SetStage(c.Stage, FREQ);
      const auto measured = MeasureGain(c.Stage, c.Freq);
      const auto model = filter.GetResponse(c.Freq);
      std::printf("output stage %-24s %8.0fHz: measured %6.2fdB, response %6.2fdB, expected %6.2fdB\n", c.Name, c.Freq,
                  measured, model, c.Expected);
      Check(std::abs(measured - c.Expected) < c.Tolerance, "output stage gain");
    }
  }

  std::vector<uint8_t> ReadFile(const char* name)
  {
    std::ifstream f(name, std::ios::binary);
    return {std::istreambuf_iterator<char>(f), {}};
  }
}  // namespace FmCoresTest

int main(int argc, char** argv)
{
  using namespace FmCoresTest;
  const uint_t seconds = argc > 1 ? std::atoi(argv[1]) : 10;
  namespace OPN2 = FM::OPN2;
  const auto opn2Custom = [](const char* name, Parameters::IntType core, Parameters::IntType chip,
                             Parameters::IntType ladder) {
    return Config{name,
                  {{OPN2::CORE, core},
                   {OPN2::PRESET, FM::PRESET_CUSTOM},
                   {OPN2::CHIP, chip},
                   {OPN2::LADDER, ladder},
                   {OPN2::LOWPASS, 0},
                   {OPN2::HIGHPASS, 0}}};
  };
  const std::vector<Config> opn2 = {
      opn2Custom("GPGX", OPN2::CORE_GPGX, OPN2::CHIP_YM2612, 1),
      opn2Custom("Nuked YM2612", OPN2::CORE_NUKED, OPN2::CHIP_YM2612, 1),
      opn2Custom("Nuked YM2612 no ladder", OPN2::CORE_NUKED, OPN2::CHIP_YM2612, 0),
      opn2Custom("Nuked YM3438", OPN2::CORE_NUKED, OPN2::CHIP_YM3438, 0),
      opn2Custom("Nuked YMF276", OPN2::CORE_NUKED, OPN2::CHIP_YMF276, 0),
      opn2Custom("ymfm YM2612", OPN2::CORE_YMFM, OPN2::CHIP_YM2612, 1),
      opn2Custom("ymfm YM3438", OPN2::CORE_YMFM, OPN2::CHIP_YM3438, 0),
      opn2Custom("ymfm YMF276", OPN2::CORE_YMFM, OPN2::CHIP_YMF276, 0),
      {"default (MD1)", {}},
  };
  const std::vector<Config> opm = {{"Nuked", {{FM::OPM::CORE, FM::OPM::CORE_NUKED}}},
                                   {"MAME", {{FM::OPM::CORE, FM::OPM::CORE_MAME}}},
                                   {"default (ymfm)", {}}};
  const std::vector<Config> opl = {{"AdLibEmu", {{FM::OPL::CORE, FM::OPL::CORE_ADLIBEMU}}},
                                   {"MAME", {{FM::OPL::CORE, FM::OPL::CORE_MAME}}},
                                   {"default (Nuked)", {}}};
  const std::vector<Config> opll = {{"EMU2413", {{FM::OPLL::CORE, FM::OPLL::CORE_EMU2413}}},
                                    {"MAME", {{FM::OPLL::CORE, FM::OPLL::CORE_MAME}}},
                                    {"default (Nuked)", {}}};
  const std::vector<Config> opn = {{"MAME", {{FM::OPN::CORE, FM::OPN::CORE_MAME}}}, {"default (ymfm)", {}}};
  std::vector<Tune> tunes;
  for (int idx = 2; idx < argc; ++idx)
  {
    String name = argv[idx];
    name = name.substr(name.find_last_of('/') + 1);
    tunes.push_back({name, ReadFile(argv[idx]), opn2});
  }
  tunes.push_back({"YM2612", MakeOpn2(seconds), opn2});
  tunes.push_back({"YM2151", MakeOpm(seconds), opm});
  tunes.push_back({"YM3812", MakeOpl2(seconds), opl});
  tunes.push_back({"YMF262", MakeOpl3(seconds), opl});
  tunes.push_back({"YM2413", MakeOpll(seconds), opll});
  tunes.push_back({"YM2203", MakeOpn(seconds, false, false), opn});
  tunes.push_back({"YM2203+SSG", MakeOpn(seconds, false, true), opn});
  tunes.push_back({"YM2608", MakeOpn(seconds, true, false), opn});
  tunes.push_back({"YM2610", MakeOpnb(seconds), opn});

  TestOutputStage();
  const auto service = ZXTune::Service::Create(Parameters::Container::Create());
  for (const auto& tune : tunes)
  {
    for (const auto& cfg : tune.Configs)
    {
      Test(*service, tune, cfg, seconds);
    }
    // the last one is default, so switch to the first one with explicit settings
    TestLive(*service, tune, tune.Configs.back(), tune.Configs.front(), true);
  }
  {
    // output stage only
    const Config unfiltered{"YM3438 preset", {{OPN2::PRESET, OPN2::PRESET_YM3438}}};
    const Config mono{"custom mono 1kHz",
                      {{OPN2::PRESET, FM::PRESET_CUSTOM}, {OPN2::LOWPASS, 1000}, {OPN2::STEREO, 0}}};
    TestLive(*service, tunes.front(), unfiltered, mono, false);
  }
  std::printf("%u failures\n", Failures);
  return Failures != 0;
}
