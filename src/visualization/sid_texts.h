/**
 *
 * @file
 *
 * @brief Live texts of SID dashboard rows
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "visualization/sid_description.h"

#include <climits>
#include <cstdint>
#include <string>
#include <vector>

namespace Visualization
{
  /**
   * Fixed set of texts, each one is rebuilt only when the quantized value it's made from is changed. Texts are drawn
   * every frame while registers mostly stay the same, so this avoids allocations per frame without delaying any
   * change.
   */
  class LiveTexts
  {
  public:
    explicit LiveTexts(std::size_t size)
      : Keys(size, INT64_MIN)
      , Texts(size)
    {}

    const std::string& operator[](std::size_t idx) const
    {
      return Texts[idx];
    }

    //! @return true if text should be rebuilt for the new key (see Set)
    bool Changed(std::size_t idx, int64_t key)
    {
      if (Keys[idx] == key)
      {
        return false;
      }
      Keys[idx] = key;
      return true;
    }

    void Set(std::size_t idx, std::string text)
    {
      Texts[idx] = std::move(text);
    }

  private:
    std::vector<int64_t> Keys;
    std::vector<std::string> Texts;
  };

  /**
   * Description of a single cell of values grid under gauges row
   * Caption is small label above the value. Templates are widest typical values, to reserve space regardless of the
   * current one. Numbers are right-aligned to keep digits in place (as in trackers), words are left-aligned. Cells
   * with greater priority values are dropped first if there's not enough space.
   */
  struct CellSpec
  {
    std::string Caption;
    std::vector<std::string> Templates;
    bool Numeric = false;
    int Priority = 0;
  };

  namespace SidCells
  {
    const int WAVE = 0;
    const int DUTY = 1;
    const int NOTE = 2;
    const int PITCH = 3;
    const int MOD = 4;
    const int FILTER = 5;
    const int LEVEL = 6;
    const int STAGE = 7;
    const int ATTACK = 8;
    const int DECAY = 9;
    const int SUSTAIN = 10;
    const int RELEASE = 11;

    const int VOLUME = 0;
    const int MODE = 1;
    const int CUTOFF = 2;
    const int RESONANCE = 3;
    const int ROUTED = 4;

    const std::vector<CellSpec>& Voice();
    const std::vector<CellSpec>& Global();
  }  // namespace SidCells

  /**
   * Texts of gauges row: title bands of 3 gauges (raw registers with greyed human readable value split to main part
   * and tail, e.g. "60%" and "Sus") and cells grid below. Values are displayed as is, only quantization with
   * hysteresis is applied where the last digit would flicker without any meaning.
   */
  class SidRowText
  {
  public:
    static const int TITLES = 9;

    static int Title(int gauge)
    {
      return gauge;
    }

    static int Main(int gauge)
    {
      return 3 + gauge * 2;
    }

    static int Tail(int gauge)
    {
      return 4 + gauge * 2;
    }

    explicit SidRowText(std::size_t cells)
      : Titles(TITLES)
      , Cells(cells)
    {}

    LiveTexts Titles;
    LiveTexts Cells;
  };

  class SidVoiceText : public SidRowText
  {
  public:
    SidVoiceText();

    //! @param chipLabel 1-based index of chip in multichip setup or 0
    void Update(const ChipState& state, int voice, int trend, const SidDescription::Chip& chip, int chipLabel);

  private:
    int Note;
  };

  class SidGlobalText : public SidRowText
  {
  public:
    SidGlobalText();

    void Update(const ChipState& state, const SidDescription::Chip& chip);
  };
}  // namespace Visualization
