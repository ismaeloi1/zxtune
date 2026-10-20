/**
 *
 * @file
 *
 * @brief Main window of desktop player implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/main_window.h"

#include "apps/lhuna-desktop/ui/about_dialog.h"
#include "apps/lhuna-desktop/ui/all_settings_dialog.h"
#include "apps/lhuna-desktop/ui/media_keys.h"
#include "apps/lhuna-desktop/ui/playlist_io.h"
#include "apps/lhuna-desktop/ui/scope_view.h"
#include "apps/lhuna-desktop/ui/settings.h"
#include "apps/lhuna-desktop/ui/settings_panel.h"
#include "apps/lhuna-desktop/ui/spectrum_view.h"
#include "apps/lhuna-desktop/ui/theme.h"
#include "visualization/emulation_context.h"
#include "visualization/engine_brands.h"
#include "visualization/gauges.h"

#include <QtCore/QDir>
#include <QtCore/QDirIterator>
#include <QtCore/QFileInfo>
#include <QtCore/QMimeData>
#include <QtCore/QStandardPaths>
#include <QtGui/QCloseEvent>
#include <QtGui/QFileSystemModel>
#include <QtGui/QKeyEvent>
#include <QtGui/QShortcut>
#include <QtWidgets/QApplication>
#include <QtWidgets/QFileDialog>
#include <QtWidgets/QHBoxLayout>
#include <QtWidgets/QHeaderView>
#include <QtWidgets/QLabel>
#include <QtWidgets/QListWidget>
#include <QtWidgets/QMenuBar>
#include <QtWidgets/QMessageBox>
#include <QtWidgets/QProgressDialog>
#include <QtWidgets/QPushButton>
#include <QtWidgets/QSlider>
#include <QtWidgets/QSplitter>
#include <QtWidgets/QStackedWidget>
#include <QtWidgets/QStatusBar>
#include <QtWidgets/QTabWidget>
#include <QtWidgets/QToolButton>
#include <QtWidgets/QTreeView>
#include <QtWidgets/QTreeWidget>
#include <QtWidgets/QVBoxLayout>

#include <map>
#include <unordered_map>

namespace Lhuna
{
  // too big folders are not scanned completely to keep UI responsive
  const int MAX_SCANNED_FILES = 2000;
  // progress is shown only for long operations
  const int PROGRESS_MIN_FILES = 20;
  const int REPEAT_OFF = 0;
  const int REPEAT_ALL = 1;
  const int REPEAT_ONE = 2;
  const int SEEK_STEP_MS = 5000;
  const int SAVE_DELAY_MS = 2000;
  const int TRACK_INDEX_ROLE = Qt::UserRole;
  const int ITEM_ID_ROLE = Qt::UserRole + 1;
  const auto GEOMETRY_KEY = "ui.window.geometry";
  const auto SPLITTER_KEY = "ui.window.splitter";
  const auto TAB_KEY = "ui.window.tab";
  const auto FILES_DIR_KEY = "ui.files.dir";
  const auto CURRENT_KEY = "ui.playlist.current";

  QString FormatTime(uint32_t ms)
  {
    const auto sec = ms / 1000;
    return QString("%1:%2").arg(sec / 60).arg(sec % 60, 2, 10, QChar('0'));
  }

  QString Filename(const std::string& path)
  {
    return QFileInfo(QString::fromStdString(path)).fileName();
  }

  QToolButton* MakeButton(const QString& text, const QString& tip, int size = 20)
  {
    auto* button = new QToolButton();
    button->setText(text);
    button->setToolTip(tip);
    auto font = button->font();
    font.setPixelSize(size);
    button->setFont(font);
    button->setMinimumSize(40, 40);
    return button;
  }

  // archived files have path-like subpath, subtunes are '#N' only
  bool IsArchiveContent(const std::vector<TrackInfo>& tracks)
  {
    if (tracks.size() == 1 && tracks.front().Subpath.empty())
    {
      return false;
    }
    return std::any_of(tracks.begin(), tracks.end(), [](const TrackInfo& t) { return !t.Subpath.starts_with('#'); });
  }

  MainWindow::MainWindow(Engine& engine, bool session)
    : Player(engine)
    , Session(session)
    , Random(std::random_device()())
  {
    Options = new Settings(Player, this);
    Feed = new VisualFeed(Player, *Options, this);
    BuildUi();
    BuildMenu();
    setAcceptDrops(true);
    connect(&Ticker, &QTimer::timeout, this, &MainWindow::Tick);
    Ticker.start(200);
    SaveTimer.setSingleShot(true);
    SaveTimer.setInterval(SAVE_DELAY_MS);
    connect(&SaveTimer, &QTimer::timeout, this, &MainWindow::SaveSession);
    // session end (logout) quits application without closing windows
    connect(qApp, &QCoreApplication::aboutToQuit, this, &MainWindow::SaveSession);
    connect(Options, &Settings::changed, this, [this](const QString& key) {
      if (key == Settings::OUTPUT_BACKEND || key == Settings::OUTPUT_DEVICE)
      {
        RestartOutput();
      }
    });
    resize(1280, 800);
    setWindowTitle("Lhuna");

    MediaCommands commands;
    commands.PlayPause = [this]() { PlayPause(); };
    commands.Play = [this]() {
      if (Player.State() != PlaybackState::PLAYING)
      {
        PlayPause();
      }
    };
    commands.Pause = [this]() {
      if (Player.State() == PlaybackState::PLAYING)
      {
        PlayPause();
      }
    };
    commands.Stop = [this]() { Stop(); };
    commands.Next = [this]() { Next(true); };
    commands.Previous = [this]() { Prev(); };
    commands.Raise = [this]() {
      showNormal();
      raise();
      activateWindow();
    };
    commands.Quit = [this]() { close(); };
    commands.SeekTo = [this](uint32_t ms) { SeekTo(ms); };
    commands.Position = [this]() { return Player.PositionMs(); };
    Media = MediaKeys::Create(this, std::move(commands));
    if (Session)
    {
      RestoreSession();
    }
    UpdateMediaKeys();
  }

  MainWindow::~MainWindow()
  {
    Player.Stop();
  }

  void MainWindow::BuildUi()
  {
    Splitter = new QSplitter(this);
    Splitter->addWidget(BuildBrowser());
    auto* right = new QWidget(Splitter);
    auto* rightLayout = new QVBoxLayout(right);
    rightLayout->setContentsMargins(0, 0, 0, 0);
    rightLayout->setSpacing(0);
    rightLayout->addWidget(BuildNowPlaying());
    rightLayout->addWidget(BuildTransport());
    Splitter->addWidget(right);
    Splitter->setStretchFactor(0, 0);
    Splitter->setStretchFactor(1, 1);
    Splitter->setSizes({330, 950});
    setCentralWidget(Splitter);
    StatusLine = new QLabel(this);
    statusBar()->addWidget(StatusLine, 1);
    statusBar()->setSizeGripEnabled(false);
    // temporary messages are painted over normal widgets without hiding them
    connect(statusBar(), &QStatusBar::messageChanged, StatusLine,
            [this](const QString& message) { StatusLine->setVisible(message.isEmpty()); });
  }

  void MainWindow::BuildMenu()
  {
    const auto add = [this](QMenu* menu, const QString& text, const QList<QKeySequence>& keys, auto handler) {
      auto* action = menu->addAction(text);
      // standard keys (e.g. QKeySequence::Quit) may be the same as explicit ones or empty on some platforms, duplicates
      // make shortcut ambiguous
      QList<QKeySequence> unique;
      for (const auto& key : keys)
      {
        if (!key.isEmpty() && !unique.contains(key))
        {
          unique << key;
        }
      }
      action->setShortcuts(unique);
      // shortcuts work while menu bar is hidden (e.g. full screen) and over any child widget
      action->setShortcutContext(Qt::WindowShortcut);
      addAction(action);
      connect(action, &QAction::triggered, this, handler);
      return action;
    };
    auto* file = menuBar()->addMenu(tr("&File"));
    add(file, tr("Add files..."), {QKeySequence::Open},
        [this]() { AddPaths(QFileDialog::getOpenFileNames(this, tr("Add files"))); });
    add(file, tr("Add folder..."), {QKeySequence(Qt::CTRL | Qt::SHIFT | Qt::Key_O)}, [this]() {
      const auto dir = QFileDialog::getExistingDirectory(this, tr("Add folder"));
      if (!dir.isEmpty())
      {
        AddPaths({dir});
      }
    });
    file->addSeparator();
    add(file, tr("Import playlist..."), {QKeySequence(Qt::CTRL | Qt::Key_I)}, [this]() {
      const auto name = QFileDialog::getOpenFileName(this, tr("Import playlist"), {},
                                                     tr("Playlists (*.xspf *.m3u *.m3u8);;All files (*)"));
      if (!name.isEmpty())
      {
        ImportPlaylist(name);
      }
    });
    add(file, tr("Export playlist..."), {QKeySequence::Save}, [this]() {
      QString filter;
      auto name = QFileDialog::getSaveFileName(this, tr("Export playlist"), "playlist.xspf",
                                               tr("XSPF playlist (*.xspf);;M3U8 playlist (*.m3u8)"), &filter);
      if (!name.isEmpty())
      {
        if (QFileInfo(name).suffix().isEmpty())
        {
          name += filter.contains("m3u8") ? ".m3u8" : ".xspf";
        }
        ExportPlaylist(name);
      }
    });
    add(file, tr("Clear playlist"), {QKeySequence(Qt::CTRL | Qt::SHIFT | Qt::Key_Delete)},
        [this]() { ClearPlaylist(); });
    file->addSeparator();
    add(file, tr("Quit"), {QKeySequence::Quit, QKeySequence(Qt::CTRL | Qt::Key_Q)}, [this]() { close(); });

    auto* playback = menuBar()->addMenu(tr("&Playback"));
    add(playback, tr("Play/Pause"),
        {QKeySequence(Qt::Key_Space), QKeySequence(Qt::Key_MediaTogglePlayPause), QKeySequence(Qt::Key_MediaPlay),
         QKeySequence(Qt::Key_MediaPause)},
        [this]() { PlayPause(); });
    add(playback, tr("Stop"), {QKeySequence(Qt::CTRL | Qt::Key_Period), QKeySequence(Qt::Key_MediaStop)},
        [this]() { Stop(); });
    add(playback, tr("Next"), {QKeySequence(Qt::CTRL | Qt::Key_Right), QKeySequence(Qt::Key_MediaNext)},
        [this]() { Next(true); });
    add(playback, tr("Previous"), {QKeySequence(Qt::CTRL | Qt::Key_Left), QKeySequence(Qt::Key_MediaPrevious)},
        [this]() { Prev(); });
    add(playback, tr("Seek forward"), {QKeySequence(Qt::SHIFT | Qt::Key_Right)}, [this]() { SeekBy(SEEK_STEP_MS); });
    add(playback, tr("Seek backward"), {QKeySequence(Qt::SHIFT | Qt::Key_Left)}, [this]() { SeekBy(-SEEK_STEP_MS); });
    playback->addSeparator();
    add(playback, tr("Shuffle"), {QKeySequence(Qt::CTRL | Qt::Key_H)}, [this]() { ShuffleButton->toggle(); });
    add(playback, tr("Repeat mode"), {QKeySequence(Qt::CTRL | Qt::Key_R)}, [this]() { RepeatButton->click(); });

    auto* view = menuBar()->addMenu(tr("&View"));
    add(view, tr("Playlist"), {QKeySequence(Qt::CTRL | Qt::Key_1)}, [this]() { Browser->setCurrentIndex(0); });
    add(view, tr("Files"), {QKeySequence(Qt::CTRL | Qt::Key_2)}, [this]() { ShowFilesTab(); });
    view->addSeparator();
    add(view, tr("Live settings"), {QKeySequence(Qt::Key_F2)}, [this]() {
      if (Panel->isVisible())
      {
        Panel->hide();
      }
      else
      {
        ShowSettingsPanel();
      }
    });
    SpectrumAction =
        add(view, tr("Spectrum analyzer"), {QKeySequence(Qt::Key_F3)}, [this](bool on) { SetSpectrumVisible(on); });
    SpectrumAction->setCheckable(true);
    DashboardAction = add(view, tr("Chips dashboard"), {QKeySequence(Qt::Key_F4)}, [this](bool on) {
      Options->Set(Settings::DASHBOARD, on ? 1 : 0);
      Dashboard->setVisible(on);
    });
    DashboardAction->setCheckable(true);
    DashboardAction->setChecked(Options->Get(Settings::DASHBOARD) != 0);
    Dashboard->setVisible(DashboardAction->isChecked());
    SpectrumAction->setChecked(Options->Get(Settings::SPECTRUM) != 0);
    Spectrum->setVisible(SpectrumAction->isChecked());
    add(view, tr("Full screen"), {QKeySequence::FullScreen, QKeySequence(Qt::Key_F11)}, [this]() {
      setWindowState(windowState() ^ Qt::WindowFullScreen);
      menuBar()->setVisible(!isFullScreen());
    });

    auto* settings = menuBar()->addMenu(tr("&Settings"));
    add(settings, tr("All settings..."), {QKeySequence::Preferences, QKeySequence(Qt::CTRL | Qt::Key_Comma)},
        [this]() { ShowAllSettings(); });

    auto* help = menuBar()->addMenu(tr("&Help"));
    add(help, tr("Keyboard shortcuts"), {QKeySequence::HelpContents, QKeySequence(Qt::Key_F1)},
        [this]() { ShowShortcuts(); });
    add(help, tr("About Lhuna"), {}, [this]() { ShowAbout(); });
  }

  QWidget* MainWindow::BuildBrowser()
  {
    Browser = new QTabWidget();
    Browser->setMinimumWidth(240);

    auto* playlistPage = new QWidget();
    auto* playlistLayout = new QVBoxLayout(playlistPage);
    playlistLayout->setContentsMargins(0, 0, 0, 0);
    PlaylistView = new QListWidget(playlistPage);
    PlaylistView->setAlternatingRowColors(true);
    PlaylistView->setSelectionMode(QAbstractItemView::ExtendedSelection);
    PlaylistView->setDragDropMode(QAbstractItemView::InternalMove);
    PlaylistView->setDefaultDropAction(Qt::MoveAction);
    connect(PlaylistView, &QListWidget::itemActivated, this,
            [this](QListWidgetItem* item) { PlayIndex(PlaylistView->row(item)); });
    // order is synchronized by items ids, whatever way model reports the move
    connect(PlaylistView->model(), &QAbstractItemModel::rowsMoved, this, &MainWindow::SyncPlaylistOrder,
            Qt::QueuedConnection);
    connect(PlaylistView->model(), &QAbstractItemModel::layoutChanged, this, &MainWindow::SyncPlaylistOrder,
            Qt::QueuedConnection);
    PlaylistView->installEventFilter(this);
    PlaylistView->viewport()->installEventFilter(this);
    PlaylistView->setContextMenuPolicy(Qt::ActionsContextMenu);
    auto* playAction = new QAction(tr("Play"), PlaylistView);
    connect(playAction, &QAction::triggered, this, [this]() { PlayIndex(PlaylistView->currentRow()); });
    auto* removeAction = new QAction(tr("Remove"), PlaylistView);
    removeAction->setShortcut(QKeySequence::Delete);
    removeAction->setShortcutContext(Qt::WidgetShortcut);
    connect(removeAction, &QAction::triggered, this, [this]() { RemoveSelected(); });
    PlaylistView->addActions({playAction, removeAction});
    playlistLayout->addWidget(PlaylistView);
    auto* buttons = new QHBoxLayout();
    auto* addFiles = new QPushButton(tr("Add files"), playlistPage);
    connect(addFiles, &QPushButton::clicked, this,
            [this]() { AddPaths(QFileDialog::getOpenFileNames(this, tr("Add files"))); });
    auto* addFolder = new QPushButton(tr("Add folder"), playlistPage);
    connect(addFolder, &QPushButton::clicked, this, [this]() {
      const auto dir = QFileDialog::getExistingDirectory(this, tr("Add folder"));
      if (!dir.isEmpty())
      {
        AddPaths({dir});
      }
    });
    auto* clear = new QPushButton(tr("Clear"), playlistPage);
    connect(clear, &QPushButton::clicked, this, [this]() { ClearPlaylist(); });
    buttons->addWidget(addFiles);
    buttons->addWidget(addFolder);
    buttons->addWidget(clear);
    playlistLayout->addLayout(buttons);
    Browser->addTab(playlistPage, tr("Playlist"));

    FilesModel = new QFileSystemModel(this);
    FilesModel->setRootPath(QDir::rootPath());
    FilesModel->setFilter(QDir::AllDirs | QDir::Files | QDir::NoDotAndDotDot | QDir::Drives);
    FilesView = new QTreeView();
    FilesView->setModel(FilesModel);
    FilesView->setRootIndex(FilesModel->index(FilesModel->rootPath()));
    const auto startDir = Options->GetString(FILES_DIR_KEY);
    const auto dir = !startDir.isEmpty() && QFileInfo(startDir).isDir() ? startDir : QDir::currentPath();
    FilesView->setCurrentIndex(FilesModel->index(dir));
    FilesView->scrollTo(FilesView->currentIndex());
    FilesView->expand(FilesView->currentIndex());
    for (int col = 1; col < FilesModel->columnCount(); ++col)
    {
      FilesView->hideColumn(col);
    }
    FilesView->header()->hide();
    FilesView->setDragEnabled(true);
    FilesView->setSelectionMode(QAbstractItemView::ExtendedSelection);
    // double click on module adds it (and all its subtunes) to playlist and starts playback, archives are opened
    connect(FilesView, &QTreeView::activated, this, [this](const QModelIndex& index) {
      const QFileInfo info = FilesModel->fileInfo(index);
      if (!info.isFile())
      {
        return;
      }
      const auto path = info.absoluteFilePath();
      Options->SetString(FILES_DIR_KEY, info.absolutePath());
      if (PlaylistIo::IsPlaylist(path))
      {
        ImportPlaylist(path);
        return;
      }
      QApplication::setOverrideCursor(Qt::WaitCursor);
      std::string error;
      auto tracks = Player.Detect(QDir::toNativeSeparators(path).toStdString(), &error);
      QApplication::restoreOverrideCursor();
      if (tracks.empty())
      {
        statusBar()->showMessage(tr("No supported modules in %1").arg(info.fileName()), 4000);
      }
      else if (IsArchiveContent(tracks))
      {
        BrowseContents(path);
      }
      else
      {
        const auto first = static_cast<int>(Playlist.size());
        AppendTracks(std::move(tracks));
        PlayIndex(first);
      }
    });
    FilesView->setContextMenuPolicy(Qt::ActionsContextMenu);
    auto* addAction = new QAction(tr("Add to playlist"), FilesView);
    connect(addAction, &QAction::triggered, this, [this]() {
      QStringList paths;
      for (const auto& index : FilesView->selectionModel()->selectedRows())
      {
        paths << FilesModel->filePath(index);
      }
      AddPaths(paths);
    });
    auto* browseAction = new QAction(tr("Browse contents"), FilesView);
    connect(browseAction, &QAction::triggered, this, [this]() {
      const QFileInfo info = FilesModel->fileInfo(FilesView->currentIndex());
      if (info.isFile())
      {
        BrowseContents(info.absoluteFilePath());
      }
    });
    FilesView->addActions({addAction, browseAction});
    FilesStack = new QStackedWidget();
    FilesStack->addWidget(FilesView);
    FilesStack->addWidget(BuildArchiveBrowser());
    Browser->addTab(FilesStack, tr("Files"));
    return Browser;
  }

  QWidget* MainWindow::BuildArchiveBrowser()
  {
    auto* page = new QWidget();
    auto* layout = new QVBoxLayout(page);
    layout->setContentsMargins(0, 0, 0, 0);
    auto* header = new QHBoxLayout();
    auto* back = new QToolButton(page);
    back->setText(QString::fromUtf8("←"));
    back->setToolTip(tr("Back to files (Backspace)"));
    back->setShortcut(QKeySequence(Qt::Key_Backspace));
    connect(back, &QToolButton::clicked, this, [this]() {
      FilesStack->setCurrentIndex(0);
      FilesView->setFocus();
    });
    ArchiveTitle = new QLabel(page);
    ArchiveTitle->setWordWrap(true);
    header->addWidget(back);
    header->addWidget(ArchiveTitle, 1);
    layout->addLayout(header);
    ArchiveView = new QTreeWidget(page);
    ArchiveView->setColumnCount(2);
    ArchiveView->setHeaderHidden(true);
    ArchiveView->setSelectionMode(QAbstractItemView::ExtendedSelection);
    ArchiveView->header()->setStretchLastSection(false);
    ArchiveView->header()->setSectionResizeMode(0, QHeaderView::Stretch);
    ArchiveView->header()->setSectionResizeMode(1, QHeaderView::ResizeToContents);
    connect(ArchiveView, &QTreeWidget::itemActivated, this, [this](QTreeWidgetItem* item) {
      if (item->data(0, TRACK_INDEX_ROLE).isValid())
      {
        AddArchiveSelection(true);
      }
    });
    ArchiveView->setContextMenuPolicy(Qt::ActionsContextMenu);
    auto* play = new QAction(tr("Play"), ArchiveView);
    connect(play, &QAction::triggered, this, [this]() { AddArchiveSelection(true); });
    auto* addSelected = new QAction(tr("Add to playlist"), ArchiveView);
    connect(addSelected, &QAction::triggered, this, [this]() { AddArchiveSelection(false); });
    auto* addAll = new QAction(tr("Add all to playlist"), ArchiveView);
    connect(addAll, &QAction::triggered, this, [this]() { AppendTracks(ArchiveTracks); });
    ArchiveView->addActions({play, addSelected, addAll});
    layout->addWidget(ArchiveView, 1);
    return page;
  }

  void MainWindow::BrowseContents(const QString& path)
  {
    QApplication::setOverrideCursor(Qt::WaitCursor);
    std::string error;
    ArchiveTracks = Player.Detect(QDir::toNativeSeparators(path).toStdString(), &error);
    QApplication::restoreOverrideCursor();
    ArchiveView->clear();
    // folders of archive are built from subpaths components
    std::map<QString, QTreeWidgetItem*> folders;
    for (std::size_t idx = 0; idx < ArchiveTracks.size(); ++idx)
    {
      const auto& track = ArchiveTracks[idx];
      auto parts = QString::fromStdString(track.Subpath).split('/', Qt::SkipEmptyParts);
      if (parts.isEmpty())
      {
        parts << QFileInfo(path).fileName();
      }
      QTreeWidgetItem* parent = nullptr;
      QString prefix;
      for (int part = 0; part + 1 < parts.size(); ++part)
      {
        prefix += parts[part] + '/';
        auto& folder = folders[prefix];
        if (!folder)
        {
          folder = parent ? new QTreeWidgetItem(parent, {parts[part]})
                          : new QTreeWidgetItem(ArchiveView, {parts[part]});
          folder->setForeground(0, Theme::ACCENT.lighter(130));
        }
        parent = folder;
      }
      auto name = parts.back();
      if (!track.Title.empty())
      {
        name += QString::fromUtf8(" — ") + QString::fromStdString(track.DisplayName());
      }
      const QStringList columns{name, track.DurationMs ? FormatTime(track.DurationMs) : QString()};
      auto* item = parent ? new QTreeWidgetItem(parent, columns) : new QTreeWidgetItem(ArchiveView, columns);
      item->setData(0, TRACK_INDEX_ROLE, static_cast<int>(idx));
      item->setToolTip(0, QString::fromStdString(track.Type + " " + track.Subpath));
    }
    ArchiveView->expandToDepth(0);
    const auto title = QFileInfo(path).fileName();
    ArchiveTitle->setText(ArchiveTracks.empty()
                              ? tr("%1: no supported modules").arg(title)
                              : tr("<b>%1</b>: %2 tracks").arg(title.toHtmlEscaped()).arg(ArchiveTracks.size()));
    FilesStack->setCurrentIndex(1);
    Browser->setCurrentWidget(FilesStack);
    ArchiveView->setFocus();
    if (ArchiveView->topLevelItemCount())
    {
      ArchiveView->setCurrentItem(ArchiveView->topLevelItem(0));
    }
  }

  void MainWindow::AddArchiveSelection(bool play)
  {
    std::vector<TrackInfo> tracks;
    std::function<void(QTreeWidgetItem*)> collect = [&](QTreeWidgetItem* item) {
      const auto idx = item->data(0, TRACK_INDEX_ROLE);
      if (idx.isValid())
      {
        tracks.push_back(ArchiveTracks.at(idx.toInt()));
      }
      // selected folder means all its content
      for (int child = 0; child < item->childCount(); ++child)
      {
        collect(item->child(child));
      }
    };
    for (auto* item : ArchiveView->selectedItems())
    {
      collect(item);
    }
    if (tracks.empty())
    {
      return;
    }
    const auto first = static_cast<int>(Playlist.size());
    AppendTracks(std::move(tracks));
    if (play)
    {
      PlayIndex(first);
    }
  }

  QWidget* MainWindow::BuildNowPlaying()
  {
    auto* page = new QWidget();
    auto* layout = new QVBoxLayout(page);
    layout->setContentsMargins(12, 8, 12, 0);
    layout->setSpacing(2);
    TitleLabel = new QLabel(page);
    auto titleFont = TitleLabel->font();
    titleFont.setPixelSize(20);
    titleFont.setBold(true);
    TitleLabel->setFont(titleFont);
    AuthorLabel = new QLabel(page);
    auto authorFont = AuthorLabel->font();
    authorFont.setPixelSize(15);
    AuthorLabel->setFont(authorFont);
    AuthorLabel->setStyleSheet(QString("color: %1;").arg(Theme::ACCENT.lighter(120).name()));
    InfoLabel = new QLabel(page);
    InfoLabel->setStyleSheet("color: #9a9a9a;");
    EngineLabel = new QLabel(page);
    EngineLabel->setStyleSheet("color: #9a9a9a;");
    layout->addWidget(TitleLabel);
    layout->addWidget(AuthorLabel);
    layout->addWidget(InfoLabel);
    layout->addWidget(EngineLabel);

    // oscilloscope over dashboard, as Android's visualizer, with live settings panel over both
    Visualizer = new QWidget(page);
    Visualizer->installEventFilter(this);
    auto* visualLayout = new QVBoxLayout(Visualizer);
    visualLayout->setContentsMargins(0, 6, 0, 0);
    visualLayout->setSpacing(2);
    visualLayout->addWidget(CreateScopeView(*Feed, *Options, Visualizer), 5);
    Spectrum = new SpectrumView(Player, Visualizer);
    visualLayout->addWidget(Spectrum, 2);
    Dashboard = new DashboardView(*Feed, Visualizer);
    visualLayout->addWidget(Dashboard, 4);
    layout->addWidget(Visualizer, 1);
    Panel = new SettingsPanel(*Options, SidLiveSource(), Visualizer);
    Panel->hide();
    PanelButton = MakeButton(QString::fromUtf8("⚙"), tr("Live settings (F2)"), 22);
    PanelButton->setParent(Visualizer);
    PanelButton->setFixedSize(40, 40);
    PanelButton->setStyleSheet("QToolButton { background: rgba(0,0,0,0.5); }");
    connect(PanelButton, &QToolButton::clicked, this, [this]() {
      if (Panel->isVisible())
      {
        Panel->hide();
      }
      else
      {
        ShowSettingsPanel();
      }
    });
    return page;
  }

  QWidget* MainWindow::BuildTransport()
  {
    auto* page = new QWidget();
    auto* layout = new QVBoxLayout(page);
    layout->setContentsMargins(12, 4, 12, 4);
    auto* seekRow = new QHBoxLayout();
    PositionLabel = new QLabel("0:00", page);
    DurationLabel = new QLabel("0:00", page);
    Seek = new QSlider(Qt::Horizontal, page);
    Seek->setFocusPolicy(Qt::NoFocus);
    connect(Seek, &QSlider::sliderPressed, this, [this]() { Seeking = true; });
    connect(Seek, &QSlider::sliderReleased, this, [this]() {
      Seeking = false;
      SeekTo(Seek->value());
    });
    connect(Seek, &QSlider::actionTriggered, this, [this](int action) {
      // clicks on groove (page steps) seek immediately
      if (action == QAbstractSlider::SliderPageStepAdd || action == QAbstractSlider::SliderPageStepSub)
      {
        QTimer::singleShot(0, this, [this]() { SeekTo(Seek->value()); });
      }
    });
    seekRow->addWidget(PositionLabel);
    seekRow->addWidget(Seek, 1);
    seekRow->addWidget(DurationLabel);
    layout->addLayout(seekRow);
    auto* buttons = new QHBoxLayout();
    ShuffleButton = MakeButton(QString::fromUtf8("⇄"), tr("Shuffle (Ctrl+H)"));
    ShuffleButton->setCheckable(true);
    ShuffleButton->setChecked(Options->Get(Settings::SHUFFLE) != 0);
    connect(ShuffleButton, &QToolButton::toggled, this, [this](bool on) {
      Options->Set(Settings::SHUFFLE, on ? 1 : 0);
      UpdateMediaKeys();
    });
    auto* prev = MakeButton(QString::fromUtf8("⏮"), tr("Previous (Ctrl+Left)"));
    connect(prev, &QToolButton::clicked, this, &MainWindow::Prev);
    PlayButton = MakeButton(QString::fromUtf8("▶"), tr("Play/Pause (Space)"), 28);
    PlayButton->setMinimumSize(56, 56);
    PlayButton->setStyleSheet(
        QString("QToolButton { background: %1; border-radius: 28px; color: white; }").arg(Theme::ACCENT.name()));
    connect(PlayButton, &QToolButton::clicked, this, &MainWindow::PlayPause);
    auto* next = MakeButton(QString::fromUtf8("⏭"), tr("Next (Ctrl+Right)"));
    connect(next, &QToolButton::clicked, this, [this]() { Next(true); });
    RepeatButton = MakeButton(QString::fromUtf8("↻"), tr("Repeat"));
    connect(RepeatButton, &QToolButton::clicked, this, [this]() {
      Options->Set(Settings::REPEAT, (Options->Get(Settings::REPEAT) + 1) % 3);
      UpdateTransport();
      UpdateMediaKeys();
    });
    buttons->addStretch();
    for (auto* button : {ShuffleButton, prev, PlayButton, next, RepeatButton})
    {
      // space is play/pause shortcut, it should not click focused button
      button->setFocusPolicy(Qt::NoFocus);
      buttons->addWidget(button);
      buttons->addSpacing(8);
    }
    buttons->addStretch();
    layout->addLayout(buttons);
    UpdateTransport();
    UpdateNowPlaying();
    return page;
  }

  void MainWindow::AddPaths(const QStringList& paths)
  {
    QStringList files;
    for (const auto& relative : paths)
    {
      // playlists store absolute paths, so the current directory does not matter
      const auto path = relative.isEmpty() ? relative : QFileInfo(relative).absoluteFilePath();
      const QFileInfo info(path);
      if (info.isDir())
      {
        QDirIterator it(path, QDir::Files, QDirIterator::Subdirectories);
        while (it.hasNext() && files.size() < MAX_SCANNED_FILES)
        {
          const auto file = it.next();
          // playlists found in folders would duplicate the folder's content
          if (!PlaylistIo::IsPlaylist(file))
          {
            files << file;
          }
        }
      }
      else if (PlaylistIo::IsPlaylist(path))
      {
        ImportPlaylist(path);
      }
      else if (!path.isEmpty())
      {
        files << path;
      }
    }
    if (files.isEmpty())
    {
      return;
    }
    files.sort();
    std::unique_ptr<QProgressDialog> progress;
    if (files.size() >= PROGRESS_MIN_FILES)
    {
      progress = std::make_unique<QProgressDialog>(tr("Scanning files..."), tr("Cancel"), 0, files.size(), this);
      progress->setWindowModality(Qt::WindowModal);
      progress->setMinimumDuration(500);
    }
    std::vector<TrackInfo> tracks;
    for (int idx = 0; idx < files.size(); ++idx)
    {
      if (progress)
      {
        progress->setValue(idx);
        if (progress->wasCanceled())
        {
          break;
        }
      }
      std::string error;
      for (auto& track : Player.Detect(QDir::toNativeSeparators(files[idx]).toStdString(), &error))
      {
        tracks.push_back(std::move(track));
      }
    }
    AppendTracks(std::move(tracks));
  }

  void MainWindow::AppendTracks(std::vector<TrackInfo> tracks)
  {
    for (auto& track : tracks)
    {
      auto* item = new QListWidgetItem(QString::fromStdString(track.DisplayName()), PlaylistView);
      item->setToolTip(QString::fromStdString(track.Path + (track.Subpath.empty() ? "" : " > " + track.Subpath)));
      item->setData(ITEM_ID_ROLE, NextId);
      // items are moved only within the list
      item->setFlags(item->flags() & ~Qt::ItemIsDropEnabled);
      PlaylistIds.push_back(NextId++);
      Playlist.push_back(std::move(track));
    }
    statusBar()->showMessage(tr("%1 tracks in playlist").arg(Playlist.size()), 3000);
    SchedulePlaylistSave();
    UpdateMediaKeys();
  }

  void MainWindow::SyncPlaylistOrder()
  {
    std::unordered_map<quint64, std::size_t> positions;
    for (std::size_t idx = 0; idx < PlaylistIds.size(); ++idx)
    {
      positions[PlaylistIds[idx]] = idx;
    }
    const auto currentId = CurrentIndex >= 0 ? PlaylistIds[CurrentIndex] : 0;
    std::vector<TrackInfo> tracks;
    std::vector<quint64> ids;
    for (int row = 0; row < PlaylistView->count(); ++row)
    {
      const auto id = PlaylistView->item(row)->data(ITEM_ID_ROLE).toULongLong();
      const auto it = positions.find(id);
      if (it == positions.end())
      {
        continue;
      }
      tracks.push_back(Playlist[it->second]);
      ids.push_back(id);
    }
    if (ids == PlaylistIds || tracks.size() != Playlist.size())
    {
      return;
    }
    Playlist = std::move(tracks);
    PlaylistIds = std::move(ids);
    const auto current = std::find(PlaylistIds.begin(), PlaylistIds.end(), currentId);
    CurrentIndex = currentId && current != PlaylistIds.end() ? static_cast<int>(current - PlaylistIds.begin()) : -1;
    SchedulePlaylistSave();
  }

  void MainWindow::ClearPlaylist()
  {
    Player.Stop();
    Playlist.clear();
    PlaylistIds.clear();
    PlaylistView->clear();
    CurrentIndex = -1;
    UpdateNowPlaying();
    UpdateTransport();
    SchedulePlaylistSave();
    UpdateMediaKeys();
  }

  void MainWindow::RemoveSelected()
  {
    auto rows = PlaylistView->selectionModel()->selectedRows();
    std::sort(rows.begin(), rows.end(), [](const auto& lh, const auto& rh) { return lh.row() > rh.row(); });
    for (const auto& index : rows)
    {
      const auto row = index.row();
      delete PlaylistView->takeItem(row);
      Playlist.erase(Playlist.begin() + row);
      PlaylistIds.erase(PlaylistIds.begin() + row);
      if (row < CurrentIndex)
      {
        --CurrentIndex;
      }
      else if (row == CurrentIndex)
      {
        CurrentIndex = -1;
      }
    }
    SchedulePlaylistSave();
    UpdateMediaKeys();
  }

  bool MainWindow::ImportPlaylist(const QString& file)
  {
    PlaylistIo::LoadResult result;
    QString error;
    if (!PlaylistIo::Load(file, result, &error))
    {
      statusBar()->showMessage(tr("Failed to import %1: %2").arg(QFileInfo(file).fileName(), error), 5000);
      return false;
    }
    const auto count = result.Tracks.size();
    AppendTracks(std::move(result.Tracks));
    statusBar()->showMessage(
        result.Skipped ? tr("Imported %1 tracks, %2 entries without local files skipped").arg(count).arg(result.Skipped)
                       : tr("Imported %1 tracks").arg(count),
        5000);
    return true;
  }

  bool MainWindow::ExportPlaylist(const QString& file)
  {
    QString error;
    const auto name = QFileInfo(file).completeBaseName();
    const auto isM3u = file.endsWith(".m3u", Qt::CaseInsensitive) || file.endsWith(".m3u8", Qt::CaseInsensitive);
    const auto saved = isM3u ? PlaylistIo::SaveM3u(file, name, Playlist, &error)
                             : PlaylistIo::SaveXspf(file, name, Playlist, &error);
    statusBar()->showMessage(saved ? tr("Exported %1 tracks to %2").arg(Playlist.size()).arg(file)
                                   : tr("Failed to export playlist: %1").arg(error),
                             5000);
    return saved;
  }

  QString MainWindow::SessionPlaylistFile() const
  {
    return QDir(QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)).filePath("playlist.xspf");
  }

  void MainWindow::SchedulePlaylistSave()
  {
    if (Session)
    {
      SaveTimer.start();
    }
  }

  void MainWindow::SaveSession()
  {
    if (!Session)
    {
      return;
    }
    SaveTimer.stop();
    const auto file = SessionPlaylistFile();
    QDir().mkpath(QFileInfo(file).absolutePath());
    QString error;
    if (!PlaylistIo::SaveXspf(file, tr("Session"), Playlist, &error))
    {
      statusBar()->showMessage(tr("Failed to save playlist: %1").arg(error), 5000);
    }
    auto& store = Options->Storage();
    store.setValue(CURRENT_KEY, CurrentIndex);
    store.setValue(GEOMETRY_KEY, saveGeometry());
    store.setValue(SPLITTER_KEY, Splitter->saveState());
    store.setValue(TAB_KEY, Browser->currentIndex());
  }

  void MainWindow::RestoreSession()
  {
    auto& store = Options->Storage();
    // position is not restored on Wayland (compositor places windows), size is
    if (const auto geometry = store.value(GEOMETRY_KEY).toByteArray(); !geometry.isEmpty())
    {
      restoreGeometry(geometry);
    }
    if (const auto splitter = store.value(SPLITTER_KEY).toByteArray(); !splitter.isEmpty())
    {
      Splitter->restoreState(splitter);
    }
    Browser->setCurrentIndex(store.value(TAB_KEY, 0).toInt());
    PlaylistIo::LoadResult result;
    if (QFileInfo::exists(SessionPlaylistFile()) && PlaylistIo::Load(SessionPlaylistFile(), result, nullptr))
    {
      AppendTracks(std::move(result.Tracks));
      const auto current = store.value(CURRENT_KEY, -1).toInt();
      if (current >= 0 && current < static_cast<int>(Playlist.size()))
      {
        // the last played track is selected, so Space resumes it
        CurrentIndex = current;
        PlaylistView->setCurrentRow(current);
      }
    }
  }

  void MainWindow::PlayIndex(int index)
  {
    if (index < 0 || index >= static_cast<int>(Playlist.size()))
    {
      return;
    }
    std::string error;
    CurrentIndex = index;
    PlaylistView->setCurrentRow(index);
    ++TrackCounter;
    if (!Player.Play(Playlist[index], &error))
    {
      statusBar()->showMessage(QString::fromStdString(error), 5000);
    }
    Options->UpdateLatency();
    EngineKey.clear();
    UpdateNowPlaying();
    UpdateTransport();
    UpdateMediaKeys();
    SchedulePlaylistSave();
    if (Panel->isVisible())
    {
      // settings depend on chips of the playing tune, wait a bit for scope layout
      QTimer::singleShot(300, this, &MainWindow::ShowSettingsPanel);
    }
  }

  void MainWindow::RestartOutput()
  {
    if (Player.State() == PlaybackState::STOPPED || CurrentIndex < 0)
    {
      Options->UpdateLatency();
      return;
    }
    const auto paused = Player.State() == PlaybackState::PAUSED;
    const auto pos = Player.PositionMs();
    PlayIndex(CurrentIndex);
    SeekTo(pos);
    if (paused)
    {
      Player.Pause();
    }
    statusBar()->showMessage(tr("Output: %1").arg(QString::fromStdString(Player.BackendId())), 3000);
  }

  void MainWindow::PlayPause()
  {
    switch (Player.State())
    {
    case PlaybackState::PLAYING:
      Player.Pause();
      break;
    case PlaybackState::PAUSED:
      Player.Resume();
      break;
    default:
      PlayIndex(std::max(CurrentIndex, 0));
      break;
    }
    UpdateTransport();
    UpdateMediaKeys();
  }

  void MainWindow::Stop()
  {
    Player.Stop();
    UpdateNowPlaying();
    UpdateTransport();
    UpdateMediaKeys();
  }

  void MainWindow::Next(bool byUser)
  {
    const auto size = static_cast<int>(Playlist.size());
    if (size == 0)
    {
      return;
    }
    const auto repeat = Options->Get(Settings::REPEAT);
    if (!byUser && repeat == REPEAT_ONE)
    {
      PlayIndex(CurrentIndex);
    }
    else if (Options->Get(Settings::SHUFFLE))
    {
      PlayIndex(std::uniform_int_distribution<int>(0, size - 1)(Random));
    }
    else if (CurrentIndex + 1 < size)
    {
      PlayIndex(CurrentIndex + 1);
    }
    else if (byUser || repeat == REPEAT_ALL)
    {
      PlayIndex(0);
    }
    else
    {
      Stop();
    }
  }

  void MainWindow::Prev()
  {
    // as in most players: restart current track if it's played for a while
    if (Player.PositionMs() > 3000 || CurrentIndex <= 0)
    {
      PlayIndex(std::max(CurrentIndex, 0));
    }
    else
    {
      PlayIndex(CurrentIndex - 1);
    }
  }

  void MainWindow::SeekTo(uint32_t ms)
  {
    if (Player.State() == PlaybackState::STOPPED)
    {
      return;
    }
    const auto duration = Player.Current().DurationMs;
    const auto target = duration ? std::min(ms, duration) : ms;
    Player.Seek(target);
    Media->Seeked(target);
  }

  void MainWindow::SeekBy(int deltaMs)
  {
    const auto pos = static_cast<int64_t>(Player.PositionMs()) + deltaMs;
    SeekTo(static_cast<uint32_t>(std::max<int64_t>(pos, 0)));
  }

  void MainWindow::Tick()
  {
    if (Player.TakeFinished())
    {
      Next(false);
      return;
    }
    const auto pos = Player.PositionMs();
    if (!Seeking)
    {
      const QSignalBlocker block(Seek);
      Seek->setValue(static_cast<int>(pos));
    }
    PositionLabel->setText(FormatTime(Seeking ? Seek->value() : pos));
    const auto backend = Player.BackendId();
    const auto& frame = Feed->Frame();
    const auto description = QString::fromStdString(frame.Status).section('\n', 0, 0);
    StatusLine->setText(backend.empty() ? tr("Stopped")
                                        : tr("Output: %1  |  %2").arg(QString::fromStdString(backend), description));
    UpdateEngineLines();
    UpdateTransport();
    if (Player.State() != LastState)
    {
      UpdateMediaKeys();
    }
  }

  void MainWindow::UpdateEngineLines()
  {
    if (Player.State() == PlaybackState::STOPPED)
    {
      EngineLabel->clear();
      EngineKey.clear();
      return;
    }
    const auto& track = Player.Current();
    auto description = Feed->Frame().Status.substr(0, Feed->Frame().Status.find('\n'));
    // emulation description is optional, scope statistics line follows it
    if (description.starts_with("scope:"))
    {
      description.clear();
    }
    const auto key = QString::fromStdString(track.Type + '\n' + track.Program + '\n' + description);
    if (key == EngineKey)
    {
      return;
    }
    EngineKey = key;
    const auto lines = Visualization::StatusHeader::Lines(track.Type, track.Program, description,
                                                          [&track]() { return Engine::FormatName(track.Type); });
    QStringList texts;
    for (const auto& line : lines)
    {
      texts << QString::fromStdString(line);
    }
    EngineLabel->setText(texts.join(QString::fromUtf8("  ·  ")));
  }

  void MainWindow::UpdateNowPlaying()
  {
    if (CurrentIndex < 0 || Player.State() == PlaybackState::STOPPED)
    {
      TitleLabel->setText(tr("Lhuna"));
      AuthorLabel->setText(tr("Open files or folders to start playback, or drop them here"));
      InfoLabel->clear();
      EngineLabel->clear();
      Seek->setRange(0, 0);
      DurationLabel->setText("0:00");
      setWindowTitle("Lhuna");
      return;
    }
    const auto& track = Player.Current();
    const auto filename = Filename(track.Path);
    TitleLabel->setText(track.Title.empty() ? filename : QString::fromStdString(track.Title));
    AuthorLabel->setText(track.Author.empty() ? tr("Unknown author") : QString::fromStdString(track.Author));
    QStringList info;
    for (const auto* field : {&track.Type, &track.Program, &track.Computer})
    {
      if (!field->empty())
      {
        info << QString::fromStdString(*field);
      }
    }
    if (!track.Subpath.empty())
    {
      info << filename + " > " + QString::fromStdString(track.Subpath);
    }
    InfoLabel->setText(info.join(QString::fromUtf8("  ·  ")));
    Seek->setRange(0, static_cast<int>(track.DurationMs));
    Seek->setPageStep(std::max<int>(track.DurationMs / 20, 1000));
    DurationLabel->setText(FormatTime(track.DurationMs));
    setWindowTitle(QString::fromStdString(track.DisplayName()) + " - Lhuna");
  }

  void MainWindow::UpdateTransport()
  {
    const auto playing = Player.State() == PlaybackState::PLAYING;
    PlayButton->setText(QString::fromUtf8(playing ? "⏸" : "▶"));
    const auto repeat = Options->Get(Settings::REPEAT);
    RepeatButton->setCheckable(true);
    RepeatButton->setChecked(repeat != REPEAT_OFF);
    RepeatButton->setText(QString::fromUtf8(repeat == REPEAT_ONE ? "↻1" : "↻"));
    RepeatButton->setToolTip(repeat == REPEAT_ONE
                                 ? tr("Repeat track (Ctrl+R)")
                                 : (repeat == REPEAT_ALL ? tr("Repeat playlist (Ctrl+R)") : tr("Repeat off (Ctrl+R)")));
  }

  void MainWindow::UpdateMediaKeys()
  {
    if (!Media)
    {
      return;
    }
    LastState = Player.State();
    MediaStatus status;
    status.State = LastState;
    if (LastState != PlaybackState::STOPPED)
    {
      const auto& track = Player.Current();
      status.Title = track.Title.empty() ? Filename(track.Path) : QString::fromStdString(track.Title);
      status.Artist = QString::fromStdString(track.Author);
      status.DurationMs = track.DurationMs;
      status.TrackId = TrackCounter;
    }
    const auto size = static_cast<int>(Playlist.size());
    status.HasNext = size > 0;
    status.HasPrevious = size > 0;
    Media->Update(status);
  }

  void MainWindow::ShowSettingsPanel()
  {
    // chips are detected when panel is opened, as Android's live settings do
    std::vector<std::string> groups;
    const auto& frame = Feed->Frame();
    for (const auto& group : frame.Layout->Groups)
    {
      groups.push_back(group.Name);
    }
    auto description = frame.Status.substr(0, frame.Status.find('\n'));
    // emulation description is optional, scope statistics line follows it
    if (description.starts_with("scope:"))
    {
      description.clear();
    }
    const auto type = Player.State() == PlaybackState::STOPPED ? std::string() : Player.Current().Type;
    const auto context = Visualization::EmulationContext::Detect(type, groups, description);
    QStringList chips;
    for (const auto& chip : context.Chips)
    {
      chips << QString::fromStdString(chip);
    }
    QStringList names;
    for (const auto& name : context.Names)
    {
      names << QString::fromStdString(name);
    }
    Panel->Setup(chips, names);
    Panel->show();
    PlaceOverlay();
    Panel->raise();
    PanelButton->raise();
    Panel->setFocus();
  }

  FilterCurveView::LiveSource MainWindow::SidLiveSource() const
  {
    const auto* feed = Feed;
    return [feed]() {
      FilterCurveView::Live live;
      const auto& frame = feed->Frame();
      const auto description = frame.Status.substr(0, frame.Status.find('\n'));
      if (!frame.Live || description.find("MOS") == std::string::npos)
      {
        return live;
      }
      live.Known = true;
      live.Is8580 = description.find("8580") != std::string::npos;
      const auto& gauges = frame.Gauges;
      if (gauges.Kind != Visualization::GaugesKind::CHIPS)
      {
        return live;
      }
      const auto stateOffset = Visualization::ChipGauges::GAUGES * Visualization::ChipGauges::COLUMNS * 2;
      for (int chip = 0; chip < gauges.Count; ++chip)
      {
        const auto offset = static_cast<std::size_t>(chip) * Visualization::ChipGauges::SIZE + stateOffset;
        if (offset + Visualization::ChipState::SIZE > gauges.Bytes.size())
        {
          break;
        }
        const Visualization::ChipState state(gauges.Bytes.data() + offset);
        live.Chips.push_back({state.Cutoff(), state.Mode(), state.Resonance(), state.Routing()});
      }
      return live;
    };
  }

  QWidget* MainWindow::ShowAbout()
  {
    auto* dialog = new AboutDialog(this);
    dialog->setAttribute(Qt::WA_DeleteOnClose);
    dialog->show();
    return dialog;
  }

  QWidget* MainWindow::ShowAllSettings(const QString& page)
  {
    auto* dialog = new AllSettingsDialog(Player, *Options, SidLiveSource(), page, this);
    dialog->setAttribute(Qt::WA_DeleteOnClose);
    dialog->show();
    return dialog;
  }

  void MainWindow::ShowShortcuts()
  {
    QString text = "<table>";
    for (const auto* action : actions())
    {
      if (action->shortcuts().isEmpty())
      {
        continue;
      }
      QStringList keys;
      for (const auto& key : action->shortcuts())
      {
        keys << key.toString(QKeySequence::NativeText);
      }
      text += QString("<tr><td>%1&nbsp;&nbsp;</td><td><b>%2</b></td></tr>")
                  .arg(action->text().remove('&').toHtmlEscaped(), keys.join(", ").toHtmlEscaped());
    }
    text += QString("<tr><td>%1&nbsp;&nbsp;</td><td><b>Delete</b></td></tr>").arg(tr("Remove from playlist"));
    text += QString("<tr><td>%1&nbsp;&nbsp;</td><td><b>Backspace</b></td></tr>").arg(tr("Leave archive"));
    text += "</table>";
    QMessageBox::information(this, tr("Keyboard shortcuts"), text);
  }

  void MainWindow::SetSpectrumVisible(bool visible)
  {
    // test runs (e.g. --spectrum --no-session) should not change user's view
    if (Session)
    {
      Options->Set(Settings::SPECTRUM, visible ? 1 : 0);
    }
    SpectrumAction->setChecked(visible);
    Spectrum->setVisible(visible);
  }

  void MainWindow::ShowFilesTab()
  {
    Browser->setCurrentWidget(FilesStack);
  }

  void MainWindow::PlaceOverlay()
  {
    const auto area = Visualizer->rect();
    // panel takes the right part where less important voices are, as Android's landscape layout
    const auto width = std::max(360, area.width() * 45 / 100);
    Panel->setGeometry(area.right() - width - 8, area.top() + 8, width, area.height() - 16);
    PanelButton->move(area.right() - PanelButton->width() - 12 - (Panel->isVisible() ? width : 0), area.top() + 12);
    PanelButton->raise();
  }

  void MainWindow::resizeEvent(QResizeEvent* event)
  {
    QMainWindow::resizeEvent(event);
    PlaceOverlay();
  }

  void MainWindow::closeEvent(QCloseEvent* event)
  {
    SaveSession();
    QMainWindow::closeEvent(event);
  }

  QStringList LocalPaths(const QMimeData* data)
  {
    QStringList result;
    if (data && data->hasUrls())
    {
      for (const auto& url : data->urls())
      {
        if (url.isLocalFile())
        {
          result << url.toLocalFile();
        }
      }
    }
    return result;
  }

  void MainWindow::dragEnterEvent(QDragEnterEvent* event)
  {
    if (!LocalPaths(event->mimeData()).isEmpty())
    {
      event->acceptProposedAction();
    }
  }

  void MainWindow::dropEvent(QDropEvent* event)
  {
    HandleDrop(event);
  }

  bool MainWindow::HandleDrop(QDropEvent* event)
  {
    const auto paths = LocalPaths(event->mimeData());
    if (paths.isEmpty())
    {
      return false;
    }
    event->acceptProposedAction();
    const auto first = static_cast<int>(Playlist.size());
    // let drag source finish before long scanning
    QTimer::singleShot(0, this, [this, paths, first]() {
      AddPaths(paths);
      if (Player.State() == PlaybackState::STOPPED && first < static_cast<int>(Playlist.size()))
      {
        PlayIndex(first);
      }
    });
    return true;
  }

  bool MainWindow::eventFilter(QObject* watched, QEvent* event)
  {
    if (watched == Visualizer && (event->type() == QEvent::Resize || event->type() == QEvent::Show))
    {
      PlaceOverlay();
    }
    else if (watched == PlaylistView->viewport())
    {
      // files dropped from outside (file managers, files tab) are added, internal moves reorder playlist
      if (event->type() == QEvent::DragEnter || event->type() == QEvent::DragMove)
      {
        auto* drag = static_cast<QDropEvent*>(event);
        if (drag->source() != PlaylistView && !LocalPaths(drag->mimeData()).isEmpty())
        {
          drag->acceptProposedAction();
          return true;
        }
      }
      else if (event->type() == QEvent::Drop)
      {
        auto* drop = static_cast<QDropEvent*>(event);
        if (drop->source() != PlaylistView && HandleDrop(drop))
        {
          return true;
        }
      }
    }
    return QMainWindow::eventFilter(watched, event);
  }
}  // namespace Lhuna
