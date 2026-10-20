/**
 *
 * @file
 *
 * @brief About dialog: credits, open source components and supported formats
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <QtWidgets/QDialog>

namespace Lhuna
{
  //! The same pages as Android's AboutFragment
  class AboutDialog : public QDialog
  {
  public:
    explicit AboutDialog(QWidget* parent);
  };
}  // namespace Lhuna
