/**
 *
 * @file
 *
 * @brief Lhuna desktop player entry point
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/engine/engine.h"
#include "apps/lhuna-desktop/ui/main_window.h"
#include "apps/lhuna-desktop/ui/theme.h"

#include "platform/version/api.h"

#include <QtCore/QCommandLineParser>
#include <QtCore/QTimer>
#include <QtGui/QIcon>
#include <QtWidgets/QApplication>

namespace Platform::Version
{
  // used by core in diagnostics and output streams names
  const StringView PROGRAM_NAME = "Lhuna";
}  // namespace Platform::Version

namespace Lhuna
{
  //! Reverse DNS id: Wayland app_id, .desktop file and AppStream component names must match it
  const auto APP_ID = "app.zxtune.Lhuna";

  // Native Wayland is preferred on Wayland sessions (no XWayland scaling blur, proper app_id), X11 is the fallback
  // when Wayland platform plugin is missing or compositor is not reachable. Explicit user choice is kept.
  void SelectPlatform()
  {
#if defined(Q_OS_LINUX) || defined(Q_OS_FREEBSD)
    if (!qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM"))
    {
      return;
    }
    const auto session = qgetenv("XDG_SESSION_TYPE");
    if (!qEnvironmentVariableIsEmpty("WAYLAND_DISPLAY") || session == "wayland")
    {
      qputenv("QT_QPA_PLATFORM", "wayland;xcb");
    }
#endif
  }
}  // namespace Lhuna

int main(int argc, char* argv[])
{
  Lhuna::SelectPlatform();
  QApplication::setApplicationName("Lhuna");
  QApplication::setOrganizationName("zxtune");
  QApplication::setDesktopFileName(Lhuna::APP_ID);
  QApplication app(argc, argv);
  QIcon icon;
  for (const auto* size : {"32", "64", "256"})
  {
    icon.addFile(QString(":/lhuna/icon-%1.png").arg(size));
  }
  // installed theme icon is preferred, so the same one is used by window and desktop shell
  QApplication::setWindowIcon(QIcon::fromTheme(Lhuna::APP_ID, icon));
  Lhuna::Theme::Apply(app);

  QCommandLineParser parser;
  parser.setApplicationDescription("Chiptunes player with per-voice oscilloscope and chips dashboards");
  parser.addHelpOption();
  parser.addPositionalArgument("paths", "Files, folders or playlists to add to playlist, the first one is played");
  // screenshots allow to check UI headless (QT_QPA_PLATFORM=offscreen), e.g. in CI
  const QCommandLineOption screenshot("screenshot", "Save window (or opened dialog) picture to file and quit", "file");
  const QCommandLineOption delay("delay", "Delay before screenshot, ms", "ms", "3000");
  const QCommandLineOption panel("settings-panel", "Open live settings panel");
  const QCommandLineOption files("files-tab", "Show files browser tab");
  const QCommandLineOption browse("browse", "Show contents of archive or multitrack file in files browser", "file");
  const QCommandLineOption spectrum("spectrum", "Show spectrum analyzer");
  const QCommandLineOption dialog("dialog", "Open dialog: about or settings[:page id]", "name");
  const QCommandLineOption exportTo("export", "Export playlist (xspf or m3u8 by extension) before quit", "file");
  const QCommandLineOption noSession("no-session", "Do not restore and save playlist and window state");
  const QCommandLineOption size("size", "Window size", "WxH");
  parser.addOptions({screenshot, delay, panel, files, browse, spectrum, dialog, exportTo, noSession, size});
  parser.process(app);

  Lhuna::Engine engine;
  Lhuna::MainWindow window(engine, !parser.isSet(noSession));
  if (parser.isSet(size))
  {
    const auto dims = parser.value(size).split('x');
    if (dims.size() == 2)
    {
      window.resize(dims[0].toInt(), dims[1].toInt());
    }
  }
  window.show();
  const auto paths = parser.positionalArguments();
  if (!paths.isEmpty())
  {
    window.AddPaths(paths);
    window.PlayIndex(0);
  }
  if (parser.isSet(files))
  {
    window.ShowFilesTab();
  }
  if (parser.isSet(browse))
  {
    window.BrowseContents(parser.value(browse));
  }
  if (parser.isSet(spectrum))
  {
    window.SetSpectrumVisible(true);
  }
  QWidget* shown = &window;
  if (parser.value(dialog) == "about")
  {
    shown = window.ShowAbout();
  }
  else if (parser.value(dialog).startsWith("settings"))
  {
    // e.g. "settings:sid" opens page of SID settings
    shown = window.ShowAllSettings(parser.value(dialog).section(':', 1));
  }
  if (parser.isSet(panel))
  {
    QTimer::singleShot(parser.value(delay).toInt() / 2, &window, [&window]() { window.ShowSettingsPanel(); });
  }
  if (parser.isSet(screenshot) || parser.isSet(exportTo))
  {
    QTimer::singleShot(parser.value(delay).toInt(), &window, [&]() {
      auto ok = true;
      if (parser.isSet(exportTo))
      {
        ok &= window.ExportPlaylist(parser.value(exportTo));
      }
      if (parser.isSet(screenshot))
      {
        ok &= shown->grab().save(parser.value(screenshot));
      }
      app.exit(ok ? 0 : 1);
    });
  }
  return app.exec();
}
