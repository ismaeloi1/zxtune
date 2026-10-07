/**
 *
 * @file
 *
 * @brief Chips dashboard widget implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/zxtune-qt/ui/controls/dashboard_view.h"

#include "apps/zxtune-qt/supp/playback_supp.h"
#include "apps/zxtune-qt/ui/controls/chip_widgets.h"
#include "apps/zxtune-qt/ui/controls/scope_data.h"
#include "apps/zxtune-qt/ui/utils.h"

#include "sound/scope.h"

#include "contract.h"

#include <QtCore/QElapsedTimer>
#include <QtCore/QEvent>
#include <QtCore/QTimer>
#include <QtGui/QFontDatabase>
#include <QtGui/QFontMetricsF>
#include <QtGui/QPainter>

#include <algorithm>
#include <array>
#include <vector>

namespace DashboardDetails
{
  const int UPDATE_INTERVAL_MS = 16;
  // layout and status are cheap enough to not track changes precisely
  const qint64 STATUS_PERIOD_MS = 500;
  const uint_t WAVE_WINDOW_MS = 10;
  const uint_t MAX_CHIPS = 3;
  const uint_t MAX_VOICES = 64;
  // same as in oscilloscope
  const uint_t MAX_ROWS = 8;
  const uint_t COLUMNS = Sound::Scope::GAUGE_COLUMNS;

  uint_t ColumnsOf(uint_t voices)
  {
    return std::max<uint_t>((voices + MAX_ROWS - 1) / MAX_ROWS, 1);
  }

  uint_t RowsOf(uint_t voices)
  {
    return std::max<uint_t>((voices + ColumnsOf(voices) - 1) / ColumnsOf(voices), 1);
  }

  QColor White(int alpha)
  {
    return {255, 255, 255, alpha};
  }

  QString Hex(uint_t value, int digits)
  {
    return QString::number(value, 16).toUpper().rightJustified(digits, QLatin1Char('0'));
  }

  // Waveform bits and flags: Sync, Ring modulation, Test, Filtered
  QString WaveTitle(const ScopeData::ChipGauges& chip, uint_t voice)
  {
    const auto ctrl = chip.Control(voice);
    QString result = QLatin1String("Wave ") + QString::number(ctrl >> 4, 16).toUpper() + QLatin1Char(' ');
    if (ctrl & 2)
    {
      result += QLatin1Char('S');
    }
    if (ctrl & 4)
    {
      result += QLatin1Char('R');
    }
    if (ctrl & 8)
    {
      result += QLatin1Char('T');
    }
    if (chip.Routing() & (1 << voice))
    {
      result += QLatin1Char('F');
    }
    return result;
  }

  // Filter modes: Lowpass, Bandpass, Highpass
  QString FilterTitle(const ScopeData::ChipGauges& chip)
  {
    QString result = QLatin1String("Filter ");
    const auto mode = chip.FilterMode();
    if (mode & 1)
    {
      result += QLatin1Char('L');
    }
    if (mode & 2)
    {
      result += QLatin1Char('B');
    }
    if (mode & 4)
    {
      result += QLatin1Char('H');
    }
    return result + QLatin1Char(' ') + QString::number(chip.Cutoff());
  }

  QFont MakeMonospace(const QFont& base)
  {
    QFont result = QFontDatabase::systemFont(QFontDatabase::FixedFont);
    result.setPointSizeF(base.pointSizeF() > 0 ? base.pointSizeF() * 0.9 : 8);
    return result;
  }

  QFont MakeLabel(const QFont& base)
  {
    QFont result(base);
    result.setBold(true);
    if (base.pointSizeF() > 0)
    {
      result.setPointSizeF(base.pointSizeF() * 0.85);
    }
    return result;
  }

  class DashboardViewImpl : public DashboardView
  {
  public:
    DashboardViewImpl(QWidget& parent, PlaybackSupport& supp)
      : DashboardView(parent)
      , Monospace(MakeMonospace(font()))
      , Widgets(Monospace, MakeLabel(font()))
    {
      setObjectName(QLatin1String("DashboardView"));
      setMinimumSize(160, 80);
      setAttribute(Qt::WA_OpaquePaintEvent);
      SetTitle();

      Timer.setTimerType(Qt::PreciseTimer);
      Timer.setInterval(UPDATE_INTERVAL_MS);

      Require(connect(&supp, &PlaybackSupport::OnStartModule, this, &DashboardViewImpl::InitState));
      Require(connect(&supp, &PlaybackSupport::OnStopModule, this, &DashboardViewImpl::CloseState));
      Require(connect(&Timer, &QTimer::timeout, this, &DashboardViewImpl::UpdateState));
    }

    // visualization is useless if too small, so take reasonable space by default
    QSize sizeHint() const override
    {
      return {640, 320};
    }

    void changeEvent(QEvent* event) override
    {
      if (event && QEvent::LanguageChange == event->type())
      {
        SetTitle();
      }
      DashboardView::changeEvent(event);
    }

    void paintEvent(QPaintEvent*) override
    {
      QPainter painter(this);
      painter.fillRect(rect(), Qt::black);
      const QFontMetricsF metrics(Monospace);
      const auto lines = Status.split(QLatin1Char('\n'), Qt::SkipEmptyParts);
      const qreal padding = 4;
      const auto textHeight = lines.empty() ? 0 : metrics.lineSpacing() * lines.size() + padding;
      const auto areaHeight = height() - textHeight;
      if (Voices)
      {
        DrawVoices(painter, areaHeight);
      }
      else if (Chips)
      {
        DrawChips(painter, areaHeight);
      }
      painter.setFont(Monospace);
      painter.setPen(White(230));
      auto y = areaHeight + padding / 2 + metrics.ascent();
      for (const auto& line : lines)
      {
        painter.drawText(QPointF(padding, y), line);
        y += metrics.lineSpacing();
      }
    }

  private:
    void InitState(Sound::Backend::Ptr player, Playlist::Item::Data::Ptr)
    {
      Scope = player->GetScope();
      Layout = {};
      Chips = Voices = 0;
      Status.clear();
      for (auto& smooth : Smooth)
      {
        smooth.fill(0);
      }
      LastStatus.invalidate();
      Timer.start();
    }

    void CloseState()
    {
      Timer.stop();
      Scope.reset();
      Chips = Voices = 0;
      update();
    }

    void UpdateState()
    {
      // separate voices are rendered by players only while data is requested
      if (!Scope || !isVisible())
      {
        return;
      }
      const bool needStatus = !LastStatus.isValid() || LastStatus.elapsed() >= STATUS_PERIOD_MS;
      if (needStatus)
      {
        LastStatus.start();
        Layout = ScopeData::VoicesLayout::Parse(Scope->GetLayout());
      }
      Chips = Voices = 0;
      if (Layout.HasRegisters)
      {
        Data.resize(MAX_CHIPS * Sound::Scope::GAUGES_SIZE);
        Chips = Scope->GetGauges(MAX_CHIPS, -1, WAVE_WINDOW_MS, Data.data());
      }
      else if (const auto voices = std::min(Layout.VoicesCount(), MAX_VOICES))
      {
        Data.resize(voices * Sound::Scope::VOICE_GAUGES_SIZE);
        Voices = Scope->GetVoiceGauges(voices, -1, WAVE_WINDOW_MS, Data.data());
      }
      if (needStatus)
      {
        Status = ToQString(Scope->GetStatus());
      }
      update();
    }

    void SetTitle()
    {
      setWindowTitle(DashboardView::tr("Dashboard"));
    }

    // Chips are placed side by side as in oscilloscope (long chips are split to several columns), card per voice
    void DrawVoices(QPainter& painter, qreal areaHeight)
    {
      if (areaHeight < 40)
      {
        return;
      }
      uint_t totalCols = 0;
      for (const auto& group : Layout.Groups)
      {
        totalCols += ColumnsOf(static_cast<uint_t>(group.Voices.size()));
      }
      const auto cellW = qreal(width()) / std::max<uint_t>(totalCols, 1);
      uint_t voice = 0;
      uint_t col = 0;
      for (const auto& group : Layout.Groups)
      {
        const auto voices = static_cast<uint_t>(group.Voices.size());
        const auto rows = RowsOf(voices);
        // chips with less voices get taller cards
        const auto cellH = areaHeight / rows;
        if (col != 0)
        {
          painter.setPen(QPen(White(160), 2));
          painter.drawLine(QPointF(cellW * col, 0), QPointF(cellW * col, areaHeight));
        }
        for (uint_t idx = 0; idx < voices; ++idx, ++voice)
        {
          if (voice >= Voices)
          {
            return;
          }
          const ScopeData::VoiceGauges gauges(Data.data() + voice * Sound::Scope::VOICE_GAUGES_SIZE);
          const QRectF cell(cellW * (col + idx / rows), cellH * (idx % rows), cellW, cellH);
          DrawVoiceCard(painter, gauges, ToQString(group.Name), ToQString(group.Voices[idx]), cell, Smooth[voice]);
        }
        col += ColumnsOf(voices);
      }
    }

    void DrawVoiceCard(QPainter& painter, const ScopeData::VoiceGauges& gauges, const QString& group,
                       const QString& name, const QRectF& cell, ChipWidgets::Smooth& smooth)
    {
      const qreal pad = 2;
      const auto card = cell.adjusted(pad, pad, -pad, -pad);
      painter.setPen(QPen(White(70), 1));
      painter.setBrush(Qt::NoBrush);
      painter.drawRect(card);
      // title band is separated from graphs to keep both readable
      const auto summary = ChipWidgets::Summary(gauges);
      const auto key = gauges.IsKeyOn() ? QString::fromUtf8(" ●") : QString();
      // group name is omitted if there's no space for it
      const auto full = group + QLatin1Char(' ') + name + key;
      const auto available = card.width() - 9 - QFontMetricsF(Monospace).horizontalAdvance(summary);
      const auto title = QFontMetricsF(font()).horizontalAdvance(full) <= available ? full : name + key;
      const auto band = DrawTitleBand(painter, card, title, summary);
      const QRectF body(QPointF(card.left() + pad * 2, band + pad),
                        QPointF(card.right() - pad * 2, card.bottom() - pad * 2));
      if (body.height() < 18)
      {
        // no space for details, only current level
        DrawHistory(painter, body, gauges, false);
        return;
      }
      QRectF history;
      QRectF widget;
      if (body.width() > body.height() * 2.2)
      {
        // operators schemas need more space
        const auto split = body.left() + body.width() * (ChipWidgets::HasSchema(gauges.Kind()) ? 0.35 : 0.45);
        history = QRectF(QPointF(body.left(), body.top()), QPointF(split - pad * 2, body.bottom()));
        widget = QRectF(QPointF(split + pad * 2, body.top()), body.bottomRight());
      }
      else
      {
        const auto split = body.top() + body.height() * 0.4;
        history = QRectF(body.topLeft(), QPointF(body.right(), split - pad));
        widget = QRectF(QPointF(body.left(), split + pad * 2), body.bottomRight());
      }
      if (Widgets.Draw(painter, widget, gauges, smooth))
      {
        DrawHistory(painter, history, gauges, true);
      }
      else
      {
        DrawHistory(painter, body, gauges, true);
      }
    }

    // @return bottom of title band
    qreal DrawTitleBand(QPainter& painter, const QRectF& card, const QString& title, const QString& summary)
    {
      const qreal pad = 3;
      const QFontMetricsF titleMetrics(font());
      const QFontMetricsF summaryMetrics(Monospace);
      const auto baseline = card.top() + pad + titleMetrics.ascent();
      const auto bottom = baseline + titleMetrics.descent() + pad;
      const auto summaryWidth = summaryMetrics.horizontalAdvance(summary);
      const auto available = card.width() - pad * 3 - summaryWidth;
      painter.setPen(White(230));
      painter.setFont(font());
      painter.drawText(QPointF(card.left() + pad, baseline),
                       titleMetrics.elidedText(title, Qt::ElideRight, std::max<qreal>(available, 0)));
      if (!summary.isEmpty())
      {
        painter.setFont(Monospace);
        painter.setPen(White(200));
        painter.drawText(QPointF(card.right() - pad - summaryWidth, baseline), summary);
      }
      painter.setPen(QPen(White(70), 1));
      painter.drawLine(QPointF(card.left(), bottom), QPointF(card.right(), bottom));
      return bottom;
    }

    // level (dB) as filled columns, pitch (log scale) as line over it
    void DrawHistory(QPainter& painter, const QRectF& area, const ScopeData::VoiceGauges& gauges, bool withPitch)
    {
      if (area.height() <= 0 || area.width() <= 0)
      {
        return;
      }
      const auto columnWidth = area.width() / COLUMNS;
      painter.setRenderHint(QPainter::Antialiasing, false);
      for (uint_t col = 0; col < COLUMNS; ++col)
      {
        const auto height = area.height() * gauges.Max(ScopeData::VoiceGauges::LEVEL, col) / 255;
        painter.fillRect(QRectF(area.left() + columnWidth * col, area.bottom() - height, columnWidth + 0.5, height),
                         White(110));
      }
      if (!withPitch)
      {
        return;
      }
      for (uint_t col = 0; col < COLUMNS; ++col)
      {
        const auto value = gauges.Max(ScopeData::VoiceGauges::FREQUENCY, col);
        if (value == 0)
        {
          continue;
        }
        const auto yMax = area.bottom() - area.height() * value / 255;
        const auto yMin = area.bottom() - area.height() * gauges.Min(ScopeData::VoiceGauges::FREQUENCY, col) / 255;
        painter.fillRect(QRectF(QPointF(area.left() + columnWidth * col, yMax - 1),
                                QPointF(area.left() + columnWidth * (col + 1) + 0.5, std::max(yMin, yMax) + 1)),
                         Qt::white);
      }
    }

    // Chips are placed side by side as in oscilloscope: 3 gauges columns per chip
    void DrawChips(QPainter& painter, qreal areaHeight)
    {
      if (areaHeight < 40)
      {
        return;
      }
      const auto groupW = qreal(width()) / Chips;
      const auto cellW = groupW / 3;
      const auto cellH = areaHeight / 4;
      for (uint_t chip = 0; chip < Chips; ++chip)
      {
        const ScopeData::ChipGauges gauges(Data.data() + chip * Sound::Scope::GAUGES_SIZE);
        const auto left = groupW * chip;
        const auto prefix = Chips > 1 ? QString::fromLatin1("S%1").arg(chip + 1) : QString();
        for (uint_t voice = 0; voice < 3; ++voice)
        {
          const auto top = cellH * voice;
          DrawGauge(painter, gauges, ScopeData::ChipGauges::Wave(voice), QRectF(left, top, cellW, cellH),
                    QString::fromLatin1("%1V%2 %3").arg(prefix).arg(voice + 1).arg(WaveTitle(gauges, voice)));
          DrawGauge(painter, gauges, ScopeData::ChipGauges::Envelope(voice), QRectF(left + cellW, top, cellW, cellH),
                    QLatin1String("Env ") + Hex(gauges.AttackDecay(voice), 2) + Hex(gauges.SustainRelease(voice), 2));
          DrawGauge(painter, gauges, ScopeData::ChipGauges::Frequency(voice),
                    QRectF(left + cellW * 2, top, cellW, cellH),
                    QLatin1String("Freq ") + Hex(gauges.VoiceFrequency(voice), 4));
        }
        const auto top = cellH * 3;
        DrawGauge(painter, gauges, ScopeData::ChipGauges::VOLUME, QRectF(left, top, cellW, cellH),
                  QString::fromLatin1("Vol %1").arg(gauges.Volume()));
        DrawGauge(painter, gauges, ScopeData::ChipGauges::RESONANCE, QRectF(left + cellW, top, cellW, cellH),
                  QString::fromLatin1("Res %1").arg(gauges.Resonance()));
        DrawGauge(painter, gauges, ScopeData::ChipGauges::CUTOFF, QRectF(left + cellW * 2, top, cellW, cellH),
                  FilterTitle(gauges));
        if (chip != 0)
        {
          painter.setPen(QPen(White(160), 2));
          painter.drawLine(QPointF(left, 0), QPointF(left, areaHeight));
        }
      }
    }

    // Each column is vertical line between min and max values, as in JSIDPlay2
    void DrawGauge(QPainter& painter, const ScopeData::Gauges& gauges, uint_t gauge, const QRectF& cell,
                   const QString& title)
    {
      const qreal pad = 1;
      const auto card = cell.adjusted(pad, pad, -pad, -pad);
      painter.setPen(QPen(White(70), 1));
      painter.setBrush(Qt::NoBrush);
      painter.drawRect(card);
      const auto plotTop = DrawTitleBand(painter, card, title, QString()) + pad * 2;
      const auto plotHeight = card.bottom() - pad * 2 - plotTop;
      if (plotHeight <= 0)
      {
        return;
      }
      const auto plotLeft = card.left() + pad;
      const auto columnWidth = (card.width() - pad * 2) / COLUMNS;
      // minimal visible segment for flat parts
      const qreal minHeight = 1;
      painter.setRenderHint(QPainter::Antialiasing, false);
      for (uint_t col = 0; col < COLUMNS; ++col)
      {
        const auto yMax = plotTop + plotHeight * (1 - gauges.Max(gauge, col) / 255.0);
        const auto yMin = plotTop + plotHeight * (1 - gauges.Min(gauge, col) / 255.0);
        const auto center = (yMin + yMax) / 2;
        const auto half = std::max((yMin - yMax) / 2, minHeight / 2);
        painter.fillRect(QRectF(QPointF(plotLeft + columnWidth * col, center - half),
                                QPointF(plotLeft + columnWidth * (col + 1) + 0.5, center + half)),
                         Qt::white);
      }
    }

  private:
    const QFont Monospace;
    const ChipWidgets Widgets;
    QTimer Timer;
    QElapsedTimer LastStatus;
    Sound::Scope::Ptr Scope;
    ScopeData::VoicesLayout Layout;
    std::vector<uint8_t> Data;
    uint_t Chips = 0;
    uint_t Voices = 0;
    QString Status;
    std::array<ChipWidgets::Smooth, MAX_VOICES> Smooth = {};
  };
}  // namespace DashboardDetails

DashboardView::DashboardView(QWidget& parent)
  : QWidget(&parent)
{}

DashboardView* DashboardView::Create(QWidget& parent, PlaybackSupport& supp)
{
  return new DashboardDetails::DashboardViewImpl(parent, supp);
}
