// Usage: module_test_sid_filters [calibrate|<configuration name part>]
// Host check: SID filters presets and custom parameters are applied to both engines, measured cutoff frequencies
// follow the small signal models used to translate presets (and displayed by UI). Tunes are synthesized.
#include "module/players/external/sid/filters.h"
#include "module/players/pipeline.h"
#include "module/test/sid_psid.h"

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
#include <functional>
#include <string>
#include <vector>

namespace
{
  const uint_t FREQ = 48000;
  const std::size_t FFT_SIZE = 8192;

  // White noise on voice 1 at small level to stay in linear region of filter, lowpass without resonance
  SidTest::Registers MakeNoise(bool filtered, uint_t fc)
  {
    auto regs = SidTest::MakeVoice(0xffff, 0x81);
    regs[6] = 0x80;
    regs[0x15] = uint8_t(fc & 7);
    regs[0x16] = uint8_t(fc >> 3);
    regs[0x17] = filtered ? 0x01 : 0x00;
    regs[0x18] = filtered ? 0x1f : 0x0f;
    return regs;
  }

  std::vector<double> Render(const Module::Holder& holder, Parameters::Accessor::Ptr params)
  {
    const auto renderer = Module::CreatePipelinedRenderer(holder, std::move(params));
    std::vector<double> samples;
    while (samples.size() < 2 * FREQ)
    {
      const auto chunk = renderer->Render();
      if (chunk.empty())
      {
        break;
      }
      for (const auto& s : chunk)
      {
        samples.push_back(s.Left());
      }
    }
    // skip power-on
    samples.erase(samples.begin(), samples.begin() + FREQ / 4);
    return samples;
  }

  void Fft(std::vector<std::complex<double>>& data)
  {
    const auto n = data.size();
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
  }

  // Welch averaged power spectrum
  std::vector<double> Spectrum(const std::vector<double>& samples)
  {
    std::vector<double> result(FFT_SIZE / 2);
    std::vector<std::complex<double>> data(FFT_SIZE);
    for (std::size_t start = 0; start + FFT_SIZE <= samples.size(); start += FFT_SIZE / 2)
    {
      for (std::size_t i = 0; i < FFT_SIZE; ++i)
      {
        data[i] = samples[start + i] * (0.5 - 0.5 * std::cos(2 * M_PI * i / FFT_SIZE));
      }
      Fft(data);
      for (std::size_t i = 0; i < FFT_SIZE / 2; ++i)
      {
        result[i] += std::norm(data[i]);
      }
    }
    return result;
  }

  // Fits 2-pole lowpass response (cutoff, Q and gain) to filtered/unfiltered power ratio in logarithmic bands
  double MeasureCutoff(const std::vector<double>& reference, const std::vector<double>& filtered)
  {
    const std::size_t BANDS = 96;
    const double LOW = 40;
    const double HIGH = 18000;
    std::vector<double> refBands(BANDS);
    std::vector<double> filtBands(BANDS);
    for (std::size_t i = 1; i < FFT_SIZE / 2; ++i)
    {
      const double freq = double(i) * FREQ / FFT_SIZE;
      if (freq < LOW || freq >= HIGH)
      {
        continue;
      }
      const auto band = std::size_t(std::log(freq / LOW) / std::log(HIGH / LOW) * BANDS);
      refBands[band] += reference[i];
      filtBands[band] += filtered[i];
    }
    std::vector<double> freqs;
    std::vector<double> ratios;
    for (std::size_t band = 0; band < BANDS; ++band)
    {
      if (refBands[band] > 0 && filtBands[band] > 0)
      {
        freqs.push_back(LOW * std::pow(HIGH / LOW, (band + 0.5) / BANDS));
        // stop band is limited by numerical noise floor
        ratios.push_back(std::max(10 * std::log10(filtBands[band] / refBands[band]), -50.0));
      }
    }
    double bestError = HUGE_VAL;
    double bestCutoff = 0;
    std::vector<double> model(freqs.size());
    for (double cutoff = 20; cutoff < 40000; cutoff *= 1.01)
    {
      for (double q = 0.3; q < 1.5; q += 0.025)
      {
        // gain is the mean residual for given shape
        double gain = 0;
        for (std::size_t i = 0; i < freqs.size(); ++i)
        {
          const auto x = freqs[i] / cutoff;
          model[i] = -10 * std::log10((1 - x * x) * (1 - x * x) + x * x / (q * q));
          gain += ratios[i] - model[i];
        }
        gain /= freqs.size();
        double error = 0;
        for (std::size_t i = 0; i < freqs.size(); ++i)
        {
          const auto diff = ratios[i] - std::max(model[i] + gain, -50.0);
          error += diff * diff;
        }
        if (error < bestError)
        {
          bestError = error;
          bestCutoff = cutoff;
        }
      }
    }
    return bestCutoff;
  }

  struct Config
  {
    std::string Name;
    std::vector<std::pair<Parameters::Identifier, Parameters::IntType>> Params;
    // expected cutoff
    std::function<double(uint_t)> Model;
  };
}  // namespace

int main(int argc, char** argv)
{
  // Calibration mode prints measured cutoffs of custom filters for dense set of registers values
  const std::string arg = argc > 1 ? argv[1] : "";
  const bool calibrate = arg == "calibrate";
  try
  {
    std::vector<uint_t> cutoffs = {96, 256, 512, 768, 1024, 1280, 1536, 1792, 2047};
    if (calibrate)
    {
      cutoffs.clear();
      for (uint_t fc = 0; fc < 2048; fc += 64)
      {
        cutoffs.push_back(fc);
      }
      cutoffs.push_back(2047);
    }
    std::vector<SidTest::Registers> songs = {MakeNoise(false, 0)};
    for (const auto fc : cutoffs)
    {
      songs.push_back(MakeNoise(true, fc));
    }
    const auto psid = SidTest::MakePsid(songs);
    const auto global = Parameters::Container::Create();
    global->SetValue(Parameters::ZXTune::Sound::FREQUENCY, FREQ);
    const auto service = ZXTune::Service::Create(global);
    const auto data = Binary::CreateContainer(Binary::View(psid.data(), psid.size()));
    std::vector<Module::Holder::Ptr> holders;
    for (std::size_t idx = 0; idx < songs.size(); ++idx)
    {
      holders.push_back(service->OpenModule(data, "#" + std::to_string(idx + 1), Parameters::Container::Create()));
    }

    using namespace Parameters::ZXTune::Core::SID;
    using namespace Module::Sid;
    using FilterModel::Engine;
    std::vector<Config> configs;
    // Linear digital filter of reSID 8580 is the reference to calibrate measurement
    configs.push_back({"reSID 8580", {{ENGINE, ENGINE_RESID}, {MODEL, MODEL_8580}}, [](uint_t fc) {
                         return FilterModel::GetResid8580Cutoff(fc, FilterModel::RESID_8580_MAX_CUTOFF);
                       }});
    // custom parameters
    const auto curves = calibrate ? std::vector<int>{0, 25, 50, 75, 100} : std::vector<int>{0, 50, 100};
    for (const auto curve : curves)
    {
      for (const auto range : {25, 50, 75})
      {
        configs.push_back(
            {"fp 6581 curve=" + std::to_string(curve) + " range=" + std::to_string(range),
             {{ENGINE, ENGINE_RESIDFP}, {MODEL, MODEL_6581}, {FILTER_6581_CURVE, curve}, {FILTER_6581_RANGE, range}},
             [curve, range](uint_t fc) {
               return FilterModel::Get6581Cutoff(Engine::RESIDFP,
                                                 FilterModel::GetResidFp6581DacVoltage(fc, curve / 100.0),
                                                 FilterModel::GetResidFpUcox(range / 100.0));
             }});
      }
    }
    const auto biases = calibrate ? std::vector<int>{-1000, -750, -500, -250, 0, 250, 500, 750, 1000}
                                  : std::vector<int>{-1000, 0, 500, 1000};
    for (const auto bias : biases)
    {
      configs.push_back({"reSID 6581 bias=" + std::to_string(bias),
                         {{ENGINE, ENGINE_RESID}, {MODEL, MODEL_6581}, {FILTER_BIAS, bias}},
                         [bias](uint_t fc) {
                           return FilterModel::Get6581Cutoff(Engine::RESID,
                                                             FilterModel::GetResid6581DacVoltage(fc, bias / 1000.0),
                                                             FilterModel::RESID_UCOX);
                         }});
    }
    for (const auto curve : curves)
    {
      configs.push_back({"fp 8580 curve=" + std::to_string(curve),
                         {{ENGINE, ENGINE_RESIDFP}, {MODEL, MODEL_8580}, {FILTER_8580_CURVE, curve}},
                         [curve](uint_t fc) { return FilterModel::GetResidFp8580Cutoff(fc, curve / 100.0); }});
    }
    // presets: emulated curves should follow preset ones except of clamped parts
    for (int_t id = 1; const auto* preset = calibrate ? nullptr : FindFilter6581Preset(id); ++id)
    {
      // see sid.cpp
      const auto uCox = FilterModel::GetResidFpUcox(FilterModel::GetResidFpRange(
          preset->Type == CutoffCurve::TYPE3 ? FilterModel::Get6581Ucox(preset->Params[0]) : FilterModel::RESID_UCOX));
      configs.push_back({"fp 6581 preset=" + std::to_string(id),
                         {{ENGINE, ENGINE_RESIDFP}, {MODEL, MODEL_6581}, {FILTER_6581_PRESET, id}},
                         [preset, uCox](uint_t fc) {
                           const auto vw =
                               FilterModel::Get6581DacVoltage(Engine::RESIDFP, preset->GetFrequency(fc), uCox);
                           return FilterModel::Get6581Cutoff(Engine::RESIDFP, vw, uCox);
                         }});
      const auto bias = FilterModel::FitResid6581Bias(*preset);
      configs.push_back({"reSID 6581 preset=" + std::to_string(id),
                         {{ENGINE, ENGINE_RESID}, {MODEL, MODEL_6581}, {FILTER_6581_PRESET, id}},
                         [bias](uint_t fc) {
                           return FilterModel::Get6581Cutoff(
                               Engine::RESID, FilterModel::GetResid6581DacVoltage(fc, bias), FilterModel::RESID_UCOX);
                         }});
    }
    for (int_t id = 1; const auto* preset = calibrate ? nullptr : FindFilter8580Preset(id); ++id)
    {
      const auto curve = FilterModel::FitResidFp8580Curve(*preset);
      configs.push_back({"fp 8580 preset=" + std::to_string(id),
                         {{ENGINE, ENGINE_RESIDFP}, {MODEL, MODEL_8580}, {FILTER_8580_PRESET, id}},
                         [curve](uint_t fc) { return FilterModel::GetResidFp8580Cutoff(fc, curve); }});
      const auto max = preset->GetFrequency(CUTOFF_VALUES - 1);
      configs.push_back({"reSID 8580 preset=" + std::to_string(id),
                         {{ENGINE, ENGINE_RESID}, {MODEL, MODEL_8580}, {FILTER_8580_PRESET, id}},
                         [max](uint_t fc) { return FilterModel::GetResid8580Cutoff(fc, max); }});
    }

    std::printf("%-28s", "measured/expected cutoff");
    for (const auto fc : cutoffs)
    {
      std::printf(" %13u", fc);
    }
    std::printf("\n");
    bool ok = true;
    // measured to real cutoff ratio by measured cutoff
    std::vector<std::pair<double, double>> measurementBias;
    const auto correct = [&measurementBias](double measured) {
      if (measurementBias.empty() || measured <= measurementBias.front().first)
      {
        return measured / (measurementBias.empty() ? 1.0 : measurementBias.front().second);
      }
      for (std::size_t idx = 1; idx < measurementBias.size(); ++idx)
      {
        const auto& lo = measurementBias[idx - 1];
        const auto& hi = measurementBias[idx];
        if (measured < hi.first)
        {
          return measured / (lo.second + (hi.second - lo.second) * (measured - lo.first) / (hi.first - lo.first));
        }
      }
      return measured / measurementBias.back().second;
    };
    for (const auto& cfg : configs)
    {
      const bool isReference = &cfg == &configs.front();
      if (!calibrate && !isReference && cfg.Name.find(arg) == std::string::npos)
      {
        continue;
      }
      const auto params = Parameters::Container::Clone(*global);
      params->SetValue(MODEL_FORCE, 1);
      for (const auto& p : cfg.Params)
      {
        params->SetValue(p.first, p.second);
      }
      const auto reference = Spectrum(Render(*holders[0], params));
      std::printf("%-28s", cfg.Name.c_str());
      std::vector<double> measured;
      for (std::size_t idx = 0; idx < cutoffs.size(); ++idx)
      {
        const auto raw = MeasureCutoff(reference, Spectrum(Render(*holders[idx + 1], params)));
        const auto expected = cfg.Model(cutoffs[idx]);
        if (isReference)
        {
          measurementBias.emplace_back(raw, raw / expected);
        }
        const auto cutoff = isReference ? raw : correct(raw);
        std::printf(" %6.0f/%6.0f", cutoff, expected);
        measured.push_back(cutoff);
        // Fitting is not reliable near the ends of measured band. At low frequencies VCR starts conducting and
        // its exponential characteristic makes cutoff dependent on signal level.
        const auto tolerance = expected < 400 ? 1.4 : 1.2;
        if (!isReference && expected > 150 && expected < 12000
            && std::abs(std::log(cutoff / expected)) > std::log(tolerance))
        {
          ok = false;
          std::printf("!");
        }
      }
      std::printf("\n");
      for (std::size_t idx = 1; idx < measured.size(); ++idx)
      {
        if (measured[idx] < measured[idx - 1] * 0.9)
        {
          std::printf("  non-monotonic cutoff at %u\n", cutoffs[idx]);
        }
      }
    }
    std::printf(ok ? "OK\n" : "FAILED: measured cutoff differs from expected too much\n");
    return ok ? 0 : 1;
  }
  catch (const Error& e)
  {
    std::printf("%s\n", e.ToString().c_str());
    return 1;
  }
}
