/**
 *
 * @file
 *
 * @brief Accessors to oscilloscope and gauges data of Sound::Scope
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "module/voices_scope.h"
#include "sound/scope.h"

#include "string_type.h"
#include "types.h"

#include <algorithm>
#include <cstring>
#include <vector>

namespace ScopeData
{
  //! Decoded Sound::Scope::GetLayout result
  struct VoicesLayout
  {
    struct Group
    {
      String Name;
      std::vector<String> Voices;
    };

    //! SID-like chips with registers state, see Sound::Scope::GetGauges, else Sound::Scope::GetVoiceGauges
    bool HasRegisters = false;
    std::vector<Group> Groups;

    uint_t VoicesCount() const
    {
      uint_t result = 0;
      for (const auto& group : Groups)
      {
        result += static_cast<uint_t>(group.Voices.size());
      }
      return result;
    }

    static VoicesLayout Parse(const String& description)
    {
      VoicesLayout result;
      std::vector<String> lines;
      for (std::size_t pos = 0; pos <= description.size();)
      {
        const auto end = std::min(description.find('\n', pos), description.size());
        if (end != pos)
        {
          lines.emplace_back(description.substr(pos, end - pos));
        }
        pos = end + 1;
      }
      // single master channel
      if (lines.size() < 2)
      {
        return result;
      }
      result.HasRegisters = lines.front() == "registers";
      for (auto it = lines.begin() + 1; it != lines.end(); ++it)
      {
        Group group;
        std::size_t pos = 0;
        for (bool first = true; pos <= it->size(); first = false)
        {
          const auto end = std::min(it->find('\t', pos), it->size());
          auto field = it->substr(pos, end - pos);
          if (first)
          {
            group.Name = std::move(field);
          }
          else
          {
            group.Voices.emplace_back(std::move(field));
          }
          pos = end + 1;
        }
        result.Groups.emplace_back(std::move(group));
      }
      return result;
    }
  };

  //! Columns of (min, max) bytes
  class Gauges
  {
  public:
    explicit Gauges(const uint8_t* data)
      : Data(data)
    {}

    uint_t Min(uint_t gauge, uint_t column) const
    {
      return Data[(gauge * Sound::Scope::GAUGE_COLUMNS + column) * 2];
    }

    uint_t Max(uint_t gauge, uint_t column) const
    {
      return Data[(gauge * Sound::Scope::GAUGE_COLUMNS + column) * 2 + 1];
    }

  protected:
    const uint8_t* const Data;
  };

  //! Single voice of Sound::Scope::GetVoiceGauges
  class VoiceGauges : public Gauges
  {
  public:
    enum
    {
      WAVE = 0,
      LEVEL = 1,
      FREQUENCY = 2
    };

    explicit VoiceGauges(const uint8_t* data)
      : Gauges(data)
      , State(data + Sound::Scope::VOICE_GAUGES_COUNT * Sound::Scope::GAUGE_COLUMNS * 2)
    {}

    //! Hz, valid if HasFrequency
    float Frequency() const
    {
      return GetFloat(0);
    }

    //! dB, 0 or negative, valid if HasLevel
    float Level() const
    {
      return GetFloat(4);
    }

    bool IsKeyOn() const
    {
      return State[8] & Module::VoiceState::KEY_ON;
    }

    bool HasFrequency() const
    {
      return State[8] & Module::VoiceState::HAS_FREQUENCY;
    }

    bool HasLevel() const
    {
      return State[8] & Module::VoiceState::HAS_LEVEL;
    }

    bool IsNoise() const
    {
      return State[8] & Module::VoiceState::NOISE;
    }

    //! Module::VoiceState::Kind
    uint_t Kind() const
    {
      return State[9];
    }

    uint_t Field(uint_t idx) const
    {
      return State[10 + idx];
    }

    int SignedField(uint_t idx) const
    {
      return static_cast<int8_t>(State[10 + idx]);
    }

    String Text() const
    {
      const auto* const begin = reinterpret_cast<const char*>(State + 10 + Module::VoiceState::FIELDS);
      std::size_t size = 0;
      while (size < Module::VoiceState::TEXT && begin[size])
      {
        ++size;
      }
      return {begin, size};
    }

  private:
    // stored in little endian as all the supported platforms are
    float GetFloat(uint_t offset) const
    {
      float result = 0;
      std::memcpy(&result, State + offset, sizeof(result));
      return result;
    }

  private:
    const uint8_t* const State;
  };

  //! Single chip of Sound::Scope::GetGauges
  class ChipGauges : public Gauges
  {
  public:
    // wave and volume columns are 128 CPU cycles, others are 16384 cycles
    static uint_t Wave(uint_t voice)
    {
      return voice;
    }

    static uint_t Envelope(uint_t voice)
    {
      return 3 + voice;
    }

    static uint_t Frequency(uint_t voice)
    {
      return 6 + voice;
    }

    enum
    {
      VOLUME = 9,
      RESONANCE = 10,
      CUTOFF = 11
    };

    explicit ChipGauges(const uint8_t* data)
      : Gauges(data)
      , Regs(data + Sound::Scope::GAUGES_COUNT * Sound::Scope::GAUGE_COLUMNS * 2)
    {}

    uint_t Register(uint_t idx) const
    {
      return Regs[idx];
    }

    uint_t VoiceFrequency(uint_t voice) const
    {
      return Regs[voice * 7] | (Regs[voice * 7 + 1] << 8);
    }

    uint_t Control(uint_t voice) const
    {
      return Regs[voice * 7 + 4];
    }

    uint_t AttackDecay(uint_t voice) const
    {
      return Regs[voice * 7 + 5];
    }

    uint_t SustainRelease(uint_t voice) const
    {
      return Regs[voice * 7 + 6];
    }

    uint_t Cutoff() const
    {
      return (Regs[0x15] & 7) | (Regs[0x16] << 3);
    }

    uint_t Resonance() const
    {
      return Regs[0x17] >> 4;
    }

    uint_t Routing() const
    {
      return Regs[0x17] & 15;
    }

    uint_t FilterMode() const
    {
      return (Regs[0x18] >> 4) & 7;
    }

    uint_t Volume() const
    {
      return Regs[0x18] & 15;
    }

  private:
    const uint8_t* const Regs;
  };
}  // namespace ScopeData
