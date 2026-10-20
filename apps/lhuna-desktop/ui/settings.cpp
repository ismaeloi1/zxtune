/**
 *
 * @file
 *
 * @brief Persistent settings of desktop player implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/settings.h"

#include "apps/lhuna-desktop/engine/engine.h"
#include "visualization/sid_filter_model.h"
#include "visualization/trigger_presets.h"

namespace Lhuna
{
  const auto FILTER_6581_PRESET = "zxtune.core.sid.filter_6581_preset";
  const auto FILTER_8580_PRESET = "zxtune.core.sid.filter_8580_preset";

  const std::vector<std::pair<QString, int>> INTERPOLATION = {
      {QObject::tr("None"), 0}, {QObject::tr("Performance"), 1}, {QObject::tr("Quality (high CPU usage)"), 2}};

  std::vector<std::pair<QString, int>> GraphicsBackends()
  {
    using B = GraphicsBackend;
    std::vector<std::pair<QString, int>> result = {{QObject::tr("Auto"), static_cast<int>(B::AUTO)},
                                                   {"Vulkan", static_cast<int>(B::VULKAN)},
                                                   {"OpenGL", static_cast<int>(B::OPENGL)}};
#ifdef Q_OS_WIN
    result.emplace_back("Direct3D 11", static_cast<int>(B::D3D11));
#endif
    result.emplace_back(QObject::tr("Software (CPU)"), static_cast<int>(B::SOFTWARE));
    return result;
  }

  std::vector<OptionsPage> MakePages()
  {
    using O = OptionDesc;
    std::vector<OptionsPage> pages;
    pages.push_back(
        {"scope",
         QObject::tr("Oscilloscope"),
         {
             {Settings::WINDOW_MS, QObject::tr("Window"), O::SLIDER, 40, 5, 200, {}, "ms"},
             {Settings::GAIN,
              QObject::tr("Gain"),
              O::CHOICE,
              0,
              0,
              0,
              {{QObject::tr("Auto"), 0}, {"50%", 50}, {"100%", 100}, {"200%", 200}, {"400%", 400}}},
             {Settings::MAX_FPS,
              QObject::tr("Max frame rate"),
              O::CHOICE,
              60,
              0,
              0,
              {{"30", 30}, {"60", 60}, {"120", 120}, {"144", 144}}},
             {Settings::GAUGE_WINDOW_MS, QObject::tr("SID gauges window"), O::SLIDER, 10, 1, 100, {}, "ms"},
             {Settings::LATENCY_MS, QObject::tr("Latency compensation (manual)"), O::SLIDER, 100, 0, 500, {}, "ms"},
             // top-level window composition is bound to graphics API when shown, so it can't be switched live
             {Settings::GRAPHICS_BACKEND, QObject::tr("Graphics backend (on restart)"), O::CHOICE,
              static_cast<int>(GraphicsBackend::AUTO), 0, 0, GraphicsBackends()},
         }});
    OptionsPage trigger{"trigger", QObject::tr("Trigger"), {}};
    for (const auto& param : Visualization::TriggerPresets::Params())
    {
      O desc;
      desc.Key = QString::fromStdString(param.Key());
      desc.Title = QString::fromStdString(param.Name).replace('_', ' ');
      desc.Title[0] = desc.Title[0].toUpper();
      desc.Default = param.Default;
      desc.Min = param.Min;
      desc.Max = param.Max;
      switch (param.Kind)
      {
      case Visualization::TriggerPresets::Type::BOOLEAN:
        desc.Type = O::BOOLEAN;
        break;
      case Visualization::TriggerPresets::Type::CHOICE:
        desc.Type = O::CHOICE;
        desc.Choices =
            param.Name == "search_rate"
                ? std::vector<std::pair<QString, int>>{{QObject::tr("Each frame"), 0},
                                                       {"15/s", 15},
                                                       {"30/s", 30},
                                                       {"60/s", 60}}
                : std::vector<std::pair<QString, int>>{{QObject::tr("Auto"), 0}, {"1", 1}, {"2", 2}, {"4", 4}};
        break;
      default:
        desc.Type = O::SLIDER;
        desc.Unit = param.Name == "width" ? "ms" : (param.Name == "post_radius" ? "" : "%");
        break;
      }
      trigger.Options.push_back(desc);
    }
    pages.push_back(trigger);
    pages.push_back(
        {"aym",
         "AY-3-8910/YM2149F",
         {
             {"zxtune.core.aym.type", QObject::tr("Chip type"), O::CHOICE, 0, 0, 0, {{"AY-3-8910", 0}, {"YM2149F", 1}}},
             {"zxtune.core.aym.interpolation", QObject::tr("Interpolation"), O::CHOICE, 2, 0, 0, INTERPOLATION},
             {"zxtune.core.aym.layout",
              QObject::tr("Channels layout"),
              O::CHOICE,
              0,
              0,
              0,
              {{"ABC", 0}, {"ACB", 1}, {"BAC", 2}, {"BCA", 3}, {"CAB", 4}, {"CBA", 5}, {"Mono", 6}}},
             {"zxtune.core.aym.clockrate",
              QObject::tr("Clockrate"),
              O::CHOICE,
              1750000,
              0,
              0,
              {{"ZX Spectrum (1.7734 MHz)", 1773400},
               {"Pentagon (1.75 MHz)", 1750000},
               {"Taganrog (3.5 MHz)", 3500000},
               {"Atari ST (2 MHz)", 2000000},
               {"Amstrad CPC (1 MHz)", 1000000}}},
             {"zxtune.core.aym.duty_cycle_mask",
              QObject::tr("Duty cycle applied to"),
              O::CHOICE,
              0,
              0,
              0,
              {{QObject::tr("None"), 0},
               {"A", 1},
               {"B", 2},
               {"C", 4},
               {"A, B", 3},
               {"A, C", 5},
               {"B, C", 6},
               {QObject::tr("All"), 7}}},
             {"zxtune.core.aym.duty_cycle", QObject::tr("Duty cycle"), O::SLIDER, 50, 1, 99, {}, "%"},
         }});
    pages.push_back(
        {"z80",
         "Z80",
         {
             {"zxtune.core.z80.clockrate",
              QObject::tr("Clockrate"),
              O::CHOICE,
              3500000,
              0,
              0,
              {{"ZX Spectrum 48K, Pentagon (3.5 MHz)", 3500000},
               {"ZX Spectrum 128K (3.5469 MHz)", 3546900},
               {"Amstrad CPC (4 MHz)", 4000000},
               {"Turbo (7 MHz)", 7000000}}},
             {"zxtune.core.z80.int_ticks", QObject::tr("Interrupt duration"), O::SLIDER, 24, 1, 100, {}, ""},
         }});
    pages.push_back({"fm",
                     "YM2203/TurboFM",
                     {
                         {"zxtune.core.fm.clockrate",
                          QObject::tr("Clockrate"),
                          O::CHOICE,
                          3500000,
                          0,
                          0,
                          {{"1.75 MHz", 1750000},
                           {"TurboFM (3.5 MHz)", 3500000},
                           {"NTSC (3.58 MHz)", 3579545},
                           {"4 MHz", 4000000}}},
                     }});
    std::vector<std::pair<QString, int>> presets6581;
    std::vector<std::pair<QString, int>> presets8580;
    for (const auto& [names, target] : {std::pair{&Visualization::SidFilterModel::PresetNames6581(), &presets6581},
                                        std::pair{&Visualization::SidFilterModel::PresetNames8580(), &presets8580}})
    {
      for (std::size_t idx = 0; idx < names->size(); ++idx)
      {
        target->emplace_back(idx ? QString::fromStdString((*names)[idx]) : QObject::tr("Custom"),
                             static_cast<int>(idx));
      }
    }
    pages.push_back(
        {"sid",
         "MOS6581/SID",
         {
             {"zxtune.core.sid.engine",
              QObject::tr("Emulation engine"),
              O::CHOICE,
              1,
              0,
              0,
              {{"reSID", 0}, {"reSIDfp", 1}}},
             {"zxtune.core.sid.model",
              QObject::tr("Default chip model"),
              O::CHOICE,
              0,
              0,
              0,
              {{"MOS6581", 0}, {"MOS8580", 1}}},
             {"zxtune.core.sid.model_force", QObject::tr("Force chip model"), O::BOOLEAN, 0},
             {"zxtune.core.sid.clock",
              QObject::tr("Default C64 model"),
              O::CHOICE,
              0,
              0,
              0,
              {{"PAL", 0}, {"NTSC", 1}, {"NTSC (old)", 2}, {"Drean (PAL-N)", 3}, {"PAL-M", 4}}},
             {"zxtune.core.sid.clock_force", QObject::tr("Force C64 model"), O::BOOLEAN, 0},
             {"zxtune.core.sid.interpolation", QObject::tr("Interpolation"), O::CHOICE, 0, 0, 0, INTERPOLATION},
             {"zxtune.core.sid.filter", QObject::tr("Filter emulation"), O::BOOLEAN, 1},
             {FILTER_6581_PRESET, QObject::tr("MOS6581 filter preset"), O::CHOICE, 0, 0, 0, presets6581},
             {FILTER_8580_PRESET, QObject::tr("MOS8580 filter preset"), O::CHOICE, 0, 0, 0, presets8580},
             {"zxtune.core.sid.filter_6581_curve",
              QObject::tr("6581 filter curve (reSIDfp, custom)"),
              O::SLIDER,
              50,
              0,
              100,
              {},
              "%"},
             {"zxtune.core.sid.filter_6581_range",
              QObject::tr("6581 filter range (reSIDfp, custom)"),
              O::SLIDER,
              50,
              0,
              100,
              {},
              "%"},
             {"zxtune.core.sid.filter_8580_curve",
              QObject::tr("8580 filter curve (reSIDfp, custom)"),
              O::SLIDER,
              50,
              0,
              100,
              {},
              "%"},
             {"zxtune.core.sid.filter_bias",
              QObject::tr("6581 filter bias (reSID, custom)"),
              O::SLIDER,
              0,
              -1000,
              1000,
              {},
              "mV"},
             {"zxtune.core.sid.combined_waveforms",
              QObject::tr("Combined waveforms"),
              O::CHOICE,
              0,
              0,
              0,
              {{QObject::tr("Average"), 0}, {QObject::tr("Weak"), 1}, {QObject::tr("Strong"), 2}}},
             {"zxtune.core.sid.strong_pulse_saw",
              QObject::tr("6581 pulse+saw"),
              O::CHOICE,
              0,
              0,
              0,
              {{QObject::tr("Normal (most 6581 chips)"), 0}, {QObject::tr("Strong (chips with loud pulse+saw)"), 1}}},
             {"zxtune.core.sid.digiboost", QObject::tr("MOS8580 digi boost"), O::BOOLEAN, 0},
         }});
    pages.push_back({"dac",
                     QObject::tr("DAC / tracker modules"),
                     {{"zxtune.core.dac.interpolation", QObject::tr("Interpolation"), O::BOOLEAN, 0}}});
    pages.push_back(
        {"saa",
         "SAA1099",
         {
             {"zxtune.core.saa.clockrate",
              QObject::tr("Clockrate"),
              O::CHOICE,
              8000000,
              0,
              0,
              {{QString::fromUtf8("SAM Coup\u00e9 (8 MHz)"), 8000000}, {"Creative Music System (7.16 MHz)", 7159090}}},
             {"zxtune.core.saa.interpolation", QObject::tr("Interpolation"), O::CHOICE, 1, 0, 0, INTERPOLATION},
         }});
    pages.push_back(
        {"mt32",
         "Roland MT-32",
         {
             {"zxtune.core.mt32.model",
              QObject::tr("Model"),
              O::CHOICE,
              0,
              0,
              0,
              {{QObject::tr("Automatic (newest MT-32 first)"), 0}, {"MT-32", 1}, {"CM-32L / LAPC-I", 2}}},
             {"zxtune.core.mt32.voices",
              QObject::tr("Voices"),
              O::CHOICE,
              0,
              0,
              0,
              {{QObject::tr("9 parts (8 melodic + rhythm)"), 0}, {QObject::tr("32 partials (LA synthesis)"), 1}}},
         }});
    const auto seconds = [](std::initializer_list<int> values) {
      std::vector<std::pair<QString, int>> result;
      for (const auto val : values)
      {
        result.emplace_back(QObject::tr("%1 s").arg(val), val);
      }
      return result;
    };
    pages.push_back(
        {"general",
         QObject::tr("Playback"),
         {
             {"zxtune.sound.looped", QObject::tr("Loop track"), O::BOOLEAN, 0},
             {"zxtune.sound.looplimit",
              QObject::tr("Loops limit"),
              O::CHOICE,
              0,
              0,
              0,
              {{QObject::tr("None"), 0},
               {QObject::tr("1 loop"), 1},
               {QObject::tr("2 loops"), 2},
               {QObject::tr("3 loops"), 3},
               {QObject::tr("4 loops"), 4},
               {QObject::tr("5 loops"), 5}}},
             {"zxtune.core.plugins.default_duration",
              QObject::tr("Default duration"),
              O::CHOICE,
              180,
              0,
              0,
              {{QObject::tr("30 seconds"), 30},
               {QObject::tr("1 minute"), 60},
               {QObject::tr("2 minutes"), 120},
               {QObject::tr("3 minutes"), 180},
               {QObject::tr("5 minutes"), 300},
               {QObject::tr("10 minutes"), 600},
               {QObject::tr("15 minutes"), 900},
               {QObject::tr("30 minutes"), 1800}}},
             {"zxtune.sound.silencelimit", QObject::tr("Skip silence after"), O::CHOICE, 0, 0, 0,
              seconds({0, 3, 4, 5, 10, 15})},
             {"zxtune.sound.fadein", QObject::tr("Fade in"), O::CHOICE, 0, 0, 0, seconds({0, 1, 2, 3, 5, 10})},
             {"zxtune.sound.fadeout", QObject::tr("Fade out"), O::CHOICE, 0, 0, 0, seconds({0, 1, 2, 3, 5, 10})},
             {"zxtune.sound.gain", QObject::tr("Gain"), O::SLIDER, 100, 50, 200, {}, "%"},
         }});
    // backends read these when playback starts, so they are not live
    pages.push_back(
        {"output",
         QObject::tr("Output"),
         {
             {Settings::LATENCY_AUTO, QObject::tr("Estimate latency compensation by output buffers"), O::BOOLEAN, 1},
         }});
    // only buffers of the platform's backends are shown
    auto& output = pages.back().Options;
#ifdef Q_OS_WIN
    output.push_back({"zxtune.sound.backends.dsound.latency",
                      QObject::tr("DirectSound buffer"),
                      O::SLIDER,
                      100,
                      20,
                      1000,
                      {},
                      "ms"});
    output.push_back(
        {"zxtune.sound.backends.win32.buffers", QObject::tr("WinMM buffers"), O::SLIDER, 3, 3, 10, {}, ""});
#else
    output.push_back(
        {"zxtune.sound.backends.alsa.latency", QObject::tr("ALSA buffer"), O::SLIDER, 100, 20, 1000, {}, "ms"});
#endif
    return pages;
  }

  const std::vector<OptionsPage>& Settings::Pages()
  {
    static const auto INSTANCE = MakePages();
    return INSTANCE;
  }

  const OptionDesc* Settings::Find(const QString& key)
  {
    for (const auto& page : Pages())
    {
      for (const auto& desc : page.Options)
      {
        if (desc.Key == key)
        {
          return &desc;
        }
      }
    }
    return nullptr;
  }

  Settings::Settings(Engine& engine, QObject* parent)
    : QObject(parent)
    , Player(engine)
    , Store("zxtune", "lhuna-desktop")
  {
    // only explicitly changed values are passed to engine, others are native defaults
    for (const auto& key : Store.allKeys())
    {
      if (key.startsWith("zxtune."))
      {
        Apply(key, Store.value(key).toInt());
      }
    }
    ApplyOutput();
  }

  int Settings::Get(const QString& key) const
  {
    const auto* desc = Find(key);
    // view options without controls at settings pages
    const auto def = desc ? desc->Default : (key == DASHBOARD ? 1 : 0);
    return Store.value(key, def).toInt();
  }

  void Settings::Set(const QString& key, int value)
  {
    if (Get(key) == value && Store.contains(key))
    {
      return;
    }
    Store.setValue(key, value);
    Apply(key, value);
    emit changed(key);
  }

  QString Settings::GetString(const QString& key) const
  {
    return Store.value(key).toString();
  }

  void Settings::SetString(const QString& key, const QString& value)
  {
    if (GetString(key) == value && Store.contains(key))
    {
      return;
    }
    Store.setValue(key, value);
    if (key == OUTPUT_BACKEND || key == OUTPUT_DEVICE)
    {
      ApplyOutput();
    }
    emit changed(key);
  }

  void Settings::ApplyOutput()
  {
    Player.SetOutput(GetString(OUTPUT_BACKEND).toStdString(), GetString(OUTPUT_DEVICE).toStdString());
    Player.SetLatencyCompensation(EffectiveLatency());
  }

  int Settings::EffectiveLatency() const
  {
    if (Get(LATENCY_AUTO))
    {
      // unknown buffering (e.g. PulseAudio) falls back to manual value
      if (const auto estimated = Player.EstimatedLatencyMs())
      {
        return static_cast<int>(estimated);
      }
    }
    return Get(LATENCY_MS);
  }

  void Settings::UpdateLatency()
  {
    Player.SetLatencyCompensation(EffectiveLatency());
  }

  void Settings::Apply(const QString& key, int value)
  {
    if (key.startsWith("zxtune."))
    {
      const auto name = key.toStdString();
      Player.Options().SetValue(name, value);
      if (key.startsWith("zxtune.sound.backends."))
      {
        Player.SetLatencyCompensation(EffectiveLatency());
      }
    }
    else if (key == LATENCY_MS || key == LATENCY_AUTO)
    {
      Player.SetLatencyCompensation(EffectiveLatency());
    }
  }

  void Settings::ApplyTriggerPreset(const QString& id)
  {
    if (const auto* preset = Visualization::TriggerPresets::Find(id.toStdString()))
    {
      for (const auto& [name, value] : preset->Values)
      {
        Set(QString::fromStdString(Visualization::TriggerPresets::PREFIX + name), value);
      }
    }
  }

  QString Settings::TriggerPreset() const
  {
    std::map<std::string, int> current;
    for (const auto& param : Visualization::TriggerPresets::Params())
    {
      current[param.Name] = Get(QString::fromStdString(param.Key()));
    }
    return QString::fromStdString(Visualization::TriggerPresets::Detect(current));
  }
}  // namespace Lhuna
