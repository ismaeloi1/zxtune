/**
 *
 * @file
 *
 * @brief Spectrum analyzer view implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/spectrum_view.h"

#include "apps/lhuna-desktop/engine/engine.h"
#include "apps/lhuna-desktop/ui/theme.h"

#include <QtGui/QLinearGradient>
#include <QtGui/QPainter>

#include <algorithm>

namespace Lhuna
{
  // as Android's analyzer: levels are 0..100, falling by 2 per frame at 30fps, here doubled rate with half step
  const int MAX_LEVEL = 100;
  const int FALL_SPEED = 1;
  const int PEAK_FALL_SPEED = 1;
  const int UPDATE_PERIOD_MS = 16;

  SpectrumView::SpectrumView(Engine& engine, QWidget* parent)
    : QWidget(parent)
    , Player(engine)
  {
    setMinimumHeight(48);
    setAttribute(Qt::WA_OpaquePaintEvent);
    Timer.setInterval(UPDATE_PERIOD_MS);
    Timer.setTimerType(Qt::PreciseTimer);
    connect(&Timer, &QTimer::timeout, this, &SpectrumView::Update);
  }

  QSize SpectrumView::sizeHint() const
  {
    return {600, 120};
  }

  void SpectrumView::showEvent(QShowEvent* event)
  {
    QWidget::showEvent(event);
    Timer.start();
  }

  void SpectrumView::hideEvent(QHideEvent* event)
  {
    Timer.stop();
    QWidget::hideEvent(event);
  }

  void SpectrumView::Update()
  {
    std::array<Sound::Analyzer::LevelType, BANDS> fresh{};
    const auto analyzer = Player.State() == PlaybackState::PLAYING ? Player.Analyzer() : Sound::Analyzer::Ptr();
    if (analyzer)
    {
      analyzer->GetSpectrum(fresh.data(), fresh.size());
    }
    bool active = false;
    for (int band = 0; band < BANDS; ++band)
    {
      const int level = std::min<int>(fresh[band].Raw(), MAX_LEVEL);
      Levels[band] = std::max(Levels[band] - FALL_SPEED, level);
      Peaks[band] = std::max(Peaks[band] - PEAK_FALL_SPEED, Levels[band]);
      active |= Peaks[band] != 0;
    }
    if (active || analyzer)
    {
      update();
    }
  }

  void SpectrumView::paintEvent(QPaintEvent* /*event*/)
  {
    QPainter painter(this);
    painter.fillRect(rect(), palette().color(QPalette::Window));
    const auto area = rect().adjusted(4, 4, -4, -2);
    if (area.width() <= 0 || area.height() <= 0)
    {
      return;
    }
    const auto barWidth = static_cast<double>(area.width()) / BANDS;
    const auto gap = barWidth > 4 ? 2.0 : 1.0;
    QLinearGradient gradient(0, area.bottom(), 0, area.top());
    gradient.setColorAt(0, Theme::ACCENT.darker(160));
    gradient.setColorAt(0.7, Theme::ACCENT);
    gradient.setColorAt(1, Theme::ACCENT.lighter(160));
    for (int band = 0; band < BANDS; ++band)
    {
      const auto left = area.left() + band * barWidth;
      const auto height = area.height() * Levels[band] / MAX_LEVEL;
      if (height > 0)
      {
        painter.fillRect(QRectF(left, area.bottom() + 1 - height, barWidth - gap, height), gradient);
      }
      if (Peaks[band] > 0)
      {
        const auto peak = area.bottom() + 1 - area.height() * Peaks[band] / MAX_LEVEL;
        painter.fillRect(QRectF(left, peak - 2, barWidth - gap, 2), QColor(255, 255, 255, 160));
      }
    }
  }
}  // namespace Lhuna
