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
#include "apps/lhuna-desktop/ui/filter_curve_view.h"

#include <QtCore/QTimer>
#include <QtWidgets/QMainWindow>

#include <memory>
#include <random>
#include <vector>

class QLabel;
class QListWidget;
class QSlider;
class QSplitter;
class QStackedWidget;
class QTabWidget;
class QToolButton;
class QTreeView;
class QTreeWidget;
class QFileSystemModel;

namespace Lhuna
{
  class MediaKeys;
  class Settings;
  class SettingsPanel;
  class VisualFeed;

  class MainWindow : public QMainWindow
  {
    Q_OBJECT
  public:
    //! @param session restore and save playlist and window state
    MainWindow(Engine& engine, bool session);
    ~MainWindow() override;

    //! Adds files or folders (recursively) to playlist, playlist files are imported
    void AddPaths(const QStringList& paths);
    void PlayIndex(int index);
    void ShowSettingsPanel();
    void ShowFilesTab();
    //! Shows contents (subtunes, archived files) of file in files browser
    void BrowseContents(const QString& path);
    void SetSpectrumVisible(bool visible);
    //! @return dialog shown non-modal
    QWidget* ShowAbout();
    QWidget* ShowAllSettings(const QString& page = {});
    bool ImportPlaylist(const QString& file);
    bool ExportPlaylist(const QString& file);

  protected:
    void resizeEvent(QResizeEvent* event) override;
    bool eventFilter(QObject* watched, QEvent* event) override;
    void closeEvent(QCloseEvent* event) override;
    void dragEnterEvent(QDragEnterEvent* event) override;
    void dropEvent(QDropEvent* event) override;

  private:
    void BuildUi();
    void BuildMenu();
    QWidget* BuildBrowser();
    QWidget* BuildArchiveBrowser();
    QWidget* BuildNowPlaying();
    QWidget* BuildTransport();
    void PlayPause();
    void Stop();
    void Next(bool byUser);
    void Prev();
    void SeekBy(int deltaMs);
    void SeekTo(uint32_t ms);
    void Tick();
    void UpdateNowPlaying();
    void UpdateEngineLines();
    void UpdateTransport();
    void UpdateMediaKeys();
    void PlaceOverlay();
    void AppendTracks(std::vector<TrackInfo> tracks);
    void ClearPlaylist();
    void RemoveSelected();
    void SyncPlaylistOrder();
    void SchedulePlaylistSave();
    void SaveSession();
    void RestoreSession();
    void RestartOutput();
    void AddArchiveSelection(bool play);
    void ShowShortcuts();
    FilterCurveView::LiveSource SidLiveSource() const;
    bool HandleDrop(QDropEvent* event);
    QString SessionPlaylistFile() const;

  private:
    Engine& Player;
    const bool Session;
    Settings* Options = nullptr;
    VisualFeed* Feed = nullptr;
    std::unique_ptr<MediaKeys> Media;
    std::vector<TrackInfo> Playlist;
    //! unique ids of playlist items (stored in list items), to keep order in sync after drag&drop reordering
    std::vector<quint64> PlaylistIds;
    quint64 NextId = 1;
    int CurrentIndex = -1;
    int TrackCounter = 0;
    std::mt19937 Random;
    QTimer Ticker;
    QTimer SaveTimer;

    QSplitter* Splitter = nullptr;
    QTabWidget* Browser = nullptr;
    QListWidget* PlaylistView = nullptr;
    QStackedWidget* FilesStack = nullptr;
    QTreeView* FilesView = nullptr;
    QFileSystemModel* FilesModel = nullptr;
    QLabel* ArchiveTitle = nullptr;
    QTreeWidget* ArchiveView = nullptr;
    std::vector<TrackInfo> ArchiveTracks;
    QLabel* TitleLabel = nullptr;
    QLabel* AuthorLabel = nullptr;
    QLabel* InfoLabel = nullptr;
    QLabel* EngineLabel = nullptr;
    QString EngineKey;
    QWidget* Visualizer = nullptr;
    QWidget* Dashboard = nullptr;
    QWidget* Spectrum = nullptr;
    SettingsPanel* Panel = nullptr;
    QToolButton* PanelButton = nullptr;
    QSlider* Seek = nullptr;
    QLabel* PositionLabel = nullptr;
    QLabel* DurationLabel = nullptr;
    QToolButton* PlayButton = nullptr;
    QToolButton* ShuffleButton = nullptr;
    QToolButton* RepeatButton = nullptr;
    QLabel* StatusLine = nullptr;
    QAction* SpectrumAction = nullptr;
    QAction* DashboardAction = nullptr;
    bool Seeking = false;
    PlaybackState LastState = PlaybackState::STOPPED;
  };
}  // namespace Lhuna
