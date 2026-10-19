/**
 *
 * @file
 *
 * @brief Musical notes helpers implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "visualization/notes.h"

#include <cmath>

namespace Visualization::Notes
{
  float Semitones(float hz)
  {
    return 12 * std::log2(hz / 440.f) + 57;
  }

  std::string Name(int note)
  {
    static const char* const NAMES[] = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    if (note < 0 || note > 131)
    {
      return {};
    }
    return NAMES[note % 12] + std::to_string(note / 12);
  }

  std::string NameOf(float hz)
  {
    return hz <= 0.f ? std::string() : Name(static_cast<int>(std::lround(Semitones(hz))));
  }

  int Quantize(float semitones, int shown)
  {
    if (shown != NONE && std::abs(semitones - shown) <= 0.5f + NOTE_MARGIN)
    {
      return shown;
    }
    return static_cast<int>(std::lround(semitones));
  }
}  // namespace Visualization::Notes
