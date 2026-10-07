/**
 *
 * @file
 *
 * @brief Oscilloscope widget implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/zxtune-qt/ui/controls/oscilloscope_view.h"

#include "apps/zxtune-qt/supp/playback_supp.h"
#include "apps/zxtune-qt/ui/controls/scope_data.h"
#include "apps/zxtune-qt/ui/parameters.h"
#include "apps/zxtune-qt/ui/utils.h"

#include "sound/scope.h"

#include "contract.h"

#include <QtCore/QEvent>
#include <QtCore/QTimer>
#include <QtGui/QContextMenuEvent>
#include <QtGui/QFontMetricsF>
#include <QtGui/QPainter>
#include <QtWidgets/QActionGroup>
#include <QtWidgets/QMenu>

#include <algorithm>
#include <array>
#include <cmath>
#include <utility>
#include <vector>

namespace OscilloscopeDetails
{
  // high refresh rate makes triggered waveforms smooth
  const int UPDATE_INTERVAL_MS = 16;
  const uint_t POINTS = 512;
  const uint_t MIN_POINTS = 64;
  const uint_t MAX_CHANNELS = 32;
  const uint_t MAX_ROWS = 8;
  // separate labels band is used if trace area is at least this times higher
  const qreal MIN_TRACE_TO_BAND = 3;
  const qreal AMPLIFICATION = 0.95;
  const qreal LINE_WIDTH = 1.5;
  const qreal CELL_MARGIN = 0.01;
  const int LABEL_ALPHA = 200;
  const int GRID_ALPHA = 38;
  const float AUTO_GAIN_TARGET = 0.9f;
  const float AUTO_GAIN_MAX = 8.0f;
  // per frame, ~0.5s to rise at 60Hz
  const float AUTO_GAIN_RELEASE = 0.08f;

  const uint_t WINDOWS[] = {10, 20, 40, 80, 160};
  const uint_t GAINS[] = {0, 100, 200, 400};
  const uint_t LATENCIES[] = {0, 40, 80, 120, 160, 240, 320};

  // Grid of cells: column per chip, row per voice
  struct Grid
  {
    uint_t Cols = 1;
    uint_t Rows = 1;
    std::vector<uint_t> CellColumns;
    std::vector<uint_t> CellRows;
    QStringList Labels;

    static Grid Create(const ScopeData::VoicesLayout& layout, uint_t channels)
    {
      Grid result;
      if (layout.Groups.empty())
      {
        result.Rows = std::max<uint_t>(channels, 1);
        for (uint_t chan = 0; chan < channels; ++chan)
        {
          result.CellColumns.push_back(0);
          result.CellRows.push_back(chan);
        }
        return result;
      }
      uint_t cols = 0;
      uint_t maxRows = 1;
      for (const auto& group : layout.Groups)
      {
        const auto voices = static_cast<uint_t>(group.Voices.size());
        // split long chips (e.g. OPL3 with 23 voices) to equal columns
        const auto groupCols = std::max<uint_t>((voices + MAX_ROWS - 1) / MAX_ROWS, 1);
        const auto groupRows = std::max<uint_t>((voices + groupCols - 1) / groupCols, 1);
        for (uint_t idx = 0; idx < voices && result.CellColumns.size() < channels; ++idx)
        {
          result.CellColumns.push_back(cols + idx / groupRows);
          result.CellRows.push_back(idx % groupRows);
          result.Labels.append(ToQString(group.Name + " - " + group.Voices[idx]));
        }
        cols += groupCols;
        maxRows = std::max(maxRows, groupRows);
      }
      result.Cols = std::max<uint_t>(cols, 1);
      result.Rows = maxRows;
      return result;
    }

    uint_t Channels() const
    {
      return static_cast<uint_t>(CellColumns.size());
    }
  };

  class OscilloscopeViewImpl : public OscilloscopeView
  {
  public:
    OscilloscopeViewImpl(QWidget& parent, PlaybackSupport& supp, Parameters::Container::Ptr options)
      : OscilloscopeView(parent)
      , Options(std::move(options))
      , Samples(MAX_CHANNELS * POINTS)
    {
      setObjectName(QLatin1String("OscilloscopeView"));
      setMinimumSize(160, 80);
      setAttribute(Qt::WA_OpaquePaintEvent);
      SetTitle();
      LoadOptions();

      Timer.setTimerType(Qt::PreciseTimer);
      Timer.setInterval(UPDATE_INTERVAL_MS);

      Require(connect(&supp, &PlaybackSupport::OnStartModule, this, &OscilloscopeViewImpl::InitState));
      Require(connect(&supp, &PlaybackSupport::OnStopModule, this, &OscilloscopeViewImpl::CloseState));
      Require(connect(&Timer, &QTimer::timeout, this, &OscilloscopeViewImpl::UpdateState));
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
      OscilloscopeView::changeEvent(event);
    }

    void paintEvent(QPaintEvent*) override
    {
      QPainter painter(this);
      painter.fillRect(rect(), Qt::black);
      if (!Channels || CurrentGrid.Channels() != Channels)
      {
        return;
      }
      painter.setRenderHint(QPainter::Antialiasing);
      const auto band = LabelBand();
      DrawGrid(painter, band);
      DrawTraces(painter, band);
      DrawLabels(painter, band);
    }

    void contextMenuEvent(QContextMenuEvent* event) override
    {
      QMenu menu(this);
      AddChoices(*menu.addMenu(OscilloscopeView::tr("Window")), WINDOWS, WindowMs,
                 [](uint_t val) { return OscilloscopeView::tr("%1 ms").arg(val); });
      AddChoices(*menu.addMenu(OscilloscopeView::tr("Gain")), GAINS, GainPercent,
                 [](uint_t val) { return val ? OscilloscopeView::tr("%1%").arg(val) : OscilloscopeView::tr("Auto"); });
      AddChoices(*menu.addMenu(OscilloscopeView::tr("Latency compensation")), LATENCIES, LatencyMs,
                 [](uint_t val) { return OscilloscopeView::tr("%1 ms").arg(val); });
      if (menu.exec(event->globalPos()))
      {
        SaveOptions();
      }
    }

  private:
    template<std::size_t N, class Formatter>
    void AddChoices(QMenu& menu, const uint_t (&values)[N], uint_t& target, Formatter fmt)
    {
      auto* const group = new QActionGroup(&menu);
      for (const auto val : values)
      {
        auto* const action = menu.addAction(fmt(val));
        action->setCheckable(true);
        action->setChecked(val == target);
        group->addAction(action);
        Require(connect(action, &QAction::triggered, this, [&target, val]() { target = val; }));
      }
    }

    void LoadOptions()
    {
      using namespace Parameters::ZXTuneQT::UI::Scope;
      WindowMs = static_cast<uint_t>(Parameters::GetInteger(*Options, WINDOW, WINDOW_DEFAULT));
      GainPercent = static_cast<uint_t>(Parameters::GetInteger(*Options, GAIN, GAIN_DEFAULT));
      LatencyMs = static_cast<uint_t>(Parameters::GetInteger(*Options, LATENCY, LATENCY_DEFAULT));
    }

    void SaveOptions()
    {
      using namespace Parameters::ZXTuneQT::UI::Scope;
      Options->SetValue(WINDOW, WindowMs);
      Options->SetValue(GAIN, GainPercent);
      Options->SetValue(LATENCY, LatencyMs);
      if (Scope)
      {
        Scope->SetLatency(LatencyMs);
      }
    }

    void InitState(Sound::Backend::Ptr player, Playlist::Item::Data::Ptr)
    {
      Scope = player->GetScope();
      Scope->SetLatency(LatencyMs);
      LayoutId = ~uint_t(0);
      Channels = 0;
      Peaks.fill(0);
      Timer.start();
    }

    void CloseState()
    {
      Timer.stop();
      Scope.reset();
      Channels = 0;
      update();
    }

    void UpdateState()
    {
      // separate voices are rendered by players only while data is requested
      if (!Scope || !isVisible())
      {
        return;
      }
      // narrow cells don't need many points
      Points = std::clamp<uint_t>(width() / std::max<uint_t>(CurrentGrid.Cols, 1), MIN_POINTS, POINTS);
      const auto layout = Scope->Get(MAX_CHANNELS, Points, -1, WindowMs, Samples.data());
      if (layout.Id != LayoutId)
      {
        // layout description is requested only on change
        Layout = ScopeData::VoicesLayout::Parse(Scope->GetLayout());
        LayoutId = layout.Id;
        CurrentGrid = {};
      }
      Channels = layout.Channels;
      if (CurrentGrid.Channels() != Channels)
      {
        CurrentGrid = Grid::Create(Layout, Channels);
      }
      update();
    }

    void SetTitle()
    {
      setWindowTitle(OscilloscopeView::tr("Oscilloscope"));
    }

    // Height of labels band in pixels, labels are drawn over traces only if cells are too small
    qreal LabelBand() const
    {
      if (CurrentGrid.Labels.isEmpty())
      {
        return 0;
      }
      const QFontMetricsF metrics(font());
      const auto band = metrics.height() * 1.5;
      return qreal(height()) / CurrentGrid.Rows >= band * MIN_TRACE_TO_BAND ? band : 0;
    }

    QRectF Cell(uint_t chan) const
    {
      const auto cellW = qreal(width()) / CurrentGrid.Cols;
      const auto cellH = qreal(height()) / CurrentGrid.Rows;
      return {cellW * CurrentGrid.CellColumns[chan], cellH * CurrentGrid.CellRows[chan], cellW, cellH};
    }

    // Center line of trace area and bottom border of labels band
    void DrawGrid(QPainter& painter, qreal band) const
    {
      painter.setPen(QPen(QColor(255, 255, 255, GRID_ALPHA), 1));
      for (uint_t chan = 0; chan < Channels; ++chan)
      {
        const auto cell = Cell(chan);
        const auto centerY = (cell.top() + band + cell.bottom()) / 2;
        painter.drawLine(QPointF(cell.left(), centerY), QPointF(cell.right(), centerY));
        if (band != 0)
        {
          painter.drawLine(QPointF(cell.left(), cell.top() + band), QPointF(cell.right(), cell.top() + band));
        }
      }
    }

    void DrawTraces(QPainter& painter, qreal band)
    {
      painter.setPen(QPen(Qt::white, LINE_WIDTH, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin));
      QPolygonF line(Points);
      for (uint_t chan = 0; chan < Channels; ++chan)
      {
        const auto cell = Cell(chan);
        const auto left = cell.left() + cell.width() * CELL_MARGIN;
        const auto traceW = cell.width() * (1 - 2 * CELL_MARGIN);
        // trace area is below the label band
        const auto top = cell.top() + band;
        const auto traceH = cell.height() - band;
        const auto centerY = top + traceH / 2;
        const auto ampl = traceH * 0.5 * AMPLIFICATION;
        const auto* const samples = Samples.data() + chan * Points;
        const auto gain = ChannelGain(chan, samples);
        for (uint_t idx = 0; idx < Points; ++idx)
        {
          // amplified peaks are limited by voice's cell
          const auto value = std::clamp(samples[idx] * gain / 32768.0f, -1.0f, 1.0f);
          line[idx] = QPointF(left + traceW * idx / (Points - 1), centerY - ampl * value);
        }
        painter.drawPolyline(line);
      }
    }

    // Automatic gain follows peak level: immediate attack to avoid clipping,
    // slow release to avoid visible pumping. Limited to not amplify noise of silent voices
    float ChannelGain(uint_t chan, const int16_t* samples)
    {
      if (GainPercent)
      {
        return GainPercent / 100.0f;
      }
      int peak = 0;
      for (uint_t idx = 0; idx < Points; ++idx)
      {
        peak = std::max(peak, std::abs(int(samples[idx])));
      }
      const auto level = peak / 32768.0f;
      auto& smoothed = Peaks[chan];
      smoothed = level > smoothed ? level : smoothed + (level - smoothed) * AUTO_GAIN_RELEASE;
      return std::max(AUTO_GAIN_TARGET / std::max(smoothed, AUTO_GAIN_TARGET / AUTO_GAIN_MAX), 1.0f);
    }

    void DrawLabels(QPainter& painter, qreal band) const
    {
      if (CurrentGrid.Labels.isEmpty())
      {
        return;
      }
      const QFontMetricsF metrics(font());
      const auto padding = metrics.height() / 4;
      painter.setPen(QColor(255, 255, 255, LABEL_ALPHA));
      for (uint_t chan = 0; chan < Channels; ++chan)
      {
        const auto cell = Cell(chan);
        // centered in the band if any
        const auto bandHeight = band != 0 ? band : metrics.height() + padding * 2;
        const QRectF area(cell.left() + padding * 2, cell.top(), cell.width() - padding * 4, bandHeight);
        const auto text = metrics.elidedText(CurrentGrid.Labels[chan], Qt::ElideRight, area.width());
        if (band == 0)
        {
          // keeps label readable over trace
          const auto textWidth = metrics.horizontalAdvance(text);
          painter.fillRect(QRectF(cell.left(), cell.top(), textWidth + padding * 4, bandHeight), QColor(0, 0, 0, 210));
        }
        painter.drawText(area, Qt::AlignLeft | Qt::AlignVCenter, text);
      }
    }

  private:
    const Parameters::Container::Ptr Options;
    QTimer Timer;
    Sound::Scope::Ptr Scope;
    std::vector<int16_t> Samples;
    std::array<float, MAX_CHANNELS> Peaks = {};
    uint_t Points = POINTS;
    uint_t Channels = 0;
    uint_t LayoutId = ~uint_t(0);
    ScopeData::VoicesLayout Layout;
    Grid CurrentGrid;
    uint_t WindowMs = 0;
    uint_t GainPercent = 0;
    uint_t LatencyMs = 0;
  };
}  // namespace OscilloscopeDetails

OscilloscopeView::OscilloscopeView(QWidget& parent)
  : QWidget(&parent)
{}

OscilloscopeView* OscilloscopeView::Create(QWidget& parent, PlaybackSupport& supp, Parameters::Container::Ptr options)
{
  return new OscilloscopeDetails::OscilloscopeViewImpl(parent, supp, std::move(options));
}
