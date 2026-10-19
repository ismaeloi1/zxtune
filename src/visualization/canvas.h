/**
 *
 * @file
 *
 * @brief Toolkit independent drawing interface for chips visualization
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace Visualization
{
  struct Color
  {
    uint8_t A = 255;
    uint8_t R = 255;
    uint8_t G = 255;
    uint8_t B = 255;

    // all the visualization is drawn as white with alpha over dark background, see Android views
    static constexpr Color White(int alpha)
    {
      return {static_cast<uint8_t>(alpha), 255, 255, 255};
    }

    static constexpr Color Black()
    {
      return {255, 0, 0, 0};
    }
  };

  struct RectF
  {
    float Left = 0;
    float Top = 0;
    float Right = 0;
    float Bottom = 0;

    void Set(float left, float top, float right, float bottom)
    {
      Left = left;
      Top = top;
      Right = right;
      Bottom = bottom;
    }

    float Width() const
    {
      return Right - Left;
    }

    float Height() const
    {
      return Bottom - Top;
    }

    float CenterX() const
    {
      return (Left + Right) / 2;
    }

    float CenterY() const
    {
      return (Top + Bottom) / 2;
    }
  };

  enum class FontFace
  {
    DEFAULT,
    MONOSPACE,
    BOLD
  };

  struct Font
  {
    float Size = 12;
    FontFace Face = FontFace::DEFAULT;
    //! digits of the same width keep changing numbers in place
    bool Tabular = false;
  };

  struct FontMetrics
  {
    //! distance above baseline, negative (as in Android Paint)
    float Ascent = 0;
    float Descent = 0;
    //! recommended distance between baselines
    float Spacing = 0;
  };

  enum class Align
  {
    LEFT,
    CENTER,
    RIGHT
  };

  //! Set of polylines
  class Path
  {
  public:
    void Reset()
    {
      Points.clear();
      Starts.clear();
    }

    void MoveTo(float x, float y)
    {
      Starts.push_back(Points.size() / 2);
      Points.push_back(x);
      Points.push_back(y);
    }

    void LineTo(float x, float y)
    {
      if (Starts.empty())
      {
        Starts.push_back(0);
      }
      Points.push_back(x);
      Points.push_back(y);
    }

    //! x, y pairs
    std::vector<float> Points;
    //! indices of the first point of each polyline
    std::vector<std::size_t> Starts;
  };

  /**
   * Minimal set of primitives used by visualization, implemented by UI toolkit (e.g. QPainter, Android Canvas).
   * Coordinates are in pixels, y is from top. Texts are UTF-8.
   */
  class Canvas
  {
  public:
    virtual ~Canvas() = default;

    virtual void DrawLine(float x0, float y0, float x1, float y1, Color color, float width) = 0;
    //! @param coords x0, y0, x1, y1 of each segment, flat caps (as columns of gauges)
    virtual void DrawSegments(const float* coords, std::size_t segments, Color color, float width) = 0;
    //! @param xy points of single antialiased polyline with round joins (as oscilloscope traces)
    virtual void DrawPolyline(const float* xy, std::size_t points, Color color, float width) = 0;
    virtual void StrokeRect(const RectF& rect, Color color, float width) = 0;
    virtual void FillRect(const RectF& rect, Color color) = 0;
    virtual void StrokePath(const Path& path, Color color, float width) = 0;
    virtual void DrawText(std::string_view text, float x, float baseline, const Font& font, Color color,
                          Align align) = 0;
    virtual float MeasureText(std::string_view text, const Font& font) const = 0;
    virtual FontMetrics GetMetrics(const Font& font) const = 0;
    virtual void Save() = 0;
    virtual void ClipRect(const RectF& rect) = 0;
    virtual void Restore() = 0;
  };

  //! @return bytes count of the longest UTF-8 prefix of text fitting maxWidth
  std::size_t BreakText(const Canvas& canvas, std::string_view text, float maxWidth, const Font& font);

  //! @return text cut with ellipsis to fit maxWidth, empty if even ellipsis doesn't fit
  std::string Ellipsize(const Canvas& canvas, std::string_view text, float maxWidth, const Font& font);
}  // namespace Visualization
