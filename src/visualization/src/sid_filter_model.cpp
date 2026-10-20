/**
 *
 * @file
 *
 * @brief SID filters cutoff curves implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "visualization/sid_filter_model.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <functional>
#include <limits>
#include <map>
#include <numbers>
#include <utility>

namespace Visualization::SidFilterModel
{
  const int CUTOFF_BITS = 11;
  const double PI = std::numbers::pi;

  // 6581 constants are the same in reSID 1.0 (filter.cc) and reSIDfp (FilterModelConfig6581.cpp)
  const double VDDT = 12.18 - 1.31;
  const double VTH = 1.31;
  const double WORKING_POINT = 4.54;
  const double UT = 26.0e-3;
  const double CAPACITOR = 470e-12;
  const double WL_VCR = 9.0;
  const double WL_SNAKE = 1.0 / 115;
  const double DAC_ZERO = 6.65;
  const double DAC_SCALE = 2.63;
  const double MIN_VW = 6.0;
  const double MAX_VW = 10.3;

  // 8580 constants are from reSIDfp FilterModelConfig8580.cpp, Filter8580.cpp and Integrator8580.h
  const double UCOX_8580 = 100e-6;
  const double VTH_8580 = 0.80;
  const double CAPACITOR_8580 = 22e-9;
  const double DAC_WL0 = 0.00615;
  const double WORKING_POINT_8580 = 4.813;

  //! reSID 1.0 6581 model with DAC zero level shift in volts
  class ResidCurve : public Curve
  {
  public:
    explicit ResidCurve(double bias)
      : Bias(bias)
    {}

    double Frequency(int fc) const override
    {
      return Cutoff6581(false, ResidDacVoltage(fc, Bias), RESID_UCOX);
    }

    const double Bias;
  };

  // JSIDPlay2 builder.resid.residfp.SID.kinkedDac
  double KinkedDac(int input, double nonlinearity)
  {
    double value = 0.0;
    double weight = 1.0;
    const double dir = 2 * nonlinearity;
    for (int bit = 0; bit < CUTOFF_BITS; ++bit)
    {
      if (input & (1 << bit))
      {
        value += weight;
      }
      weight *= dir;
    }
    return value / (weight / nonlinearity / nonlinearity) * (1 << CUTOFF_BITS);
  }

  //! resid-fp (Antti Lankila) model: fixed resistance parallel to FET one charging 470pF capacitor
  class Type3Curve : public Curve
  {
  public:
    Type3Curve(double base, double offset, double steepness, double minFet, double nonlinearity = 0.96,
               double attenuation = 0.5)
      : Base(base)
      , Offset(offset)
      , Steepness(steepness)
      , MinFet(minFet)
      , Nonlinearity(nonlinearity)
      , Attenuation(attenuation)
    {}

    double Frequency(int fc) const override
    {
      const auto dynamic = MinFet + Offset / std::pow(Steepness, KinkedDac(fc, Nonlinearity));
      const auto resistance = Base * dynamic / (Base + dynamic);
      // highpass output attenuation lowers cutoff of 2-pole loop
      return std::sqrt(Attenuation) / (2 * PI * 470e-12 * resistance);
    }

    const double Base;
    const double Offset;
    const double Steepness;
    const double MinFet;
    const double Nonlinearity;
    const double Attenuation;
  };

  class LinearCurve : public Curve
  {
  public:
    LinearCurve(double perUnit, double atZero)
      : PerUnit(perUnit)
      , AtZero(atZero)
    {}

    double Frequency(int fc) const override
    {
      return PerUnit * fc + AtZero;
    }

  private:
    const double PerUnit;
    const double AtZero;
  };

  // JSIDPlay2 reSID 1.0 curve position P means DAC zero of 6.65V - (P - 0.5) * 2V
  Curve::Ptr ResidPosition(double position)
  {
    return std::make_shared<ResidCurve>((0.5 - position) * 2);
  }

  Curve::Ptr Type3(double base, double offset, double steepness, double minFet, double nonlinearity = 0.96)
  {
    return std::make_shared<Type3Curve>(base, offset, steepness, minFet, nonlinearity);
  }

  // Presets are from JSIDPlay2 default configuration (jsidplay2.ini) and VICE defaults
  const std::map<int, Curve::Ptr>& Presets6581()
  {
    static const std::map<int, Curve::Ptr> INSTANCE = {
        {1, ResidPosition(0.1)},
        {2, ResidPosition(0.3)},
        {3, ResidPosition(0.4)},
        {4, ResidPosition(0.5)},
        {5, ResidPosition(0.6)},
        {6, ResidPosition(0.7)},
        {7, ResidPosition(0.9)},
        {8, std::make_shared<ResidCurve>(0.5)},
        {9, Type3(1537690.1305811733, 76322577.13241087, 1.0072175060266852, 19114.018733380854)},
        {10, Type3(1147036.4394268463, 274228796.97550374, 1.0066634233403395, 16125.154840564108, 0.9613160610660189)},
        {11, Type3(1522171.922983084, 21729926.667291082, 1.004994802537475, 14299.149638099827)},
        {12, Type3(1613349.4942964897, 67122945.35403329, 1.005727808339829, 15462.006399118263)},
        {13, Type3(1.55e6, 2.5e8, 1.006, 1.9e4)},
        {14, Type3(1399768.3253307983, 553018906.8926692, 1.0051493199361266, 11961.908870403166)},
        {15, Type3(1250736.2235895505, 1521187976.8735676, 1.005543646897986, 8581.78418415723)},
        {16, Type3(1.9e6, 8.5e7, 1.0058, 2e4)},
        {17, Type3(1.83e6, 2.6e9, 1.0064, 2.5e4)},
        {18, Type3(1.65e6, 1.2e10, 1.006, 1e4)},
        {19, Type3(1.5e6, 1.8e8, 1.0065, 1.8e4)},
        {20, Type3(1.3e6, 1.9e8, 1.0066, 1.8e4)},
        {21, Type3(1.3e6, 3.7e8, 1.0066, 1.8e4)},
        {22, Type3(1164920.4999651583, 12915042.165290257, 1.0058853753357735, 12914.5661141159)},
        {23, Type3(1.16e6, 9.9e6, 1.0058, 1.48e4)},
        {24, Type3(840577.4520801408, 1909158.8633669745, 1.0068865662510837, 14858.140079688419)},
        {25, Type3(1.08e6, 1.8e6, 1.006, 1.0e4)},
        {26, Type3(1.1e6, 8e6, 1.0052, 1.7e4)},
        {27, Type3(1590881.4720854638, 32349044.218055427, 1.005583147929036, 16357.252396224501)},
        {28, Type3(1.45e6, 1.75e8, 1.0055, 1e4)},
        {29, Type3(1585454.0607309118, 97702791.79673694, 1.0053036856077033, 14497.859765307807)},
        {30, Type3(1090649.9265298771, 264401456.3944572, 1.0145069244334666, 19890.786352277173)},
        {31, Type3(1119380.8539989102, 257709531.3343812, 1.0073881870085593, 20269.79137269368)},
    };
    return INSTANCE;
  }

  const std::map<int, Curve::Ptr>& Presets8580()
  {
    static const std::map<int, Curve::Ptr> INSTANCE = {
        {1, std::make_shared<LinearCurve>(13400.0 / (CUTOFF_VALUES - 1), 0.0)},
        {2, std::make_shared<LinearCurve>(12500.0 / (CUTOFF_VALUES - 1), 0.0)},
        {3, std::make_shared<LinearCurve>(11700.0 / (CUTOFF_VALUES - 1), 0.0)},
        {4, std::make_shared<LinearCurve>(5.7, 20.0)},
        {5, std::make_shared<LinearCurve>(6.55, 20.0)},
    };
    return INSTANCE;
  }

  Curve::Ptr FindPreset(const std::map<int, Curve::Ptr>& presets, int preset)
  {
    const auto it = presets.find(preset);
    return it != presets.end() ? it->second : Curve::Ptr();
  }

  Curve::Ptr Preset6581(int preset)
  {
    return FindPreset(Presets6581(), preset);
  }

  Curve::Ptr Preset8580(int preset)
  {
    return FindPreset(Presets8580(), preset);
  }

  const std::vector<std::string>& PresetNames6581()
  {
    static const std::vector<std::string> INSTANCE = {"Custom",
                                                      "JSIDPlay2 reSID: Lightest",
                                                      "JSIDPlay2 reSID: Lighter",
                                                      "JSIDPlay2 reSID: Light",
                                                      "JSIDPlay2 reSID: Average",
                                                      "JSIDPlay2 reSID: Dark",
                                                      "JSIDPlay2 reSID: Darker",
                                                      "JSIDPlay2 reSID: Darkest",
                                                      "VICE default (reSID bias +500 mV)",
                                                      "ReSID 6581 (resid-fp fit)",
                                                      "Alankila 6581R4AR 3789",
                                                      "Alankila 6581R3 3984 #1",
                                                      "Alankila 6581R3 3984 #2",
                                                      "Lord Nightmare 6581R3 4285",
                                                      "Lord Nightmare 6581R3 4485",
                                                      "Lord Nightmare 6581R4 1986S",
                                                      "ZrX 6581R3 0384",
                                                      "ZrX 6581R3 1984",
                                                      "ZrX 6581R3 3684",
                                                      "ZrX 6581R3 3985",
                                                      "ZrX 6581R4AR 2286",
                                                      "Trurl 6581R3 0784",
                                                      "Trurl 6581R3 0486S",
                                                      "Trurl 6581R3 3384",
                                                      "Trurl 6581R3 4885",
                                                      "Trurl 6581R4AR 3789",
                                                      "Trurl 6581R4AR 4486",
                                                      "Nata 6581R3 2083",
                                                      "Grue 6581R4AR 3488",
                                                      "KruLLo",
                                                      "Enigma 6581R3 4885",
                                                      "Enigma 6581R3 1585"};
    return INSTANCE;
  }

  const std::vector<std::string>& PresetNames8580()
  {
    static const std::vector<std::string> INSTANCE = {"Custom",
                                                      "JSIDPlay2 reSID: Light",
                                                      "JSIDPlay2 reSID: Average",
                                                      "JSIDPlay2 reSID: Dark",
                                                      "Trurl 8580R5 1489",
                                                      "Trurl 8580R5 3691"};
    return INSTANCE;
  }

  // R-2R ladder of 6581 cutoff DAC with 2R/R=2.2 and missing termination
  const std::array<double, CUTOFF_BITS>& DacBits()
  {
    static const auto INSTANCE = [] {
      std::array<double, CUTOFF_BITS> result{};
      const double inf = std::numeric_limits<double>::infinity();
      for (int setBit = 0; setBit < CUTOFF_BITS; ++setBit)
      {
        const double r = 1.0;
        const double r2 = 2.2 * r;
        double vn = 1.0;
        double rn = inf;
        int bit = 0;
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
          const double i = vn / rn;
          rn = r2 * rn / (r2 + rn);
          vn = rn * i;
        }
        result[setBit] = vn;
      }
      return result;
    }();
    return INSTANCE;
  }

  double DacSum()
  {
    double result = 0;
    for (const auto bit : DacBits())
    {
      result += bit;
    }
    return result;
  }

  double DacOutput(int fc, double leakage)
  {
    const auto& bits = DacBits();
    double result = 0;
    for (int idx = 0; idx < CUTOFF_BITS; ++idx)
    {
      result += bits[idx] * ((fc & (1 << idx)) ? 1.0 : leakage);
    }
    return result;
  }

  double ResidFpUcox(double range)
  {
    return (1 + 39 * std::clamp(range, 0.0, 1.0)) * 1e-6;
  }

  double ResidFpRange(double uCox)
  {
    return std::clamp((uCox * 1e6 - 1) / 39, 0.0, 1.0);
  }

  double ResidFpDacVoltage(int fc, double curve)
  {
    return DAC_ZERO + (1 - curve) + DacOutput(fc, 0.0075) / DacSum() * DAC_SCALE;
  }

  double ResidDacVoltage(int fc, double bias)
  {
    const auto dac = std::floor(DacOutput(fc, 0.01) * ((1 << CUTOFF_BITS) - 1) + 0.5);
    return DAC_ZERO + bias + dac * DAC_SCALE / (1 << CUTOFF_BITS);
  }

  double IntegratorFrequency6581(double vw, double uCox)
  {
    const auto vgdt = VDDT - WORKING_POINT;
    const auto snake = uCox * WL_SNAKE * vgdt;
    const auto vg = VDDT - std::sqrt(((VDDT - vw) * (VDDT - vw) + vgdt * vgdt) / 2);
    const auto u = (vg - VTH - WORKING_POINT) / (2 * UT);
    const auto vcr = 2 * UT * WL_VCR * uCox * std::log1p(std::exp(u)) / (1 + std::exp(-u));
    return (snake + vcr) / (2 * PI * CAPACITOR);
  }

  using Correction = std::vector<std::pair<double, double>>;

  // Measured cutoff to integrator frequency ratios (see filters.cpp)
  const Correction RESID_6581_CORRECTION = {{300.0, 0.73},   {500.0, 0.75},   {700.0, 0.66},
                                            {1000.0, 0.6},   {2000.0, 0.61},  {8000.0, 0.625},
                                            {11000.0, 0.64}, {16000.0, 0.67}, {22000.0, 0.7}};
  const Correction RESIDFP_6581_CORRECTION = {{250.0, 0.71},   {400.0, 0.75},   {700.0, 0.79},    {1400.0, 0.81},
                                              {2000.0, 0.75},  {2800.0, 0.68},  {4000.0, 0.62},   {8000.0, 0.625},
                                              {11000.0, 0.62}, {16000.0, 0.59}, {22000.0, 0.535}, {32000.0, 0.49}};
  const Correction RESIDFP_8580_CORRECTION = {{150.0, 0.95},   {1000.0, 0.94},  {2000.0, 0.965}, {4000.0, 0.945},
                                              {5600.0, 0.93},  {8000.0, 0.895}, {11000.0, 0.84}, {16000.0, 0.785},
                                              {22000.0, 0.68}, {32000.0, 0.66}};

  double Correct(const Correction& table, double freq)
  {
    if (freq <= table[0].first)
    {
      return freq * table[0].second;
    }
    for (std::size_t idx = 1; idx < table.size(); ++idx)
    {
      const auto [loFreq, loFactor] = table[idx - 1];
      const auto [hiFreq, hiFactor] = table[idx];
      if (freq < hiFreq)
      {
        const auto pos = std::log(freq / loFreq) / std::log(hiFreq / loFreq);
        return freq * (loFactor + (hiFactor - loFactor) * pos);
      }
    }
    return freq * table.back().second;
  }

  double Cutoff6581(bool residFp, double vw, double uCox)
  {
    return Correct(residFp ? RESIDFP_6581_CORRECTION : RESID_6581_CORRECTION, IntegratorFrequency6581(vw, uCox));
  }

  double DacVoltage6581(bool residFp, double cutoff, double uCox)
  {
    double lo = MIN_VW;
    double hi = MAX_VW;
    if (cutoff <= Cutoff6581(residFp, lo, uCox))
    {
      return lo;
    }
    if (cutoff >= Cutoff6581(residFp, hi, uCox))
    {
      return hi;
    }
    for (int iter = 0; iter < 40; ++iter)
    {
      const auto mid = (lo + hi) / 2;
      (Cutoff6581(residFp, mid, uCox) < cutoff ? lo : hi) = mid;
    }
    return (lo + hi) / 2;
  }

  double UcoxForBase(double baseResistance)
  {
    return 1 / (baseResistance * WL_SNAKE * (VDDT - WORKING_POINT));
  }

  double DacWL8580(int fc)
  {
    return fc != 0 ? DAC_WL0 * (fc & (CUTOFF_VALUES - 1)) : DAC_WL0 / 2;
  }

  double ResidFp8580Cutoff(int fc, double curve)
  {
    const auto cp = 1.8 - std::clamp(curve, 0.0, 1.0) * 3 / 5;
    const auto vgst = 4.76 * cp - VTH_8580 - WORKING_POINT_8580;
    return Correct(RESIDFP_8580_CORRECTION, UCOX_8580 * DacWL8580(fc) * vgst / (2 * PI * CAPACITOR_8580));
  }

  // golden section search minimizing logarithmic error
  double Fit(const Curve& curve, double low, double high, const std::function<double(int, double)>& cutoff)
  {
    const auto error = [&](double param) {
      double result = 0.0;
      for (int fc = 32; fc < CUTOFF_VALUES; fc += 32)
      {
        const auto diff = std::log(cutoff(fc, param)) - std::log(curve.Frequency(fc));
        result += diff * diff;
      }
      return result;
    };
    const double ratio = (std::sqrt(5.0) - 1) / 2;
    double lo = low;
    double hi = high;
    for (int iter = 0; iter < 50; ++iter)
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

  double FitResidBias(const Curve& curve)
  {
    if (const auto* resid = dynamic_cast<const ResidCurve*>(&curve))
    {
      return resid->Bias;
    }
    return Fit(curve, -2.0, 2.0,
               [](int fc, double bias) { return Cutoff6581(false, ResidDacVoltage(fc, bias), RESID_UCOX); });
  }

  std::vector<double> Tabulate(const std::function<double(int)>& func)
  {
    std::vector<double> result(CUTOFF_VALUES);
    for (int fc = 0; fc < CUTOFF_VALUES; ++fc)
    {
      result[fc] = func(fc);
    }
    return result;
  }

  std::vector<double> Cutoff6581(const Settings& settings)
  {
    const auto preset = Preset6581(settings.Preset6581);
    if (!settings.ResidFp)
    {
      const auto bias = preset ? FitResidBias(*preset) : std::clamp(settings.BiasMv, -1000, 1000) / 1000.0;
      return Tabulate([bias](int fc) { return Cutoff6581(false, ResidDacVoltage(fc, bias), RESID_UCOX); });
    }
    if (!preset)
    {
      const auto uCox = ResidFpUcox(settings.Range6581);
      const auto curve = settings.Curve6581;
      return Tabulate([uCox, curve](int fc) { return Cutoff6581(true, ResidFpDacVoltage(fc, curve), uCox); });
    }
    const auto* type3 = dynamic_cast<const Type3Curve*>(preset.get());
    const auto base = type3 ? UcoxForBase(type3->Base) : RESID_UCOX;
    const auto uCox = ResidFpUcox(ResidFpRange(base));
    return Tabulate([&](int fc) { return Cutoff6581(true, DacVoltage6581(true, preset->Frequency(fc), uCox), uCox); });
  }

  std::vector<double> Cutoff8580(const Settings& settings)
  {
    const auto preset = Preset8580(settings.Preset8580);
    if (settings.ResidFp)
    {
      const auto curve = preset ? Fit(*preset, 0.0, 1.0, &ResidFp8580Cutoff) : settings.Curve8580;
      return Tabulate([curve](int fc) { return ResidFp8580Cutoff(fc, curve); });
    }
    const auto max = preset ? preset->Frequency(CUTOFF_VALUES - 1) : RESID_8580_MAX_CUTOFF;
    return Tabulate([max](int fc) { return max * (fc + 1) / CUTOFF_VALUES; });
  }

  std::vector<double> Reference(const Curve::Ptr& curve)
  {
    return curve ? Tabulate([&curve](int fc) { return curve->Frequency(fc); }) : std::vector<double>();
  }

  std::vector<double> Reference6581(const Settings& settings)
  {
    return Reference(Preset6581(settings.Preset6581));
  }

  std::vector<double> Reference8580(const Settings& settings)
  {
    return Reference(Preset8580(settings.Preset8580));
  }
}  // namespace Visualization::SidFilterModel
