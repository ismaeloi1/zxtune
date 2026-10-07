// Usage: module_test_vgm_voices <file> [seconds] [subpath, e.g. "#2" for multitrack]
// Host check: separate voices of libvgm-based tunes match the mixed output
#include "module/players/pipeline.h"
// test real scope implementation too
#include "binary/container_factories.h"
#include "core/core_parameters.h"
#include "core/service.h"
#include "module/holder.h"
#include "module/voices_scope.h"
#include "parameters/container.h"
#include "sound/scope.h"
#include "sound/sound_parameters.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>

namespace
{
  const uint_t FREQ = 48000;

  class TestScope : public Module::VoicesScope
  {
  public:
    void SetVoicesGroups(const std::vector<Module::VoicesGroup>& groups, bool hasRegisters) override
    {
      Names.clear();
      for (const auto& g : groups)
      {
        for (const auto& v : g.Voices)
        {
          Names.push_back(g.Name + " - " + v);
        }
      }
      std::printf("groups=%zu voices=%zu registers=%d\n", groups.size(), Names.size(), int(hasRegisters));
      Samples.assign(Names.size(), {});
      States.assign(Names.size(), {});
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

    void FeedVoices(uint_t firstVoice, const Module::VoiceState* states, uint_t count) override
    {
      for (uint_t idx = 0; idx < count; ++idx)
      {
        States[firstVoice + idx].push_back(states[idx]);
      }
    }

    void SetDescription(const String& description) override
    {
      std::printf("description: %s\n", description.c_str());
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

    std::vector<String> Names;
    std::vector<std::vector<int16_t>> Samples;
    std::vector<std::vector<Module::VoiceState>> States;
  };

  const char* NoteName(float freq)
  {
    static char buf[16];
    if (freq <= 0)
    {
      return "-";
    }
    static const char* NAMES[] = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    const int note = int(std::lround(12 * std::log2(freq / 440.0) + 57));
    std::snprintf(buf, sizeof(buf), "%s%d", NAMES[((note % 12) + 12) % 12], note / 12);
    return buf;
  }
}  // namespace

int main(int argc, char** argv)
{
  std::ifstream f(argv[1], std::ios::binary);
  std::vector<uint8_t> data((std::istreambuf_iterator<char>(f)), {});
  const uint_t seconds = argc > 2 ? std::atoi(argv[2]) : 20;
  const auto global = Parameters::Container::Create();
  global->SetValue(Parameters::ZXTune::Sound::FREQUENCY, FREQ);
  const auto service = ZXTune::Service::Create(global);
  const auto holder = service->OpenModule(Binary::CreateContainer(Binary::View(data.data(), data.size())),
                                          argc > 3 ? argv[3] : "", Parameters::Container::Create());
  std::vector<int32_t> mono;
  double plainMs = 0;
  {
    auto renderer = Module::CreatePipelinedRenderer(*holder, global);
    const auto start = std::chrono::steady_clock::now();
    while (mono.size() < seconds * FREQ)
    {
      const auto chunk = renderer->Render();
      if (chunk.empty())
      {
        break;
      }
      for (const auto& s : chunk)
      {
        mono.push_back(int(s.Left()) + s.Right());
      }
    }
    plainMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
  }
  auto renderer = Module::CreatePipelinedRenderer(*holder, global);
  auto scope = std::make_shared<TestScope>();
  auto* source = dynamic_cast<Module::VoicesScopeSource*>(renderer.get());
  if (!source || !source->SetVoicesScope(scope))
  {
    std::printf("voices are not supported\n");
    return 1;
  }
  std::vector<int32_t> mono2;
  const auto start = std::chrono::steady_clock::now();
  while (mono2.size() < mono.size())
  {
    const auto chunk = renderer->Render();
    if (chunk.empty())
    {
      break;
    }
    for (const auto& s : chunk)
    {
      mono2.push_back(int(s.Left()) + s.Right());
    }
  }
  const auto tapMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
  std::printf("%us rendered: plain %.0fms, with voices %.0fms\n", seconds, plainMs, tapMs);
  std::size_t diffs = 0;
  int maxDiff = 0;
  std::size_t firstDiff = 0;
  for (std::size_t i = 0; i < std::min(mono.size(), mono2.size()); ++i)
  {
    if (mono[i] != mono2[i] && !diffs++)
    {
      firstDiff = i;
    }
    maxDiff = std::max(maxDiff, std::abs(mono[i] - mono2[i]));
  }
  std::printf("output unchanged by tap: %s (%zu diffs, max %d, first at %zu)\n", diffs ? "NO" : "yes", diffs, maxDiff,
              firstDiff);

  const auto count = std::min(mono2.size(), scope->Samples.empty() ? 0 : scope->Samples[0].size());
  std::printf("voices samples %zu of %zu output\n", count, mono2.size());
  // correlation of voices sum with output, with small lag search (resampling latency)
  double best = -1;
  int bestLag = 0;
  for (int lag = -64; lag <= 64; ++lag)
  {
    double sxy = 0, sxx = 0, syy = 0;
    for (std::size_t i = 64; i + 64 < count; ++i)
    {
      double sum = 0;
      for (const auto& v : scope->Samples)
      {
        sum += v[i];
      }
      const double out = mono2[i + lag];
      sxy += sum * out;
      sxx += sum * sum;
      syy += out * out;
    }
    const auto corr = sxy / std::sqrt(sxx * syy + 1);
    if (corr > best)
    {
      best = corr;
      bestLag = lag;
    }
  }
  std::printf("correlation(sum of voices, output) = %.4f at lag %d samples\n", best, bestLag);
  for (std::size_t v = 0; v < scope->Names.size(); ++v)
  {
    const auto& smp = scope->Samples[v];
    double rms = 0;
    int peak = 0;
    for (const auto s : smp)
    {
      rms += double(s) * s;
      peak = std::max(peak, std::abs(int(s)));
    }
    rms = std::sqrt(rms / std::max<std::size_t>(smp.size(), 1));
    const auto& st = scope->States[v];
    std::size_t keyOn = 0, withFreq = 0;
    for (const auto& s : st)
    {
      keyOn += (s.Flags & Module::VoiceState::KEY_ON) != 0;
      withFreq += (s.Flags & Module::VoiceState::HAS_FREQUENCY) != 0;
    }
    std::printf("%-28s rms=%6.0f peak=%6d states=%zu keyon=%3.0f%% freq=%3.0f%%", scope->Names[v].c_str(), rms, peak,
                st.size(), 100.0 * keyOn / std::max<std::size_t>(st.size(), 1),
                100.0 * withFreq / std::max<std::size_t>(st.size(), 1));
    // a few samples of state
    for (std::size_t i = 2000; i < st.size(); i += 3000)
    {
      const auto& s = st[i];
      std::printf(" | %.0fHz %s %.0fdB%s", s.Frequency, NoteName(s.Frequency), s.Level,
                  s.Flags & Module::VoiceState::KEY_ON ? "*" : "");
    }
    std::printf("\n");
  }
  {
    // Sound::Scope gauges check
    auto r = Module::CreatePipelinedRenderer(*holder, global);
    auto sc = Sound::Scope::Create(FREQ);
    dynamic_cast<Module::VoicesScopeSource*>(r.get())->SetVoicesScope(sc);
    uint64_t played = 0;
    std::vector<int16_t> wave(32 * 512);
    sc->Get(32, 256, 0, 40, wave.data());  // activate
    while (played < FREQ * 2)
    {
      const auto chunk = r->Render();
      sc->Commit(chunk);
      sc->Played(played, chunk.size());
      played += chunk.size();
    }
    {
      // steady state cost of triggering, as at 120Hz display
      const auto start = std::chrono::steady_clock::now();
      const int frames = 120;
      for (int frame = 0; frame < frames; ++frame)
      {
        sc->Get(32, 512, played - FREQ + frame * FREQ / 120, 40, wave.data());
      }
      std::printf("trigger: %.2fms per frame\n",
                  std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count() / frames);
    }
    const auto layout = sc->Get(32, 256, played - FREQ / 10, 40, wave.data());
    std::printf("scope layout id=%u channels=%u:\n%s\n", layout.Id, layout.Channels, sc->GetLayout().c_str());
    std::vector<uint8_t> gauges(32 * Sound::Scope::VOICE_GAUGES_SIZE);
    for (int pass = 0; pass < 20; ++pass)
    {
      sc->GetVoiceGauges(32, played - FREQ / 10, 10, gauges.data());
    }
    const auto voices = sc->GetVoiceGauges(32, played - FREQ / 10, 10, gauges.data());
    for (uint_t v = 0; v < voices; ++v)
    {
      const auto* g = gauges.data() + v * Sound::Scope::VOICE_GAUGES_SIZE;
      auto col = [g](uint_t gauge, uint_t c) { return g + (gauge * Sound::Scope::GAUGE_COLUMNS + c) * 2; };
      const auto* st = g + Sound::Scope::VOICE_GAUGES_COUNT * Sound::Scope::GAUGE_COLUMNS * 2;
      float freq, level;
      std::memcpy(&freq, st, 4);
      std::memcpy(&level, st + 4, 4);
      std::printf(
          "voice %u: wave[255]=%d..%d level[255]=%d..%d freq[255]=%d..%d freq[100]=%d state=%.1fHz %.1fdB flags=%d\n",
          v, col(0, 255)[0], col(0, 255)[1], col(1, 255)[0], col(1, 255)[1], col(2, 255)[0], col(2, 255)[1],
          col(2, 100)[1], freq, level, st[8]);
    }
    std::printf("%s\n", sc->GetStatus().c_str());
  }
  return 0;
}
