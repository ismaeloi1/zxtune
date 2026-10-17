/**
 *
 * @file
 *
 * @brief  Render parameters names
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "zxtune.h"

#include "parameters/types.h"

namespace Parameters::ZXTune::Sound
{
  //! @brief Parameters#ZXTune#Sound namespace prefix
  const auto PREFIX = ZXTune::PREFIX + "sound"_id;

  //@{
  //! @name %Sound frequency in Hz

  //! Default value- 44.1kHz
  const IntType FREQUENCY_DEFAULT = 44100;
  //! Parameter name
  const auto FREQUENCY = PREFIX + "frequency"_id;
  //@}

  //@{
  //! @name Looped playback

  //! Parameter name
  const auto LOOPED = PREFIX + "looped"_id;
  const auto LOOP_LIMIT = PREFIX + "looplimit"_id;
  //@}

  //@{
  //! @name Fadein in seconds
  const IntType FADEIN_PRECISION = 1;

  //! Default value- no fading
  const IntType FADEIN_DEFAULT = 0;
  //! Parameter name
  const auto FADEIN = PREFIX + "fadein"_id;
  //@}

  //@{
  //! @name Fadeout in seconds
  const IntType FADEOUT_PRECISION = 1;

  //! Default value- no fading
  const IntType FADEOUT_DEFAULT = 0;
  //! Parameter name
  const auto FADEOUT = PREFIX + "fadeout"_id;
  //@}

  //@{
  //! @name Gain in percents
  const IntType GAIN_PRECISION = 100;

  //! Default value - no gain
  const IntType GAIN_DEFAULT = GAIN_PRECISION;
  //! Parameter name
  const auto GAIN = PREFIX + "gain"_id;
  //@}

  //@{
  const IntType SILENCE_LIMIT_PRECISION = 1;

  //! Default value - no silence detection
  const IntType SILENCE_LIMIT_DEFAULT = 0;
  //! Parameter name
  const auto SILENCE_LIMIT = PREFIX + "silencelimit"_id;
  //@}
}  // namespace Parameters::ZXTune::Sound

//! @brief Oscilloscope correlation trigger, see corrscope's CorrelationTriggerConfig for meaning
//! Defaults differ from corrscope's template as recommended by its help for evolving notes (lower edge strength,
//! full responsiveness, buffer reset on new notes), it was the most stable for all the chips on test tunes
namespace Parameters::ZXTune::Sound::Scope
{
  const auto PREFIX = Sound::PREFIX + "scope"_id;
  const auto TRIGGER_PREFIX = PREFIX + "trigger"_id;

  //! Values of strengths, rates and widths are in percents
  const IntType PERCENTS = 100;

  //! Correlation buffer width in ms (corrscope's trigger_ms), its default for new projects
  const IntType TRIGGER_WIDTH_DEFAULT = 60;
  const auto TRIGGER_WIDTH = TRIGGER_PREFIX + "width"_id;

  const IntType EDGE_STRENGTH_DEFAULT = 50;
  const auto EDGE_STRENGTH = TRIGGER_PREFIX + "edge_strength"_id;

  //! In wave periods
  const IntType SLOPE_WIDTH_DEFAULT = 25;
  const auto SLOPE_WIDTH = TRIGGER_PREFIX + "slope_width"_id;

  const IntType BUFFER_STRENGTH_DEFAULT = 100;
  const auto BUFFER_STRENGTH = TRIGGER_PREFIX + "buffer_strength"_id;

  const IntType RESPONSIVENESS_DEFAULT = 100;
  const auto RESPONSIVENESS = TRIGGER_PREFIX + "responsiveness"_id;

  //! In wave periods
  const IntType BUFFER_FALLOFF_DEFAULT = 50;
  const auto BUFFER_FALLOFF = TRIGGER_PREFIX + "buffer_falloff"_id;

  const IntType RESET_BELOW_DEFAULT = 70;
  const auto RESET_BELOW = TRIGGER_PREFIX + "reset_below"_id;

  //! DC removal rate
  const IntType MEAN_RESPONSIVENESS_DEFAULT = 0;
  const auto MEAN_RESPONSIVENESS = TRIGGER_PREFIX + "mean_responsiveness"_id;

  const IntType SIGN_STRENGTH_DEFAULT = 0;
  const auto SIGN_STRENGTH = TRIGGER_PREFIX + "sign_strength"_id;

  //! Search range, part of trigger width
  const IntType DIAMETER_DEFAULT = 50;
  const auto DIAMETER = TRIGGER_PREFIX + "diameter"_id;

  //! Non-zero to look for falling edges
  const IntType FALLING_DEFAULT = 0;
  const auto FALLING = TRIGGER_PREFIX + "falling"_id;

  const IntType PITCH_TRACKING_DEFAULT = 1;
  const auto PITCH_TRACKING = TRIGGER_PREFIX + "pitch_tracking"_id;

  //! Zero crossing post trigger radius in samples, 0 to disable
  const IntType POST_RADIUS_DEFAULT = 0;
  const auto POST_RADIUS = TRIGGER_PREFIX + "post_radius"_id;

  //! Full searches per second, 0 for each frame (phase between searches is extrapolated, it's less stable)
  const IntType SEARCH_RATE_DEFAULT = 0;
  const auto SEARCH_RATE = TRIGGER_PREFIX + "search_rate"_id;

  //! Trigger input subsampling, 0 for automatic (depends on samplerate and voices count)
  const IntType SUBSAMPLING_DEFAULT = 0;
  const auto SUBSAMPLING = TRIGGER_PREFIX + "subsampling"_id;
}  // namespace Parameters::ZXTune::Sound::Scope
