/**
 *
 * @file
 *
 * @brief Settings page controls shared by live settings panel and all settings dialog
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "apps/lhuna-desktop/ui/filter_curve_view.h"

class QWidget;

namespace Lhuna
{
  class Settings;
  struct OptionsPage;

  /**
   * Controls are bound to settings both ways, so changes made elsewhere (e.g. trigger presets or the other settings
   * view) are reflected immediately.
   * @param live source of playing tune's SID filter state for filter curve of "sid" page
   */
  QWidget* MakeOptionsForm(Settings& settings, const OptionsPage& page, const FilterCurveView::LiveSource& live);
}  // namespace Lhuna
