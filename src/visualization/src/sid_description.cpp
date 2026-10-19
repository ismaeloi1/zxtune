/**
 *
 * @file
 *
 * @brief Human readable description of SID registers state implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "visualization/sid_description.h"

#include "visualization/format.h"
#include "visualization/notes.h"

#include <algorithm>
#include <cmath>
#include <utility>

namespace Visualization::SidDescription
{
  // Rates are taken from 6581 datasheet, 8580 has the same timings
  const int ATTACK_MS[] = {2, 8, 16, 24, 38, 56, 68, 80, 100, 250, 500, 800, 1000, 3000, 5000, 8000};
  const int DECAY_MS[] = {6, 24, 48, 72, 114, 168, 204, 240, 300, 750, 1500, 2400, 3000, 9000, 15000, 24000};

  // Rough average of measured 6581 cutoff curves (they differ noticeably between chips), cutoff register to Hz
  const std::pair<int, float> CUTOFF_6581[] = {{0, 220.f},     {384, 300.f},    {512, 420.f},   {640, 780.f},
                                               {768, 1600.f},  {896, 3000.f},   {1024, 4400.f}, {1280, 6000.f},
                                               {1536, 8500.f}, {1792, 12500.f}, {2047, 18000.f}};

  const int GATE = 0x01;
  const int SYNC = 0x02;
  const int RING = 0x04;
  const int TEST = 0x08;
  const int TRIANGLE = 0x10;
  const int SAWTOOTH = 0x20;
  const int PULSE = 0x40;
  const int NOISE = 0x80;

  const int MODE_LOWPASS = 1;
  const int MODE_BANDPASS = 2;
  const int MODE_HIGHPASS = 4;
  const int MODE_VOICE3_OFF = 8;

  Chip Chip::FromStatus(std::string_view status)
  {
    return {status.find("NTSC") != std::string_view::npos ? NTSC_CLOCK : PAL_CLOCK,
            status.find("8580") != std::string_view::npos};
  }

  std::string_view PhaseShort(Phase phase)
  {
    static const std::string_view NAMES[] = {"Atk", "Dec", "Sus", "Rel", "Off"};
    return NAMES[static_cast<int>(phase)];
  }

  std::string_view PhaseWord(Phase phase)
  {
    static const std::string_view NAMES[] = {Words::ATTACK, Words::DECAY, Words::SUSTAIN, Words::RELEASE,
                                             Words::SILENT};
    return NAMES[static_cast<int>(phase)];
  }

  int AttackMs(int nibble)
  {
    return ATTACK_MS[nibble & 15];
  }

  int DecayMs(int nibble)
  {
    return DECAY_MS[nibble & 15];
  }

  int SustainPercent(int nibble)
  {
    return (nibble & 15) * 100 / 15;
  }

  float FrequencyHz(int reg, const Chip& chip)
  {
    return static_cast<float>(reg) * chip.Clock / 16777216.f;
  }

  int PulsePercent(int reg)
  {
    return static_cast<int>(std::lround((reg & 0xfff) * 100.f / 4096.f));
  }

  float CutoffHz(int reg, const Chip& chip)
  {
    const auto cutoff = std::clamp(reg, 0, 2047);
    if (chip.Is8580)
    {
      // almost linear, ~12kHz at maximum
      return 30.f + cutoff * 5.8f;
    }
    std::size_t upper = 1;
    while (upper < std::size(CUTOFF_6581) - 1 && CUTOFF_6581[upper].first < cutoff)
    {
      ++upper;
    }
    const auto [x0, y0] = CUTOFF_6581[upper - 1];
    const auto [x1, y1] = CUTOFF_6581[upper];
    return y0 + (y1 - y0) * (cutoff - x0) / (x1 - x0);
  }

  int ModulatorOf(int voice)
  {
    return (voice + 2) % 3;
  }

  std::string WaveName(int control, int pulsePercent)
  {
    std::string result;
    int parts = 0;
    // order matches common naming of combined waveforms (e.g. "Pulse+Saw")
    const std::pair<int, std::string_view> NAMES[] = {
        {NOISE, "Noise"}, {PULSE, "Pulse"}, {SAWTOOTH, "Saw"}, {TRIANGLE, "Tri"}};
    for (const auto& [bit, name] : NAMES)
    {
      if (control & bit)
      {
        if (parts++)
        {
          result += '+';
        }
        result += name;
      }
    }
    if (parts == 0)
    {
      return "None";
    }
    // pulse width is less important than combination itself in narrow title
    if (parts == 1 && (control & PULSE))
    {
      result += ' ' + std::to_string(pulsePercent) + '%';
    }
    return result;
  }

  std::string WaveFlags(int control)
  {
    std::string result;
    if (control & SYNC)
    {
      result += " Sync";
    }
    // ring bit without triangle is silently ignored by chip, so don't claim it (matches Mod cell)
    if (HasRing(control))
    {
      result += " Ring";
    }
    if (control & TEST)
    {
      result += " Test";
    }
    return result;
  }

  bool IsGate(int control)
  {
    return control & GATE;
  }

  bool HasPulse(int control)
  {
    return control & PULSE;
  }

  bool HasSync(int control)
  {
    return control & SYNC;
  }

  bool HasRing(int control)
  {
    return (control & RING) && (control & TRIANGLE);
  }

  Phase GetPhase(const ChipState& state, int voice, int trend)
  {
    const auto env = state.Envelope(voice);
    const auto gate = IsGate(state.Control(voice));
    const auto sustain = (state.SustainRelease(voice) >> 4) * 0x11;
    if (!gate)
    {
      return env == 0 ? Phase::IDLE : Phase::RELEASE;
    }
    else if (env == 0xff || env < sustain)
    {
      return Phase::ATTACK;
    }
    else if (env == sustain)
    {
      return Phase::SUSTAIN;
    }
    else
    {
      return trend > 0 ? Phase::ATTACK : Phase::DECAY;
    }
  }

  int EnvelopePercent(const ChipState& state, int voice)
  {
    return state.Envelope(voice) * 100 / 255;
  }

  std::string_view ModeShort(int mode)
  {
    static const std::string_view NAMES[] = {"Off", "LP", "BP", "LP+BP", "HP", "Notch", "BP+HP", "All"};
    return NAMES[mode & 7];
  }

  std::string_view ModeWord(int mode)
  {
    static const std::string_view NAMES[] = {"Off", "Low", "Band", "Low+Band", "High", "Notch", "Band+High", "All"};
    return NAMES[mode & 7];
  }

  std::string WaveWord(int control)
  {
    if (control & TEST)
    {
      return std::string(Words::RESET);
    }
    // single waveforms are spelled fully, mixes are abbreviated to fit
    switch (control & 0xf0)
    {
    case 0:
      return std::string(Words::NONE);
    case NOISE:
      return std::string(Words::NOISE);
    case PULSE:
      return std::string(Words::PULSE);
    case SAWTOOTH:
      return std::string(Words::SAW);
    case TRIANGLE:
      return std::string(Words::TRIANGLE);
    default:
      return WaveName(control, 0);
    }
  }

  std::string ModulationWord(int control, int voice)
  {
    const auto sync = HasSync(control);
    const auto ring = HasRing(control);
    const auto source = std::to_string(ModulatorOf(voice) + 1);
    if (sync && ring)
    {
      return std::string(Words::SYNC_RING);
    }
    else if (sync)
    {
      return std::string(Words::SYNC) + " V" + source;
    }
    else if (ring)
    {
      return std::string(Words::RING) + " V" + source;
    }
    return std::string(Words::DASH);
  }

  std::string FilterWord(bool filtered, int mode, bool muted)
  {
    // filtered voice is heard only through enabled filter outputs
    if (filtered)
    {
      return std::string((mode & 7) == 0 ? Words::MUTED : ModeWord(mode));
    }
    // typical trick to use voice 3 as modulation source only
    return std::string(muted ? Words::MUTED : Words::DASH);
  }

  bool IsMuted(int voice, int mode, int routing)
  {
    return voice == 2 && (mode & MODE_VOICE3_OFF) && (routing & (1 << voice)) == 0;
  }

  std::string RoutingWord(int routing)
  {
    if ((routing & 15) == 0)
    {
      return std::string(Words::NONE);
    }
    std::string result;
    for (int idx = 0; idx < 4; ++idx)
    {
      if (routing & (1 << idx))
      {
        if (!result.empty())
        {
          result += '+';
        }
        result += idx == 3 ? std::string(Words::EXT) : "V" + std::to_string(idx + 1);
      }
    }
    return result;
  }

  std::string Percent(int value)
  {
    return std::to_string(value) + '%';
  }

  std::string PercentOf15(int value)
  {
    return Percent(value * 100 / 15);
  }

  // Spaced units are easier to read in narrow cells, digits are tabular so values don't move
  std::string HzWord(int hz)
  {
    return std::to_string(hz) + " Hz";
  }

  int RoundCutoff(float hz)
  {
    if (hz >= 10000.f)
    {
      return static_cast<int>(std::lround(hz / 1000.f)) * 1000;
    }
    else if (hz >= 1000.f)
    {
      return static_cast<int>(std::lround(hz / 100.f)) * 100;
    }
    else if (hz >= 100.f)
    {
      return static_cast<int>(std::lround(hz / 10.f)) * 10;
    }
    return static_cast<int>(std::lround(hz));
  }

  std::string CutoffWord(int roundedHz)
  {
    if (roundedHz >= 10000)
    {
      return Format("~%d kHz", roundedHz / 1000);
    }
    else if (roundedHz >= 1000)
    {
      return Format("~%d.%d kHz", roundedHz / 1000, roundedHz / 100 % 10);
    }
    return Format("~%d Hz", roundedHz);
  }

  std::string DurationWord(int ms)
  {
    if (ms >= 1000 && ms % 1000 == 0)
    {
      return Format("%d s", ms / 1000);
    }
    else if (ms >= 1000)
    {
      return Format("%d.%d s", ms / 1000, ms / 100 % 10);
    }
    return Format("%d ms", ms);
  }

  std::string FormatHz(float hz)
  {
    if (hz >= 9950.f)
    {
      return Format("%dkHz", static_cast<int>(std::lround(hz / 1000)));
    }
    else if (hz >= 1000.f)
    {
      return Format("%.1fkHz", hz / 1000);
    }
    return Format("%dHz", static_cast<int>(std::lround(hz)));
  }

  int EnvelopeGaugeLevel(int env)
  {
    if (env == 0)
    {
      return 0;
    }
    const auto level = 1.f + 20.f * std::log10(env / 255.f) / 48.f;
    return static_cast<int>(std::clamp(level, 0.f, 1.f) * 255);
  }

  std::string WaveTitle(int control, bool filtered)
  {
    auto result = Format("Wave %x ", control >> 4);
    if (control & 2)
    {
      result += 'S';
    }
    if (control & 4)
    {
      result += 'R';
    }
    if (control & 8)
    {
      result += 'T';
    }
    if (filtered)
    {
      result += 'F';
    }
    return result;
  }

  std::string FilterTitle(int mode, int cutoff)
  {
    std::string result = "Filter ";
    if (mode & 1)
    {
      result += 'L';
    }
    if (mode & 2)
    {
      result += 'B';
    }
    if (mode & 4)
    {
      result += 'H';
    }
    return result + ' ' + std::to_string(cutoff);
  }
}  // namespace Visualization::SidDescription
