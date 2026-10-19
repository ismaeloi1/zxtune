/**
 *
 * @file
 *
 * @brief Oscilloscope cells geometry, static background and traces rendering
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "visualization/canvas.h"
#include "visualization/voices_layout.h"

#include <array>
#include <string>
#include <vector>

namespace Visualization
{
  /**
   * Grid of cells: column per chip, row per voice. Columns are shared with dashboard, see ChipColumns
   */
  class ScopeGrid
  {
  public:
    static const int MAX_POINTS = 2048;
    static const int MIN_POINTS = 128;
    static const int MAX_CHANNELS = 64;

    ScopeGrid(const VoicesLayout& layout, int channels);

    int Channels() const
    {
      return static_cast<int>(CellColumns.size());
    }

    /**
     * Two points per pixel column of the narrowest cell: samples range of each column is displayed by its minimum and
     * maximum (see Sound::Scope::Get), so nothing is lost by decimation.
     */
    int Points(int viewWidth) const;

    //! Trace area of channel in pixels
    struct Trace
    {
      float Left = 0;
      float Width = 0;
      float CenterY = 0;
      float Amplitude = 0;
    };

    std::vector<Trace> Traces(int viewWidth, int viewHeight, float textSize) const;

    //! Static part of picture (center lines, titles bands borders and titles), drawn once per layout or size change
    void DrawBackground(Canvas& canvas, int width, int height, float textSize) const;

    //! @return text size of labels in cell of specified height, 0 if there's no space for labels
    static float LabelSize(float cellHeight, float textSize);

  private:
    float Left(int chan) const
    {
      return Columns.Left(CellColumns[chan]);
    }

    float Width(int chan) const
    {
      return Columns.Width(CellColumns[chan]);
    }

    float LabelBand(int viewHeight, float textSize) const;

  private:
    const ChipColumns Columns;
    int Rows = 1;
    std::vector<int> CellColumns;
    std::vector<int> CellRows;
    std::vector<std::string> Labels;
  };

  //! Per channel gain keeping quiet voices visible, as corrscope's amplification
  class AutoGain
  {
  public:
    static const int AUTO = 0;

    //! @param fixedPercent AUTO or fixed gain in percents
    float Of(int chan, const int16_t* samples, int points, int fixedPercent);

  private:
    std::array<float, ScopeGrid::MAX_CHANNELS> SmoothedPeaks = {};
  };

  /**
   * Draws triggered waveforms as polylines
   * @param samples [channels][points] as returned by Sound::Scope::Get
   */
  class ScopeTraces
  {
  public:
    void Draw(Canvas& canvas, const ScopeGrid& grid, const int16_t* samples, int channels, int points, int width,
              int height, float textSize, int gainPercent, float lineWidth);

  private:
    AutoGain Gain;
    std::vector<float> Points;
  };
}  // namespace Visualization
