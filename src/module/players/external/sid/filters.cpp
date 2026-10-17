/**
 *
 * @file
 *
 * @brief  SID filters presets and cutoff models implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "module/players/external/sid/filters.h"

#include <algorithm>
#include <cmath>
#include <numbers>

namespace Module::Sid
{
  const uint_t CUTOFF_BITS = 11;

  /*
    Presets are taken from JSIDPlay2 default configuration (jsidplay2.ini, GPL), see
    https://sourceforge.net/p/jsidplay2/code/HEAD/tree/trunk/jsidplay2/src/main/resources/sidplay/ini/jsidplay2.ini
    Ids are stored in settings, so only append new ones. Names are kept in UI (see FilterPresets.kt).
  */
  struct Preset
  {
    int_t Id;
    CutoffCurve Curve;
  };

  // reSID 1.0 filters of JSIDPlay2 are defined by curve position P: DAC zero is 6.65V - (P - 0.5) * 2V
  // (FilterModelConfig.getDacZero), so P maps to reSID bias of (0.5 - P) * 2V.
  constexpr CutoffCurve ResidCurve(double position)
  {
    return {CutoffCurve::RESID_6581, {(0.5 - position) * 2, 0, 0, 0, 0, 0}};
  }

  // All the resid-fp filters of JSIDPlay2 use Attenuation=0.5 and VoiceNonlinearity=0.96 (except of one)
  constexpr CutoffCurve Type3(double base, double offset, double steepness, double minFet, double nonlinearity = 0.96)
  {
    return {CutoffCurve::TYPE3, {base, offset, steepness, minFet, nonlinearity, 0.5}};
  }

  const Preset PRESETS_6581[] = {
      // [FilterLightest6581]..[FilterDarkest6581]
      {1, ResidCurve(0.1)},
      {2, ResidCurve(0.3)},
      {3, ResidCurve(0.4)},
      {4, ResidCurve(0.5)},
      {5, ResidCurve(0.6)},
      {6, ResidCurve(0.7)},
      {7, ResidCurve(0.9)},
      // VICE default SidResidFilterBias=500 (mV), see vice/src/sid/sid.h RESID_6581_FILTER_BIAS_DEFAULT
      {8, {CutoffCurve::RESID_6581, {0.5, 0, 0, 0, 0, 0}}},
      // resid-fp filters of JSIDPlay2, named by authors of measurements and chips
      {9, Type3(1537690.1305811733, 76322577.13241087, 1.0072175060266852, 19114.018733380854)},  // ReSID6581
      {10, Type3(1147036.4394268463, 274228796.97550374, 1.0066634233403395, 16125.154840564108,
                 0.9613160610660189)},  // Alankila6581R4AR_3789
      {11,
       Type3(1522171.922983084, 21729926.667291082, 1.004994802537475, 14299.149638099827)},  // Alankila6581R3_3984_1
      {12,
       Type3(1613349.4942964897, 67122945.35403329, 1.005727808339829, 15462.006399118263)},  // Alankila6581R3_3984_2
      {13, Type3(1.55e6, 2.5e8, 1.006, 1.9e4)},  // LordNightmare6581R3_4285
      {14, Type3(1399768.3253307983, 553018906.8926692, 1.0051493199361266,
                 11961.908870403166)},  // LordNightmare6581R3_4485
      {15, Type3(1250736.2235895505, 1521187976.8735676, 1.005543646897986,
                 8581.78418415723)},              // LordNightmare6581R4_1986S
      {16, Type3(1.9e6, 8.5e7, 1.0058, 2e4)},     // ZrX6581R3_0384
      {17, Type3(1.83e6, 2.6e9, 1.0064, 2.5e4)},  // ZrX6581R3_1984
      {18, Type3(1.65e6, 1.2e10, 1.006, 1e4)},    // Zrx6581R3_3684
      {19, Type3(1.5e6, 1.8e8, 1.0065, 1.8e4)},   // Zrx6581R3_3985
      {20, Type3(1.3e6, 1.9e8, 1.0066, 1.8e4)},   // Zrx6581R4AR_2286
      {21, Type3(1.3e6, 3.7e8, 1.0066, 1.8e4)},   // Trurl6581R3_0784
      {22, Type3(1164920.4999651583, 12915042.165290257, 1.0058853753357735, 12914.5661141159)},   // Trurl6581R3_0486S
      {23, Type3(1.16e6, 9.9e6, 1.0058, 1.48e4)},                                                  // Trurl6581R3_3384
      {24, Type3(840577.4520801408, 1909158.8633669745, 1.0068865662510837, 14858.140079688419)},  // Trurl6581R3_4885
      {25, Type3(1.08e6, 1.8e6, 1.006, 1.0e4)},                                                    // Trurl6581R4AR_3789
      {26, Type3(1.1e6, 8e6, 1.0052, 1.7e4)},                                                      // Trurl6581R4AR_4486
      {27, Type3(1590881.4720854638, 32349044.218055427, 1.005583147929036, 16357.252396224501)},  // Nata6581R3_2083
      {28, Type3(1.45e6, 1.75e8, 1.0055, 1e4)},                                                    // Grue6581R4AR_3488
      {29, Type3(1585454.0607309118, 97702791.79673694, 1.0053036856077033, 14497.859765307807)},  // KruLLo
      {30, Type3(1090649.9265298771, 264401456.3944572, 1.0145069244334666, 19890.786352277173)},  // Enigma6581R3_4885
      {31, Type3(1119380.8539989102, 257709531.3343812, 1.0073881870085593, 20269.79137269368)},   // Enigma6581R3_1585
  };

  constexpr CutoffCurve Linear(double maxCutoff)
  {
    return {CutoffCurve::LINEAR, {maxCutoff / (CUTOFF_VALUES - 1), 0, 0, 0, 0, 0}};
  }

  const Preset PRESETS_8580[] = {
      // reSID 1.0 filters, Filter8580CurvePosition is the cutoff at maximal register value
      {1, Linear(13400)},  // FilterLight8580
      {2, Linear(12500)},  // FilterAverage8580
      {3, Linear(11700)},  // FilterDark8580
      // resid-fp Type4 filters: k * fc + b
      {4, {CutoffCurve::LINEAR, {5.7, 20, 0, 0, 0, 0}}},   // Trurl8580R5_1489
      {5, {CutoffCurve::LINEAR, {6.55, 20, 0, 0, 0, 0}}},  // Trurl8580R5_3691
  };

  template<std::size_t N>
  const CutoffCurve* FindPreset(const Preset (&presets)[N], int_t id)
  {
    const auto* it = std::find_if(std::begin(presets), std::end(presets), [id](const auto& p) { return p.Id == id; });
    return it != std::end(presets) ? &it->Curve : nullptr;
  }

  const CutoffCurve* FindFilter6581Preset(int_t id)
  {
    return FindPreset(PRESETS_6581, id);
  }

  const CutoffCurve* FindFilter8580Preset(int_t id)
  {
    return FindPreset(PRESETS_8580, id);
  }

  // JSIDPlay2 builder.resid.residfp.SID.kinkedDac
  double GetKinkedDac(uint_t input, double nonlinearity)
  {
    double value = 0;
    double weight = 1;
    const double dir = 2 * nonlinearity;
    for (uint_t bit = 0; bit < CUTOFF_BITS; ++bit)
    {
      if (input & (1u << bit))
      {
        value += weight;
      }
      weight *= dir;
    }
    return value / (weight / nonlinearity / nonlinearity) * (1 << CUTOFF_BITS);
  }

  double CutoffCurve::GetFrequency(uint_t fc) const
  {
    switch (Type)
    {
    case RESID_6581:
      return FilterModel::Get6581Cutoff(FilterModel::Engine::RESID, FilterModel::GetResid6581DacVoltage(fc, Params[0]),
                                        FilterModel::RESID_UCOX);
    case TYPE3:
    {
      // JSIDPlay2 builder.resid.residfp.FilterModelConfig.estimateFrequency gives integrators frequency.
      // Filter6581.clock attenuates highpass output, so cutoff of 2-pole loop is sqrt(attenuation) lower.
      const auto base = Params[0];
      const auto dynamic = Params[3] + Params[1] / std::pow(Params[2], GetKinkedDac(fc, Params[4]));
      const auto resistance = base * dynamic / (base + dynamic);
      return std::sqrt(Params[5]) / (2 * std::numbers::pi * 470e-12 * resistance);
    }
    default:
      return Params[0] * fc + Params[1];
    }
  }

  namespace FilterModel
  {
    /*
      6581 constants are the same in reSID 1.0 (filter.cc) and reSIDfp (FilterModelConfig6581.cpp)
    */
    const double VDD = 12.18;
    const double VTH = 1.31;
    const double VDDT = VDD - VTH;
    // vi = vo point of op-amp voltage transfer function
    const double WORKING_POINT = 4.54;
    const double UT = 26.0e-3;
    const double CAPACITOR = 470e-12;
    const double WL_VCR = 9.0;
    const double WL_SNAKE = 1.0 / 115;
    const double DAC_ZERO = 6.65;
    const double DAC_SCALE = 2.63;
    // Inversion is limited to keep EKV model tables lookup (asserted only in debug builds) in range for extreme
    // signals: range of reSIDfp curve parameter is 6.65V..10.28V, VCR is closed below 6V anyway
    const double MIN_VW = 6.0;
    const double MAX_VW = 10.3;

    // R-2R ladder of 6581 cutoff DAC with 2R/R=2.2 and missing termination (Dac::kinkedDac, build_dac_table)
    std::array<double, CUTOFF_BITS> Get6581DacBits()
    {
      const double r = 1;
      const double r2 = 2.2 * r;
      std::array<double, CUTOFF_BITS> result{};
      for (uint_t setBit = 0; setBit < CUTOFF_BITS; ++setBit)
      {
        double vn = 1;
        double rn = HUGE_VAL;
        uint_t bit = 0;
        for (; bit < setBit; ++bit)
        {
          rn = std::isinf(rn) ? r + r2 : r + r2 * rn / (r2 + rn);
        }
        if (std::isinf(rn))
        {
          rn = r2;
        }
        else
        {
          rn = r2 * rn / (r2 + rn);
          vn = vn * rn / r2;
        }
        for (++bit; bit < CUTOFF_BITS; ++bit)
        {
          rn += r;
          const auto i = vn / rn;
          rn = r2 * rn / (r2 + rn);
          vn = rn * i;
        }
        result[setBit] = vn;
      }
      return result;
    }

    // Engines differ in leakage of switched off bits and output normalization
    double Get6581DacOutput(uint_t fc, double leakage, double scale)
    {
      static const auto BITS = Get6581DacBits();
      double result = 0;
      for (uint_t bit = 0; bit < CUTOFF_BITS; ++bit)
      {
        result += BITS[bit] * ((fc & (1u << bit)) ? 1 : leakage);
      }
      return result * scale;
    }

    double GetResidFpUcox(double range)
    {
      // FilterModelConfig6581::setFilterRange
      return (1 + 39 * std::clamp(range, 0.0, 1.0)) * 1e-6;
    }

    double GetResidFpRange(double uCox)
    {
      return std::clamp((uCox * 1e6 - 1) / 39, 0.0, 1.0);
    }

    double GetResidFp6581DacVoltage(uint_t fc, double curve)
    {
      static const auto BITS = Get6581DacBits();
      static const auto SUM = [] {
        double sum = 0;
        for (const auto b : BITS)
        {
          sum += b;
        }
        return sum;
      }();
      // FilterModelConfig6581::getDacZero and Dac::getOutput
      return DAC_ZERO + (1 - curve) + Get6581DacOutput(fc, 0.0075, 1 / SUM) * DAC_SCALE;
    }

    double GetResid6581DacVoltage(uint_t fc, double bias)
    {
      // build_dac_table scales to 2^bits-1 with rounding, f0_dac divides by 2^bits
      const auto dac = std::floor(Get6581DacOutput(fc, 0.01, (1 << CUTOFF_BITS) - 1) + 0.5);
      return DAC_ZERO + bias + dac * DAC_SCALE / (1 << CUTOFF_BITS);
    }

    double Get6581IntegratorFrequency(double vw, double uCox)
    {
      // Integrator6581::solve linearized at vi = vx = working point: triode mode "snake" in parallel with EKV
      // modelled VCR, which gate voltage is Vddt - sqrt(((Vddt - Vw)^2 + Vgdt^2) / 2)
      const auto vgdt = VDDT - WORKING_POINT;
      const auto snake = uCox * WL_SNAKE * vgdt;
      const auto vg = VDDT - std::sqrt(((VDDT - vw) * (VDDT - vw) + vgdt * vgdt) / 2);
      const auto u = (vg - VTH - WORKING_POINT) / (2 * UT);
      // d/dVd of Is*ln^2(1 + e^u), Is = 2*uCox*Ut^2*W/L
      const auto vcr = 2 * UT * WL_VCR * uCox * std::log1p(std::exp(u)) / (1 + std::exp(-u));
      return (snake + vcr) / (2 * std::numbers::pi * CAPACITOR);
    }

    struct Correction
    {
      double Frequency;
      double Factor;
    };

    /*
      Ratio of measured cutoff to integrator frequency (white noise at half level, see module/test/sid_filters).
      Loop gain of 2-pole filter is lowered by op-amps (gain is ~5 at working point), reSIDfp differs at high
      frequencies, 8580 op-amps have higher gain.
    */
    const Correction RESID_6581_CORRECTION[] = {{300, 0.73},   {500, 0.75},   {700, 0.66},   {1000, 0.6}, {2000, 0.61},
                                                {8000, 0.625}, {11000, 0.64}, {16000, 0.67}, {22000, 0.7}};
    const Correction RESIDFP_6581_CORRECTION[] = {{250, 0.71},   {400, 0.75},   {700, 0.79},    {1400, 0.81},
                                                  {2000, 0.75},  {2800, 0.68},  {4000, 0.62},   {8000, 0.625},
                                                  {11000, 0.62}, {16000, 0.59}, {22000, 0.535}, {32000, 0.49}};
    const Correction RESIDFP_8580_CORRECTION[] = {{150, 0.95},   {1000, 0.94},  {2000, 0.965}, {4000, 0.945},
                                                  {5600, 0.93},  {8000, 0.895}, {11000, 0.84}, {16000, 0.785},
                                                  {22000, 0.68}, {32000, 0.66}};

    // piecewise linear by logarithm of frequency
    template<std::size_t N>
    double Correct(const Correction (&table)[N], double freq)
    {
      if (freq <= table[0].Frequency)
      {
        return freq * table[0].Factor;
      }
      for (std::size_t idx = 1; idx < N; ++idx)
      {
        const auto& lo = table[idx - 1];
        const auto& hi = table[idx];
        if (freq < hi.Frequency)
        {
          const auto pos = std::log(freq / lo.Frequency) / std::log(hi.Frequency / lo.Frequency);
          return freq * (lo.Factor + (hi.Factor - lo.Factor) * pos);
        }
      }
      return freq * table[N - 1].Factor;
    }

    double Get6581Cutoff(Engine engine, double vw, double uCox)
    {
      const auto freq = Get6581IntegratorFrequency(vw, uCox);
      return engine == Engine::RESID ? Correct(RESID_6581_CORRECTION, freq) : Correct(RESIDFP_6581_CORRECTION, freq);
    }

    double Get6581DacVoltage(Engine engine, double cutoff, double uCox)
    {
      // monotonic, so bisection is precise enough after 40 steps
      double lo = MIN_VW;
      double hi = MAX_VW;
      if (cutoff <= Get6581Cutoff(engine, lo, uCox))
      {
        return lo;
      }
      if (cutoff >= Get6581Cutoff(engine, hi, uCox))
      {
        return hi;
      }
      for (int step = 0; step < 40; ++step)
      {
        const auto mid = (lo + hi) / 2;
        (Get6581Cutoff(engine, mid, uCox) < cutoff ? lo : hi) = mid;
      }
      return (lo + hi) / 2;
    }

    double Get6581Ucox(double baseResistance)
    {
      return 1 / (baseResistance * WL_SNAKE * (VDDT - WORKING_POINT));
    }

    /*
      8580 constants are from reSIDfp FilterModelConfig8580.cpp, Filter8580.cpp and Integrator8580.h
    */
    const double UCOX_8580 = 100e-6;
    const double VTH_8580 = 0.80;
    const double CAPACITOR_8580 = 22e-9;
    const double DAC_WL0 = 0.00615;
    // vi = vo point of 8580 op-amp voltage transfer function
    const double WORKING_POINT_8580 = 4.813;

    double Get8580DacWL(uint_t fc)
    {
      // no bits selected means resistance with half W/L ratio
      return fc ? DAC_WL0 * (fc & (CUTOFF_VALUES - 1)) : DAC_WL0 / 2;
    }

    double GetResidFp8580Cutoff(uint_t fc, double curve)
    {
      // Integrator8580::solve linearized at working point: triode DAC transistors with gate voltage 4.76V*cp,
      // where Filter8580::setFilterCurve maps curve to cp = 1.8 - curve * 3/5
      const auto cp = 1.8 - std::clamp(curve, 0.0, 1.0) * 3 / 5;
      const auto vgst = 4.76 * cp - VTH_8580 - WORKING_POINT_8580;
      return Correct(RESIDFP_8580_CORRECTION,
                     UCOX_8580 * Get8580DacWL(fc) * vgst / (2 * std::numbers::pi * CAPACITOR_8580));
    }

    double GetResid8580Cutoff(uint_t fc, double maxCutoff)
    {
      return maxCutoff * (fc + 1) / CUTOFF_VALUES;
    }

    // golden section search of single parameter minimizing logarithmic error, which is unimodal in reasonable range
    template<class F>
    double Fit(const CutoffCurve& curve, double lo, double hi, F&& cutoff)
    {
      const auto error = [&](double param) {
        double result = 0;
        // zero cutoff is not representable on logarithmic scale
        for (uint_t fc = 32; fc < CUTOFF_VALUES; fc += 32)
        {
          const auto diff = std::log(cutoff(fc, param)) - std::log(curve.GetFrequency(fc));
          result += diff * diff;
        }
        return result;
      };
      const double ratio = (std::sqrt(5.0) - 1) / 2;
      for (int step = 0; step < 50; ++step)
      {
        const auto x1 = hi - ratio * (hi - lo);
        const auto x2 = lo + ratio * (hi - lo);
        if (error(x1) < error(x2))
        {
          hi = x2;
        }
        else
        {
          lo = x1;
        }
      }
      return (lo + hi) / 2;
    }

    double FitResidFp8580Curve(const CutoffCurve& curve)
    {
      return Fit(curve, 0, 1, &GetResidFp8580Cutoff);
    }

    double FitResid6581Bias(const CutoffCurve& curve)
    {
      if (curve.Type == CutoffCurve::RESID_6581)
      {
        return curve.Params[0];
      }
      return Fit(curve, -2, 2, [](uint_t fc, double bias) {
        return Get6581Cutoff(Engine::RESID, GetResid6581DacVoltage(fc, bias), RESID_UCOX);
      });
    }
  }  // namespace FilterModel
}  // namespace Module::Sid
