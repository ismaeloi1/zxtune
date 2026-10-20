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

#include "apps/lhuna-desktop/ui/scope_view.h"
#include "apps/lhuna-desktop/ui/settings.h"
#include "apps/lhuna-desktop/ui/settings_panel.h"
#include "apps/lhuna-desktop/ui/theme.h"
#include "visualization/emulation_context.h"

#include <QtCore/QDir>
#include <QtCore/QDirIterator>
#include <QtCore/QFileInfo>
#include <QtGui/QFileSystemModel>
#include <QtGui/QKeyEvent>
#include <QtGui/QShortcut>
#include <QtWidgets/QFileDialog>
#include <QtWidgets/QHBoxLayout>
#include <QtWidgets/QHeaderView>
#include <QtWidgets/QLabel>
#include <QtWidgets/QListWidget>
#include <QtWidgets/QPushButton>
#include <QtWidgets/QSlider>
#include <QtWidgets/QSplitter>
#include <QtWidgets/QStatusBar>
#include <QtWidgets/QTabWidget>
#include <QtWidgets/QToolButton>
#include <QtWidgets/QTreeView>
#include <QtWidgets/QVBoxLayout>

namespace Lhuna
{
  // too big folders are not scanned completely to keep UI responsive
  const int MAX_SCANNED_FILES = 2000;
  const int REPEAT_OFF = 0;
  const int REPEAT_ALL = 1;
  const int REPEAT_ONE = 2;

  QString FormatTime(uint32_t ms)
  {
    const auto sec = ms / 1000;
    return QString("%1:%2").arg(sec / 60).arg(sec % 60, 2, 10, QChar('0'));
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

  MainWindow::MainWindow(Engine& engine)
    : Player(engine)
    , Random(std::random_device()())
  {
    Options = new Settings(Player, this);
    Feed = new VisualFeed(Player, *Options, this);
    BuildUi();
    connect(&Ticker, &QTimer::timeout, this, &MainWindow::Tick);
    Ticker.start(200);
    resize(1280, 800);
    setWindowTitle("Lhuna");
  }

  void MainWindow::BuildUi()
  {
    auto* splitter = new QSplitter(this);
    splitter->addWidget(BuildBrowser());
    auto* right = new QWidget(splitter);
    auto* rightLayout = new QVBoxLayout(right);
    rightLayout->setContentsMargins(0, 0, 0, 0);
    rightLayout->setSpacing(0);
    rightLayout->addWidget(BuildNowPlaying());
    rightLayout->addWidget(BuildTransport());
    splitter->addWidget(right);
    splitter->setStretchFactor(0, 0);
    splitter->setStretchFactor(1, 1);
    splitter->setSizes({330, 950});
    setCentralWidget(splitter);
    StatusLine = new QLabel(this);
    statusBar()->addWidget(StatusLine, 1);
    statusBar()->setSizeGripEnabled(false);

    auto* space = new QShortcut(QKeySequence(Qt::Key_Space), this);
    connect(space, &QShortcut::activated, this, &MainWindow::PlayPause);
    auto* settings = new QShortcut(QKeySequence(Qt::Key_F2), this);
    connect(settings, &QShortcut::activated, this, &MainWindow::ShowSettingsPanel);
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
    connect(PlaylistView, &QListWidget::itemActivated, this,
            [this](QListWidgetItem* item) { PlayIndex(PlaylistView->row(item)); });
    PlaylistView->installEventFilter(this);
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
    connect(clear, &QPushButton::clicked, this, [this]() {
      Player.Stop();
      Playlist.clear();
      PlaylistView->clear();
      CurrentIndex = -1;
      UpdateNowPlaying();
    });
    buttons->addWidget(addFiles);
    buttons->addWidget(addFolder);
    buttons->addWidget(clear);
    playlistLayout->addLayout(buttons);
    Browser->addTab(playlistPage, tr("Playlist"));

    FilesModel = new QFileSystemModel(this);
    FilesModel->setRootPath(QDir::rootPath());
    FilesModel->setFilter(QDir::AllDirs | QDir::Files | QDir::NoDotAndDotDot);
    FilesView = new QTreeView();
    FilesView->setModel(FilesModel);
    FilesView->setRootIndex(FilesModel->index(QDir::rootPath()));
    FilesView->setCurrentIndex(FilesModel->index(QDir::currentPath()));
    FilesView->scrollTo(FilesView->currentIndex());
    FilesView->expand(FilesView->currentIndex());
    for (int col = 1; col < FilesModel->columnCount(); ++col)
    {
      FilesView->hideColumn(col);
    }
    FilesView->header()->hide();
    // double click on file adds it (and all its subtunes) to playlist and starts playback, folders are expanded
    connect(FilesView, &QTreeView::activated, this, [this](const QModelIndex& index) {
      const QFileInfo info = FilesModel->fileInfo(index);
      if (info.isFile())
      {
        const auto first = static_cast<int>(Playlist.size());
        AddPaths({info.absoluteFilePath()});
        if (first < static_cast<int>(Playlist.size()))
        {
          PlayIndex(first);
        }
      }
    });
    FilesView->setContextMenuPolicy(Qt::ActionsContextMenu);
    auto* addAction = new QAction(tr("Add to playlist"), FilesView);
    connect(addAction, &QAction::triggered, this,
            [this]() { AddPaths({FilesModel->filePath(FilesView->currentIndex())}); });
    FilesView->addAction(addAction);
    Browser->addTab(FilesView, tr("Files"));
    return Browser;
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
    layout->addWidget(TitleLabel);
    layout->addWidget(AuthorLabel);
    layout->addWidget(InfoLabel);

    // oscilloscope over dashboard, as Android's visualizer, with live settings panel over both
    Visualizer = new QWidget(page);
    Visualizer->installEventFilter(this);
    auto* visualLayout = new QVBoxLayout(Visualizer);
    visualLayout->setContentsMargins(0, 6, 0, 0);
    visualLayout->setSpacing(2);
    visualLayout->addWidget(CreateScopeView(*Feed, *Options, Visualizer), 5);
    visualLayout->addWidget(new DashboardView(*Feed, Visualizer), 4);
    layout->addWidget(Visualizer, 1);
    Panel = new SettingsPanel(*Options, Visualizer);
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
    connect(Seek, &QSlider::sliderPressed, this, [this]() { Seeking = true; });
    connect(Seek, &QSlider::sliderReleased, this, [this]() {
      Seeking = false;
      Player.Seek(Seek->value());
    });
    connect(Seek, &QSlider::actionTriggered, this, [this](int action) {
      // clicks on groove (page steps) seek immediately
      if (action == QAbstractSlider::SliderPageStepAdd || action == QAbstractSlider::SliderPageStepSub)
      {
        QTimer::singleShot(0, this, [this]() { Player.Seek(Seek->value()); });
      }
    });
    seekRow->addWidget(PositionLabel);
    seekRow->addWidget(Seek, 1);
    seekRow->addWidget(DurationLabel);
    layout->addLayout(seekRow);
    auto* buttons = new QHBoxLayout();
    ShuffleButton = MakeButton(QString::fromUtf8("⇄"), tr("Shuffle"));
    ShuffleButton->setCheckable(true);
    ShuffleButton->setChecked(Options->Get(Settings::SHUFFLE) != 0);
    connect(ShuffleButton, &QToolButton::toggled, this,
            [this](bool on) { Options->Set(Settings::SHUFFLE, on ? 1 : 0); });
    auto* prev = MakeButton(QString::fromUtf8("⏮"), tr("Previous"));
    connect(prev, &QToolButton::clicked, this, &MainWindow::Prev);
    PlayButton = MakeButton(QString::fromUtf8("▶"), tr("Play/Pause (Space)"), 28);
    PlayButton->setMinimumSize(56, 56);
    PlayButton->setStyleSheet(
        QString("QToolButton { background: %1; border-radius: 28px; color: white; }").arg(Theme::ACCENT.name()));
    connect(PlayButton, &QToolButton::clicked, this, &MainWindow::PlayPause);
    auto* next = MakeButton(QString::fromUtf8("⏭"), tr("Next"));
    connect(next, &QToolButton::clicked, this, [this]() { Next(true); });
    RepeatButton = MakeButton(QString::fromUtf8("↻"), tr("Repeat"));
    connect(RepeatButton, &QToolButton::clicked, this, [this]() {
      Options->Set(Settings::REPEAT, (Options->Get(Settings::REPEAT) + 1) % 3);
      UpdateTransport();
    });
    buttons->addStretch();
    for (auto* button : {ShuffleButton, prev, PlayButton, next, RepeatButton})
    {
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
    for (const auto& path : paths)
    {
      const QFileInfo info(path);
      if (info.isDir())
      {
        QDirIterator it(path, QDir::Files, QDirIterator::Subdirectories);
        while (it.hasNext() && files.size() < MAX_SCANNED_FILES)
        {
          files << it.next();
        }
      }
      else if (!path.isEmpty())
      {
        files << path;
      }
    }
    files.sort();
    for (const auto& file : files)
    {
      std::string error;
      for (auto& track : Player.Detect(file.toStdString(), &error))
      {
        auto* item = new QListWidgetItem(QString::fromStdString(track.DisplayName()), PlaylistView);
        item->setToolTip(QString::fromStdString(track.Path + (track.Subpath.empty() ? "" : "?" + track.Subpath)));
        Playlist.push_back(std::move(track));
      }
    }
    statusBar()->showMessage(tr("%1 tracks in playlist").arg(Playlist.size()), 3000);
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
    if (!Player.Play(Playlist[index], &error))
    {
      statusBar()->showMessage(QString::fromStdString(error), 5000);
    }
    UpdateNowPlaying();
    UpdateTransport();
    if (Panel->isVisible())
    {
      // settings depend on chips of the playing tune, wait a bit for scope layout
      QTimer::singleShot(300, this, &MainWindow::ShowSettingsPanel);
    }
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
      Player.Stop();
      UpdateTransport();
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
    UpdateTransport();
  }

  void MainWindow::UpdateNowPlaying()
  {
    if (CurrentIndex < 0 || Player.State() == PlaybackState::STOPPED)
    {
      TitleLabel->setText(tr("Lhuna"));
      AuthorLabel->setText(tr("Open files or folders to start playback"));
      InfoLabel->clear();
      Seek->setRange(0, 0);
      DurationLabel->setText("0:00");
      return;
    }
    const auto& track = Player.Current();
    const auto slash = track.Path.find_last_of("/\\");
    const auto filename =
        QString::fromStdString(slash == std::string::npos ? track.Path : track.Path.substr(slash + 1));
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
    RepeatButton->setToolTip(repeat == REPEAT_ONE ? tr("Repeat track")
                                                  : (repeat == REPEAT_ALL ? tr("Repeat playlist") : tr("Repeat off")));
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

  void MainWindow::ShowFilesTab()
  {
    Browser->setCurrentIndex(1);
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

  bool MainWindow::eventFilter(QObject* watched, QEvent* event)
  {
    if (watched == Visualizer && (event->type() == QEvent::Resize || event->type() == QEvent::Show))
    {
      PlaceOverlay();
    }
    else if (watched == PlaylistView && event->type() == QEvent::KeyPress
             && static_cast<QKeyEvent*>(event)->key() == Qt::Key_Delete)
    {
      const auto row = PlaylistView->currentRow();
      if (row >= 0)
      {
        delete PlaylistView->takeItem(row);
        Playlist.erase(Playlist.begin() + row);
        if (row < CurrentIndex)
        {
          --CurrentIndex;
        }
        else if (row == CurrentIndex)
        {
          CurrentIndex = -1;
        }
      }
      return true;
    }
    return QMainWindow::eventFilter(watched, event);
  }
}  // namespace Lhuna
