/**
 *
 * @file
 *
 * @brief SID filter cutoff curve implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/filter_curve_view.h"

#include "apps/lhuna-desktop/ui/settings.h"
#include "apps/lhuna-desktop/ui/theme.h"

#include <QtGui/QPainter>
#include <QtGui/QPainterPath>

#include <algorithm>
#include <cmath>

namespace Lhuna
{
  const double MIN_FREQ = 30.0;
  const double MAX_FREQ = 24000.0;
  const int FREQ_GRID[] = {50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000};
  const int CUTOFF_GRID[] = {0, 512, 1024, 1536, 2047};
  const QColor MARKER(0xff, 0x70, 0x43);
  // live state of the playing tune is polled, as Android's preference does, so no listeners for gauges are needed
  const int POLL_PERIOD_MS = 100;

  QString FormatHz(double hz)
  {
    return hz >= 1000 ? QString::asprintf("%.1f kHz", hz / 1000) : QString("%1 Hz").arg(std::lround(hz));
  }

  bool SameCurves(const Visualization::SidFilterModel::Settings& lh, const Visualization::SidFilterModel::Settings& rh)
  {
    return lh.ResidFp == rh.ResidFp && lh.Preset6581 == rh.Preset6581 && lh.Preset8580 == rh.Preset8580
           && lh.Curve6581 == rh.Curve6581 && lh.Range6581 == rh.Range6581 && lh.Curve8580 == rh.Curve8580
           && lh.BiasMv == rh.BiasMv;
  }

  FilterCurveView::FilterCurveView(Settings& settings, LiveSource live, QWidget* parent)
    : QWidget(parent)
    , Options(settings)
    , Source(std::move(live))
  {
    setMinimumHeight(170);
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Preferred);
    Timer.setInterval(POLL_PERIOD_MS);
    connect(&Timer, &QTimer::timeout, this, &FilterCurveView::Poll);
    connect(&Options, &Settings::changed, this, [this](const QString& key) {
      if (key.startsWith("zxtune.core.sid."))
      {
        update();
      }
    });
  }

  QSize FilterCurveView::sizeHint() const
  {
    return {360, 220};
  }

  void FilterCurveView::showEvent(QShowEvent* event)
  {
    QWidget::showEvent(event);
    if (Source)
    {
      Timer.start();
      Poll();
    }
  }

  void FilterCurveView::hideEvent(QHideEvent* event)
  {
    Timer.stop();
    QWidget::hideEvent(event);
  }

  void FilterCurveView::Poll()
  {
    auto live = Source();
    const auto changed = live.Known != Current.Known || live.Is8580 != Current.Is8580
                         || live.Chips.size() != Current.Chips.size()
                         || !std::equal(live.Chips.begin(), live.Chips.end(), Current.Chips.begin(),
                                        [](const LiveState& lh, const LiveState& rh) {
                                          return lh.Cutoff == rh.Cutoff && lh.Mode == rh.Mode
                                                 && lh.Resonance == rh.Resonance && lh.Routing == rh.Routing;
                                        });
    if (changed)
    {
      Current = std::move(live);
      update();
    }
  }

  Visualization::SidFilterModel::Settings FilterCurveView::ReadSettings() const
  {
    Visualization::SidFilterModel::Settings model;
    model.ResidFp = Options.Get("zxtune.core.sid.engine") != 0;
    model.Preset6581 = Options.Get("zxtune.core.sid.filter_6581_preset");
    model.Preset8580 = Options.Get("zxtune.core.sid.filter_8580_preset");
    model.Curve6581 = Options.Get("zxtune.core.sid.filter_6581_curve") / 100.0;
    model.Range6581 = Options.Get("zxtune.core.sid.filter_6581_range") / 100.0;
    model.Curve8580 = Options.Get("zxtune.core.sid.filter_8580_curve") / 100.0;
    model.BiasMv = Options.Get("zxtune.core.sid.filter_bias");
    return model;
  }

  void FilterCurveView::paintEvent(QPaintEvent* /*event*/)
  {
    using Visualization::SidFilterModel::CUTOFF_VALUES;
    using Visualization::SidFilterModel::PresetNames6581;
    using Visualization::SidFilterModel::PresetNames8580;
    const auto settings = ReadSettings();
    if (!HasCurves || !SameCurves(settings, CurvesSettings))
    {
      Emulated6581 = Visualization::SidFilterModel::Cutoff6581(settings);
      Emulated8580 = Visualization::SidFilterModel::Cutoff8580(settings);
      Reference6581 = Visualization::SidFilterModel::Reference6581(settings);
      Reference8580 = Visualization::SidFilterModel::Reference8580(settings);
      CurvesSettings = settings;
      HasCurves = true;
    }
    const auto is8580 = Current.Known ? Current.Is8580 : Options.Get("zxtune.core.sid.model") != 0;
    const auto& active = is8580 ? Emulated8580 : Emulated6581;
    const auto& other = is8580 ? Emulated6581 : Emulated8580;
    const auto& reference = is8580 ? Reference8580 : Reference6581;
    const auto preset = is8580 ? settings.Preset8580 : settings.Preset6581;
    const auto& names = is8580 ? PresetNames8580() : PresetNames6581();

    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    QFont labelFont = font();
    labelFont.setPixelSize(10);
    QFont textFont = font();
    textFont.setPixelSize(12);
    const QFontMetricsF labels(labelFont);
    const QFontMetricsF texts(textFont);
    const QColor textColor = palette().color(QPalette::WindowText);
    const QColor secondary(0x90, 0x90, 0x90);
    const auto labelWidth = labels.horizontalAdvance("20k") + 4;
    const QRectF plot(labelWidth, texts.height() * 1.3, width() - labelWidth - 4,
                      height() - texts.height() * 1.3 - labels.height() - 2 - texts.height() * 1.3);
    if (plot.width() <= 0 || plot.height() <= 0 || active.empty())
    {
      return;
    }
    const auto toX = [&plot](double fc) { return plot.left() + plot.width() * fc / (CUTOFF_VALUES - 1); };
    const auto toY = [&plot](double hz) {
      const auto pos = std::log(std::clamp(hz, MIN_FREQ, MAX_FREQ) / MIN_FREQ) / std::log(MAX_FREQ / MIN_FREQ);
      return plot.bottom() - plot.height() * pos;
    };

    // grid
    painter.setFont(labelFont);
    for (const auto freq : FREQ_GRID)
    {
      const auto y = toY(freq);
      painter.setPen(QPen(QColor(0x90, 0x90, 0x90, 60), 1));
      painter.drawLine(QPointF(plot.left(), y), QPointF(plot.right(), y));
      painter.setPen(secondary);
      painter.drawText(QPointF(0, y + labels.ascent() / 3),
                       freq >= 1000 ? QString("%1k").arg(freq / 1000) : QString::number(freq));
    }
    for (const auto fc : CUTOFF_GRID)
    {
      const auto x = toX(fc);
      painter.setPen(QPen(QColor(0x90, 0x90, 0x90, 60), 1));
      painter.drawLine(QPointF(x, plot.top()), QPointF(x, plot.bottom()));
      const auto label = QString::number(fc);
      const auto w = labels.horizontalAdvance(label);
      painter.setPen(secondary);
      painter.drawText(QPointF(std::clamp(x - w / 2, plot.left(), plot.right() - w), plot.bottom() + labels.height()),
                       label);
    }

    const auto drawCurve = [&](const std::vector<double>& values, const QPen& pen) {
      if (values.empty())
      {
        return;
      }
      QPainterPath path;
      const auto step = std::max<std::size_t>(1, values.size() / std::max(1, static_cast<int>(plot.width())));
      for (std::size_t idx = 0; idx < values.size(); idx += step)
      {
        const QPointF pt(toX(static_cast<double>(idx)), toY(values[idx]));
        if (idx == 0)
        {
          path.moveTo(pt);
        }
        else
        {
          path.lineTo(pt);
        }
      }
      path.lineTo(toX(static_cast<double>(values.size() - 1)), toY(values.back()));
      painter.setPen(pen);
      painter.setBrush(Qt::NoBrush);
      painter.drawPath(path);
    };
    QColor otherColor = secondary;
    otherColor.setAlpha(110);
    drawCurve(other, QPen(otherColor, 1.2));
    QColor referenceColor = textColor;
    referenceColor.setAlpha(170);
    QPen referencePen(referenceColor, 1.5, Qt::DashLine);
    drawCurve(reference, referencePen);
    QPen activePen(Theme::ACCENT, 2.5);
    activePen.setJoinStyle(Qt::RoundJoin);
    drawCurve(active, activePen);

    // header
    painter.setFont(textFont);
    painter.setPen(textColor);
    const auto presetName =
        preset > 0 && preset < static_cast<int>(names.size()) ? QString::fromStdString(names[preset]) : tr("Custom");
    const auto header = QString::fromUtf8("%1 · %2 · %3")
                            .arg(is8580 ? "MOS8580" : "MOS6581", settings.ResidFp ? "reSIDfp" : "reSID", presetName);
    painter.drawText(QPointF(0, texts.ascent()), texts.elidedText(header, Qt::ElideRight, width()));

    // legend in bottom right corner, it's free for any curve
    if (!reference.empty())
    {
      painter.setFont(labelFont);
      const qreal sample = 18;
      const qreal gap = 6;
      auto y = plot.bottom() - gap - labels.descent();
      for (const auto& [text, pen] :
           {std::pair{tr("preset reference"), referencePen}, std::pair{tr("emulated"), activePen}})
      {
        const auto textWidth = labels.horizontalAdvance(text);
        const auto x = plot.right() - gap - textWidth;
        painter.setPen(secondary);
        painter.drawText(QPointF(x, y), text);
        const auto lineY = y - labels.ascent() / 3;
        painter.setPen(pen);
        painter.drawLine(QPointF(x - gap - sample, lineY), QPointF(x - gap, lineY));
        y -= labels.height();
      }
    }

    // live cutoff
    const auto baseline = height() - texts.descent();
    if (Current.Chips.empty())
    {
      painter.setFont(labelFont);
      painter.setPen(secondary);
      painter.drawText(QPointF(0, baseline),
                       labels.elidedText(tr("Cutoff register → frequency. Play a SID tune to see live cutoff"),
                                         Qt::ElideRight, width()));
      return;
    }
    QStringList descriptions;
    for (std::size_t chip = 0; chip < Current.Chips.size(); ++chip)
    {
      const auto& live = Current.Chips[chip];
      const auto fc = std::clamp(live.Cutoff, 0, CUTOFF_VALUES - 1);
      const auto hz = active[fc];
      QColor marker = MARKER;
      // filter does not affect sound without routed voices
      marker.setAlpha((live.Routing & 0x0f) ? 255 : 110);
      painter.setPen(QPen(marker, 1.5));
      painter.setBrush(Qt::NoBrush);
      painter.drawLine(QPointF(toX(fc), plot.top()), QPointF(toX(fc), plot.bottom()));
      painter.drawEllipse(QPointF(toX(fc), toY(hz)), 4, 4);
      QStringList modes;
      if (live.Mode & 1)
      {
        modes << "LP";
      }
      if (live.Mode & 2)
      {
        modes << "BP";
      }
      if (live.Mode & 4)
      {
        modes << "HP";
      }
      QString voices;
      for (int voice = 0; voice < 4; ++voice)
      {
        if (live.Routing & (1 << voice))
        {
          voices += voice == 3 ? QString("E") : QString::number(voice + 1);
        }
      }
      auto text = Current.Chips.size() > 1 ? QString("#%1 ").arg(chip + 1) : QString();
      text += QString::fromUtf8("FC %1 ≈ %2 %3 Res %4 %5")
                  .arg(live.Cutoff)
                  .arg(FormatHz(hz), modes.isEmpty() ? QString("off") : modes.join('+'))
                  .arg(live.Resonance)
                  .arg(voices.isEmpty() ? tr("no voices") : "V" + voices);
      descriptions << text;
    }
    painter.setFont(textFont);
    painter.setPen(textColor);
    painter.drawText(QPointF(0, baseline), texts.elidedText(descriptions.join("  "), Qt::ElideRight, width()));
  }
}  // namespace Lhuna
