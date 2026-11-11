/**
 *
 * @file
 *
 * @brief  Yamaha FM chips emulation settings and analog output stage of their platforms
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "module/players/external/fm_output.h"

#include "core/core_parameters.h"

#include <algorithm>
#include <cmath>
#include <numbers>

namespace Module::FmOutput
{
  namespace Params = Parameters::ZXTune::Core::FM;

  struct Preset
  {
    int_t Id;
    Stage Output;
  };

  struct Opn2Preset
  {
    int_t Id;
    int_t Chip;
    bool Ladder;
    Stage Output;
  };

  /*
    Discrete YM2612 (with its DAC crossover distortion) is used by Mega Drive model 1 VA0..VA6 and model 2 VA2,
    YM3438 integrated into the main ASIC by model 1 VA7, the other model 2 revisions and Genesis 3 (Nuked-OPN2
    readme, chip modes; Wikipedia "Yamaha YM2612").
    Model 1 mixes the chip with PSG and filters the result by first order RC low-pass: BlastEm uses 3390Hz
    (default.cfg, audio.lowpass_cutoff), Genesis Plus GX defaults its one pole filter to 0.6 (libretro.c,
    config.lp_range = 0x9999) that is -3dB at about 3.7kHz for 44.1kHz output.
    Model 2 is filtered more (libretro forum "Genesis Plus GX, where is the audio lowpass filter?", recordings of
    model 1 VA6 and model 2 VA4): its amplifier is a second order low-pass with the knee about 2.84kHz with stock
    capacitors (consolemods.org "Genesis:Audio Circuit Mod").
    Genesis 3 filter was not measured, model 1 one is assumed.
    Coupling capacitors of the boards remove DC bias of the DAC (it's essential for the ladder effect that adds
    offset to each channel, even silent one), the cutoff is not documented and is put below the audible band.
    Bare chips: YM3438 with external DAC (e.g. FM Towns) and YMF276 with 14-bit digital output to external DAC.
  */
  const Opn2Preset OPN2_PRESETS[] = {
      {Params::OPN2::PRESET_MEGADRIVE1, Params::OPN2::CHIP_YM2612, true, {3390, 1, 5, 100}},
      {Params::OPN2::PRESET_MEGADRIVE1_VA7, Params::OPN2::CHIP_YM3438, false, {3390, 1, 5, 100}},
      {Params::OPN2::PRESET_MEGADRIVE2, Params::OPN2::CHIP_YM3438, false, {2840, 2, 5, 100}},
      {Params::OPN2::PRESET_GENESIS3, Params::OPN2::CHIP_YM3438, false, {3390, 1, 5, 100}},
      {Params::OPN2::PRESET_YM3438, Params::OPN2::CHIP_YM3438, false, {0, 1, 0, 100}},
      {Params::OPN2::PRESET_YMF276, Params::OPN2::CHIP_YMF276, false, {0, 1, 0, 100}},
  };

  /*
    YM2151 output is converted by YM3012 (stereo) or YM3014 (mono) floating point DAC, it's emulated by the cores.
    Boards (Sharp X68000, arcade) have no explicit low-pass filter of FM part (jotego/jtcores issue 556), their
    output stages were not measured, so they are not modelled.
  */
  const Preset OPM_PRESETS[] = {
      {Params::OPM::PRESET_YM3012, {0, 2, 0, 100}},
      {Params::OPM::PRESET_YM3014, {0, 2, 0, 0}},
  };

  /*
    YM2203 (PC-8801mkIISR, PC-9801-26K) and YM2608 (PC-8801FA/VA, PC-9801-86) boards output stages were not measured,
    so they are not modelled.
  */
  const Preset OPN_PRESETS[] = {
      {Params::OPN::PRESET_PC88_98, {0, 2, 0, 100}},
  };

  /*
    Sound Blaster cards OPL output filters from DOSBox Staging (src/hardware/audio/soundblaster.cpp,
    configure_opl_filter_for_model), "tweaked by analysing real hardware recordings":
    SB 1.x/2.0 - first order low-pass at 12kHz, SB Pro 1/2 - first order low-pass at 8kHz, SB16 - not filtered.
    AdLib card is mono (OPL2), its output stage after YM3014B DAC was not measured (DOSBox Staging doesn't filter it
    either), so it is not modelled.
  */
  const Preset OPL_PRESETS[] = {
      {Params::OPL::PRESET_ADLIB, {0, 1, 0, 0}},
      {Params::OPL::PRESET_SB2, {12000, 1, 0, 100}},
      {Params::OPL::PRESET_SBPRO2, {8000, 1, 0, 100}},
      {Params::OPL::PRESET_SB16, {0, 1, 0, 100}},
  };

  template<class T, std::size_t N>
  const T* FindPreset(const T (&presets)[N], int_t id)
  {
    const auto* it = std::find_if(presets, presets + N, [id](const auto& p) { return p.Id == id; });
    return it != presets + N ? it : nullptr;
  }

  struct StageParams
  {
    Parameters::Identifier Lowpass;
    Parameters::IntType LowpassDefault;
    Parameters::Identifier Order;
    Parameters::IntType OrderDefault;
    Parameters::Identifier Highpass;
    Parameters::IntType HighpassDefault;
    // empty for mono chips
    Parameters::Identifier Stereo;
    Parameters::IntType StereoDefault;
  };

  uint_t ReadClamped(const Parameters::Accessor& params, Parameters::Identifier name, Parameters::IntType def,
                     Parameters::IntType min, Parameters::IntType max)
  {
    return static_cast<uint_t>(std::clamp(Parameters::GetInteger(params, name, def), min, max));
  }

  Stage ReadStage(const Parameters::Accessor& params, const StageParams& names)
  {
    Stage res;
    res.Lowpass = ReadClamped(params, names.Lowpass, names.LowpassDefault, Params::LOWPASS_MIN, Params::LOWPASS_MAX);
    res.Order =
        ReadClamped(params, names.Order, names.OrderDefault, Params::LOWPASS_ORDER_MIN, Params::LOWPASS_ORDER_MAX);
    res.Highpass =
        ReadClamped(params, names.Highpass, names.HighpassDefault, Params::HIGHPASS_MIN, Params::HIGHPASS_MAX);
    res.Stereo = names.Stereo.IsEmpty()
                     ? 100
                     : ReadClamped(params, names.Stereo, names.StereoDefault, Params::STEREO_MIN, Params::STEREO_MAX);
    return res;
  }

  template<std::size_t N>
  Stage ReadStage(const Parameters::Accessor& params, const Preset (&presets)[N], Parameters::Identifier preset,
                  Parameters::IntType presetDefault, const StageParams& names)
  {
    if (const auto* known = FindPreset(presets, Parameters::GetInteger(params, preset, presetDefault)))
    {
      return known->Output;
    }
    return ReadStage(params, names);
  }

  Stage& StageOf(Settings& res, Family family)
  {
    return res.Stages[static_cast<std::size_t>(family)];
  }

  Settings ReadSettings(const Parameters::Accessor& params)
  {
    using Parameters::GetInteger;
    Settings res;
    {
      namespace P = Params::OPN2;
      res.Emu.Opn2Core = GetInteger(params, P::CORE, P::CORE_DEFAULT);
      if (const auto* known = FindPreset(OPN2_PRESETS, GetInteger(params, P::PRESET, P::PRESET_DEFAULT)))
      {
        res.Emu.Opn2Chip = known->Chip;
        res.Emu.Opn2Ladder = known->Ladder;
        StageOf(res, Family::OPN2) = known->Output;
      }
      else
      {
        res.Emu.Opn2Chip = GetInteger(params, P::CHIP, P::CHIP_DEFAULT);
        res.Emu.Opn2Ladder = GetInteger(params, P::LADDER, P::LADDER_DEFAULT) != 0;
        StageOf(res, Family::OPN2) =
            ReadStage(params, {P::LOWPASS, P::LOWPASS_DEFAULT, P::LOWPASS_ORDER, P::LOWPASS_ORDER_DEFAULT, P::HIGHPASS,
                               P::HIGHPASS_DEFAULT, P::STEREO, P::STEREO_DEFAULT});
      }
    }
    {
      namespace P = Params::OPM;
      res.Emu.OpmCore = GetInteger(params, P::CORE, P::CORE_DEFAULT);
      StageOf(res, Family::OPM) = ReadStage(params, OPM_PRESETS, P::PRESET, P::PRESET_DEFAULT,
                                            {P::LOWPASS, P::LOWPASS_DEFAULT, P::LOWPASS_ORDER, P::LOWPASS_ORDER_DEFAULT,
                                             P::HIGHPASS, P::HIGHPASS_DEFAULT, P::STEREO, P::STEREO_DEFAULT});
    }
    {
      namespace P = Params::OPN;
      res.Emu.OpnCore = GetInteger(params, P::CORE, P::CORE_DEFAULT);
      StageOf(res, Family::OPN) = ReadStage(params, OPN_PRESETS, P::PRESET, P::PRESET_DEFAULT,
                                            {P::LOWPASS, P::LOWPASS_DEFAULT, P::LOWPASS_ORDER, P::LOWPASS_ORDER_DEFAULT,
                                             P::HIGHPASS, P::HIGHPASS_DEFAULT, P::STEREO, P::STEREO_DEFAULT});
    }
    {
      namespace P = Params::OPL;
      res.Emu.OplCore = GetInteger(params, P::CORE, P::CORE_DEFAULT);
      StageOf(res, Family::OPL) = ReadStage(params, OPL_PRESETS, P::PRESET, P::PRESET_DEFAULT,
                                            {P::LOWPASS, P::LOWPASS_DEFAULT, P::LOWPASS_ORDER, P::LOWPASS_ORDER_DEFAULT,
                                             P::HIGHPASS, P::HIGHPASS_DEFAULT, P::STEREO, P::STEREO_DEFAULT});
    }
    {
      namespace P = Params::OPLL;
      res.Emu.OpllCore = GetInteger(params, P::CORE, P::CORE_DEFAULT);
      StageOf(res, Family::OPLL) =
          ReadStage(params, {P::LOWPASS, P::LOWPASS_DEFAULT, P::LOWPASS_ORDER, P::LOWPASS_ORDER_DEFAULT, P::HIGHPASS,
                             P::HIGHPASS_DEFAULT, "", 100});
    }
    return res;
  }

  // filter can't be realized at or above Nyquist frequency, so such a setting means "not filtered"
  const double MAX_CUTOFF_RATIO = 0.45;

  void Filter::SetStage(const Stage& stage, uint_t samplerate)
  {
    if (Current == stage && Samplerate == samplerate)
    {
      return;
    }
    Current = stage;
    Samplerate = samplerate;
    Lowpass = stage.Lowpass != 0 && samplerate != 0 && stage.Lowpass < samplerate * MAX_CUTOFF_RATIO;
    Highpass = stage.Highpass != 0 && samplerate != 0 && stage.Highpass < samplerate * MAX_CUTOFF_RATIO;
    Active = Lowpass || Highpass || stage.Stereo < 100;
    HG = 1;
    HR = 0;
    if (Highpass)
    {
      const auto k = std::tan(std::numbers::pi * stage.Highpass / samplerate);
      HG = 1 / (1 + k);
      HR = (1 - k) / (1 + k);
    }
    B0 = 1;
    B1 = B2 = A1 = A2 = 0;
    if (Lowpass)
    {
      const auto k = std::tan(std::numbers::pi * stage.Lowpass / samplerate);
      if (stage.Order == 1)
      {
        // Analog RC magnitude is matched at DC, cutoff and Nyquist frequencies (as in M. Vicanek, "Matched One-Pole
        // Digital Shelving Filters"), so the whole band follows it, unlike bilinear transform that attenuates more
        // near Nyquist frequency. Pole is found by bisection, zero is defined by DC and Nyquist gains.
        const auto ratio = samplerate / (2.0 * stage.Lowpass);
        const auto nyquistGain = 1 / std::sqrt(1 + ratio * ratio);
        const auto wc = 2 * std::numbers::pi * stage.Lowpass / samplerate;
        const auto gain2 = [nyquistGain, wc](double pole) {
          const auto b0 = ((1 - pole) + nyquistGain * (1 + pole)) / 2;
          const auto b1 = ((1 - pole) - nyquistGain * (1 + pole)) / 2;
          const auto c = std::cos(wc);
          return (b0 * b0 + b1 * b1 + 2 * b0 * b1 * c) / (1 + pole * pole - 2 * pole * c);
        };
        // gain at cutoff decreases with pole
        double lo = 0, hi = 1;
        for (int iter = 0; iter < 50; ++iter)
        {
          const auto mid = (lo + hi) / 2;
          (gain2(mid) > 0.5 ? lo : hi) = mid;
        }
        const auto pole = (lo + hi) / 2;
        B0 = ((1 - pole) + nyquistGain * (1 + pole)) / 2;
        B1 = ((1 - pole) - nyquistGain * (1 + pole)) / 2;
        A1 = -pole;
      }
      else
      {
        // Butterworth, bilinear transform with prewarping, so cutoff frequency is exact
        const auto q = std::numbers::sqrt2 / 2;
        const auto norm = 1 / (1 + k / q + k * k);
        B0 = k * k * norm;
        B1 = 2 * B0;
        B2 = B0;
        A1 = 2 * (k * k - 1) * norm;
        A2 = (1 - k / q + k * k) * norm;
      }
    }
    // state is kept to avoid clicks on changes
  }

  void Filter::Process(Sound::Chunk& chunk)
  {
    if (!Active)
    {
      return;
    }
    const auto width = Current.Stereo / 100.0;
    for (auto& smp : chunk)
    {
      std::array<double, Sound::Sample::CHANNELS> in = {double(smp.Left()), double(smp.Right())};
      if (width < 1)
      {
        const auto mid = (in[0] + in[1]) / 2;
        const auto side = (in[0] - in[1]) / 2 * width;
        in = {mid + side, mid - side};
      }
      std::array<int_t, Sound::Sample::CHANNELS> out = {};
      for (uint_t ch = 0; ch < Sound::Sample::CHANNELS; ++ch)
      {
        auto res = in[ch];
        if (Highpass)
        {
          auto& st = HighpassState[ch];
          const auto hp = HG * (res - st.X1) + HR * st.Y1;
          st.X1 = res;
          st.Y1 = hp;
          res = hp;
        }
        if (Lowpass)
        {
          auto& st = State[ch];
          const auto x = res;
          res = B0 * x + B1 * st.X1 + B2 * st.X2 - A1 * st.Y1 - A2 * st.Y2;
          st.X2 = st.X1;
          st.X1 = x;
          st.Y2 = st.Y1;
          st.Y1 = res;
        }
        out[ch] = std::clamp<int_t>(static_cast<int_t>(std::lround(res)), Sound::Sample::MIN, Sound::Sample::MAX);
      }
      smp = Sound::Sample(out[0], out[1]);
    }
  }

  double Filter::GetResponse(double freq) const
  {
    // H(z) at z = exp(jw)
    const auto w = 2 * std::numbers::pi * freq / std::max<uint_t>(Samplerate, 1);
    const auto c1 = std::cos(w);
    const auto s1 = std::sin(w);
    double mag2 = 1;
    if (Lowpass)
    {
      const auto c2 = std::cos(2 * w);
      const auto s2 = std::sin(2 * w);
      const auto numRe = B0 + B1 * c1 + B2 * c2;
      const auto numIm = -(B1 * s1 + B2 * s2);
      const auto denRe = 1 + A1 * c1 + A2 * c2;
      const auto denIm = -(A1 * s1 + A2 * s2);
      mag2 *= (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm);
    }
    if (Highpass)
    {
      // hg * (1 - z^-1) / (1 - hr * z^-1)
      const auto numRe = HG * (1 - c1);
      const auto numIm = HG * s1;
      const auto denRe = 1 - HR * c1;
      const auto denIm = HR * s1;
      mag2 *= (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm);
    }
    return 10 * std::log10(std::max(mag2, 1e-12));
  }
}  // namespace Module::FmOutput
