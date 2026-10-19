/**
 *
 * @file
 *
 * @brief Oscilloscope cells geometry and rendering implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "visualization/scope_grid.h"

#include <algorithm>
#include <cstdlib>

namespace Visualization
{
  namespace ScopeGridDetails
  {
    // labels are never drawn over traces, so they are shrunk for short cells to keep traces high enough
    const float MAX_BAND_PART = 0.4f;
    const float LABEL_BAND_TO_TEXT = 1.4f;
    const float MIN_LABEL_SCALE = 0.6f;
    const float AMPLIFICATION = 0.95f;
    const float CELL_MARGIN = 0.01f;
    const int LABEL_ALPHA = 200;
    // 0.15 of white
    const int GRID_ALPHA = 38;

    const float GAIN_TARGET = 0.9f;
    const float GAIN_MAX = 8.f;
    // per frame, ~0.5s to rise at 120Hz
    const float GAIN_RELEASE = 0.04f;
  }  // namespace ScopeGridDetails

  ScopeGrid::ScopeGrid(const VoicesLayout& layout, int channels)
    : Columns(layout.Groups)
  {
    if (layout.Groups.empty())
    {
      Rows = std::max(channels, 1);
      CellColumns.assign(channels, 0);
      for (int chan = 0; chan < channels; ++chan)
      {
        CellRows.push_back(chan);
      }
      return;
    }
    Rows = Columns.MaxRows();
    for (std::size_t group = 0; group < layout.Groups.size(); ++group)
    {
      const auto& desc = layout.Groups[group];
      for (std::size_t idx = 0; idx < desc.Voices.size(); ++idx)
      {
        if (static_cast<int>(CellColumns.size()) >= channels)
        {
          return;
        }
        CellColumns.push_back(Columns.Column(static_cast<int>(group), static_cast<int>(idx)));
        CellRows.push_back(Columns.Row(static_cast<int>(group), static_cast<int>(idx)));
        Labels.push_back(desc.Name + " - " + desc.Voices[idx]);
      }
    }
  }

  int ScopeGrid::Points(int viewWidth) const
  {
    return std::clamp(static_cast<int>(viewWidth * Columns.Narrowest() * 2), MIN_POINTS, MAX_POINTS);
  }

  float ScopeGrid::LabelSize(float cellHeight, float textSize)
  {
    using namespace ScopeGridDetails;
    const auto size = std::min(textSize, cellHeight * MAX_BAND_PART / LABEL_BAND_TO_TEXT);
    return size >= textSize * MIN_LABEL_SCALE ? size : 0.f;
  }

  float ScopeGrid::LabelBand(int viewHeight, float textSize) const
  {
    return Labels.empty()
               ? 0.f
               : LabelSize(static_cast<float>(viewHeight) / Rows, textSize) * ScopeGridDetails::LABEL_BAND_TO_TEXT;
  }

  std::vector<ScopeGrid::Trace> ScopeGrid::Traces(int viewWidth, int viewHeight, float textSize) const
  {
    using namespace ScopeGridDetails;
    const auto band = LabelBand(viewHeight, textSize);
    const auto cellH = static_cast<float>(viewHeight) / Rows;
    std::vector<Trace> result(Channels());
    for (int chan = 0; chan < Channels(); ++chan)
    {
      const auto cellW = viewWidth * Width(chan);
      const auto top = cellH * CellRows[chan] + band;
      const auto traceH = cellH - band;
      auto& trace = result[chan];
      trace.Left = viewWidth * Left(chan) + cellW * CELL_MARGIN;
      trace.Width = cellW * (1 - 2 * CELL_MARGIN);
      trace.CenterY = top + traceH / 2;
      trace.Amplitude = traceH * 0.5f * AMPLIFICATION;
    }
    return result;
  }

  void ScopeGrid::DrawBackground(Canvas& canvas, int width, int height, float textSize) const
  {
    using namespace ScopeGridDetails;
    const auto cellH = static_cast<float>(height) / Rows;
    const auto band = LabelBand(height, textSize);
    const auto lineColor = Color::White(GRID_ALPHA);
    for (int chan = 0; chan < Channels(); ++chan)
    {
      const auto left = width * Left(chan);
      const auto right = left + width * Width(chan);
      const auto top = cellH * CellRows[chan];
      const auto centerY = (top + band + top + cellH) / 2;
      canvas.DrawLine(left, centerY, right, centerY, lineColor, 1);
      if (band != 0.f)
      {
        canvas.DrawLine(left, top + band, right, top + band, lineColor, 1);
      }
    }
    const auto size = LabelSize(cellH, textSize);
    if (Labels.empty() || size <= 0.f)
    {
      return;
    }
    const Font font{size, FontFace::DEFAULT, false};
    const auto metrics = canvas.GetMetrics(font);
    const auto padding = size * (LABEL_BAND_TO_TEXT - 1);
    for (int chan = 0; chan < Channels(); ++chan)
    {
      const auto x = width * Left(chan) + padding;
      // centered in the band
      const auto y = cellH * CellRows[chan] + padding / 2 - metrics.Ascent;
      canvas.DrawText(Ellipsize(canvas, Labels[chan], width * Width(chan) - padding * 2, font), x, y, font,
                      Color::White(LABEL_ALPHA), Align::LEFT);
    }
  }

  float AutoGain::Of(int chan, const int16_t* samples, int points, int fixedPercent)
  {
    using namespace ScopeGridDetails;
    if (fixedPercent != AUTO)
    {
      return fixedPercent / 100.f;
    }
    int peak = 0;
    for (int idx = 0; idx < points; ++idx)
    {
      peak = std::max(peak, std::abs(static_cast<int>(samples[idx])));
    }
    const auto level = peak / 32768.f;
    const auto prev = SmoothedPeaks[chan];
    const auto smoothed = level > prev ? level : prev + (level - prev) * GAIN_RELEASE;
    SmoothedPeaks[chan] = smoothed;
    return std::max(GAIN_TARGET / std::max(smoothed, GAIN_TARGET / GAIN_MAX), 1.f);
  }

  void ScopeTraces::Draw(Canvas& canvas, const ScopeGrid& grid, const int16_t* samples, int channels, int points,
                         int width, int height, float textSize, int gainPercent, float lineWidth)
  {
    if (points < 2)
    {
      return;
    }
    const auto traces = grid.Traces(width, height, textSize);
    const auto count = std::min<int>({channels, grid.Channels(), ScopeGrid::MAX_CHANNELS});
    Points.resize(points * 2);
    for (int chan = 0; chan < count; ++chan)
    {
      const auto& trace = traces[chan];
      const auto* data = samples + chan * points;
      const auto gain = Gain.Of(chan, data, points, gainPercent);
      const auto step = trace.Width / (points - 1);
      for (int idx = 0; idx < points; ++idx)
      {
        // amplified peaks are limited by voice's cell
        const auto value = std::clamp(data[idx] * gain / 32768.f, -1.f, 1.f);
        Points[idx * 2] = trace.Left + step * idx;
        Points[idx * 2 + 1] = trace.CenterY - trace.Amplitude * value;
      }
      canvas.DrawPolyline(Points.data(), points, Color::White(255), lineWidth);
    }
  }
}  // namespace Visualization
