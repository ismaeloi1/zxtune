/**
 *
 * @file
 *
 * @brief  Yamaha FM chips emulation settings and analog output stage of their platforms
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "parameters/accessor.h"
#include "sound/chunk.h"

#include <array>

namespace Module::FmOutput
{
  //! Families of chips sharing settings, in priority order of output stage selection
  enum class Family
  {
    OPN2,
    OPM,
    OPN,
    OPL,
    OPLL,

    COUNT
  };

  //! Board filters and stereo of platform
  struct Stage
  {
    //! Low-pass cutoff in Hz, 0 if not filtered
    uint_t Lowpass = 0;
    //! 1 (6dB/oct) or 2 (12dB/oct)
    uint_t Order = 1;
    //! Output coupling high-pass cutoff in Hz, 0 if DC is kept
    uint_t Highpass = 0;
    //! 0..100
    uint_t Stereo = 100;

    bool operator==(const Stage&) const = default;
  };

  //! Chips emulation, see Parameters::ZXTune::Core::FM for values
  struct Emulation
  {
    int_t Opn2Core = 0;
    int_t Opn2Chip = 0;
    bool Opn2Ladder = true;
    int_t OpmCore = 0;
    int_t OpnCore = 0;
    int_t OplCore = 0;
    int_t OpllCore = 0;

    bool operator==(const Emulation&) const = default;
  };

  struct Settings
  {
    Emulation Emu;
    std::array<Stage, static_cast<std::size_t>(Family::COUNT)> Stages;

    const Stage& GetStage(Family family) const
    {
      return Stages[static_cast<std::size_t>(family)];
    }

    bool operator==(const Settings&) const = default;
  };

  //! Presets are resolved to effective values
  Settings ReadSettings(const Parameters::Accessor& params);

  //! Analog output stage applied to rendered stereo output
  class Filter
  {
  public:
    void SetStage(const Stage& stage, uint_t samplerate);
    void Process(Sound::Chunk& chunk);

    //! Magnitude response in dB at frequency, as applied (with discretization)
    double GetResponse(double freq) const;

  private:
    struct Channel
    {
      double X1 = 0;
      double X2 = 0;
      double Y1 = 0;
      double Y2 = 0;
    };

    Stage Current;
    uint_t Samplerate = 0;
    bool Active = false;
    bool Lowpass = false;
    bool Highpass = false;
    // y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2
    double B0 = 1, B1 = 0, B2 = 0, A1 = 0, A2 = 0;
    // y = hg * (x - x1) + hr * y1
    double HG = 1, HR = 0;
    std::array<Channel, Sound::Sample::CHANNELS> State;
    std::array<Channel, Sound::Sample::CHANNELS> HighpassState;
  };
}  // namespace Module::FmOutput
