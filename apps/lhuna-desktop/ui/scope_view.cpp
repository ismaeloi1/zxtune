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
#ifdef LHUNA_GPU_SCOPE
#  include "apps/lhuna-desktop/ui/gpu_scope_view.h"
#endif
#include "apps/lhuna-desktop/ui/qt_canvas.h"
#include "apps/lhuna-desktop/ui/settings.h"

#include <QtGui/QPainter>
#include <QtGui/QResizeEvent>

#include <cstdio>

namespace Lhuna
{
  // as Android's ScopeDisplay label text size
  const float LABEL_TEXT_SIZE = 12;
  // cosmetic (1px) antialiased lines are drawn by fast path of raster engine, thicker ones are stroked
  const float LINE_WIDTH = 1.f;
  const int STATUS_PERIOD_MS = 500;
  // playback start is noticed with this delay at most, and it's the only wakeup while paused or stopped
  const int WATCH_PERIOD_MS = 250;
  // presentation of hidden or minimized window may never happen, request is repeated then to resume when shown
  const int64_t STALLED_FRAME_NS = 1000000000;
  const int64_t STATS_PERIOD_NS = 1000000000;

  void FrameStats::Data(int64_t nanos)
  {
    DataNanos += nanos;
    MaxData = std::max(MaxData, nanos);
    ++DataCalls;
  }

  void FrameStats::Frame(int64_t startNanos, int64_t endNanos)
  {
    if (Frames == 0)
    {
      FramesStart = startNanos;
    }
    else
    {
      MaxInterval = std::max(MaxInterval, startNanos - LastFrameStart);
    }
    LastFrameStart = startNanos;
    ++Frames;
    ++Draws;
    DrawNanos += endNanos - startNanos;
    MaxDraw = std::max(MaxDraw, endNanos - startNanos);
  }

  std::string FrameStats::Take(const std::string& backend, int points)
  {
    // intervals between starts of frames, so the last frame starts the next period
    const auto period = LastFrameStart - FramesStart;
    if (Frames < 2 || period < STATS_PERIOD_NS)
    {
      return {};
    }
    const auto fps = (Frames - 1) * 1e9 / period;
    char buf[256];
    std::snprintf(buf, sizeof(buf),
                  "%s: %.1f fps, frame %.2f ms (max %.1f), draw %.2f ms (max %.1f), data %.2f ms (max %.1f), "
                  "%d points",
                  backend.c_str(), fps, 1000 / fps, MaxInterval / 1e6, DrawNanos / 1e6 / Draws, MaxDraw / 1e6,
                  DataCalls ? DataNanos / 1e6 / DataCalls : 0., MaxData / 1e6, points);
    const auto last = LastFrameStart;
    *this = FrameStats();
    Frames = 1;
    FramesStart = LastFrameStart = last;
    return buf;
  }

  VisualFeed::VisualFeed(Engine& engine, Settings& settings, QObject* parent)
    : QObject(parent)
    , Player(engine)
    , Options(settings)
  {
    Monotonic.start();
    Timer.setTimerType(Qt::PreciseTimer);
    connect(&Timer, &QTimer::timeout, this, &VisualFeed::Update);
    connect(&Watchdog, &QTimer::timeout, this, &VisualFeed::Watch);
    connect(&Options, &Settings::changed, this, [this](const QString& key) {
      if (key == Settings::MAX_FPS && Mode == Pacing::TIMER && Timer.isActive())
      {
        Timer.start(FramePeriodMs());
      }
      else if (!IsPlaying())
      {
        // e.g. gain or trigger change of paused tune is displayed, there are no regular frames to show it
        Update();
      }
    });
    Watchdog.start(WATCH_PERIOD_MS);
    StatusTimer.start();
  }

  int VisualFeed::FramePeriodMs() const
  {
    return 1000 / std::max(Options.Get(Settings::MAX_FPS), 10);
  }

  bool VisualFeed::IsPlaying() const
  {
    return Player.State() == PlaybackState::PLAYING;
  }

  void VisualFeed::SetPacing(Pacing pacing)
  {
    if (Mode != pacing)
    {
      Mode = pacing;
      Pending = false;
      Timer.stop();
      Watch();
    }
  }

  void VisualFeed::SetRenderer(std::string backend, std::string description)
  {
    Backend = std::move(backend);
    Renderer = std::move(description);
    Current.Stats = Renderer;
    Stats = FrameStats();
  }

  void VisualFeed::FrameDrawn(int64_t startNanos, int64_t endNanos)
  {
    Stats.Frame(startNanos, endNanos);
    auto text = Stats.Take(Backend, Current.Points);
    if (!text.empty())
    {
      Current.Stats = Renderer.empty() ? text : Renderer + '\n' + text;
    }
  }

  void VisualFeed::Watch()
  {
    const auto playing = IsPlaying();
    if (!playing)
    {
      Timer.stop();
      if (WasPlaying || Current.Live != static_cast<bool>(Player.Scope()))
      {
        // single frame of paused or stopped state, idle afterwards
        Update();
      }
    }
    else if (Mode == Pacing::TIMER)
    {
      if (!Timer.isActive())
      {
        Timer.setSingleShot(false);
        Timer.start(FramePeriodMs());
      }
    }
    else if (!Pending || Clock() - LastRequest > STALLED_FRAME_NS)
    {
      // the first frame of playback (next ones are requested on presentation) or retry of stalled one
      Update();
    }
    WasPlaying = playing;
  }

  void VisualFeed::FramePresented(double displayHz)
  {
    Pending = false;
    if (Mode == Pacing::PRESENTATION && IsPlaying() && !Timer.isActive())
    {
      ScheduleNext(displayHz);
    }
  }

  void VisualFeed::ScheduleNext(double displayHz)
  {
    // presentation callback is called while painting, so the next frame is started from event loop: repaint
    // requests are not lost and the rest of events (input, timers) are not starved
    Timer.setSingleShot(true);
    const auto maxFps = std::max(Options.Get(Settings::MAX_FPS), 10);
    const auto periodNs = 1e9 / maxFps;
    // Presentation is normally throttled by vsync, then the margin only keeps timer's jitter from skipping one. It's
    // not throttled at all by some drivers (or settings), so frames are never requested more often than max rate.
    // Rate below display's one waits for half of refresh interval less, so frame catches the vsync just after period
    // instead of the next one.
    const auto marginNs = displayHz > 0 && maxFps + 1 < displayHz ? 0.5e9 / displayHz : 0.1 * periodNs;
    const auto remainsMs = (periodNs - marginNs - static_cast<double>(Clock() - LastRequest)) / 1e6;
    Timer.start(std::max(0, static_cast<int>(remainsMs)));
  }

  void VisualFeed::Update()
  {
    if (Mode == Pacing::PRESENTATION)
    {
      Pending = true;
      LastRequest = Clock();
    }
    const auto scope = Player.Scope();
    if (!scope)
    {
      if (Current.Live || Current.Channels != 0)
      {
        Current = VisualFrame{{}, 0, 0, ~0u, Visualization::VoicesLayout::Master(), {}, {}, Current.Stats, false};
        LayoutText.clear();
      }
      emit frameReady();
      return;
    }
    const auto dataStart = Clock();
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
    Stats.Data(Clock() - dataStart);
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
    Feed.SetPacing(VisualFeed::Pacing::TIMER);
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
    const auto start = Feed.Clock();
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
    Feed.FrameDrawn(start, Feed.Clock());
  }

  QWidget* CreateScopeView(VisualFeed& feed, Settings& settings, QWidget* parent)
  {
#ifdef LHUNA_GPU_SCOPE
    if (auto* gpu = GpuScopeView::Create(feed, settings, parent))
    {
      return gpu;
    }
#else
    feed.SetRenderer("software", "gpu: not supported by this build");
#endif
    return new ScopeView(feed, settings, parent);
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
