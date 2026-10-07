// Usage: module_test_engine_params <samples/chiptunes dir>
// Host check: chips emulation and playback parameters are applied in realtime.
// Each parameter is switched A->B in the middle of playback and the rest is compared with references rendered with A
// and B from the very beginning: by spectrum for timbre-related parameters and by level for volume-related ones.
#include "module/players/pipeline.h"

#include "binary/container_factories.h"
#include "core/core_parameters.h"
#include "core/service.h"
#include "module/holder.h"
#include "parameters/container.h"
#include "sound/sound_parameters.h"

#include "error.h"

#include <cmath>
#include <complex>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>

namespace
{
  const uint_t FREQ = 48000;
  const uint_t SECONDS = 2;

  using Samples = std::vector<int16_t>;

  Samples Render(Module::Renderer& r, uint_t seconds)
  {
    Samples res;
    while (res.size() < seconds * FREQ)
    {
      const auto chunk = r.Render();
      if (chunk.empty())
      {
        break;
      }
      for (const auto& s : chunk)
      {
        res.push_back(static_cast<int16_t>(s.Left()));
      }
    }
    res.resize(seconds * FREQ);
    return res;
  }

  // log-magnitude spectrum in octave fractions bands, insensitive to phase
  std::vector<double> Spectrum(const Samples& samples)
  {
    const std::size_t n = 65536;
    std::vector<std::complex<double>> data(n);
    for (std::size_t i = 0; i < n && i < samples.size(); ++i)
    {
      data[i] = samples[i] * (0.5 - 0.5 * std::cos(2 * M_PI * i / n));
    }
    for (std::size_t i = 1, j = 0; i < n; ++i)
    {
      std::size_t bit = n >> 1;
      for (; j & bit; bit >>= 1)
      {
        j ^= bit;
      }
      j ^= bit;
      if (i < j)
      {
        std::swap(data[i], data[j]);
      }
    }
    for (std::size_t len = 2; len <= n; len <<= 1)
    {
      const auto w = std::polar(1.0, -2 * M_PI / len);
      for (std::size_t i = 0; i < n; i += len)
      {
        std::complex<double> wk = 1;
        for (std::size_t k = 0; k < len / 2; ++k, wk *= w)
        {
          const auto u = data[i + k];
          const auto v = data[i + k + len / 2] * wk;
          data[i + k] = u + v;
          data[i + k + len / 2] = u - v;
        }
      }
    }
    std::vector<double> bands(96, 1e-3);
    for (std::size_t i = 1; i < n / 2; ++i)
    {
      const double freq = double(i) * FREQ / n;
      if (freq < 30)
      {
        continue;
      }
      const auto band = std::min<std::size_t>(95, std::size_t(std::log2(freq / 30) * 9.6));
      bands[band] += std::norm(data[i]);
    }
    for (auto& b : bands)
    {
      b = 10 * std::log10(b);
    }
    return bands;
  }

  double SpecDist(const Samples& a, const Samples& b)
  {
    const auto sa = Spectrum(a);
    const auto sb = Spectrum(b);
    double sum = 0;
    for (std::size_t i = 0; i < sa.size(); ++i)
    {
      sum += std::abs(sa[i] - sb[i]);
    }
    return sum / sa.size();
  }

  // level of the last quarter in dB, to catch fading at the end
  double Level(const Samples& s)
  {
    double sum = 1;
    for (std::size_t i = s.size() * 3 / 4; i < s.size(); ++i)
    {
      sum += double(s[i]) * s[i];
    }
    return 10 * std::log10(sum / (s.size() / 4));
  }

  double LevelDist(const Samples& a, const Samples& b)
  {
    return std::abs(Level(a) - Level(b));
  }

  using Setting = std::pair<Parameters::Identifier, Parameters::IntType>;

  struct Case
  {
    const char* File;
    const char* Name;
    Parameters::Identifier Param;
    Parameters::IntType A, B;
    std::vector<Setting> Context;
    // compare by level instead of spectrum
    bool ByLevel = false;
    // play the end of track instead of the beginning
    bool AtEnd = false;
  };

  Module::Holder::Ptr Open(const std::string& path)
  {
    std::ifstream f(path, std::ios::binary);
    std::vector<uint8_t> data((std::istreambuf_iterator<char>(f)), {});
    const auto global = Parameters::Container::Create();
    const auto service = ZXTune::Service::Create(global);
    return service->OpenModule(Binary::CreateContainer(Binary::View(data.data(), data.size())), "",
                               Parameters::Container::Create());
  }
}  // namespace

int main(int argc, char** argv)
{
  if (argc < 2)
  {
    std::printf("Usage: %s <samples/chiptunes dir>\n", argv[0]);
    return 1;
  }
  const std::string root = argv[1];
  namespace Core = Parameters::ZXTune::Core;
  namespace SoundParams = Parameters::ZXTune::Sound;
  const char* const PT3 = "AY-3-8910/pt3/Speccy2.pt3";
  const char* const AY = "AY-3-8910/ay/AYMD39.ay";
  const char* const BEEPER = "AY-3-8910/ay/1bit_mod.ay";
  const char* const SAA = "SAA1099/cop/axel.cop";
  const char* const TFM = "YM2203/tfe/disco.tfe";
  const char* const DAC = "DAC/ZX/chi/balala.chi";
  const std::vector<Case> cases = {
      {PT3, "aym.clockrate", Core::AYM::CLOCKRATE, 1750000, 1000000, {}},
      {PT3, "aym.type", Core::AYM::TYPE, 0, 1, {}},
      {PT3, "aym.interpolation", Core::AYM::INTERPOLATION, 0, 2, {}},
      {PT3, "aym.layout", Core::AYM::LAYOUT, 0, 6, {}},
      {PT3, "aym.duty_cycle", Core::AYM::DUTY_CYCLE, 50, 10, {{Core::AYM::DUTY_CYCLE_MASK, 7}}},
      {PT3, "aym.duty_cycle_mask", Core::AYM::DUTY_CYCLE_MASK, 0, 7, {{Core::AYM::DUTY_CYCLE, 10}}},
      {AY, "z80.clockrate(ay)", Core::Z80::CLOCKRATE, 3500000, 7000000, {}},
      {AY, "z80.int_ticks(ay)", Core::Z80::INT_TICKS, 24, 1, {}},
      {BEEPER, "z80.clockrate(beep)", Core::Z80::CLOCKRATE, 3500000, 7000000, {}},
      {BEEPER, "z80.int_ticks(beep)", Core::Z80::INT_TICKS, 24, 100, {}},
      {SAA, "saa.clockrate", Core::SAA::CLOCKRATE, 8000000, 4000000, {}},
      {SAA, "saa.interpolation", Core::SAA::INTERPOLATION, 0, 2, {}},
      {TFM, "fm.clockrate", Core::FM::CLOCKRATE, 3500000, 1750000, {}},
      {DAC, "dac.interpolation", Core::DAC::INTERPOLATION, 0, 1, {}},
      {PT3, "sound.gain", SoundParams::GAIN, 100, 30, {}, true},
      {PT3, "sound.fadeout", SoundParams::FADEOUT, 0, 3, {}, true, true},
  };
  int failures = 0;
  for (const auto& c : cases)
  {
    try
    {
      const auto holder = Open(root + '/' + c.File);
      const auto global = Parameters::Container::Create();
      global->SetValue(SoundParams::FREQUENCY, FREQ);
      auto setup = [&](Parameters::IntType val) {
        for (const auto& ctx : c.Context)
        {
          global->SetValue(ctx.first, ctx.second);
        }
        global->SetValue(c.Param, val);
      };
      const auto duration = holder->GetModuleInformation().Duration;
      const auto start = c.AtEnd ? Time::AtMillisecond() + duration - Time::Milliseconds(2 * SECONDS * 1000)
                                 : Time::AtMillisecond();
      auto render = [&](Parameters::IntType before, Parameters::IntType after) {
        setup(before);
        const auto renderer = Module::CreatePipelinedRenderer(*holder, global);
        renderer->SetPosition(start);
        Render(*renderer, SECONDS);
        setup(after);
        return Render(*renderer, SECONDS);
      };
      const auto tailA = render(c.A, c.A);
      const auto tailB = render(c.B, c.B);
      const auto tail = render(c.A, c.B);
      const auto dist = c.ByLevel ? &LevelDist : &SpecDist;
      const auto effect = dist(tailA, tailB);
      const auto toB = dist(tail, tailB);
      const auto toA = dist(tail, tailA);
      // timing-only parameters (e.g. INT duration) produce negligible differences comparable with phase noise
      const bool noEffect = effect < 1.0;
      const bool applied = toB < toA * 0.5;
      std::printf("%-22s %s A vs B=%6.2f  switched vs B=%6.2f vs A=%6.2f  %s\n", c.Name,
                  c.ByLevel ? "level dB:   " : "spectrum dB:", effect, toB, toA,
                  noEffect ? "NO EFFECT" : (applied ? "OK" : "NOT APPLIED"));
      failures += !noEffect && !applied;
    }
    catch (const Error& e)
    {
      std::printf("%-22s ERROR: %s\n", c.Name, e.ToString().c_str());
      ++failures;
    }
  }
  return failures != 0;
}
