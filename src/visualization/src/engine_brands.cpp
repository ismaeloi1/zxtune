/**
 *
 * @file
 *
 * @brief Engine brands and original driver lines implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "visualization/engine_brands.h"

#include <algorithm>
#include <cctype>
#include <map>
#include <set>
#include <utility>

namespace Visualization
{
  namespace EngineBrandsDetails
  {
    const std::map<std::string, std::string> BRANDS = {
        {"C64", "NebulaWeave3"},
        {"SNES", "NebulaWeaveSPC"},
        {"AY", "NebulaWeaveAY"},
        // both OPN (YM2203/YM2608/YM2610/YM2612, TurboFM) and OPL families
        {"OPL", "NebulaWeaveOPL"},
        {"OPM", "NebulaWeaveOPM"},
        {"PSG", "NebulaWeavePSG"},
        {"SAA", "NebulaWeaveSAA"},
        {"NES", "NebulaWeaveNES"},
        {"GB", "NebulaWeaveGB"},
        {"GBA", "NebulaWeaveGBA"},
        {"NDS", "NebulaWeaveNDS"},
        {"PCE", "NebulaWeavePCE"},
        {"MSX", "NebulaWeaveMSX"},
        {"POKEY", "NebulaWeavePOKEY"},
        {"PSX", "NebulaWeavePSX"},
        {"N64", "NebulaWeaveN64"},
        {"SATURN", "NebulaWeaveSCSP"},
        {"DREAMCAST", "NebulaWeaveAICA"},
        {"MT32", "NebulaWeaveMT32"},
        {"DAC", "NebulaWeaveDAC"},
        {"MOD", "NebulaWeaveMOD"},
        {"V2", "NebulaWeaveV2"},
        {"PCM", "NebulaWeavePCM"},
    };

    const std::string DEFAULT_SYSTEM = "PCM";

    // Multichip formats (VGM, S98) and ambiguous types (PSM, STP) are resolved by chip in description
    std::map<std::string, std::string> MakeByType()
    {
      std::map<std::string, std::string> result;
      const auto add = [&result](const std::string& system, std::initializer_list<const char*> types) {
        for (const auto* type : types)
        {
          result.emplace(type, system);
        }
      };
      add("C64", {"SID"});
      add("SNES", {"SPC", "SNSF"});
      add("AY", {"AS0", "ASC", "AY",  "AYC", "FTC", "GTR", "PSC", "PSG", "PT1", "PT2",
                 "PT3", "SQT", "ST1", "ST3", "STC", "TS",  "TXT", "VTX", "YM",  "MTC"});
      add("OPL", {"TF0", "TFC", "TFD", "TFE", "GYM"});
      add("SAA", {"COP"});
      add("NES", {"NSF", "NSFE"});
      add("GB", {"GBS"});
      add("GBA", {"GSF"});
      add("NDS", {"2SF", "NCSF"});
      add("PCE", {"HES"});
      add("MSX", {"KSS", "KSSX"});
      add("POKEY", {"SAP", "RMT"});
      add("PSX", {"PSF", "PSF2"});
      add("N64", {"USF"});
      add("SATURN", {"SSF"});
      add("DREAMCAST", {"DSF"});
      add("MT32", {"MIDI"});
      add("DAC", {"CHI", "DMM", "DST", "ET1", "PDT", "SQD", "STR"});
      add("V2", {"V2M"});
      add("MOD", {"667",  "669",  "AHX",  "AMF", "AMS", "C67", "CBA",  "DBM", "DMF", "DSM",    "DSYM", "DTM", "DTT",
                  "EMOD", "ETX",  "FAR",  "FC",  "FMT", "FNK", "FTM",  "GDM", "GMC", "GTK",    "HVL",  "IMF", "IMS",
                  "IT",   "J2B",  "KRIS", "LIQ", "MDL", "MED", "MO3",  "MOD", "MT2", "MTM",    "NRU",  "OKT", "PLM",
                  "PTM",  "PUMA", "RTM",  "S3M", "SFX", "SS",  "STIM", "STM", "STX", "SYMMOD", "TCB",  "ULT", "XM"});
      return result;
    }

    const std::map<std::string, std::string> BY_TYPE = MakeByType();

    // first match wins
    const std::vector<std::pair<std::string, std::string>> BY_CHIP = {
        {"MOS6581", "C64"}, {"MOS8580", "C64"}, {"S-DSP", "SNES"}, {"AY-3-8910", "AY"}, {"YM2149", "AY"},
        {"TurboFM", "OPL"}, {"YM2203", "OPL"},  {"YM2608", "OPL"}, {"YM2610", "OPL"},   {"YM2612", "OPL"},
        {"YM3438", "OPL"},  {"YM2413", "OPL"},  {"YM3526", "OPL"}, {"YM3812", "OPL"},   {"Y8950", "OPL"},
        {"YMF262", "OPL"},  {"YMF278", "OPL"},  {"YM2151", "OPM"}, {"SN76489", "PSG"},  {"SAA1099", "SAA"},
        {"2A03", "NES"},    {"NES APU", "NES"}, {"GB DMG", "GB"},  {"HuC6280", "PCE"},  {"POKEY", "POKEY"},
    };

    // the same ids for AY and openmpt formats, AY variant always has description
    const std::map<std::string, std::string> AMBIGUOUS_TYPES = {{"PSM", "MOD"}, {"STP", "MOD"}};

    // Program property of these formats is a game name, sound driver is a part of game code
    const std::set<std::string> GAME_DUMPS = {"SPC",  "SNSF", "NSF", "NSFE", "GBS", "HES", "KSS",
                                              "KSSX", "SAP",  "VGM", "S98",  "GYM", "PSF", "PSF2",
                                              "USF",  "GSF",  "2SF", "NCSF", "SSF", "DSF", "AY"};

    const std::string UNKNOWN = "unknown";
    const std::string SCOPE_PREFIX = "scope:";

    std::string Lower(std::string str)
    {
      std::transform(str.begin(), str.end(), str.begin(), [](unsigned char c) { return std::tolower(c); });
      return str;
    }

    bool ContainsIgnoreCase(const std::string& text, const std::string& what)
    {
      return Lower(text).find(Lower(what)) != std::string::npos;
    }

    bool IsBlank(const std::string& str)
    {
      return std::all_of(str.begin(), str.end(), [](unsigned char c) { return std::isspace(c); });
    }

    std::vector<std::string> SplitLines(const std::string& text)
    {
      std::vector<std::string> result;
      std::size_t start = 0;
      for (;;)
      {
        const auto end = text.find('\n', start);
        result.push_back(text.substr(start, end == std::string::npos ? std::string::npos : end - start));
        if (end == std::string::npos)
        {
          return result;
        }
        start = end + 1;
      }
    }
  }  // namespace EngineBrandsDetails

  namespace EngineBrands
  {
    std::string Brand(const std::string& system)
    {
      const auto it = EngineBrandsDetails::BRANDS.find(system);
      return it != EngineBrandsDetails::BRANDS.end()
                 ? it->second
                 : EngineBrandsDetails::BRANDS.at(EngineBrandsDetails::DEFAULT_SYSTEM);
    }

    std::string SystemOf(const std::string& type, const std::string& description)
    {
      if (const auto it = EngineBrandsDetails::BY_TYPE.find(type); it != EngineBrandsDetails::BY_TYPE.end())
      {
        return it->second;
      }
      for (const auto& [chip, system] : EngineBrandsDetails::BY_CHIP)
      {
        if (EngineBrandsDetails::ContainsIgnoreCase(description, chip))
        {
          return system;
        }
      }
      if (const auto it = EngineBrandsDetails::AMBIGUOUS_TYPES.find(type);
          it != EngineBrandsDetails::AMBIGUOUS_TYPES.end())
      {
        return it->second;
      }
      return EngineBrandsDetails::DEFAULT_SYSTEM;
    }

    std::string BrandOf(const std::string& type, const std::string& description)
    {
      return Brand(SystemOf(type, description));
    }
  }  // namespace EngineBrands

  namespace StatusHeader
  {
    std::string DriverOf(const std::string& type, const std::string& program,
                         const std::function<std::string()>& formatName)
    {
      if (EngineBrandsDetails::GAME_DUMPS.contains(type))
      {
        return EngineBrandsDetails::IsBlank(program) ? "in-game" : program + " (in-game)";
      }
      if (!EngineBrandsDetails::IsBlank(program))
      {
        return program;
      }
      // playroutine is detected by signature, format name says nothing about it
      if (type == "SID")
      {
        return EngineBrandsDetails::UNKNOWN;
      }
      auto name = formatName ? formatName() : std::string();
      return EngineBrandsDetails::IsBlank(name) ? EngineBrandsDetails::UNKNOWN : name;
    }

    bool IsHeader(const std::string& line)
    {
      return line.starts_with(DRIVER_PREFIX) || line.starts_with(ENGINE_PREFIX);
    }

    std::vector<std::string> Lines(const std::string& type, const std::string& program, const std::string& description,
                                   const std::function<std::string()>& formatName)
    {
      return {DRIVER_PREFIX + DriverOf(type, program, formatName),
              ENGINE_PREFIX + EngineBrands::BrandOf(type, description)};
    }

    std::string Insert(const std::string& status, const std::string& type, const std::string& program,
                       const std::function<std::string()>& formatName)
    {
      auto lines = EngineBrandsDetails::SplitLines(status);
      const auto hasDescription = !lines.empty() && !EngineBrandsDetails::IsBlank(lines[0])
                                  && !lines[0].starts_with(EngineBrandsDetails::SCOPE_PREFIX);
      const auto header = Lines(type, program, hasDescription ? lines[0] : std::string(), formatName);
      lines.insert(lines.begin() + (hasDescription ? 1 : 0), header.begin(), header.end());
      std::string result;
      for (const auto& line : lines)
      {
        if (!result.empty())
        {
          result += '\n';
        }
        result += line;
      }
      return result;
    }
  }  // namespace StatusHeader
}  // namespace Visualization
