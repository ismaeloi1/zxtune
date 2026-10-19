/**
 *
 * @file
 *
 * @brief Live settings panel over visualization
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <QtWidgets/QWidget>

#include <functional>
#include <map>

class QButtonGroup;
class QHBoxLayout;
class QStackedWidget;

namespace Lhuna
{
  class Settings;
  struct OptionsPage;

  /**
   * Semi-transparent panel over oscilloscope (it keeps animating behind), so changes are seen immediately (see
   * Android's ScopeSettingsDialog). Pages: oscilloscope display, trigger, emulation settings of the playing tune's
   * chips only and playback settings.
   */
  class SettingsPanel : public QWidget
  {
    Q_OBJECT
  public:
    SettingsPanel(Settings& settings, QWidget* parent);

    //! @param chips ids of playing tune's chips (see Visualization::EmulationContext)
    //! @param names human readable names of chips
    void Setup(const QStringList& chips, const QStringList& names);

  protected:
    void paintEvent(QPaintEvent* event) override;
    void keyPressEvent(QKeyEvent* event) override;

  private:
    void AddPage(const QString& title, QWidget* content);
    QWidget* MakePage(const OptionsPage& page);
    QWidget* MakeTriggerPage(const OptionsPage& page);
    void Refresh();

  private:
    Settings& Options;
    QHBoxLayout* Tabs = nullptr;
    QButtonGroup* TabsGroup = nullptr;
    QStackedWidget* Pages = nullptr;
    //! updaters of controls by option key, to reflect changes made elsewhere (e.g. presets)
    std::multimap<QString, std::function<void()>> Updaters;
  };
}  // namespace Lhuna
