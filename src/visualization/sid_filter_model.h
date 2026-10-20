/**
 *
 * @file
 *
 * @brief SID filters cutoff curves as applied by native emulation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <memory>
#include <string>
#include <vector>

namespace Visualization::SidFilterModel
{
  /**
   * Mirrors module/players/external/sid/filters.cpp (presets and small signal cutoff models of reSID and reSIDfp)
   * and parameters translation of module/players/external/sid.cpp, so displayed curve is the one really emulated.
   */
  const int CUTOFF_VALUES = 2048;
  const double RESID_UCOX = 20e-6;
  const double RESID_8580_MAX_CUTOFF = 12500.0;

  class Curve
  {
  public:
    using Ptr = std::shared_ptr<const Curve>;
    virtual ~Curve() = default;

    virtual double Frequency(int fc) const = 0;
  };

  //! @param preset value of zxtune.core.sid.filter_6581_preset, 0 is custom
  //! @return nullptr for custom filter
  Curve::Ptr Preset6581(int preset);
  Curve::Ptr Preset8580(int preset);

  //! Displayed names of presets by id (index), the first one is custom filter. Ids are stored in settings and core
  //! keeps only curves (see filters.cpp), so names are the same as Android's preferences use.
  const std::vector<std::string>& PresetNames6581();
  const std::vector<std::string>& PresetNames8580();

  //! Emulation settings affecting cutoff, see zxtune.core.sid.* options
  struct Settings
  {
    bool ResidFp = true;
    int Preset6581 = 0;
    int Preset8580 = 0;
    //! reSIDfp, 0..1
    double Curve6581 = 0.5;
    double Range6581 = 0.5;
    double Curve8580 = 0.5;
    //! reSID, millivolts
    int BiasMv = 0;
  };

  //! Emulated cutoff frequencies in Hz for every register value
  std::vector<double> Cutoff6581(const Settings& settings);
  std::vector<double> Cutoff8580(const Settings& settings);

  //! Curve defined by preset (e.g. measured on real chip), empty for custom filter
  std::vector<double> Reference6581(const Settings& settings);
  std::vector<double> Reference8580(const Settings& settings);

  double ResidFpUcox(double range);
  double ResidFpRange(double uCox);
  double ResidFpDacVoltage(int fc, double curve);
  double ResidDacVoltage(int fc, double bias);
  //! Small signal integrator frequency at op-amp working point
  double IntegratorFrequency6581(double vw, double uCox);
  //! Effective cutoff of reSIDfp (or reSID) 6581
  double Cutoff6581(bool residFp, double vw, double uCox);
  double DacVoltage6581(bool residFp, double cutoff, double uCox);
  double UcoxForBase(double baseResistance);
  double ResidFp8580Cutoff(int fc, double curve);
}  // namespace Visualization::SidFilterModel
