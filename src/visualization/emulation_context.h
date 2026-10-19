/**
 *
 * @file
 *
 * @brief Emulated chips of the playing tune, to show only the settings affecting it
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <set>
#include <string>
#include <vector>

namespace Visualization
{
  /**
   * Chip ids are the same as options groups (zxtune.core.<id>.*), so live settings panel shows only the emulation
   * settings of the playing tune's chips.
   */
  struct EmulationContext
  {
    static constexpr auto AYM = "aym";
    static constexpr auto Z80 = "z80";
    static constexpr auto FM = "fm";
    static constexpr auto DAC = "dac";
    static constexpr auto SAA = "saa";
    static constexpr auto MT32 = "mt32";
    static constexpr auto SID = "sid";

    //! ids of options groups with emulation settings
    std::set<std::string> Chips;
    //! human readable chips names, e.g. for chips without any settings
    std::vector<std::string> Names;

    /**
     * @param type module type (core plugin id), empty if unknown
     * @param groups voices groups names of currently playing tune, see VoicesLayout
     * @param description emulation description, the first line of Sound::Scope::GetStatus
     */
    static EmulationContext Detect(const std::string& type, const std::vector<std::string>& groups,
                                   const std::string& description);
  };
}  // namespace Visualization
