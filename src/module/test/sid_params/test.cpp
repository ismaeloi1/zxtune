// Usage: module_test_sid_params <file.sid> [subpath], e.g. "#2" for multi-song tunes
// Host check: SID emulation parameters are applied in realtime
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

  std::vector<int16_t> Render(Module::Renderer& r, uint_t seconds)
  {
    std::vector<int16_t> res;
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

  double Diff(const std::vector<int16_t>& a, const std::vector<int16_t>& b)
  {
    double sum = 0, ref = 1;
    for (std::size_t i = 0; i < a.size(); ++i)
    {
      sum += double(a[i] - b[i]) * (a[i] - b[i]);
      ref += double(b[i]) * b[i];
    }
    return std::sqrt(sum / ref);
  }

  // log-magnitude spectrum in octave fractions bands, insensitive to phase
  std::vector<double> Spectrum(const std::vector<int16_t>& samples)
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

  double SpecDist(const std::vector<int16_t>& a, const std::vector<int16_t>& b)
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

  struct Case
  {
    const char* Name;
    Parameters::Identifier Param;
    Parameters::IntType A, B;
    std::vector<std::pair<Parameters::Identifier, Parameters::IntType>> Context;
  };
}  // namespace

int main(int argc, char** argv)
{
  std::ifstream f(argv[1], std::ios::binary);
  std::vector<uint8_t> data((std::istreambuf_iterator<char>(f)), {});
  const auto global = Parameters::Container::Create();
  global->SetValue(Parameters::ZXTune::Sound::FREQUENCY, FREQ);
  const auto service = ZXTune::Service::Create(global);
  const auto holder = service->OpenModule(Binary::CreateContainer(Binary::View(data.data(), data.size())),
                                          argc > 2 ? argv[2] : "", Parameters::Container::Create());
  using namespace Parameters::ZXTune::Core::SID;
  const std::vector<Case> cases = {
      {"engine", ENGINE, 0, 1, {}},
      {"model(forced)", MODEL, 0, 1, {{MODEL_FORCE, 1}}},
      {"model_force", MODEL_FORCE, 0, 1, {{MODEL, 1}}},
      {"clock(forced)", CLOCK, 0, 1, {{CLOCK_FORCE, 1}}},
      {"clock_force", CLOCK_FORCE, 0, 1, {{CLOCK, 1}}},
      {"filter", FILTER, 1, 0, {}},
      {"interpolation", INTERPOLATION, 0, 2, {}},
      {"digiboost(8580)", DIGIBOOST, 0, 1, {{MODEL, 1}, {MODEL_FORCE, 1}}},
      {"filter_bias(reSID)", FILTER_BIAS, -500, 500, {{ENGINE, 0}, {MODEL, 0}, {MODEL_FORCE, 1}}},
      {"6581_curve(fp)", FILTER_6581_CURVE, 0, 100, {{ENGINE, 1}, {MODEL, 0}, {MODEL_FORCE, 1}}},
      {"6581_range(fp)", FILTER_6581_RANGE, 0, 100, {{ENGINE, 1}, {MODEL, 0}, {MODEL_FORCE, 1}}},
      {"8580_curve(fp)", FILTER_8580_CURVE, 0, 100, {{ENGINE, 1}, {MODEL, 1}, {MODEL_FORCE, 1}}},
      {"combined_wf(fp)", COMBINED_WAVEFORMS, 0, 2, {{ENGINE, 1}}},
      {"combined_wf(fp6581)", COMBINED_WAVEFORMS, 0, 2, {{ENGINE, 1}, {MODEL, 0}, {MODEL_FORCE, 1}}},
      {"interpolation(reSID)", INTERPOLATION, 0, 2, {{ENGINE, 0}}},
  };
  {
    // determinism check
    auto r1 = Render(*Module::CreatePipelinedRenderer(*holder, global), 4);
    auto r2 = Render(*Module::CreatePipelinedRenderer(*holder, global), 4);
    std::printf("determinism: %.3f\n", Diff(r1, r2));
  }
  for (const auto& c : cases)
    try
    {
      auto setup = [&](Parameters::IntType val) {
        for (const Parameters::Identifier id :
             {Parameters::Identifier(ENGINE), Parameters::Identifier(MODEL), Parameters::Identifier(MODEL_FORCE),
              Parameters::Identifier(CLOCK), Parameters::Identifier(CLOCK_FORCE), Parameters::Identifier(FILTER),
              Parameters::Identifier(INTERPOLATION), Parameters::Identifier(DIGIBOOST),
              Parameters::Identifier(FILTER_BIAS), Parameters::Identifier(FILTER_6581_CURVE),
              Parameters::Identifier(FILTER_6581_RANGE), Parameters::Identifier(FILTER_8580_CURVE),
              Parameters::Identifier(COMBINED_WAVEFORMS)})
        {
          global->RemoveValue(id);
        }
        for (const auto& ctx : c.Context)
        {
          global->SetValue(ctx.first, ctx.second);
        }
        global->SetValue(c.Param, val);
      };
      // references: whole playback with A or B
      setup(c.A);
      auto refA = Render(*Module::CreatePipelinedRenderer(*holder, global), 4);
      setup(c.B);
      auto refB = Render(*Module::CreatePipelinedRenderer(*holder, global), 4);
      // switch A->B at 2s
      setup(c.A);
      const auto renderer = Module::CreatePipelinedRenderer(*holder, global);
      Render(*renderer, 2);
      setup(c.B);
      auto tail = Render(*renderer, 2);
      const std::vector<int16_t> tailA(refA.begin() + 2 * FREQ, refA.end());
      const std::vector<int16_t> tailB(refB.begin() + 2 * FREQ, refB.end());
      const auto effect = SpecDist(tailA, tailB);
      const auto toB = SpecDist(tail, tailB);
      const auto toA = SpecDist(tail, tailA);
      std::printf("%-20s spectrum dB: A vs B=%5.2f  switched vs B=%5.2f vs A=%5.2f  %s\n", c.Name, effect, toB, toA,
                  effect < 0.5 ? "NO EFFECT" : (toB < toA * 0.5 ? "OK" : "NOT APPLIED"));
    }
    catch (const Error& e)
    {
      std::printf("%-20s ERROR: %s\n", c.Name, e.ToString().c_str());
    }
}
