/**
 *
 * @file
 *
 * @brief Oscilloscope view and visualization data feed
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "visualization/dashboard.h"
#include "visualization/scope_grid.h"

#include <QtCore/QElapsedTimer>
#include <QtCore/QTimer>
#include <QtGui/QImage>
#include <QtWidgets/QWidget>

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace Lhuna
{
  class Engine;
  class Settings;

  //! Waveforms and gauges of the same moment
  struct VisualFrame
  {
    std::vector<int16_t> Samples;
    int Channels = 0;
    int Points = 0;
    uint32_t LayoutId = ~0u;
    Visualization::VoicesLayout::Ptr Layout = Visualization::VoicesLayout::Master();
    Visualization::GaugesFrame Gauges;
    std::string Status;
    std::string Stats;
    bool Live = false;
  };

  /**
   * Rendering statistics as Android's ScopeStats: real frame rate, frame interval, drawing and data request times
   */
  class FrameStats
  {
  public:
    void Data(int64_t nanos);
    void Frame(int64_t startNanos, int64_t endNanos);

    //! @return text if period is complete (stats are reset then), empty otherwise
    std::string Take(const std::string& backend, int points);

  private:
    int Frames = 0;
    int Draws = 0;
    int64_t FramesStart = 0;
    int64_t LastFrameStart = 0;
    int64_t MaxInterval = 0;
    int64_t DrawNanos = 0;
    int64_t MaxDraw = 0;
    int64_t DataNanos = 0;
    int64_t MaxData = 0;
    int DataCalls = 0;
  };

  /**
   * Requests visualization data from playing tune's scope at display rate (see ScopeFeed of Android app), so the
   * oscilloscope and the dashboard display the same moment. Nothing is requested and redrawn while playback is
   * paused or stopped, except the single frame displaying the change.
   */
  class VisualFeed : public QObject
  {
    Q_OBJECT
  public:
    //! How frames are scheduled
    enum class Pacing
    {
      //! by timer at max frame rate, for views without presentation feedback (raster)
      TIMER,
      //! next frame is requested when view's previous one is presented, so rate follows display (vsync)
      PRESENTATION,
    };

    VisualFeed(Engine& engine, Settings& settings, QObject* parent);

    const VisualFrame& Frame() const
    {
      return Current;
    }

    //! points per channel required by oscilloscope view
    void SetPoints(int points)
    {
      Points = points;
    }

    void SetPacing(Pacing pacing);

    //! @param backend short name for statistics line (e.g. "vulkan")
    //! @param description renderer details line (e.g. API and GPU name)
    void SetRenderer(std::string backend, std::string description);

    //! Oscilloscope view's drawing time of current frame, nanoseconds of Clock()
    void FrameDrawn(int64_t startNanos, int64_t endNanos);

    //! Oscilloscope view's frame is passed to presentation, see Pacing::PRESENTATION
    //! @param displayHz refresh rate of view's screen, 0 if unknown
    void FramePresented(double displayHz);

    //! Monotonic time base of statistics
    int64_t Clock() const
    {
      return Monotonic.nsecsElapsed();
    }

    //! Requests single frame now
    void Update();

  signals:
    void frameReady();

  private:
    void Watch();
    bool IsPlaying() const;
    void ScheduleNext(double displayHz);
    int FramePeriodMs() const;

  private:
    Engine& Player;
    Settings& Options;
    Pacing Mode = Pacing::TIMER;
    //! frames of TIMER pacing and delays of frames limited by max rate for PRESENTATION
    QTimer Timer;
    //! detects playback start and stalled presentation (e.g. hidden window), at low rate
    QTimer Watchdog;
    QElapsedTimer StatusTimer;
    QElapsedTimer Monotonic;
    VisualFrame Current;
    std::string LayoutText;
    int Points = 512;
    //! frame is requested from view, but not presented yet
    bool Pending = false;
    int64_t LastRequest = 0;
    bool WasPlaying = false;
    FrameStats Stats;
    std::string Backend = "software";
    std::string Renderer;
  };

  class ScopeView : public QWidget
  {
    Q_OBJECT
  public:
    ScopeView(VisualFeed& feed, Settings& settings, QWidget* parent);

  protected:
    void paintEvent(QPaintEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;

  private:
    void UpdateGrid();

  private:
    VisualFeed& Feed;
    Settings& Options;
    std::unique_ptr<Visualization::ScopeGrid> Grid;
    const Visualization::VoicesLayout* GridLayout = nullptr;
    int GridChannels = -1;
    QImage Background;
    Visualization::ScopeTraces Traces;
  };

  //! @return the best oscilloscope view for "Graphics backend" setting: GPU one if available, raster otherwise
  QWidget* CreateScopeView(VisualFeed& feed, Settings& settings, QWidget* parent);

  class DashboardView : public QWidget
  {
    Q_OBJECT
  public:
    DashboardView(VisualFeed& feed, QWidget* parent);

  protected:
    void paintEvent(QPaintEvent* event) override;

  private:
    VisualFeed& Feed;
    Visualization::Dashboard Renderer;
    QImage StaticLayer;
    uint64_t StaticKey = 0;
  };
}  // namespace Lhuna
