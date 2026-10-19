/**
 *
 * @file
 *
 * @brief Oscilloscope trigger parameters presets implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "visualization/trigger_presets.h"

namespace Visualization::TriggerPresets
{
  const std::vector<Param>& Params()
  {
    static const std::vector<Param> INSTANCE = {
        {"width", Type::INT, 60, 10, 200},          {"edge_strength", Type::INT, 50, 0, 300},
        {"slope_width", Type::INT, 25, 5, 100},     {"buffer_strength", Type::INT, 100, 0, 300},
        {"responsiveness", Type::INT, 100, 0, 100}, {"buffer_falloff", Type::INT, 50, 10, 200},
        {"reset_below", Type::INT, 70, 0, 100},     {"mean_responsiveness", Type::INT, 0, 0, 100},
        {"sign_strength", Type::INT, 0, 0, 300},    {"diameter", Type::INT, 50, 10, 100},
        {"falling", Type::BOOLEAN, 0, 0, 1},        {"pitch_tracking", Type::BOOLEAN, 1, 0, 1},
        {"post_radius", Type::INT, 0, 0, 16},       {"search_rate", Type::CHOICE, 0, 0, 60},
        {"subsampling", Type::CHOICE, 0, 0, 4},
    };
    return INSTANCE;
  }

  Preset MakePreset(std::string id, std::string title, const std::map<std::string, int>& diff)
  {
    Preset result{std::move(id), std::move(title), {}};
    for (const auto& param : Params())
    {
      const auto it = diff.find(param.Name);
      result.Values[param.Name] = it != diff.end() ? it->second : param.Default;
    }
    return result;
  }

  const std::vector<Preset>& Presets()
  {
    static const std::vector<Preset> INSTANCE = {
        // The most stable on benchmark for all the chips: 60ms width catches lower notes; reduced edge strength
        // tracks sustained notes, buffer is reset on new notes, boosted responsiveness. Searched at each frame.
        MakePreset(DEFAULT, "Default", {}),
        // corrscope's template_config: trigger_ms=40, mean_responsiveness=0, edge_strength=1, responsiveness=0.5,
        // reset_below=0.3, pitch_tracking=on
        MakePreset("corrscope", "Corrscope",
                   {{"width", 40}, {"edge_strength", 100}, {"responsiveness", 50}, {"reset_below", 30}}),
        // Help: increasing edge strength tracks new notes better, post trigger slides to exact edge
        MakePreset(
            "chiptune", "Chiptune",
            {{"width", 40}, {"edge_strength", 150}, {"responsiveness", 50}, {"reset_below", 30}, {"post_radius", 4}}),
        // Help: for low bass notes increase trigger width (period > 20ms requires > 40ms)
        MakePreset("bass", "Bass", {{"width", 100}}),
        // Help: NES triangle waves - no DC removal, buffer strength 0, sign amplification 1
        MakePreset("triangle", "Triangle waves",
                   {{"buffer_strength", 0}, {"sign_strength", 100}, {"mean_responsiveness", 0}}),
        // fewer full searches, phase between them is extrapolated
        MakePreset("power_saving", "Power saving", {{"search_rate", 30}}),
    };
    return INSTANCE;
  }

  const Preset* Find(const std::string& id)
  {
    for (const auto& preset : Presets())
    {
      if (preset.Id == id)
      {
        return &preset;
      }
    }
    return nullptr;
  }

  std::string Detect(const std::map<std::string, int>& current)
  {
    std::map<std::string, int> full;
    for (const auto& param : Params())
    {
      const auto it = current.find(param.Name);
      full[param.Name] = it != current.end() ? it->second : param.Default;
    }
    for (const auto& preset : Presets())
    {
      if (preset.Values == full)
      {
        return preset.Id;
      }
    }
    return CUSTOM;
  }
}  // namespace Visualization::TriggerPresets
