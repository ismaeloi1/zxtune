/**
 *
 * @file
 *
 * @brief Emulated chips detection implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "visualization/emulation_context.h"

#include <algorithm>

namespace Visualization
{
  namespace EmulationContextDetails
  {
    // Module types (core plugins ids), see src/core/plugins/players
    const std::set<std::string> AY_TYPES = {"AS0", "ASC", "AY",  "AYC", "FTC", "GTR", "PSC", "PSG", "PSM", "PT1", "PT2",
                                            "PT3", "SQT", "ST1", "ST3", "STC", "STP", "TS",  "TXT", "VTX", "YM"};
    // Only AY files with player code are emulated with Z80, others are registers dumps or trackers
    const std::set<std::string> Z80_TYPES = {"AY"};
    const std::set<std::string> TFM_TYPES = {"TF0", "TFC", "TFD", "TFE"};
    // ZX digital trackers and tracker modules played by libxmp/libopenmpt use the same interpolation option
    const std::set<std::string> DAC_TYPES = {
        "CHI", "DMM",  "DST", "ET1",  "PDT",  "SQD", "STR",  "667", "669", "AMF", "AMS",    "C67", "CBA", "DBM", "DMF",
        "DSM", "DSYM", "DTM", "DTT",  "EMOD", "ETX", "FAR",  "FC",  "FMT", "FNK", "FTM",    "GDM", "GMC", "GTK", "IMF",
        "IMS", "IT",   "J2B", "KRIS", "LIQ",  "MDL", "MED",  "MO3", "MOD", "MT2", "MTM",    "NRU", "OKT", "PLM", "PSM",
        "PTM", "PUMA", "RTM", "S3M",  "SFX",  "SS",  "STIM", "STM", "STP", "STX", "SYMMOD", "TCB", "ULT", "XM"};
    const std::set<std::string> SAA_TYPES = {"COP"};
    // Multitrack ZX container: any of ZX chips is possible
    const std::string MTC = "MTC";

    bool Contains(const std::string& text, const char* what)
    {
      return text.find(what) != std::string::npos;
    }
  }  // namespace EmulationContextDetails

  EmulationContext EmulationContext::Detect(const std::string& type, const std::vector<std::string>& groups,
                                            const std::string& description)
  {
    using namespace EmulationContextDetails;
    // Names are given by zxtune's own chips emulation, libvgm/gme names differ (e.g. AY8910), so options of zxtune's
    // chips are not offered for them
    std::set<std::string> live;
    auto all = groups;
    all.push_back(description);
    if (std::any_of(all.begin(), all.end(),
                    [](const std::string& s) { return Contains(s, "AY-3-8910") || Contains(s, "YM2149F"); }))
    {
      live.insert(AYM);
    }
    if (Contains(description, "TurboFM"))
    {
      live.insert(FM);
    }
    if (std::any_of(groups.begin(), groups.end(), [](const std::string& s) { return s.starts_with("SID"); })
        || Contains(description, "MOS"))
    {
      live.insert(SID);
    }
    if (Contains(description, "Munt"))
    {
      live.insert(MT32);
    }
    std::set<std::string> byType;
    if (AY_TYPES.contains(type))
    {
      byType.insert(AYM);
    }
    if (Z80_TYPES.contains(type))
    {
      byType.insert(Z80);
    }
    if (TFM_TYPES.contains(type))
    {
      byType.insert(FM);
    }
    if (DAC_TYPES.contains(type))
    {
      byType.insert(DAC);
    }
    if (SAA_TYPES.contains(type))
    {
      byType.insert(SAA);
    }
    if (type == "SID")
    {
      byType.insert(SID);
    }
    if (type == "MIDI")
    {
      byType.insert(MT32);
    }
    if (type == MTC)
    {
      byType.insert({AYM, FM, DAC, SAA});
    }
    // the same type id is used by different formats (e.g. PSM for ProSoundMaker and Epic MegaGames), actual chips
    // are known only when playback is started
    const auto known = !groups.empty() || description.find_first_not_of(" \t") != std::string::npos;
    if (known && AY_TYPES.contains(type) && DAC_TYPES.contains(type))
    {
      byType.erase(live.contains(AYM) ? DAC : AYM);
    }
    EmulationContext result;
    result.Chips = live;
    result.Chips.insert(byType.begin(), byType.end());
    for (const auto& group : groups)
    {
      const auto name = group.substr(0, group.find('/'));
      if (std::find(result.Names.begin(), result.Names.end(), name) == result.Names.end())
      {
        result.Names.push_back(name);
      }
    }
    if (result.Names.empty())
    {
      const auto name = description.substr(0, description.find(','));
      if (name.find_first_not_of(" \t") != std::string::npos)
      {
        result.Names.push_back(name);
      }
    }
    return result;
  }
}  // namespace Visualization
