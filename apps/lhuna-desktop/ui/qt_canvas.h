/**
 *
 * @file
 *
 * @brief Visualization::Canvas implementation over QPainter
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "visualization/canvas.h"

#include <QtGui/QFont>
#include <QtGui/QFontMetricsF>
#include <QtGui/QPainter>

#include <array>
#include <optional>

namespace Lhuna
{
  class QtCanvas : public Visualization::Canvas
  {
  public:
    explicit QtCanvas(QPainter& painter);

    void DrawLine(float x0, float y0, float x1, float y1, Visualization::Color color, float width) override;
    void DrawSegments(const float* coords, std::size_t segments, Visualization::Color color, float width) override;
    void DrawPolyline(const float* xy, std::size_t points, Visualization::Color color, float width) override;
    void StrokeRect(const Visualization::RectF& rect, Visualization::Color color, float width) override;
    void FillRect(const Visualization::RectF& rect, Visualization::Color color) override;
    void StrokePath(const Visualization::Path& path, Visualization::Color color, float width) override;
    void DrawText(std::string_view text, float x, float baseline, const Visualization::Font& font,
                  Visualization::Color color, Visualization::Align align) override;
    float MeasureText(std::string_view text, const Visualization::Font& font) const override;
    Visualization::FontMetrics GetMetrics(const Visualization::Font& font) const override;
    void Save() override;
    void ClipRect(const Visualization::RectF& rect) override;
    void Restore() override;

  private:
    struct FontEntry
    {
      Visualization::Font Spec;
      QFont Qt;
      std::optional<QFontMetricsF> Metrics;
    };

    // fonts are created per used size only, sizes are mostly the same between frames
    FontEntry& Lookup(const Visualization::Font& font) const;

  private:
    QPainter& Painter;
    mutable std::array<FontEntry, 32> Fonts;
    mutable std::size_t FontsCount = 0;
    mutable std::size_t NextEvicted = 0;
  };
}  // namespace Lhuna
