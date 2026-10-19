/**
 *
 * @file
 *
 * @brief Oscilloscope view and visualization data feed implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/scope_view.h"

#include "apps/lhuna-desktop/engine/engine.h"
#include "apps/lhuna-desktop/ui/qt_canvas.h"
#include "apps/lhuna-desktop/ui/settings.h"

#include <QtGui/QPainter>
#include <QtGui/QResizeEvent>

#include <cstdio>

namespace Lhuna
{
  // as Android's ScopeDisplay label text size
  const float LABEL_TEXT_SIZE = 12;
  const float LINE_WIDTH = 1.6f;
  const int STATUS_PERIOD_MS = 500;

  VisualFeed::VisualFeed(Engine& engine, Settings& settings, QObject* parent)
    : QObject(parent)
    , Player(engine)
    , Options(settings)
  {
    Timer.setTimerType(Qt::PreciseTimer);
    connect(&Timer, &QTimer::timeout, this, &VisualFeed::Update);
    connect(&Options, &Settings::changed, this, [this](const QString& key) {
      if (key == Settings::MAX_FPS)
      {
        UpdateRate();
      }
    });
    UpdateRate();
    StatusTimer.start();
  }

  void VisualFeed::UpdateRate()
  {
    Timer.start(1000 / std::max(Options.Get(Settings::MAX_FPS), 10));
  }

  void VisualFeed::Update()
  {
    const auto scope = Player.Scope();
    if (!scope)
    {
      if (Current.Live || Current.Channels != 0)
      {
        Current = VisualFrame{{}, 0, 0, ~0u, Visualization::VoicesLayout::Master(), {}, {}, Current.Stats, false};
        LayoutText.clear();
        emit frameReady();
      }
      return;
    }
    const auto maxChannels = Visualization::ScopeGrid::MAX_CHANNELS;
    const auto points = std::clamp(Points, Visualization::ScopeGrid::MIN_POINTS, Visualization::ScopeGrid::MAX_POINTS);
    Current.Samples.resize(maxChannels * points);
    const auto layout = scope->Get(maxChannels, points, -1, Options.Get(Settings::WINDOW_MS), Current.Samples.data());
    Current.Channels = layout.Channels;
    Current.Points = points;
    if (layout.Id != Current.LayoutId)
    {
      Current.LayoutId = layout.Id;
      auto text = scope->GetLayout();
      if (text != LayoutText)
      {
        LayoutText = std::move(text);
        Current.Layout = Visualization::VoicesLayout::Parse(LayoutText);
      }
    }
    auto& gauges = Current.Gauges;
    gauges.Layout = Current.Layout;
    const auto waveWindow = Options.Get(Settings::GAUGE_WINDOW_MS);
    if (Current.Layout->HasRegisters)
    {
      const int maxChips = 3;
      gauges.Kind = Visualization::GaugesKind::CHIPS;
      gauges.Bytes.resize(maxChips * Sound::Scope::GAUGES_SIZE);
      gauges.Count = static_cast<int>(scope->GetGauges(maxChips, -1, waveWindow, gauges.Bytes.data()));
    }
    else if (!Current.Layout->Groups.empty())
    {
      gauges.Kind = Visualization::GaugesKind::VOICES;
      gauges.Bytes.resize(maxChannels * Sound::Scope::VOICE_GAUGES_SIZE);
      gauges.Count = static_cast<int>(scope->GetVoiceGauges(maxChannels, -1, waveWindow, gauges.Bytes.data()));
    }
    else
    {
      gauges.Kind = Visualization::GaugesKind::NONE;
      gauges.Count = 0;
    }
    // status resets scope statistics, so it's requested rarely
    if (!Current.Live || StatusTimer.elapsed() > STATUS_PERIOD_MS)
    {
      StatusTimer.restart();
      Current.Status = scope->GetStatus();
    }
    Current.Live = true;
    emit frameReady();
  }

  ScopeView::ScopeView(VisualFeed& feed, Settings& settings, QWidget* parent)
    : QWidget(parent)
    , Feed(feed)
    , Options(settings)
  {
    setAttribute(Qt::WA_OpaquePaintEvent);
    setMinimumHeight(120);
    connect(&Feed, &VisualFeed::frameReady, this, qOverload<>(&QWidget::update));
    FpsTimer.start();
  }

  void ScopeView::resizeEvent(QResizeEvent* event)
  {
    QWidget::resizeEvent(event);
    Background = QImage();
  }

  void ScopeView::UpdateGrid()
  {
    const auto& frame = Feed.Frame();
    if (!Grid || GridLayout != frame.Layout.get() || GridChannels != frame.Channels)
    {
      Grid = std::make_unique<Visualization::ScopeGrid>(*frame.Layout, frame.Channels);
      GridLayout = frame.Layout.get();
      GridChannels = frame.Channels;
      Background = QImage();
      Feed.SetPoints(Grid->Points(width()));
    }
    if (Background.size() != size() * devicePixelRatio())
    {
      Feed.SetPoints(Grid->Points(width()));
      // static part is drawn once per layout or size change, see ScopeGrid::DrawBackground
      Background = QImage(size() * devicePixelRatio(), QImage::Format_ARGB32_Premultiplied);
      Background.setDevicePixelRatio(devicePixelRatio());
      Background.fill(Qt::black);
      QPainter painter(&Background);
      painter.setRenderHint(QPainter::TextAntialiasing);
      QtCanvas canvas(painter);
      Grid->DrawBackground(canvas, width(), height(), LABEL_TEXT_SIZE);
    }
  }

  void ScopeView::paintEvent(QPaintEvent* /*event*/)
  {
    QElapsedTimer timer;
    timer.start();
    const auto& frame = Feed.Frame();
    UpdateGrid();
    QPainter painter(this);
    painter.drawImage(0, 0, Background);
    if (frame.Live && frame.Channels > 0)
    {
      painter.setRenderHint(QPainter::Antialiasing);
      QtCanvas canvas(painter);
      Traces.Draw(canvas, *Grid, frame.Samples.data(), frame.Channels, frame.Points, width(), height(), LABEL_TEXT_SIZE,
                  Options.Get(Settings::GAIN), LINE_WIDTH);
    }
    else
    {
      painter.setPen(QColor(255, 255, 255, 90));
      painter.drawText(rect(), Qt::AlignCenter, tr("Nothing is playing"));
    }
    // UI side statistics as Android's ScopeStats
    DrawMs += timer.nsecsElapsed() / 1e6;
    ++Frames;
    if (FpsTimer.elapsed() >= 1000)
    {
      char buf[128];
      std::snprintf(buf, sizeof(buf), "ui: %.0f fps, draw %.1fms, %d points", Frames * 1000.0 / FpsTimer.elapsed(),
                    DrawMs / Frames, frame.Points);
      Feed.SetStats(buf);
      Frames = 0;
      DrawMs = 0;
      FpsTimer.restart();
    }
  }

  DashboardView::DashboardView(VisualFeed& feed, QWidget* parent)
    : QWidget(parent)
    , Feed(feed)
    , Renderer(1.f)
  {
    setAttribute(Qt::WA_OpaquePaintEvent);
    setMinimumHeight(120);
    connect(&Feed, &VisualFeed::frameReady, this, qOverload<>(&QWidget::update));
  }

  void DashboardView::paintEvent(QPaintEvent* /*event*/)
  {
    const auto& frame = Feed.Frame();
    Renderer.SetStatus(frame.Status, frame.Stats);
    QPainter painter(this);
    painter.fillRect(rect(), Qt::black);
    const auto key = Renderer.StaticKey(frame.Gauges, width(), height());
    const auto layerSize = size() * devicePixelRatio();
    if (StaticLayer.size() != layerSize || key != StaticKey)
    {
      // frames, captions and separators are drawn once, only live values are drawn at each frame
      StaticKey = key;
      StaticLayer = QImage(layerSize, QImage::Format_ARGB32_Premultiplied);
      StaticLayer.setDevicePixelRatio(devicePixelRatio());
      StaticLayer.fill(Qt::transparent);
      QPainter layer(&StaticLayer);
      layer.setRenderHint(QPainter::TextAntialiasing);
      QtCanvas canvas(layer);
      Renderer.Draw(canvas, frame.Gauges, width(), height(), Visualization::Dashboard::Pass::STATIC);
    }
    painter.drawImage(0, 0, StaticLayer);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setRenderHint(QPainter::TextAntialiasing);
    QtCanvas canvas(painter);
    Renderer.Draw(canvas, frame.Gauges, width(), height(), Visualization::Dashboard::Pass::DYNAMIC);
  }
}  // namespace Lhuna
