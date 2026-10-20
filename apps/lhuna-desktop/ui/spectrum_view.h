/**
 *
 * @file
 *
 * @brief Spectrum analyzer view
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <QtCore/QTimer>
#include <QtWidgets/QWidget>

#include <array>

namespace Lhuna
{
  class Engine;

  /**
   * Port of Android's SpectrumAnalyzerView: bars of core's FFT analyzer, falling smoothly when sound stops.
   * Polled at display rate only while visible.
   */
  class SpectrumView : public QWidget
  {
  public:
    SpectrumView(Engine& engine, QWidget* parent);

    QSize sizeHint() const override;

  protected:
    void paintEvent(QPaintEvent* event) override;
    void showEvent(QShowEvent* event) override;
    void hideEvent(QHideEvent* event) override;

  private:
    void Update();

  private:
    // analyzer is initialized by the first request size, so it's fixed (as Android's maxBarsCount)
    static const int BANDS = 48;
    Engine& Player;
    QTimer Timer;
    std::array<int, BANDS> Levels{};
    std::array<int, BANDS> Peaks{};
  };
}  // namespace Lhuna
