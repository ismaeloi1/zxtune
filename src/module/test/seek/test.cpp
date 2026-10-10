// Usage: module_test_seek <file> [subpath, e.g. "#2" for multitrack] [max seconds]
// Host check: seeking speed and output after seeking compared to seeking without emulation state snapshots
#include "binary/container_factories.h"
#include "core/core_parameters.h"
#include "core/service.h"
#include "module/holder.h"
#include "parameters/container.h"
#include "parameters/merged_accessor.h"
#include "sound/sound_parameters.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <vector>

namespace
{
  const uint_t FREQ = 48000;

  using Clock = std::chrono::steady_clock;

  double Since(Clock::time_point start)
  {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
  }

  class Player
  {
  public:
    Player(const Module::Holder& holder, Parameters::IntType snapshotsMemory)
    {
      auto params = Parameters::Container::Create();
      params->SetValue(Parameters::ZXTune::Core::SEEK_SNAPSHOTS_MEMORY, snapshotsMemory);
      Delegate = holder.CreateRenderer(FREQ, Parameters::CreateMergedAccessor(params, holder.GetModuleProperties()));
    }

    std::vector<Sound::Sample> Render(uint_t ms)
    {
      std::vector<Sound::Sample> res;
      const auto samples = uint64_t(ms) * FREQ / 1000;
      while (res.size() < samples)
      {
        const auto chunk = Delegate->Render();
        if (chunk.empty())
        {
          break;
        }
        res.insert(res.end(), chunk.begin(), chunk.end());
      }
      return res;
    }

    void PlayTill(uint_t ms)
    {
      while (At() < ms && !Delegate->Render().empty())
      {}
    }

    double Seek(uint_t ms)
    {
      const auto start = Clock::now();
      Delegate->SetPosition(Time::AtMillisecond(ms));
      return Since(start);
    }

    uint_t At() const
    {
      return Delegate->GetState().At.Get();
    }

  private:
    Module::Renderer::Ptr Delegate;
  };

  struct Diff
  {
    std::size_t Count = 0;
    std::size_t First = 0;
    std::size_t Size = 0;
  };

  Diff Compare(const std::vector<Sound::Sample>& lh, const std::vector<Sound::Sample>& rh)
  {
    Diff res;
    res.Size = std::min(lh.size(), rh.size());
    for (std::size_t i = 0; i < res.Size; ++i)
    {
      if (!(lh[i] == rh[i]) && !res.Count++)
      {
        res.First = i;
      }
    }
    if (lh.size() != rh.size())
    {
      res.Count += std::max(lh.size(), rh.size()) - res.Size;
    }
    return res;
  }

  void Report(const char* name, const Diff& d)
  {
    if (d.Count)
    {
      std::printf("  %s: DIFFERS (%zu of %zu samples, first at %zu)\n", name, d.Count, d.Size, d.First);
    }
    else
    {
      std::printf("  %s: identical (%zu samples)\n", name, d.Size);
    }
  }
}  // namespace

int main(int argc, char** argv)
{
  if (argc < 2)
  {
    std::printf("Usage: %s <file> [subpath] [max seconds]\n", argv[0]);
    return 2;
  }
  std::ifstream f(argv[1], std::ios::binary);
  std::vector<uint8_t> data((std::istreambuf_iterator<char>(f)), {});
  if (data.empty())
  {
    std::printf("Failed to read %s\n", argv[1]);
    return 2;
  }
  const auto global = Parameters::Container::Create();
  global->SetValue(Parameters::ZXTune::Sound::FREQUENCY, FREQ);
  const auto service = ZXTune::Service::Create(global);
  const auto holder = service->OpenModule(Binary::CreateContainer(Binary::View(data.data(), data.size())),
                                          argc > 2 ? argv[2] : "", Parameters::Container::Create());
  const uint_t maxSeconds = argc > 3 ? std::atoi(argv[3]) : 180;
  const uint_t duration = holder->GetModuleInformation().Duration.Get();
  const uint_t len = std::min<uint_t>(duration, maxSeconds * 1000);
  std::printf("%s: duration %ums, testing %ums\n", argv[1], duration, len);
  const uint_t probe = std::min<uint_t>(1000, len / 20);

  // forward seek from current position vs from the beginning
  {
    Player fresh(*holder, 0);
    const auto restart = fresh.Seek(len / 2);
    Player playing(*holder, 0);
    playing.Render(len / 2 - 5000 > 0 && len > 10000 ? len / 2 - 5000 : len / 4);
    const auto from = playing.At();
    const auto forward = playing.Seek(len / 2);
    std::printf("forward seek to %ums: from 0 %.2fms, from %ums %.2fms\n", len / 2, restart, from, forward);
  }

  Player ref(*holder, 0);
  Player test(*holder, Parameters::ZXTune::Core::SEEK_SNAPSHOTS_MEMORY_DEFAULT);
  Player cont(*holder, 0);
  {
    auto start = Clock::now();
    const auto played = ref.Render(len * 9 / 10);
    const auto refMs = Since(start);
    start = Clock::now();
    const auto played2 = test.Render(len * 9 / 10);
    const auto testMs = Since(start);
    std::printf("play %ums: %.0fms (with snapshots %.0fms)\n", len * 9 / 10, refMs, testMs);
    Report("played", Compare(played, played2));
  }
  bool ok = true;
  // backward, forward to snapshots range, further backward, forward of snapshots range
  const uint_t positions[] = {len * 3 / 10, len * 6 / 10, len / 10, len * 85 / 100, 0, len * 95 / 100, len / 2};
  for (auto pos : positions)
  {
    // aligned to frames to compare with continuous playback
    pos -= pos % 100;
    const auto refFrom = ref.At();
    const auto refMs = ref.Seek(pos);
    const auto testMs = test.Seek(pos);
    std::printf("seek %ums -> %ums: %.2fms, with snapshots %.2fms\n", refFrom, pos, refMs, testMs);
    const auto refOut = ref.Render(probe);
    const auto testOut = test.Render(probe);
    const auto d = Compare(refOut, testOut);
    Report("after seek", d);
    ok = ok && d.Count == 0;
    if (pos < cont.At())
    {
      cont = Player(*holder, 0);
    }
    cont.PlayTill(pos);
    const auto contOut = cont.Render(probe);
    Report("vs continuous playback", Compare(contOut, testOut));
  }
  std::printf("RESULT: %s\n", ok ? "OK" : "FAIL");
  return ok ? 0 : 1;
}
