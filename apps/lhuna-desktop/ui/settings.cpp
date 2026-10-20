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
#include "visualization/trigger_presets.h"

namespace Lhuna
{
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
             {Settings::LATENCY_MS, QObject::tr("Output latency compensation"), O::SLIDER, 100, 0, 500, {}, "ms"},
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
               {"Atari ST (2 MHz)", 2000000},
               {"Amstrad CPC (1 MHz)", 1000000}}},
         }});
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
             {"zxtune.core.sid.filter_6581_curve",
              QObject::tr("6581 filter curve (reSIDfp)"),
              O::SLIDER,
              50,
              0,
              100,
              {},
              "%"},
             {"zxtune.core.sid.filter_6581_range",
              QObject::tr("6581 filter range (reSIDfp)"),
              O::SLIDER,
              50,
              0,
              100,
              {},
              "%"},
             {"zxtune.core.sid.filter_8580_curve",
              QObject::tr("8580 filter curve (reSIDfp)"),
              O::SLIDER,
              50,
              0,
              100,
              {},
              "%"},
             {"zxtune.core.sid.filter_bias",
              QObject::tr("6581 filter bias (reSID)"),
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
             {"zxtune.core.sid.digiboost", QObject::tr("MOS8580 digi boost"), O::BOOLEAN, 0},
         }});
    pages.push_back({"dac",
                     QObject::tr("DAC / tracker modules"),
                     {{"zxtune.core.dac.interpolation", QObject::tr("Interpolation"), O::BOOLEAN, 0}}});
    pages.push_back(
        {"saa",
         "SAA1099",
         {{"zxtune.core.saa.interpolation", QObject::tr("Interpolation"), O::CHOICE, 1, 0, 0, INTERPOLATION}}});
    pages.push_back({"general",
                     QObject::tr("Playback"),
                     {
                         {"zxtune.sound.looped", QObject::tr("Loop track"), O::BOOLEAN, 0},
                         {"zxtune.sound.fadeout", QObject::tr("Fade out"), O::SLIDER, 0, 0, 10, {}, "s"},
                     }});
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
    , Storage("zxtune", "lhuna-desktop")
  {
    // only explicitly changed values are passed to engine, others are native defaults
    for (const auto& key : Storage.allKeys())
    {
      if (key.startsWith("zxtune."))
      {
        Apply(key, Storage.value(key).toInt());
      }
    }
    Player.SetLatencyCompensation(Get(LATENCY_MS));
  }

  int Settings::Get(const QString& key) const
  {
    const auto* desc = Find(key);
    return Storage.value(key, desc ? desc->Default : 0).toInt();
  }

  void Settings::Set(const QString& key, int value)
  {
    if (Get(key) == value && Storage.contains(key))
    {
      return;
    }
    Storage.setValue(key, value);
    Apply(key, value);
    emit changed(key);
  }

  void Settings::Apply(const QString& key, int value)
  {
    if (key.startsWith("zxtune."))
    {
      const auto name = key.toStdString();
      Player.Options().SetValue(name, value);
    }
    else if (key == LATENCY_MS)
    {
      Player.SetLatencyCompensation(value);
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
