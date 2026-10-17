/**
 *
 * @file
 *
 * @brief  SID filters presets and cutoff models
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "types.h"

#include <array>

namespace Module::Sid
{
  const uint_t CUTOFF_VALUES = 2048;

  //! Effective cutoff frequency of lowpass filter by cutoff register value (small signals, no resonance)
  struct CutoffCurve
  {
    enum Kind
    {
      // reSID 1.0 6581 model with DAC zero level shift (bias) in volts
      RESID_6581,
      // resid-fp (Antti Lankila) 6581 model: parallel fixed and FET resistors charging 470pF capacitor
      // Params: base resistance, offset, steepness, minimum FET resistance, DAC nonlinearity, attenuation
      TYPE3,
      // Params: Hz per cutoff register unit, Hz at zero
      LINEAR,
    };

    Kind Type;
    std::array<double, 6> Params;

    double GetFrequency(uint_t fc) const;
  };

  //! @param id see Parameters::ZXTune::Core::SID::FILTER_6581_PRESET
  //! @return nullptr for custom filter
  const CutoffCurve* FindFilter6581Preset(int_t id);
  //! @param id see Parameters::ZXTune::Core::SID::FILTER_8580_PRESET
  const CutoffCurve* FindFilter8580Preset(int_t id);

  /*
    Approximations of engines filters cutoff used to translate presets to engines parameters (and to display them).
    Both 6581 engines share the same integrator model, which small signal transconductance at op-amp working point
    gives "integrator" frequency (in the way JSIDPlay2 displays it). Real cutoff is lower due to low gain of 6581
    op-amps, so it's corrected by factors measured by module/test/sid_filters host check.
  */
  namespace FilterModel
  {
    enum class Engine
    {
      RESID,
      RESIDFP
    };

    //! reSIDfp filter6581Range parameter (0..1) to transconductance coefficient (uCox)
    double GetResidFpUcox(double range);
    double GetResidFpRange(double uCox);
    //! reSIDfp filter6581Curve parameter (0..1) applied to cutoff DAC output
    double GetResidFp6581DacVoltage(uint_t fc, double curve);
    //! reSID cutoff DAC output with bias in volts
    double GetResid6581DacVoltage(uint_t fc, double bias);
    //! uCox of reSID 1.0 model (fixed)
    const double RESID_UCOX = 20e-6;

    //! Small signal integrator frequency
    //! @param vw cutoff DAC output voltage (VCR gate control)
    double Get6581IntegratorFrequency(double vw, double uCox);
    double Get6581Cutoff(Engine engine, double vw, double uCox);
    //! Inverse of Get6581Cutoff, clamped to emulation-safe range
    double Get6581DacVoltage(Engine engine, double cutoff, double uCox);
    //! uCox to make fixed "snake" transistor conductance the same as resistance of resid-fp model
    double Get6581Ucox(double baseResistance);

    //! reSIDfp filter8580Curve parameter (0..1)
    double GetResidFp8580Cutoff(uint_t fc, double curve);
    double FitResidFp8580Curve(const CutoffCurve& curve);
    //! reSID uses linear cutoff curve
    double GetResid8580Cutoff(uint_t fc, double maxCutoff);
    const double RESID_8580_MAX_CUTOFF = 12500;

    //! Best reSID bias approximation of curve
    double FitResid6581Bias(const CutoffCurve& curve);
  }  // namespace FilterModel
}  // namespace Module::Sid
