/**
 *
 * @file
 *
 * @brief Chips dashboard widget interface
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <QtWidgets/QWidget>

class PlaybackSupport;

//! Per voice state history and hardware specific widgets, JSIDPlay2-like gauges for SID chips
class DashboardView : public QWidget
{
  Q_OBJECT
protected:
  explicit DashboardView(QWidget& parent);

public:
  static DashboardView* Create(QWidget& parent, PlaybackSupport& supp);
};
