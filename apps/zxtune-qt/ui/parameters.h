/**
 *
 * @file
 *
 * @brief UI parameters definition
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "apps/zxtune-qt/app_parameters.h"

namespace Parameters::ZXTuneQT::UI
{
  const auto PREFIX = ZXTuneQT::PREFIX + "UI"_id;

  const auto LANGUAGE = PREFIX + "Language"_id;

  const auto PARAM_GEOMETRY = "Geometry"sv;
  const auto PARAM_LAYOUT = "Layout"sv;
  const auto PARAM_VISIBLE = "Visible"sv;
  const auto PARAM_INDEX = "Index"sv;
  const auto PARAM_SIZE = "Size"sv;

  namespace Scope
  {
    const auto PREFIX = UI::PREFIX + "Scope"_id;

    //! displayed duration of oscilloscope waveforms in ms
    const auto WINDOW = PREFIX + "Window"_id;
    const IntType WINDOW_DEFAULT = 40;

    //! oscilloscope amplification in percents, 0 for automatic per-voice gain
    const auto GAIN = PREFIX + "Gain"_id;
    const IntType GAIN_DEFAULT = 0;

    //! output device buffering in ms to delay visualization by
    const auto LATENCY = PREFIX + "Latency"_id;
    const IntType LATENCY_DEFAULT = 80;
  }  // namespace Scope
}  // namespace Parameters::ZXTuneQT::UI
