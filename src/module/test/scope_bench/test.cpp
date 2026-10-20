// Usage: module_test_scope_bench <file> [seconds] [key=value ...]
// Host benchmark of Sound::Scope as used by oscilloscope and dashboard views: cost of triggering and gauges
// requests, and stability of triggered traces.
// Keys:
//   subpath=#2      multitrack subpath
//   fps=120         display refresh rate
//   latency=320     output latency in ms (data rendered ahead of heard position)
//   window=40       displayed window in ms
//   points=0        points per channel, 0 for each sample of window (no resampling)
//   gauges=1        request gauges at each frame as dashboard does
//   threaded=0      render and requests in separate threads in real time, to catch locks contention
//   dump=<file>     traces of each frame as int16 [frames][channels][points]
//   voices=<prefix> raw samples of each voice as int16 mono <prefix>.<voice>.raw (e.g. for reference triggers)
//   slow=<us>       print frames with triggering slower than specified
//   <name>=<value>  trigger parameter zxtune.sound.scope.trigger.<name> (e.g. edge_strength=100)
// Stability: for each pair of consecutive frames the best horizontal alignment of the same channel's traces is
// searched. Perfectly triggered periodic wave is displayed at the same place, so shift is zero. Pairs of
// dissimilar traces (new notes, evolving timbre) are not counted.
#include "module/players/pipeline.h"

#include "binary/container_factories.h"
#include "core/service.h"
#include "module/holder.h"
#include "module/voices_scope.h"
#include "parameters/container.h"
#include "sound/scope.h"
#include "sound/sound_parameters.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <map>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace
{
  const uint_t FREQ = 48000;
  const uint_t MAX_CHANNELS = 32;

  using Clock = std::chrono::steady_clock;

  class Timings
  {
  public:
    void Add(Clock::duration dur)
    {
      Values.push_back(std::chrono::duration<double, std::micro>(dur).count());
    }

    void Print(const char* name) const
    {
      if (Values.empty())
      {
        std::printf("%-8s: no calls\n", name);
        return;
      }
      auto sorted = Values;
      std::sort(sorted.begin(), sorted.end());
      double sum = 0;
      for (auto v : sorted)
      {
        sum += v;
      }
      std::printf("%-8s: avg %7.1fus  p50 %7.1fus  p99 %7.1fus  max %7.1fus  (%zu calls)\n", name, sum / sorted.size(),
                  sorted[sorted.size() / 2], sorted[sorted.size() * 99 / 100], sorted.back(), sorted.size());
    }

  private:
    std::vector<double> Values;
  };

  class Stability
  {
  public:
    explicit Stability(double samplesPerPoint)
      : SamplesPerPoint(samplesPerPoint)
    {}

    void Add(uint_t channels, uint_t points, const int16_t* traces)
    {
      if (Prev.size() == std::size_t(channels) * points && PrevChannels == channels)
      {
        Stats.resize(channels);
        for (uint_t chan = 0; chan < channels; ++chan)
        {
          Compare(Prev.data() + chan * points, traces + chan * points, points, Stats[chan]);
        }
      }
      Prev.assign(traces, traces + std::size_t(channels) * points);
      PrevChannels = channels;
    }

    void Print(const std::vector<std::string>& names) const
    {
      double totalShift = 0;
      std::size_t totalPairs = 0;
      std::size_t totalJumps = 0;
      std::size_t totalBigJumps = 0;
      std::size_t totalFrames = 0;
      for (std::size_t chan = 0; chan < Stats.size(); ++chan)
      {
        const auto& st = Stats[chan];
        const auto name = chan < names.size() ? names[chan] : std::to_string(chan);
        std::printf("  %-24s similar pairs %5zu/%5zu  mean shift %6.2f smp  jumps >=2smp %5.1f%% >=8smp %5.1f%%\n",
                    name.c_str(), st.Pairs, st.Frames, st.Pairs ? st.Shift / st.Pairs : 0.0,
                    st.Pairs ? 100.0 * st.Jumps / st.Pairs : 0.0, st.Pairs ? 100.0 * st.BigJumps / st.Pairs : 0.0);
        totalShift += st.Shift;
        totalPairs += st.Pairs;
        totalJumps += st.Jumps;
        totalBigJumps += st.BigJumps;
        totalFrames += st.Frames;
      }
      std::printf("stability: similar pairs %zu/%zu, mean shift %.3f smp, jumps >=2smp %.2f%% >=8smp %.2f%%\n",
                  totalPairs, totalFrames, totalPairs ? totalShift / totalPairs : 0.0,
                  totalPairs ? 100.0 * totalJumps / totalPairs : 0.0,
                  totalPairs ? 100.0 * totalBigJumps / totalPairs : 0.0);
    }

  private:
    struct Channel
    {
      std::size_t Frames = 0;
      std::size_t Pairs = 0;
      std::size_t Jumps = 0;
      std::size_t BigJumps = 0;
      double Shift = 0;
    };

    void Compare(const int16_t* prev, const int16_t* cur, uint_t points, Channel& st) const
    {
      const auto [lo, hi] = std::minmax_element(cur, cur + points);
      if (*hi - *lo < 256)
      {
        // silence
        return;
      }
      ++st.Frames;
      const int maxLag = static_cast<int>(points / 4);
      const int from = maxLag;
      const int to = static_cast<int>(points) - maxLag;
      double energy = 0;
      for (int idx = from; idx < to; ++idx)
      {
        energy += double(cur[idx]) * cur[idx] + double(prev[idx]) * prev[idx];
      }
      // periodic wave matches itself at each period, so only shifts up to half period are distinguishable
      const auto period = FindPeriod(cur, from, to, maxLag, energy / 2);
      const int range = period ? period / 2 : maxLag;
      double best = -1;
      double bestNear = -1;
      int bestLag = 0;
      for (int lag = -maxLag; lag <= maxLag; ++lag)
      {
        double err = 0;
        for (int idx = from; idx < to; ++idx)
        {
          const double d = double(cur[idx]) - prev[idx + lag];
          err += d * d;
        }
        best = best < 0 ? err : std::min(best, err);
        if (std::abs(lag) <= range
            && (bestNear < 0 || err < bestNear || (err == bestNear && std::abs(lag) < std::abs(bestLag))))
        {
          bestNear = err;
          bestLag = lag;
        }
      }
      // relative error of aligned traces, the same waveform should match well somewhere
      if (energy == 0 || best / energy > 0.1)
      {
        return;
      }
      const auto shift = std::abs(bestLag) * SamplesPerPoint;
      ++st.Pairs;
      st.Shift += shift;
      st.Jumps += shift >= 2;
      st.BigJumps += shift >= 8;
    }

    // @return the first lag of good self similarity, 0 if not found
    static int FindPeriod(const int16_t* trace, int from, int to, int maxLag, double energy)
    {
      double prevErr = -1;
      bool descending = false;
      for (int lag = 2; lag <= maxLag; ++lag)
      {
        double err = 0;
        for (int idx = from; idx < to; ++idx)
        {
          const double d = double(trace[idx]) - trace[idx - lag];
          err += d * d;
        }
        if (prevErr >= 0 && descending && err > prevErr && prevErr < energy * 0.2)
        {
          return lag - 1;
        }
        descending = prevErr < 0 || err <= prevErr;
        prevErr = err;
      }
      return 0;
    }

  private:
    const double SamplesPerPoint;
    std::vector<int16_t> Prev;
    uint_t PrevChannels = 0;
    std::vector<Channel> Stats;
  };

  std::vector<std::string> ParseNames(const String& layout)
  {
    std::vector<std::string> result;
    std::size_t pos = layout.find('\n');
    while (pos != String::npos)
    {
      const auto next = layout.find('\n', pos + 1);
      const auto line = layout.substr(pos + 1, next == String::npos ? String::npos : next - pos - 1);
      const auto tab = line.find('\t');
      const auto group = line.substr(0, tab);
      for (auto start = tab; start != String::npos;)
      {
        const auto end = line.find('\t', start + 1);
        result.push_back(group + " " + line.substr(start + 1, end == String::npos ? String::npos : end - start - 1));
        start = end;
      }
      pos = next;
    }
    return result;
  }

  struct Options
  {
    String Subpath;
    uint_t Fps = 120;
    uint_t LatencyMs = 320;
    uint_t WindowMs = 40;
    uint_t Points = 0;
    bool Gauges = true;
    bool Threaded = false;
    uint_t SlowUs = 0;
    std::string Dump;
    std::string Voices;
    std::map<std::string, Parameters::IntType> Trigger;
  };

  class Requests
  {
  public:
    Requests(Sound::Scope& scope, const Options& opts)
      : Scope(scope)
      , Opts(opts)
      , WindowSamples(FREQ * opts.WindowMs / 1000)
      , Points(opts.Points ? opts.Points : WindowSamples)
      , Traces(MAX_CHANNELS * Points)
      , Gauges(MAX_CHANNELS * Sound::Scope::VOICE_GAUGES_SIZE)
      , Stable(double(WindowSamples) / Points)
    {
      if (!opts.Dump.empty())
      {
        Dump.open(opts.Dump, std::ios::binary);
      }
    }

    void Frame(int64_t playing)
    {
      const auto start = Clock::now();
      const auto layout = Scope.Get(MAX_CHANNELS, Points, playing, Opts.WindowMs, Traces.data());
      const auto triggered = Clock::now();
      Trigger.Add(triggered - start);
      if (Opts.SlowUs && triggered - start > std::chrono::microseconds(Opts.SlowUs))
      {
        std::printf(
            "slow frame %u at %lld: %lldus\n", FrameIndex, static_cast<long long>(playing),
            static_cast<long long>(std::chrono::duration_cast<std::chrono::microseconds>(triggered - start).count()));
      }
      ++FrameIndex;
      if (Opts.Gauges)
      {
        if (Layout.empty() || layout.Id != LayoutId)
        {
          Layout = Scope.GetLayout();
          LayoutId = layout.Id;
        }
        const auto gaugesStart = Clock::now();
        if (Layout.starts_with("registers"))
        {
          Scope.GetGauges(3, playing, 10, Gauges.data());
        }
        else if (Layout.starts_with("audio"))
        {
          Scope.GetVoiceGauges(MAX_CHANNELS, playing, 10, Gauges.data());
        }
        GaugesTime.Add(Clock::now() - gaugesStart);
      }
      if (layout.Channels)
      {
        Channels = layout.Channels;
        Stable.Add(layout.Channels, Points, Traces.data());
        if (Dump.is_open())
        {
          Dump.write(reinterpret_cast<const char*>(Traces.data()), layout.Channels * Points * sizeof(int16_t));
        }
      }
    }

    void Print() const
    {
      std::printf("channels %u, %u points per %ums window\n", Channels, Points, Opts.WindowMs);
      Trigger.Print("trigger");
      if (Opts.Gauges)
      {
        GaugesTime.Print("gauges");
      }
      Stable.Print(ParseNames(Scope.GetLayout()));
      std::printf("%s\n", Scope.GetStatus().c_str());
    }

  private:
    Sound::Scope& Scope;
    const Options& Opts;
    const uint_t WindowSamples;
    const uint_t Points;
    std::vector<int16_t> Traces;
    std::vector<uint8_t> Gauges;
    String Layout;
    uint_t LayoutId = 0;
    uint_t Channels = 0;
    uint_t FrameIndex = 0;
    Timings Trigger;
    Timings GaugesTime;
    Stability Stable;
    std::ofstream Dump;
  };

  // Passes voices to scope and stores them as is
  class VoicesDump : public Module::VoicesScope
  {
  public:
    VoicesDump(Module::VoicesScope::Ptr delegate, std::string prefix)
      : Delegate(std::move(delegate))
      , Prefix(std::move(prefix))
    {}

    ~VoicesDump() override
    {
      for (std::size_t idx = 0; idx < Samples.size(); ++idx)
      {
        std::ofstream out(Prefix + "." + std::to_string(idx) + ".raw", std::ios::binary);
        out.write(reinterpret_cast<const char*>(Samples[idx].data()), Samples[idx].size() * sizeof(int16_t));
      }
    }

    void SetVoicesGroups(const std::vector<Module::VoicesGroup>& groups, bool hasRegisters) override
    {
      Delegate->SetVoicesGroups(groups, hasRegisters);
    }

    bool IsActive() const override
    {
      return Delegate->IsActive();
    }

    uint_t GetStatePeriod() const override
    {
      return Delegate->GetStatePeriod();
    }

    void FeedState(uint_t chip, const uint8_t* registers, const uint8_t* osc, const uint8_t* env) override
    {
      Delegate->FeedState(chip, registers, osc, env);
    }

    void FeedChip(uint_t chip, const uint8_t* data, uint_t count) override
    {
      Delegate->FeedChip(chip, data, count);
    }

    void FeedVoices(uint_t firstVoice, const Module::VoiceState* states, uint_t count) override
    {
      Delegate->FeedVoices(firstVoice, states, count);
    }

    void SetDescription(const String& description) override
    {
      Delegate->SetDescription(description);
    }

    void Feed(uint_t firstVoice, uint_t voices, const int16_t* samples, uint_t count) override
    {
      Delegate->Feed(firstVoice, voices, samples, count);
      if (Samples.size() < firstVoice + voices)
      {
        Samples.resize(firstVoice + voices);
      }
      for (uint_t voice = 0; voice < voices; ++voice)
      {
        auto& target = Samples[firstVoice + voice];
        for (uint_t idx = 0; idx < count; ++idx)
        {
          target.push_back(samples[idx * voices + voice]);
        }
      }
    }

  private:
    const Module::VoicesScope::Ptr Delegate;
    const std::string Prefix;
    std::vector<std::vector<int16_t>> Samples;
  };

  void RunDeterministic(Module::Renderer& renderer, Sound::Scope& scope, Requests& requests, const Options& opts,
                        uint_t seconds)
  {
    const auto latency = int64_t(FREQ) * opts.LatencyMs / 1000;
    const double step = double(FREQ) / opts.Fps;
    uint64_t played = 0;
    double frameTime = 0;
    while (played < uint64_t(seconds) * FREQ)
    {
      const auto chunk = renderer.Render();
      if (chunk.empty())
      {
        break;
      }
      scope.Commit(chunk);
      scope.Played(played, static_cast<uint_t>(chunk.size()));
      played += chunk.size();
      while (frameTime + latency <= double(played))
      {
        requests.Frame(int64_t(frameTime));
        frameTime += step;
      }
    }
  }

  // Real time playback: render thread keeps data ahead of heard position, requests are made at display rate
  void RunThreaded(Module::Renderer& renderer, Sound::Scope& scope, Requests& requests, const Options& opts,
                   uint_t seconds)
  {
    const auto latency = int64_t(FREQ) * opts.LatencyMs / 1000;
    const auto start = Clock::now();
    const auto heard = [start]() {
      return std::chrono::duration_cast<std::chrono::microseconds>(Clock::now() - start).count() * FREQ / 1000000;
    };
    std::atomic<bool> done = false;
    std::thread render([&]() {
      uint64_t played = 0;
      while (!done)
      {
        if (int64_t(played) > heard() + latency)
        {
          std::this_thread::sleep_for(std::chrono::milliseconds(1));
          continue;
        }
        const auto chunk = renderer.Render();
        if (chunk.empty())
        {
          break;
        }
        scope.Commit(chunk);
        scope.Played(played, static_cast<uint_t>(chunk.size()));
        played += chunk.size();
      }
    });
    const auto period = std::chrono::microseconds(1000000 / opts.Fps);
    auto next = start + std::chrono::milliseconds(opts.LatencyMs);
    while (Clock::now() - start < std::chrono::seconds(seconds))
    {
      std::this_thread::sleep_until(next);
      next += period;
      requests.Frame(heard());
    }
    done = true;
    render.join();
  }
}  // namespace

int main(int argc, char** argv)
{
  if (argc < 2)
  {
    std::printf("Usage: %s <file> [seconds] [key=value ...]\n", argv[0]);
    return 1;
  }
  std::ifstream f(argv[1], std::ios::binary);
  std::vector<uint8_t> data((std::istreambuf_iterator<char>(f)), {});
  const uint_t seconds = argc > 2 ? std::atoi(argv[2]) : 20;
  Options opts;
  for (int arg = 3; arg < argc; ++arg)
  {
    const std::string kv = argv[arg];
    const auto eq = kv.find('=');
    if (eq == std::string::npos)
    {
      continue;
    }
    const auto key = kv.substr(0, eq);
    const auto value = kv.substr(eq + 1);
    if (key == "subpath")
    {
      opts.Subpath = value;
    }
    else if (key == "fps")
    {
      opts.Fps = std::atoi(value.c_str());
    }
    else if (key == "latency")
    {
      opts.LatencyMs = std::atoi(value.c_str());
    }
    else if (key == "window")
    {
      opts.WindowMs = std::atoi(value.c_str());
    }
    else if (key == "points")
    {
      opts.Points = std::atoi(value.c_str());
    }
    else if (key == "gauges")
    {
      opts.Gauges = std::atoi(value.c_str()) != 0;
    }
    else if (key == "threaded")
    {
      opts.Threaded = std::atoi(value.c_str()) != 0;
    }
    else if (key == "slow")
    {
      opts.SlowUs = std::atoi(value.c_str());
    }
    else if (key == "dump")
    {
      opts.Dump = value;
    }
    else if (key == "voices")
    {
      opts.Voices = value;
    }
    else
    {
      opts.Trigger[key] = std::atoll(value.c_str());
    }
  }
  const auto global = Parameters::Container::Create();
  global->SetValue(Parameters::ZXTune::Sound::FREQUENCY, FREQ);
  // names should live while container is used
  std::vector<std::string> names;
  names.reserve(opts.Trigger.size());
  for (const auto& [key, value] : opts.Trigger)
  {
    // full names are passed as is (e.g. zxtune.sound.scope.max_periods), short ones are trigger parameters
    names.push_back(key.rfind("zxtune.", 0) == 0 ? key : "zxtune.sound.scope.trigger." + key);
    global->SetValue(Parameters::Identifier(names.back()), value);
  }
  const auto service = ZXTune::Service::Create(global);
  const auto holder = service->OpenModule(Binary::CreateContainer(Binary::View(data.data(), data.size())), opts.Subpath,
                                          Parameters::Container::Create());
  auto renderer = Module::CreatePipelinedRenderer(*holder, global);
  const auto scope = Sound::Scope::Create(FREQ, global);
  std::shared_ptr<VoicesDump> voicesDump;
  if (auto* voices = dynamic_cast<Module::VoicesScopeSource*>(renderer.get()))
  {
    if (opts.Voices.empty())
    {
      voices->SetVoicesScope(scope);
    }
    else
    {
      voicesDump = std::make_shared<VoicesDump>(scope, opts.Voices);
      voices->SetVoicesScope(voicesDump);
    }
  }
  Requests requests(*scope, opts);
  // separate voices are rendered only for active scope
  std::vector<int16_t> dummy(MAX_CHANNELS * 16);
  scope->Get(MAX_CHANNELS, 16, 0, 40, dummy.data());
  if (opts.Threaded)
  {
    RunThreaded(*renderer, *scope, requests, opts, seconds);
  }
  else
  {
    RunDeterministic(*renderer, *scope, requests, opts, seconds);
  }
  requests.Print();
  return 0;
}
