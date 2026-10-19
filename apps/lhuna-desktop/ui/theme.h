/**
 *
 * @file
 *
 * @brief Dark theme of desktop player
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <QtGui/QColor>

class QApplication;

namespace Lhuna::Theme
{
  //! accent color of Android app
  const QColor ACCENT(0x26, 0xa6, 0x9a);

  void Apply(QApplication& app);
}  // namespace Lhuna::Theme
