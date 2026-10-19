/**
 *
 * @file
 *
 * @brief Chips state gauges and diagnostics (inspired by JSIDPlay2's oscilloscope view)
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "visualization/canvas.h"
#include "visualization/chip_widgets.h"
#include "visualization/gauges.h"
#include "visualization/sid_texts.h"
#include "visualization/voices_layout.h"

#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace Visualization
{
  //! Gauges of the same moment as oscilloscope waveforms
  struct GaugesFrame
  {
    GaugesKind Kind = GaugesKind::NONE;
    //! count of chips or voices stored
    int Count = 0;
    VoicesLayout::Ptr Layout = VoicesLayout::Master();
    std::vector<uint8_t> Bytes;
  };

  /**
   * Displays history of SID state for each chip:
   * - per voice: oscillator output, envelope (dB), frequency (log scale)
   * - global: master volume, resonance, filter cutoff
   * Other chips are displayed per voice as cards: title band with live summary, level and pitch history and hardware
   * specific state (see ChipWidgets). And multiline status text below. Chips are displayed side by side.
   * Static parts (frames, captions, separators) can be cached by caller (see Pass and StaticKey), so only live values
   * are drawn at each frame.
   */
  class Dashboard
  {
  public:
    enum class Pass
    {
      ALL,
      STATIC,
      DYNAMIC
    };

    //! @param density pixels per density independent unit (see Android's dp)
    explicit Dashboard(float density);
    ~Dashboard();

    //! @param status emulation description and scope statistics, see Sound::Scope::GetStatus
    //! @param stats additional UI side statistics lines
    void SetStatus(const std::string& status, const std::string& stats);

    //! Static part depends only on geometry and layout, but not on values
    uint64_t StaticKey(const GaugesFrame& frame, int width, int height) const;

    //! Background is not filled, it's expected to be black
    void Draw(Canvas& canvas, const GaugesFrame& frame, int width, int height, Pass pass);

    static const int DEFAULT_WAVE_WINDOW_MS = 10;

  private:
    class Impl;
    const std::unique_ptr<Impl> Delegate;
  };
}  // namespace Visualization
