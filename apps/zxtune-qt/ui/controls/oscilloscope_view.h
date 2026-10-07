/**
 *
 * @file
 *
 * @brief Oscilloscope widget interface
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "parameters/container.h"

#include <QtWidgets/QWidget>

class PlaybackSupport;

//! Corrscope-like triggered waveforms of separate voices (or master output if not supported)
class OscilloscopeView : public QWidget
{
  Q_OBJECT
protected:
  explicit OscilloscopeView(QWidget& parent);

public:
  static OscilloscopeView* Create(QWidget& parent, PlaybackSupport& supp, Parameters::Container::Ptr options);
};
