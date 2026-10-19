/**
 *
 * @file
 *
 * @brief Oscilloscope trigger parameters presets
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <map>
#include <string>
#include <vector>

namespace Visualization::TriggerPresets
{
  /**
   * Named sets of correlation trigger parameters (native 'zxtune.sound.scope.trigger.*' options, see
   * Parameters::ZXTune::Sound::Scope). Values are based on corrscope's template config and recommendations of its
   * help page, checked by traces stability benchmark (src/module/test/scope_bench) on real tunes.
   */
  const std::string PREFIX = "zxtune.sound.scope.trigger.";
  const std::string CUSTOM = "custom";
  const std::string DEFAULT = "default";

  enum class Type
  {
    INT,
    BOOLEAN,
    CHOICE
  };

  struct Param
  {
    std::string Name;
    Type Kind;
    //! the same as native default
    int Default;
    int Min;
    int Max;

    std::string Key() const
    {
      return PREFIX + Name;
    }
  };

  const std::vector<Param>& Params();

  struct Preset
  {
    std::string Id;
    std::string Title;
    //! all the parameters values by names
    std::map<std::string, int> Values;
  };

  const std::vector<Preset>& Presets();

  const Preset* Find(const std::string& id);

  //! @return id of preset matching current values (by names, absent ones are defaults) or CUSTOM
  std::string Detect(const std::map<std::string, int>& current);
}  // namespace Visualization::TriggerPresets
