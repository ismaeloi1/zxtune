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
#include <QtWidgets/QApplication>

namespace Platform::Version
{
  // used by core in diagnostics and output streams names
  const StringView PROGRAM_NAME = "Lhuna";
}  // namespace Platform::Version

int main(int argc, char* argv[])
{
  QApplication app(argc, argv);
  QApplication::setApplicationName("Lhuna");
  QApplication::setOrganizationName("zxtune");
  Lhuna::Theme::Apply(app);

  QCommandLineParser parser;
  parser.setApplicationDescription("Chiptunes player with per-voice oscilloscope and chips dashboards");
  parser.addHelpOption();
  parser.addPositionalArgument("paths", "Files or folders to add to playlist, the first one is played");
  // screenshots allow to check UI headless (QT_QPA_PLATFORM=offscreen), e.g. in CI
  const QCommandLineOption screenshot("screenshot", "Save window picture to file and quit", "file");
  const QCommandLineOption delay("delay", "Delay before screenshot, ms", "ms", "3000");
  const QCommandLineOption panel("settings-panel", "Open live settings panel");
  const QCommandLineOption files("files-tab", "Show files browser tab");
  const QCommandLineOption size("size", "Window size", "WxH");
  parser.addOptions({screenshot, delay, panel, files, size});
  parser.process(app);

  Lhuna::Engine engine;
  Lhuna::MainWindow window(engine);
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
  if (parser.isSet(panel))
  {
    QTimer::singleShot(parser.value(delay).toInt() / 2, &window, [&window]() { window.ShowSettingsPanel(); });
  }
  if (parser.isSet(screenshot))
  {
    QTimer::singleShot(parser.value(delay).toInt(), &window, [&window, &app, &parser, &screenshot]() {
      const auto saved = window.grab().save(parser.value(screenshot));
      app.exit(saved ? 0 : 1);
    });
  }
  return app.exec();
}
