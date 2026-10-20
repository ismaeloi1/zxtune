/**
 *
 * @file
 *
 * @brief SID filter cutoff curve with live cutoff of playing tune
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "visualization/sid_filter_model.h"

#include <QtCore/QTimer>
#include <QtWidgets/QWidget>

#include <functional>
#include <vector>

namespace Lhuna
{
  class Settings;

  /**
   * Port of Android's FilterCurveView/FilterCurvePreference: cutoff frequency (log scale) by cutoff register as
   * emulated with current settings (see Visualization::SidFilterModel), reference curve of selected preset and live
   * cutoff markers of playing tune. Placed under filter presets, so the effect of any filter setting is seen at once.
   */
  class FilterCurveView : public QWidget
  {
  public:
    //! Filter state of single chip from SID registers
    struct LiveState
    {
      int Cutoff = 0;
      int Mode = 0;
      int Resonance = 0;
      int Routing = 0;
    };

    struct Live
    {
      std::vector<LiveState> Chips;
      //! true if playing tune's chip is MOS8580 (else model from settings is displayed)
      bool Is8580 = false;
      bool Known = false;
    };
    using LiveSource = std::function<Live()>;

    FilterCurveView(Settings& settings, LiveSource live, QWidget* parent);

    QSize sizeHint() const override;

  protected:
    void paintEvent(QPaintEvent* event) override;
    void showEvent(QShowEvent* event) override;
    void hideEvent(QHideEvent* event) override;

  private:
    void Poll();
    Visualization::SidFilterModel::Settings ReadSettings() const;

  private:
    Settings& Options;
    const LiveSource Source;
    QTimer Timer;
    Live Current;
    // curves are recalculated only on settings change
    bool HasCurves = false;
    Visualization::SidFilterModel::Settings CurvesSettings;
    std::vector<double> Emulated6581;
    std::vector<double> Emulated8580;
    std::vector<double> Reference6581;
    std::vector<double> Reference8580;
  };
}  // namespace Lhuna
