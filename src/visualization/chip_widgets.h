/**
 *
 * @file
 *
 * @brief Hardware specific visualization of sound chips voices state
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "visualization/canvas.h"
#include "visualization/gauges.h"
#include "visualization/notes.h"

#include <array>
#include <climits>
#include <cstdint>
#include <functional>
#include <string>

namespace Visualization
{
  /**
   * Draws live state of a single voice according to its chip:
   * - FM (YM2612/OPN, OPL2/3): operators connection schema (as in chips datasheets) with envelope levels
   * - PSG (SN76489), NES 2A03: volume steps, duty/mode glyphs, periods
   * - SPC700 S-DSP: envelope phase, ADSR/GAIN, sample source and flags
   * - MT-32: patch name, played keys, partials states
   * Values are smoothed between frames to avoid flicker of rapidly changing registers.
   */
  class ChipWidgets
  {
  public:
    static const int SMOOTH_SIZE = 8;

    //! Texts built from registers values, rebuilt only on values change
    class TextMemo
    {
    public:
      static const int SLOTS = 4;

      const std::string& Get(int slot, int64_t key, const std::function<std::string()>& make)
      {
        if (Keys[slot] != key)
        {
          Keys[slot] = key;
          Texts[slot] = make();
        }
        return Texts[slot];
      }

    private:
      std::array<int64_t, SLOTS> Keys = {INT64_MIN, INT64_MIN, INT64_MIN, INT64_MIN};
      std::array<std::string, SLOTS> Texts;
    };

    //! Title band summary of voice card, the note is kept through vibrato (see Notes::Quantize)
    class LiveSummary
    {
    public:
      void Update(const VoiceGauges& g);

      const std::string& Text() const
      {
        return Value;
      }

    private:
      int64_t Key = INT64_MIN;
      int Note = Notes::NONE;
      std::string Value;
    };

    ChipWidgets(float density, float textSize);

    /**
     * @param smooth per voice storage of displayed values, at least SMOOTH_SIZE
     * @param texts per voice storage of displayed texts
     * @return true if widget is drawn
     */
    bool Draw(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth, TextMemo& texts);

    //! Short text describing current state, displayed in title band
    static std::string SummaryOf(const VoiceGauges& g);

    //! phase 0..1 of one period
    static float OplWaveform(int wave, float phase);

    //! Operators placement on grid and modulation connections
    struct Schema;

  private:
    void DrawOpnFm(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth);
    void DrawOpl(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth);
    void DrawSchema(Canvas& canvas, const RectF& rect, const Schema& schema, int carriers, const float* levels, int ops,
                    int keys, int feedback, const std::string& info, const int* waves);
    RectF DrawSchemaInfo(Canvas& canvas, const RectF& rect, const std::string& info);
    void DrawOperator(Canvas& canvas, const RectF& b, float level, bool carrier, bool key, int op, const int* wave);
    void DrawOplWave(Canvas& canvas, const RectF& b, int wave);
    void DrawDac(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth);
    void DrawPsg(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth);
    void DrawSpc(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth);
    void DrawMt32Part(Canvas& canvas, const RectF& rect, const VoiceGauges& g);
    void DrawKeyboard(Canvas& canvas, const RectF& kb);
    void DrawMt32Partial(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth);
    void DrawNes(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth);
    void DrawPulseGlyph(Canvas& canvas, const RectF& r, float duty);
    void DrawTriangleGlyph(Canvas& canvas, const RectF& r);
    void DrawNoiseGlyph(Canvas& canvas, const RectF& r, bool isShort);
    void DrawSegments(Canvas& canvas, const RectF& r, float value, int segments);
    void DrawVerticalBar(Canvas& canvas, const RectF& r, float value);
    void DrawCenteredBar(Canvas& canvas, const RectF& r, float value);
    void DrawFit(Canvas& canvas, const std::string& str, float x, float y, float maxWidth);

  private:
    const float Density;
    const Color StrokeColor = Color::White(210);
    const Color DimColor = Color::White(110);
    const float StrokeWidth;
    Font TextFont;
    Font LabelFont;
    const float BaseTextSize;
    const float BaseLabelSize;
    Path Shape;
    std::array<RectF, 4> Boxes;
    std::array<bool, 128> Pressed = {};
    TextMemo* Memo = nullptr;
  };
}  // namespace Visualization
