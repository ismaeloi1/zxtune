// Usage: module_test_sid_waveforms
// Host check: levels of combined waveforms relative to pure ones for both engines, chip models and reSIDfp combined
// waveforms strengths, pulse+saw at several pulse widths. Tunes are synthesized, so no files are required.
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
#include <cstdio>
#include <string>
#include <vector>

namespace
{
  const uint_t FREQ = 48000;

  struct Wave
  {
    const char* Name;
    uint8_t Control;
  };

  // gate is set for all of them
  const Wave WAVES[] = {
      {"Tri", 0x11},   {"Saw", 0x21},       {"Saw+Tri", 0x31},   {"Saw+Tri sync", 0x33},  {"Saw+Tri ring", 0x35},
      {"Pulse", 0x41}, {"Pulse+Tri", 0x51}, {"Pulse+Saw", 0x61}, {"Pulse+Saw+Tri", 0x71},
  };
  const std::size_t SAW = 1;
  const std::size_t SAW_TRI = 2;

  // pulse is high while accumulator is above pulse width, so for >50% combined pulse+saw output starts in the upper
  // half of saw period where 6581 pulls accumulator MSB down
  const uint_t PULSE_WIDTHS[] = {0x200, 0x600, 0x7f0, 0x900, 0xc00};

  double RenderRms(const Module::Holder& holder, Parameters::Container::Ptr params)
  {
    const auto renderer = Module::CreatePipelinedRenderer(holder, params);
    std::vector<double> samples;
    while (samples.size() < 3 * FREQ / 2)
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
    // skip power-on and envelope attack, then measure AC part (output has DC offset of voice zero level)
    const std::size_t start = FREQ / 2;
    const std::size_t end = std::min<std::size_t>(samples.size(), 3 * FREQ / 2);
    double sum = 0;
    for (auto i = start; i < end; ++i)
    {
      sum += samples[i];
    }
    const auto mean = sum / (end - start);
    double sum2 = 0;
    for (auto i = start; i < end; ++i)
    {
      sum2 += (samples[i] - mean) * (samples[i] - mean);
    }
    return std::sqrt(sum2 / (end - start));
  }

  double ToDb(double val, double ref)
  {
    return 20 * std::log10(std::max(val, 1e-3) / ref);
  }
}  // namespace

int main()
{
  try
  {
    std::vector<SidTest::Registers> songs;
    for (const auto& w : WAVES)
    {
      songs.push_back(SidTest::MakeVoice(SidTest::ToneRegister(440), w.Control));
    }
    const auto pulseSawFirst = songs.size();
    for (const auto pw : PULSE_WIDTHS)
    {
      songs.push_back(SidTest::MakeVoice(SidTest::ToneRegister(440), 0x61, pw));
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
    struct Config
    {
      std::string Name;
      Parameters::IntType Engine;
      Parameters::IntType Model;
      Parameters::IntType Strength;
      Parameters::IntType StrongPulseSaw = STRONG_PULSE_SAW_DEFAULT;
    };
    std::vector<Config> configs;
    for (const auto model : {MODEL_6581, MODEL_8580})
    {
      const std::string chip = model == MODEL_6581 ? "6581" : "8580";
      configs.push_back({"reSID   " + chip, ENGINE_RESID, model, COMBINED_WAVEFORMS_DEFAULT});
      configs.push_back({"reSIDfp " + chip + " weak", ENGINE_RESIDFP, model, COMBINED_WAVEFORMS_WEAK});
      configs.push_back({"reSIDfp " + chip + " average", ENGINE_RESIDFP, model, COMBINED_WAVEFORMS_AVERAGE});
      configs.push_back({"reSIDfp " + chip + " strong", ENGINE_RESIDFP, model, COMBINED_WAVEFORMS_STRONG});
      configs.push_back({"reSIDfp " + chip + " avg strPS", ENGINE_RESIDFP, model, COMBINED_WAVEFORMS_AVERAGE, 1});
    }

    std::printf("%-22s %8s", "dB relative to saw", "saw RMS");
    for (const auto& w : WAVES)
    {
      std::printf(" %13s", w.Name);
    }
    for (const auto pw : PULSE_WIDTHS)
    {
      std::printf("   P+S pw=%03x", pw);
    }
    std::printf("\n");
    bool ok = true;
    for (const auto& cfg : configs)
    {
      const auto params = Parameters::Container::Clone(*global);
      params->SetValue(ENGINE, cfg.Engine);
      params->SetValue(MODEL, cfg.Model);
      params->SetValue(MODEL_FORCE, 1);
      params->SetValue(COMBINED_WAVEFORMS, cfg.Strength);
      params->SetValue(STRONG_PULSE_SAW, cfg.StrongPulseSaw);
      std::vector<double> levels;
      for (const auto& holder : holders)
      {
        levels.push_back(RenderRms(*holder, params));
      }
      std::printf("%-22s %8.0f", cfg.Name.c_str(), levels[SAW]);
      for (const auto lvl : levels)
      {
        std::printf(" %13.1f", ToDb(lvl, levels[SAW]));
      }
      std::printf("\n");
      const auto pulseSaw = [&](uint_t idx) { return ToDb(levels[pulseSawFirst + idx], levels[SAW]); };
      // Both engines pull 6581 accumulator MSB down when combined output top bit is low, so with pulse width over
      // 50% (pulse is low at the middle of saw period) the upper half is never reached and the output is silent.
      // 8580 doesn't do it, reSIDfp optionally keeps MSB for pulse+saw as some 6581 chips do.
      const bool msbPulldown = cfg.Model == MODEL_6581 && !cfg.StrongPulseSaw;
      for (uint_t idx = 0; idx < std::size(PULSE_WIDTHS); ++idx)
      {
        const bool upperHalf = PULSE_WIDTHS[idx] > 0x800;
        if (msbPulldown && upperHalf ? pulseSaw(idx) > -40 : pulseSaw(idx) < -25)
        {
          std::printf("  FAILED: unexpected pulse+saw level for pw=%03x\n", PULSE_WIDTHS[idx]);
          ok = false;
        }
      }
      // Sampled 8580 saw+triangle (reSID tables, libsidplayfp combined-waveforms dumps) is a few dB below saw.
      // Older reSIDfp model dropped fractional top bit weight and made it ~20dB weaker for some strengths.
      if (cfg.Model == MODEL_8580 && ToDb(levels[SAW_TRI], levels[SAW]) < -10)
      {
        std::printf("  FAILED: 8580 saw+triangle is too weak\n");
        ok = false;
      }
    }
    return ok ? 0 : 1;
  }
  catch (const Error& e)
  {
    std::printf("%s\n", e.ToString().c_str());
    return 1;
  }
}
