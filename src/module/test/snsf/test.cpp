// Usage: module_test_snsf <file> [seconds] [out.wav] [frequency]
// Host check: renders SNSF (resolving _lib files from the same directory) with voices scope attached,
// reports rendering speed relative to realtime and optionally writes 16-bit stereo WAV
#include "module/players/pipeline.h"

#include "binary/container_factories.h"
#include "core/additional_files_resolve.h"
#include "core/service.h"
#include "module/additional_files.h"
#include "module/holder.h"
#include "module/voices_scope.h"
#include "parameters/container.h"
#include "sound/sound_parameters.h"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace
{
  std::vector<uint8_t> ReadFile(const std::string& name)
  {
    std::ifstream f(name, std::ios::binary);
    return {std::istreambuf_iterator<char>(f), {}};
  }

  class DirSource : public Module::AdditionalFilesSource
  {
  public:
    explicit DirSource(std::string dir)
      : Dir(std::move(dir))
    {}

    Binary::Container::Ptr Get(StringView name) const override
    {
      auto data = ReadFile(Dir + String(name));
      return Binary::CreateContainer(Binary::View(data.data(), data.size()));
    }

  private:
    const std::string Dir;
  };

  class CountingScope : public Module::VoicesScope
  {
  public:
    void SetVoicesGroups(const std::vector<Module::VoicesGroup>& groups, bool) override
    {
      Voices = groups.empty() ? 0 : groups.front().Voices.size();
    }
    bool IsActive() const override
    {
      return true;
    }
    uint_t GetStatePeriod() const override
    {
      return 48;
    }
    void FeedState(uint_t, const uint8_t*, const uint8_t*, const uint8_t*) override {}
    void FeedChip(uint_t, const uint8_t*, uint_t) override {}
    void FeedVoices(uint_t, const Module::VoiceState* states, uint_t count) override
    {
      for (uint_t idx = 0; idx < count; ++idx)
      {
        KeyOn += (states[idx].Flags & Module::VoiceState::KEY_ON) != 0;
      }
    }
    void SetDescription(const String&) override {}
    void Feed(uint_t, uint_t voices, const int16_t* samples, uint_t count) override
    {
      for (uint_t idx = 0; idx < count * voices; ++idx)
      {
        NonZero += samples[idx] != 0;
      }
    }

    std::size_t Voices = 0;
    std::size_t KeyOn = 0;
    std::size_t NonZero = 0;
  };

  void WriteWav(const std::string& name, const std::vector<int16_t>& data, uint_t freq)
  {
    std::ofstream f(name, std::ios::binary);
    const auto put = [&f](uint32_t val, int size) { f.write(reinterpret_cast<const char*>(&val), size); };
    const uint32_t bytes = data.size() * 2;
    f.write("RIFF", 4);
    put(36 + bytes, 4);
    f.write("WAVEfmt ", 8);
    put(16, 4);
    put(1, 2);
    put(2, 2);
    put(freq, 4);
    put(freq * 4, 4);
    put(4, 2);
    put(16, 2);
    f.write("data", 4);
    put(bytes, 4);
    f.write(reinterpret_cast<const char*>(data.data()), bytes);
  }
}  // namespace

int main(int argc, char** argv)
{
  if (argc < 2)
  {
    std::puts("Usage: module_test_snsf <file> [seconds] [out.wav] [frequency]");
    return 1;
  }
  const std::string path = argv[1];
  const uint_t seconds = argc > 2 ? std::atoi(argv[2]) : 30;
  const uint_t freq = argc > 4 ? std::atoi(argv[4]) : 48000;
  const auto global = Parameters::Container::Create();
  global->SetValue(Parameters::ZXTune::Sound::FREQUENCY, freq);
  const auto service = ZXTune::Service::Create(global);
  auto data = ReadFile(path);
  const auto holder = service->OpenModule(Binary::CreateContainer(Binary::View(data.data(), data.size())), "",
                                          Parameters::Container::Create());
  if (const auto* files = dynamic_cast<const Module::AdditionalFiles*>(holder.get()))
  {
    const auto slash = path.find_last_of('/');
    Module::ResolveAdditionalFiles(DirSource(slash == std::string::npos ? "" : path.substr(0, slash + 1)), *files);
  }
  const auto scope = std::make_shared<CountingScope>();
  auto renderer = Module::CreatePipelinedRenderer(*holder, global);
  auto* const source = dynamic_cast<Module::VoicesScopeSource*>(renderer.get());
  const bool withScope = source && std::getenv("NOSCOPE") == nullptr && source->SetVoicesScope(scope);
  std::vector<int16_t> out;
  const auto start = std::chrono::steady_clock::now();
  while (out.size() < std::size_t(seconds) * freq * 2)
  {
    const auto chunk = renderer->Render();
    if (chunk.empty())
    {
      break;
    }
    for (const auto& smp : chunk)
    {
      out.push_back(static_cast<int16_t>(smp.Left()));
      out.push_back(static_cast<int16_t>(smp.Right()));
    }
  }
  const double elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
  const double rendered = double(out.size() / 2) / freq;
  std::size_t nonZero = 0;
  for (auto s : out)
  {
    nonZero += s != 0;
  }
  std::printf("rendered %.1fs in %.2fs (x%.1f realtime), nonzero=%.3f scope=%d voices=%zu keyon=%zu nonzero=%zu\n",
              rendered, elapsed, rendered / elapsed, out.empty() ? 0.0 : double(nonZero) / out.size(), int(withScope),
              scope->Voices, scope->KeyOn, scope->NonZero);
  if (argc > 3)
  {
    WriteWav(argv[3], out, freq);
  }
  return 0;
}
