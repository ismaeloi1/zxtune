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

#include "apps/lhuna-desktop/ui/filter_curve_view.h"

#include <QtWidgets/QWidget>

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
    SettingsPanel(Settings& settings, FilterCurveView::LiveSource live, QWidget* parent);

    //! @param chips ids of playing tune's chips (see Visualization::EmulationContext)
    //! @param names human readable names of chips
    void Setup(const QStringList& chips, const QStringList& names);

  protected:
    void paintEvent(QPaintEvent* event) override;
    void keyPressEvent(QKeyEvent* event) override;

  private:
    void AddPage(const QString& title, QWidget* content);

  private:
    Settings& Options;
    const FilterCurveView::LiveSource Live;
    QHBoxLayout* Tabs = nullptr;
    QButtonGroup* TabsGroup = nullptr;
    QStackedWidget* Pages = nullptr;
  };
}  // namespace Lhuna
