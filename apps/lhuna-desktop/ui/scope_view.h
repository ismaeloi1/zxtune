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

#include <memory>
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
   * Requests visualization data from playing tune's scope at display rate (see ScopeFeed of Android app), so the
   * oscilloscope and the dashboard display the same moment.
   */
  class VisualFeed : public QObject
  {
    Q_OBJECT
  public:
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

    //! UI side statistics to display at dashboard
    void SetStats(std::string stats)
    {
      Current.Stats = std::move(stats);
    }

    //! Requests single frame now
    void Update();

  signals:
    void frameReady();

  private:
    void UpdateRate();

  private:
    Engine& Player;
    Settings& Options;
    QTimer Timer;
    QElapsedTimer StatusTimer;
    VisualFrame Current;
    std::string LayoutText;
    int Points = 512;
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
    QElapsedTimer FpsTimer;
    int Frames = 0;
    double DrawMs = 0;
  };

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
