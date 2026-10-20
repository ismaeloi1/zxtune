/**
 *
 * @file
 *
 * @brief All settings dialog
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "apps/lhuna-desktop/ui/filter_curve_view.h"

#include <QtWidgets/QDialog>

namespace Lhuna
{
  class Engine;
  class Settings;

  /**
   * Every settings page, unlike live panel which shows the playing tune's chips only (as Android's preferences
   * screen vs live settings). Changes are applied immediately as well.
   */
  class AllSettingsDialog : public QDialog
  {
  public:
    //! @param page id of initially shown page (see OptionsPage), the first one if empty
    AllSettingsDialog(Engine& engine, Settings& settings, FilterCurveView::LiveSource live, const QString& page,
                      QWidget* parent);

  private:
    QWidget* MakeOutputPage();

  private:
    Engine& Player;
    Settings& Options;
    const FilterCurveView::LiveSource Live;
  };
}  // namespace Lhuna
