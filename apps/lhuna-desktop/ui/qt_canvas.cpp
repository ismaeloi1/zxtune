/**
 *
 * @file
 *
 * @brief Visualization::Canvas implementation over QPainter
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/lhuna-desktop/ui/qt_canvas.h"

#include <QtGui/QFontDatabase>
#include <QtGui/QPainterPath>

#include <vector>

namespace Lhuna
{
  QColor ToQt(Visualization::Color color)
  {
    return {color.R, color.G, color.B, color.A};
  }

  QPen MakePen(Visualization::Color color, float width, Qt::PenCapStyle cap = Qt::FlatCap,
               Qt::PenJoinStyle join = Qt::MiterJoin)
  {
    QPen pen(ToQt(color));
    pen.setWidthF(width);
    pen.setCapStyle(cap);
    pen.setJoinStyle(join);
    return pen;
  }

  QString ToQt(std::string_view text)
  {
    return QString::fromUtf8(text.data(), static_cast<qsizetype>(text.size()));
  }

  QtCanvas::QtCanvas(QPainter& painter)
    : Painter(painter)
  {}

  void QtCanvas::DrawLine(float x0, float y0, float x1, float y1, Visualization::Color color, float width)
  {
    Painter.setPen(MakePen(color, width));
    Painter.drawLine(QPointF(x0, y0), QPointF(x1, y1));
  }

  void QtCanvas::DrawSegments(const float* coords, std::size_t segments, Visualization::Color color, float width)
  {
    // gauges columns are axis aligned, so antialiasing only blurs them
    const auto hints = Painter.renderHints();
    Painter.setRenderHint(QPainter::Antialiasing, false);
    Painter.setPen(MakePen(color, width));
    std::vector<QLineF> lines(segments);
    for (std::size_t idx = 0; idx < segments; ++idx)
    {
      const auto* seg = coords + idx * 4;
      lines[idx] = QLineF(seg[0], seg[1], seg[2], seg[3]);
    }
    Painter.drawLines(lines.data(), static_cast<int>(lines.size()));
    Painter.setRenderHints(hints);
  }

  void QtCanvas::DrawPolyline(const float* xy, std::size_t points, Visualization::Color color, float width)
  {
    // bevel joins are much cheaper for raster engine than round ones and look the same for dense traces
    Painter.setPen(MakePen(color, width, Qt::FlatCap, Qt::BevelJoin));
    std::vector<QPointF> pts(points);
    for (std::size_t idx = 0; idx < points; ++idx)
    {
      pts[idx] = QPointF(xy[idx * 2], xy[idx * 2 + 1]);
    }
    // two points per pixel column (minimum and maximum of samples range) form vertical strokes, so antialiasing only
    // costs time there
    const auto dense = points > 1 && (xy[(points - 1) * 2] - xy[0]) / (points - 1) < 0.75f;
    const auto hints = Painter.renderHints();
    if (dense)
    {
      Painter.setRenderHint(QPainter::Antialiasing, false);
    }
    Painter.drawPolyline(pts.data(), static_cast<int>(pts.size()));
    Painter.setRenderHints(hints);
  }

  void QtCanvas::StrokeRect(const Visualization::RectF& rect, Visualization::Color color, float width)
  {
    Painter.setPen(MakePen(color, width));
    Painter.setBrush(Qt::NoBrush);
    Painter.drawRect(QRectF(rect.Left, rect.Top, rect.Width(), rect.Height()));
  }

  void QtCanvas::FillRect(const Visualization::RectF& rect, Visualization::Color color)
  {
    Painter.fillRect(QRectF(rect.Left, rect.Top, rect.Width(), rect.Height()), ToQt(color));
  }

  void QtCanvas::StrokePath(const Visualization::Path& path, Visualization::Color color, float width)
  {
    Painter.setPen(MakePen(color, width, Qt::RoundCap, Qt::RoundJoin));
    Painter.setBrush(Qt::NoBrush);
    const auto points = path.Points.size() / 2;
    for (std::size_t line = 0; line < path.Starts.size(); ++line)
    {
      const auto begin = path.Starts[line];
      const auto end = line + 1 < path.Starts.size() ? path.Starts[line + 1] : points;
      QPainterPath qpath;
      for (auto idx = begin; idx < end; ++idx)
      {
        const QPointF pt(path.Points[idx * 2], path.Points[idx * 2 + 1]);
        if (idx == begin)
        {
          qpath.moveTo(pt);
        }
        else
        {
          qpath.lineTo(pt);
        }
      }
      Painter.drawPath(qpath);
    }
  }

  void QtCanvas::DrawText(std::string_view text, float x, float baseline, const Visualization::Font& font,
                          Visualization::Color color, Visualization::Align align)
  {
    if (text.empty())
    {
      return;
    }
    auto& entry = Lookup(font);
    const auto str = ToQt(text);
    auto left = x;
    if (align != Visualization::Align::LEFT)
    {
      const auto width = entry.Metrics->horizontalAdvance(str);
      left -= align == Visualization::Align::RIGHT ? width : width / 2;
    }
    Painter.setFont(entry.Qt);
    Painter.setPen(ToQt(color));
    Painter.drawText(QPointF(left, baseline), str);
  }

  float QtCanvas::MeasureText(std::string_view text, const Visualization::Font& font) const
  {
    return static_cast<float>(Lookup(font).Metrics->horizontalAdvance(ToQt(text)));
  }

  Visualization::FontMetrics QtCanvas::GetMetrics(const Visualization::Font& font) const
  {
    const auto& metrics = *Lookup(font).Metrics;
    return {static_cast<float>(-metrics.ascent()), static_cast<float>(metrics.descent()),
            static_cast<float>(metrics.lineSpacing())};
  }

  void QtCanvas::Save()
  {
    Painter.save();
  }

  void QtCanvas::ClipRect(const Visualization::RectF& rect)
  {
    Painter.setClipRect(QRectF(rect.Left, rect.Top, rect.Width(), rect.Height()), Qt::IntersectClip);
  }

  void QtCanvas::Restore()
  {
    Painter.restore();
  }

  QtCanvas::FontEntry& QtCanvas::Lookup(const Visualization::Font& font) const
  {
    for (std::size_t idx = 0; idx < FontsCount; ++idx)
    {
      auto& entry = Fonts[idx];
      if (entry.Spec.Size == font.Size && entry.Spec.Face == font.Face && entry.Spec.Tabular == font.Tabular)
      {
        return entry;
      }
    }
    auto& entry = FontsCount < Fonts.size() ? Fonts[FontsCount++] : Fonts[NextEvicted++ % Fonts.size()];
    entry.Spec = font;
    entry.Qt = font.Face == Visualization::FontFace::MONOSPACE ? QFontDatabase::systemFont(QFontDatabase::FixedFont)
                                                               : QFont();
    entry.Qt.setPixelSize(std::max(1, qRound(font.Size)));
    if (font.Face == Visualization::FontFace::BOLD)
    {
      entry.Qt.setBold(true);
    }
#if QT_VERSION >= QT_VERSION_CHECK(6, 7, 0)
    if (font.Tabular)
    {
      entry.Qt.setFeature(QFont::Tag("tnum"), 1);
    }
#endif
    entry.Metrics.emplace(entry.Qt);
    return entry;
  }
}  // namespace Lhuna
