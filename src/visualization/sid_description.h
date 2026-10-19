/**
 *
 * @file
 *
 * @brief Human readable description of SID registers state
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "visualization/gauges.h"

#include <string>
#include <string_view>

namespace Visualization::SidDescription
{
  /**
   * Translates SID registers into musical terms, so the dashboard is understandable without datasheet:
   * - short values for gauges title bands (waveform, envelope level and phase, note, filter)
   * - plain words for cells grid under gauges (see SidCells), one value per cell
   * Timings and frequencies depend on chip clock and model, see Chip.
   */

  const int PAL_CLOCK = 985248;
  const int NTSC_CLOCK = 1022727;

  //! Chip parameters affecting translation, guessed from emulation description
  struct Chip
  {
    int Clock = PAL_CLOCK;
    bool Is8580 = false;

    //! status starts with "PAL, MOS6581, ..." (see Sound::Scope::GetStatus)
    static Chip FromStatus(std::string_view status);
  };

  enum class Phase
  {
    ATTACK,
    DECAY,
    SUSTAIN,
    RELEASE,
    IDLE
  };

  const int PHASES_COUNT = 5;

  std::string_view PhaseShort(Phase phase);
  std::string_view PhaseWord(Phase phase);

  //! Words displayed to user, collected in one place to be localized later
  namespace Words
  {
    constexpr std::string_view WAVE = "Wave";
    constexpr std::string_view DUTY = "Duty";
    constexpr std::string_view NOTE = "Note";
    constexpr std::string_view PITCH = "Pitch";
    constexpr std::string_view MOD = "Mod";
    constexpr std::string_view FILTER = "Filter";
    constexpr std::string_view LEVEL = "Level";
    constexpr std::string_view STAGE = "Stage";
    constexpr std::string_view ATTACK = "Attack";
    constexpr std::string_view DECAY = "Decay";
    constexpr std::string_view SUSTAIN = "Sustain";
    constexpr std::string_view RELEASE = "Release";
    constexpr std::string_view VOLUME = "Volume";
    constexpr std::string_view CUTOFF = "Cutoff";
    constexpr std::string_view RESONANCE = "Resonance";
    constexpr std::string_view ROUTED = "Filtered";

    constexpr std::string_view NOISE = "Noise";
    constexpr std::string_view PULSE = "Pulse";
    constexpr std::string_view SAW = "Saw";
    constexpr std::string_view TRIANGLE = "Triangle";
    constexpr std::string_view NONE = "None";
    constexpr std::string_view RESET = "Reset";
    constexpr std::string_view SYNC = "Sync";
    constexpr std::string_view RING = "Ring";
    constexpr std::string_view SYNC_RING = "Sync+Ring";
    constexpr std::string_view MUTED = "Muted";
    constexpr std::string_view SILENT = "Off";
    constexpr std::string_view EXT = "Ext";
    constexpr std::string_view DASH = "—";
  }  // namespace Words

  constexpr std::string_view GATE_MARK = "●";

  int AttackMs(int nibble);
  //! release uses the same table as decay
  int DecayMs(int nibble);
  int SustainPercent(int nibble);
  float FrequencyHz(int reg, const Chip& chip);
  int PulsePercent(int reg);
  float CutoffHz(int reg, const Chip& chip);
  //! Voice is synchronized/ring modulated by previous one cyclically: 1 by 3, 2 by 1, 3 by 2
  int ModulatorOf(int voice);

  //! @return e.g. "Saw", "Pulse 37%", "Pulse+Saw"
  std::string WaveName(int control, int pulsePercent);
  //! @return Sync, Ring and Test flags as words with leading spaces
  std::string WaveFlags(int control);
  bool IsGate(int control);
  bool HasPulse(int control);
  bool HasSync(int control);
  //! ring modulation affects only triangle waveform
  bool HasRing(int control);

  //! @param trend envelope tendency from history: positive for rising, negative for falling, 0 if unknown
  Phase GetPhase(const ChipState& state, int voice, int trend);
  int EnvelopePercent(const ChipState& state, int voice);

  std::string_view ModeShort(int mode);
  std::string_view ModeWord(int mode);
  std::string WaveWord(int control);
  std::string ModulationWord(int control, int voice);
  std::string FilterWord(bool filtered, int mode, bool muted);
  //! voice 3 is disconnected from output only if it's not filtered
  bool IsMuted(int voice, int mode, int routing);
  std::string RoutingWord(int routing);
  std::string Percent(int value);
  std::string PercentOf15(int value);
  std::string HzWord(int hz);
  //! cutoff is rough estimation, so it's rounded to 2 significant digits
  int RoundCutoff(float hz);
  std::string CutoffWord(int roundedHz);
  std::string DurationWord(int ms);
  std::string FormatHz(float hz);

  //! Same scale as envelope gauges (see EnvelopeLevel at src/sound/impl/scope.cpp)
  int EnvelopeGaugeLevel(int env);

  //! Waveform bits and flags: Sync, Ring modulation, Test, Filtered
  std::string WaveTitle(int control, bool filtered);
  //! Filter modes: Lowpass, Bandpass, Highpass
  std::string FilterTitle(int mode, int cutoff);
}  // namespace Visualization::SidDescription
