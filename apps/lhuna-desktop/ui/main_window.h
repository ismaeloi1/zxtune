/**
 *
 * @file
 *
 * @brief Main window of desktop player
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "apps/lhuna-desktop/engine/engine.h"

#include <QtCore/QTimer>
#include <QtWidgets/QMainWindow>

#include <random>
#include <vector>

class QLabel;
class QListWidget;
class QSlider;
class QTabWidget;
class QToolButton;
class QTreeView;
class QFileSystemModel;

namespace Lhuna
{
  class Settings;
  class SettingsPanel;
  class VisualFeed;

  class MainWindow : public QMainWindow
  {
    Q_OBJECT
  public:
    explicit MainWindow(Engine& engine);

    //! Adds files or folders (recursively) to playlist
    void AddPaths(const QStringList& paths);
    void PlayIndex(int index);
    void ShowSettingsPanel();
    void ShowFilesTab();

  protected:
    void resizeEvent(QResizeEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;

  private:
    void BuildUi();
    QWidget* BuildBrowser();
    QWidget* BuildNowPlaying();
    QWidget* BuildTransport();
    void PlayPause();
    void Next(bool byUser);
    void Prev();
    void Tick();
    void UpdateNowPlaying();
    void UpdateTransport();
    void PlaceOverlay();

  private:
    Engine& Player;
    Settings* Options = nullptr;
    VisualFeed* Feed = nullptr;
    std::vector<TrackInfo> Playlist;
    int CurrentIndex = -1;
    std::mt19937 Random;
    QTimer Ticker;

    QTabWidget* Browser = nullptr;
    QListWidget* PlaylistView = nullptr;
    QTreeView* FilesView = nullptr;
    QFileSystemModel* FilesModel = nullptr;
    QLabel* TitleLabel = nullptr;
    QLabel* AuthorLabel = nullptr;
    QLabel* InfoLabel = nullptr;
    QWidget* Visualizer = nullptr;
    SettingsPanel* Panel = nullptr;
    QToolButton* PanelButton = nullptr;
    QSlider* Seek = nullptr;
    QLabel* PositionLabel = nullptr;
    QLabel* DurationLabel = nullptr;
    QToolButton* PlayButton = nullptr;
    QToolButton* ShuffleButton = nullptr;
    QToolButton* RepeatButton = nullptr;
    QLabel* StatusLine = nullptr;
    bool Seeking = false;
  };
}  // namespace Lhuna
