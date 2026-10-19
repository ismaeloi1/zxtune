/**
 *
 * @file
 *
 * @brief Musical notes helpers
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <string>

namespace Visualization::Notes
{
  const int NONE = -0x7fffffff;

  //! semitones from C0
  float Semitones(float hz);

  //! @param note semitones from C0, e.g. "C#5"
  std::string Name(int note);
  std::string NameOf(float hz);

  // vibrato often exceeds half of semitone, so the note is kept a bit more than its bounds
  const float NOTE_MARGIN = 0.3f;

  /**
   * Quantizes semitones to note with hysteresis: shown note is kept while input is within NOTE_MARGIN beyond its
   * half-step bounds. Doesn't delay real note changes.
   */
  int Quantize(float semitones, int shown);
}  // namespace Visualization::Notes
