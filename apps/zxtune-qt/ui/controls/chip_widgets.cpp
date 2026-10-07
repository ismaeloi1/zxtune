/**
 *
 * @file
 *
 * @brief Hardware specific visualization of sound chips voices state
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/zxtune-qt/ui/controls/chip_widgets.h"

#include <QtGui/QFontMetricsF>
#include <QtGui/QPainterPath>

#include <algorithm>
#include <cmath>
#include <limits>
#include <numbers>
#include <vector>

namespace ChipWidgetsDetails
{
  // exponential approach gives fluid motion at any refresh rate
  const float EASING = 0.35f;

  float Ease(ChipWidgets::Smooth& smooth, uint_t idx, float target)
  {
    const auto value = smooth[idx] + (target - smooth[idx]) * EASING;
    smooth[idx] = value;
    return value;
  }

  QColor White(int alpha)
  {
    return {255, 255, 255, alpha};
  }

  QPen Stroke(int alpha, qreal width = 1.2)
  {
    QPen pen(White(alpha), width);
    pen.setJoinStyle(Qt::RoundJoin);
    pen.setCapStyle(Qt::RoundCap);
    return pen;
  }

  const int STROKE_ALPHA = 210;
  const int DIM_STROKE_ALPHA = 110;

  const char* const OPN_NAMES[] = {"S1", "S2", "S3", "S4"};
  const char* const OPL2_OPS[] = {"M", "C"};
  const char* const OPL4_OPS[] = {"1", "2", "3", "4"};
  const char* const OPL4_NAMES[] = {"FM-FM", "AM-FM", "FM-AM", "AM-AM"};
  const char* const RHYTHM_NAMES[] = {"Bass drum", "Snare", "Tom", "Cymbal", "Hi-hat"};
  const char* const PARTIAL_STATES[] = {"-", "ATK", "SUS", "REL"};
  const char* const ENV_MODES[] = {"REL", "ATK", "DEC", "SUS"};
  const char* const NOISE_RATES[] = {"N/512", "N/1024", "N/2048", "T3"};
  const float DUTIES[] = {0.125f, 0.25f, 0.5f, 0.75f};
  const char* const DUTY_NAMES[] = {"12%", "25%", "50%", "75%"};
  const bool LONG_NOISE[] = {true,  false, false, true, true,  false, true, false,
                             false, false, true,  true, false, true,  true, false};
  const bool SHORT_NOISE[] = {true, false, true, false, true, false, true, false,
                              true, false, true, false, true, false, true, false};
  const char* const NOTES[] = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

  template<class T, std::size_t N>
  T Get(const T (&arr)[N], uint_t idx, T def)
  {
    return idx < N ? arr[idx] : def;
  }

  QString NoteName(float hz)
  {
    if (hz <= 0)
    {
      return {};
    }
    const auto note = static_cast<int>(std::lround(12 * std::log2(hz / 440.0f) + 57));
    return note >= 0 && note <= 131 ? QString::fromLatin1("%1%2").arg(QLatin1String(NOTES[note % 12])).arg(note / 12)
                                    : QString();
  }

  // phase 0..1 of one period
  float OplWaveform(uint_t wave, float phase)
  {
    const auto pi = std::numbers::pi_v<float>;
    const auto s = std::sin(2 * pi * phase);
    switch (wave)
    {
    case 0:
      return s;
    case 1:
      return std::max(s, 0.0f);
    case 2:
      return std::abs(s);
    case 3:
      return int(phase * 4) % 2 == 0 ? std::abs(s) : 0.0f;
    case 4:
      return phase < 0.5f ? std::sin(4 * pi * phase) : 0.0f;
    case 5:
      return phase < 0.5f ? std::abs(std::sin(4 * pi * phase)) : 0.0f;
    case 6:
      return phase < 0.5f ? 1.0f : -1.0f;
    default:
      return phase < 0.5f ? 1.0f - phase * 2 : -(phase - 0.5f) * 2;
    }
  }

  QString PanText(uint_t pan)
  {
    switch (pan & 3)
    {
    case 2:
      return QLatin1String(" L");
    case 1:
      return QLatin1String(" R");
    case 0:
      return QLatin1String(" -");
    default:
      return {};
    }
  }

  QString StereoText(uint_t bits)
  {
    switch (bits & 3)
    {
    case 3:
      return {};
    case 2:
      return QLatin1String(" L");
    case 1:
      return QLatin1String(" R");
    default:
      return QLatin1String(" -");
    }
  }

  bool IsBlack(int key)
  {
    switch (key % 12)
    {
    case 1:
    case 3:
    case 6:
    case 8:
    case 10:
      return true;
    default:
      return false;
    }
  }
}  // namespace ChipWidgetsDetails

// Operators placement on grid and modulation connections (source << 4 | destination)
struct ChipWidgets::Schema
{
  std::array<float, 4> Col;
  std::array<float, 4> Row;
  std::vector<uint_t> Edges;
  uint_t Carriers = 0;

  int Cols(uint_t ops) const
  {
    return static_cast<int>(*std::max_element(Col.begin(), Col.begin() + ops)) + 1;
  }

  int Rows(uint_t ops) const
  {
    return std::max(static_cast<int>(*std::max_element(Row.begin(), Row.begin() + ops) + 1.0f), 1);
  }
};

namespace ChipWidgetsDetails
{
  using Schema = ChipWidgets::Schema;

  // see YM2612 datasheet, figure "Algorithm"
  const Schema OPN_ALGORITHMS[] = {
      {{0, 1, 2, 3}, {0, 0, 0, 0}, {0x01, 0x12, 0x23}},
      {{0, 0, 1, 2}, {0, 1, 0.5f, 0.5f}, {0x02, 0x12, 0x23}},
      {{1, 0, 1, 2}, {0, 1, 1, 0.5f}, {0x03, 0x12, 0x23}},
      {{0, 1, 1, 2}, {0, 0, 1, 0.5f}, {0x01, 0x13, 0x23}},
      {{0, 1, 0, 1}, {0, 0, 1, 1}, {0x01, 0x23}},
      {{0, 1, 1, 1}, {1, 0, 1, 2}, {0x01, 0x02, 0x03}},
      {{0, 1, 2, 3}, {0, 0, 0, 0}, {0x01}},
      {{0, 1, 2, 3}, {0, 0, 0, 0}, {}},
  };

  // OPL 2 operators: FM (modulator -> carrier) or AM (both heard)
  const Schema OPL2_ALGORITHMS[] = {
      {{0, 1}, {0, 0}, {0x01}, 2},
      {{0, 1}, {0, 0}, {}, 3},
  };

  // OPL3 4 operators: FM-FM 1*2*3*4, AM-FM 1+2*3*4, FM-AM 1*2+3*4, AM-AM 1+2*3+4
  const Schema OPL4_ALGORITHMS[] = {
      {{0, 1, 2, 3}, {0, 0, 0, 0}, {0x01, 0x12, 0x23}, 8},
      {{0, 0, 1, 2}, {0, 1, 1, 1}, {0x12, 0x23}, 9},
      {{0, 1, 0, 1}, {0, 0, 1, 1}, {0x01, 0x23}, 10},
      {{0, 0, 1, 0}, {0, 1, 1, 2}, {0x12}, 13},
  };
}  // namespace ChipWidgetsDetails

using namespace ChipWidgetsDetails;
using Module::VoiceState;
using ScopeData::VoiceGauges;

ChipWidgets::ChipWidgets(const QFont& text, const QFont& label)
  : Text(text)
  , Label(label)
  , TextAscent(QFontMetricsF(text).ascent())
  , TextSpacing(QFontMetricsF(text).lineSpacing())
{}

QString ChipWidgets::Summary(const VoiceGauges& g)
{
  switch (g.Kind())
  {
  case VoiceState::MT32_PART:
  {
    const auto text = QString::fromLatin1(g.Text().c_str());
    return text.isEmpty() ? QString(QLatin1Char('-')) : text;
  }
  case VoiceState::MT32_PARTIAL:
  {
    const QString state = QLatin1String(Get(PARTIAL_STATES, g.Field(0), ""));
    const auto owner = g.Field(1);
    if (g.Field(0) == 0)
    {
      return state;
    }
    else if (owner == 9)
    {
      return state + QLatin1String(" rhythm");
    }
    else if (g.HasFrequency())
    {
      return QString::fromLatin1("%1 P%2 %3").arg(state).arg(owner).arg(NoteName(g.Frequency()));
    }
    return QString::fromLatin1("%1 P%2").arg(state).arg(owner);
  }
  case VoiceState::OPL_RHYTHM:
    return QLatin1String(Get(RHYTHM_NAMES, g.Field(0), ""));
  case VoiceState::OPN_DAC:
    return QLatin1String(g.Field(0) ? "PCM" : "off");
  default:
    break;
  }
  const auto hasPitch = g.HasFrequency() && g.Frequency() > 0;
  const auto hz = static_cast<int>(std::lround(g.Frequency()));
  if (g.IsNoise() && !g.HasFrequency())
  {
    return QLatin1String("noise");
  }
  else if (!hasPitch && g.HasLevel())
  {
    return g.Level() > -96 ? QString::fromLatin1("%1dB").arg(std::lround(g.Level())) : QString::fromLatin1("off");
  }
  else if (!hasPitch)
  {
    return {};
  }
  else if (g.IsNoise())
  {
    return QString::fromLatin1("noise %1Hz").arg(hz);
  }
  return QString::fromLatin1("%1 %2Hz").arg(NoteName(g.Frequency())).arg(hz);
}

bool ChipWidgets::HasSchema(uint_t kind)
{
  return kind == VoiceState::OPN_FM || kind == VoiceState::OPL_2OP || kind == VoiceState::OPL_4OP;
}

bool ChipWidgets::Draw(QPainter& painter, const QRectF& rect, const VoiceGauges& g, Smooth& smooth) const
{
  painter.save();
  painter.setFont(Text);
  switch (g.Kind())
  {
  case VoiceState::OPN_FM:
    DrawOpnFm(painter, rect, g, smooth);
    break;
  case VoiceState::OPN_DAC:
    DrawDac(painter, rect, g, smooth);
    break;
  case VoiceState::PSG_TONE:
  case VoiceState::PSG_NOISE:
    DrawPsg(painter, rect, g, smooth);
    break;
  case VoiceState::OPL_2OP:
  case VoiceState::OPL_4OP:
    DrawOpl(painter, rect, g, smooth);
    break;
  case VoiceState::OPL_RHYTHM:
    DrawSegments(painter, rect, Ease(smooth, 0, g.Field(1) / 255.0f), 24);
    break;
  case VoiceState::SPC_DSP:
    DrawSpc(painter, rect, g, smooth);
    break;
  case VoiceState::MT32_PART:
    DrawMt32Part(painter, rect, g);
    break;
  case VoiceState::MT32_PARTIAL:
  {
    // brightness follows envelope phase
    const float targets[] = {0.0f, 1.0f, 0.7f, 0.3f};
    DrawSegments(painter, rect, Ease(smooth, 0, Get(targets, g.Field(0), 0.0f)), 10);
    break;
  }
  case VoiceState::NES_PULSE:
  case VoiceState::NES_TRIANGLE:
  case VoiceState::NES_NOISE:
  case VoiceState::NES_DMC:
    DrawNes(painter, rect, g, smooth);
    break;
  default:
    painter.restore();
    return false;
  }
  painter.restore();
  return true;
}

// FM operators schema of YM2612 algorithms, S1..S4 order as in datasheet
void ChipWidgets::DrawOpnFm(QPainter& painter, const QRectF& rect, const VoiceGauges& g, Smooth& smooth) const
{
  const auto alg = g.Field(0) & 7;
  float levels[4];
  for (uint_t op = 0; op < 4; ++op)
  {
    levels[op] = Ease(smooth, op, g.Field(2 + op) / 255.0f);
  }
  const auto info = QString::fromLatin1("ALG%1 FB%2%3").arg(alg).arg(g.Field(1)).arg(PanText(g.Field(7)));
  DrawSchema(painter, rect, OPN_ALGORITHMS[alg], 4, g.Field(6), levels, g.Field(10), g.Field(1), info, OPN_NAMES,
             nullptr);
}

// OPL2/OPL3 2 and 4 operators channels, FM or AM (additive) connections
void ChipWidgets::DrawOpl(QPainter& painter, const QRectF& rect, const VoiceGauges& g, Smooth& smooth) const
{
  if (g.Kind() == VoiceState::OPL_4OP)
  {
    const auto alg = g.Field(0) & 3;
    float levels[4];
    uint_t waves[4];
    for (uint_t op = 0; op < 4; ++op)
    {
      levels[op] = Ease(smooth, op, g.Field(2 + op) / 255.0f);
      waves[op] = g.Field(6 + op);
    }
    const auto info =
        QString::fromLatin1("%1 FB%2%3").arg(QLatin1String(OPL4_NAMES[alg])).arg(g.Field(1)).arg(PanText(g.Field(11)));
    const auto& schema = OPL4_ALGORITHMS[alg];
    DrawSchema(painter, rect, schema, 4, schema.Carriers, levels, g.Field(10) ? 0xf : 0, g.Field(1), info, OPL4_OPS,
               waves);
  }
  else
  {
    const auto additive = g.Field(0) & 1;
    float levels[4] = {};
    uint_t waves[4] = {};
    for (uint_t op = 0; op < 2; ++op)
    {
      levels[op] = Ease(smooth, op, g.Field(2 + op) / 255.0f);
      waves[op] = g.Field(4 + op);
    }
    const auto info = QString::fromLatin1("%1 FB%2%3")
                          .arg(QLatin1String(additive ? "AM" : "FM"))
                          .arg(g.Field(1))
                          .arg(PanText(g.Field(7)));
    const auto& schema = OPL2_ALGORITHMS[additive];
    DrawSchema(painter, rect, schema, 2, schema.Carriers, levels, g.Field(6) ? 3 : 0, g.Field(1), info, OPL2_OPS,
               waves);
  }
}

/*
 Operators as boxes filled by envelope level, modulation connections as lines,
 carriers connected to output bus, feedback loop on the first operator.
*/
void ChipWidgets::DrawSchema(QPainter& painter, const QRectF& rect, const Schema& schema, uint_t ops, uint_t carriers,
                             const float* levels, uint_t keys, uint_t feedback, const QString& info,
                             const char* const* names, const uint_t* waves) const
{
  DrawFit(painter, info, rect.left(), rect.top() + TextAscent, rect.width());
  // carrier followed by another operator in the same row cannot reach the bus horizontally,
  // so it goes down to a horizontal bus under the boxes
  uint_t downCarriers = 0;
  for (uint_t op = 0; op < ops; ++op)
  {
    if (0 == (carriers & (1 << op)))
    {
      continue;
    }
    for (uint_t other = 0; other < ops; ++other)
    {
      if (schema.Row[other] == schema.Row[op] && schema.Col[other] > schema.Col[op])
      {
        downCarriers |= 1 << op;
      }
    }
  }
  const qreal busGap = downCarriers ? 6 : 0;
  const QRectF area(QPointF(rect.left(), rect.top() + TextSpacing), QPointF(rect.right() - 14, rect.bottom() - busGap));
  if (area.height() < 12 || area.width() < 24)
  {
    return;
  }
  const auto cellW = area.width() / schema.Cols(ops);
  const auto cellH = area.height() / schema.Rows(ops);
  // stacked layouts are short, so allow wider boxes to keep labels readable
  const auto boxH = std::min(cellW * 0.7, cellH * 0.85);
  const auto boxW = std::min(cellW * 0.7, boxH * 1.6);
  const auto size = std::min(boxW, boxH);
  QRectF boxes[4];
  for (uint_t op = 0; op < ops; ++op)
  {
    const auto cx = area.left() + cellW * (schema.Col[op] + 0.5);
    const auto cy = area.top() + cellH * (schema.Row[op] + 0.5);
    boxes[op] = QRectF(cx - boxW / 2, cy - boxH / 2, boxW, boxH);
  }
  painter.setRenderHint(QPainter::Antialiasing);
  painter.setBrush(Qt::NoBrush);
  const auto stroke = Stroke(STROKE_ALPHA);
  const auto dimStroke = Stroke(DIM_STROKE_ALPHA);
  // modulation connections
  painter.setPen(dimStroke);
  for (const auto edge : schema.Edges)
  {
    const auto& src = boxes[edge >> 4];
    const auto& dst = boxes[edge & 15];
    QPainterPath path;
    if (std::abs(src.center().x() - dst.center().x()) < 1)
    {
      path.moveTo(src.center().x(), src.bottom());
      path.lineTo(dst.center().x(), dst.top());
    }
    else
    {
      const auto mid = (src.right() + dst.left()) / 2;
      path.moveTo(src.right(), src.center().y());
      path.lineTo(mid, src.center().y());
      path.lineTo(mid, dst.center().y());
      path.lineTo(dst.left(), dst.center().y());
    }
    painter.drawPath(path);
  }
  // output bus of carriers
  painter.setPen(stroke);
  const auto busX = area.right() + 6;
  auto busTop = std::numeric_limits<qreal>::max();
  auto busBottom = -std::numeric_limits<qreal>::max();
  const auto lowBusY = area.bottom() + busGap * 0.6;
  for (uint_t op = 0; op < ops; ++op)
  {
    const auto& b = boxes[op];
    if (downCarriers & (1 << op))
    {
      painter.drawLine(QPointF(b.center().x(), b.bottom()), QPointF(b.center().x(), lowBusY));
      painter.drawLine(QPointF(b.center().x(), lowBusY), QPointF(busX, lowBusY));
      busTop = std::min(busTop, lowBusY);
      busBottom = std::max(busBottom, lowBusY);
    }
    else if (carriers & (1 << op))
    {
      painter.drawLine(QPointF(b.right(), b.center().y()), QPointF(busX, b.center().y()));
      busTop = std::min(busTop, b.center().y());
      busBottom = std::max(busBottom, b.center().y());
    }
  }
  if (busTop <= busBottom)
  {
    painter.drawLine(QPointF(busX, busTop), QPointF(busX, busBottom));
    const auto outY = (busTop + busBottom) / 2;
    painter.drawLine(QPointF(busX, outY), QPointF(rect.right(), outY));
  }
  // feedback loop of the first operator
  if (feedback != 0)
  {
    const auto& b = boxes[0];
    const auto loop = size * 0.25;
    const auto y = b.center().y() - size * 0.2;
    QPainterPath path;
    path.moveTo(b.right(), y);
    path.lineTo(b.right() + loop, y);
    path.lineTo(b.right() + loop, b.top() - loop * 0.6);
    path.lineTo(b.left() - loop, b.top() - loop * 0.6);
    path.lineTo(b.left() - loop, y);
    path.lineTo(b.left(), y);
    painter.setPen(dimStroke);
    painter.drawPath(path);
  }
  for (uint_t op = 0; op < ops; ++op)
  {
    DrawOperator(painter, boxes[op], levels[op], carriers & (1 << op), keys & (1 << op), QLatin1String(names[op]),
                 waves ? waves + op : nullptr);
  }
}

void ChipWidgets::DrawOperator(QPainter& painter, const QRectF& b, float level, bool carrier, bool key,
                               const QString& name, const uint_t* wave) const
{
  // envelope level fill from bottom, carriers are brighter as they are heard directly
  const auto filled = b.height() * std::clamp(level, 0.0f, 1.0f);
  painter.fillRect(QRectF(b.left(), b.bottom() - filled, b.width(), filled), White(carrier ? 150 : 80));
  // pressed key makes operator outline bold
  auto outline = Stroke(carrier ? STROKE_ALPHA : DIM_STROKE_ALPHA, key ? 2.4 : 1.2);
  painter.setPen(outline);
  painter.setBrush(Qt::NoBrush);
  painter.drawRect(b);
  QFont label(Label);
  const auto baseSize = QFontMetricsF(Label).height();
  if (baseSize > b.height() * 0.42)
  {
    label.setPointSizeF(Label.pointSizeF() * b.height() * 0.42 / baseSize);
  }
  painter.setFont(label);
  painter.setPen(Qt::white);
  if (wave && b.height() > baseSize * 2.2)
  {
    DrawOplWave(painter, b, *wave);
    painter.setPen(Qt::white);
    const auto descent = QFontMetricsF(label).descent();
    painter.drawText(QRectF(b.left(), b.top(), b.width(), b.height() - 3 + descent), Qt::AlignHCenter | Qt::AlignBottom,
                     name);
  }
  else
  {
    painter.drawText(b, Qt::AlignCenter, name);
  }
  painter.setFont(Text);
}

// OPL waveforms: sine, half sine, abs sine, quarter sine, and OPL3 extensions
void ChipWidgets::DrawOplWave(QPainter& painter, const QRectF& b, uint_t wave) const
{
  const auto w = b.width() * 0.7;
  const auto h = b.height() * 0.22;
  const auto left = b.center().x() - w / 2;
  const auto cy = b.top() + b.height() * 0.32;
  const int steps = 24;
  QPolygonF line;
  for (int step = 0; step <= steps; ++step)
  {
    const auto phase = float(step) / steps;
    line << QPointF(left + w * phase, cy - OplWaveform(wave & 7, phase) * h);
  }
  painter.setPen(Stroke(STROKE_ALPHA));
  painter.drawPolyline(line);
}

void ChipWidgets::DrawDac(QPainter& painter, const QRectF& rect, const VoiceGauges& g, Smooth& smooth) const
{
  const auto value = (int(g.Field(1)) - 128) / 128.0f;
  DrawFit(painter, QLatin1String(g.Field(0) ? "DAC on" : "DAC off"), rect.left(), rect.top() + TextAscent,
          rect.width());
  DrawCenteredBar(painter, QRectF(QPointF(rect.left(), rect.top() + TextSpacing), rect.bottomRight()),
                  Ease(smooth, 0, value));
}

// SN76489: 4 bit attenuation in 2dB steps, 10 bit period or noise mode
void ChipWidgets::DrawPsg(QPainter& painter, const QRectF& rect, const VoiceGauges& g, Smooth& smooth) const
{
  const auto att = g.Field(0);
  QString info;
  if (g.Kind() == VoiceState::PSG_TONE)
  {
    const auto period = g.Field(1) | (g.Field(2) << 8);
    info =
        QString::fromLatin1("N=%1 ATT %2%3").arg(period, 3, 16, QLatin1Char('0')).arg(att).arg(StereoText(g.Field(3)));
  }
  else
  {
    info = QString::fromLatin1("%1 %2 ATT %3%4")
               .arg(QLatin1String(g.Field(1) ? "WHITE" : "PERIODIC"))
               .arg(QLatin1String(Get(NOISE_RATES, g.Field(2), "?")))
               .arg(att)
               .arg(StereoText(g.Field(3)));
  }
  DrawFit(painter, info.toUpper(), rect.left(), rect.top() + TextAscent, rect.width());
  DrawSegments(painter, QRectF(QPointF(rect.left(), rect.top() + TextSpacing), rect.bottomRight()),
               Ease(smooth, 0, (15 - std::min<int>(att, 15)) / 15.0f), 15);
}

// S-DSP: ENVX with envelope phase, ADSR or GAIN registers, sample source and rate
void ChipWidgets::DrawSpc(QPainter& painter, const QRectF& rect, const VoiceGauges& g, Smooth& smooth) const
{
  const auto envx = Ease(smooth, 0, g.Field(0) / 127.0f);
  const auto mode = g.Field(1);
  const auto adsr1 = g.Field(2);
  const auto adsr2 = g.Field(3);
  const auto pitch = g.Field(8) | (g.Field(9) << 8);
  const auto flags = g.Field(10);
  const auto barW = std::min<qreal>(rect.width() * 0.18, 18);
  DrawVerticalBar(painter, QRectF(rect.left(), rect.top(), barW, rect.height()), envx);
  const auto x = rect.left() + barW + 6;
  auto y = rect.top() + TextAscent;
  const auto envelope = mode == 4 ? QString::fromLatin1("GAIN %1").arg(g.Field(4), 2, 16, QLatin1Char('0'))
                                  : QString::fromLatin1("%1 A%2 D%3 S%4 R%5")
                                        .arg(QLatin1String(Get(ENV_MODES, mode, "?")))
                                        .arg(adsr1 & 15, 0, 16)
                                        .arg((adsr1 >> 4) & 7, 0, 16)
                                        .arg(adsr2 >> 5, 0, 16)
                                        .arg(adsr2 & 31, 2, 16, QLatin1Char('0'));
  DrawFit(painter, envelope.toUpper(), x, y, rect.right() - x);
  y += TextSpacing;
  DrawFit(painter,
          QString::fromLatin1("SRC %1 %2kHz")
              .arg(g.Field(5), 2, 16, QLatin1Char('0'))
              .arg(pitch * 32.0 / 4096, 0, 'f', 1)
              .toUpper(),
          x, y, rect.right() - x);
  y += TextSpacing;
  QString badges;
  if (flags & 1)
  {
    badges += QLatin1String("ECHO ");
  }
  if (flags & 2)
  {
    badges += QLatin1String("PMOD ");
  }
  if (flags & 4)
  {
    badges += QLatin1String("NOISE ");
  }
  if (y < rect.bottom())
  {
    const auto signedText = [](int val) {
      return (val >= 0 ? QLatin1String("+") : QLatin1String("")) + QString::number(val);
    };
    DrawFit(painter,
            QString::fromLatin1("L%1 R%2 %3")
                .arg(signedText(g.SignedField(6)))
                .arg(signedText(g.SignedField(7)))
                .arg(badges),
            x, y, rect.right() - x);
  }
}

void ChipWidgets::DrawMt32Part(QPainter& painter, const QRectF& rect, const VoiceGauges& g) const
{
  const auto notes = g.Field(0);
  DrawFit(painter, QString::fromLatin1("KEYS %1 PARTIALS %2").arg(notes).arg(g.Field(9)), rect.left(),
          rect.top() + TextAscent, rect.width());
  const QRectF kb(QPointF(rect.left(), rect.top() + TextSpacing), rect.bottomRight());
  if (kb.height() < 8)
  {
    return;
  }
  std::array<bool, 128> pressed = {};
  for (uint_t idx = 1; idx <= std::min<uint_t>(notes, 8); ++idx)
  {
    if (const auto key = g.Field(idx))
    {
      pressed[key & 127] = true;
    }
  }
  DrawKeyboard(painter, kb, pressed);
}

void ChipWidgets::DrawKeyboard(QPainter& painter, const QRectF& kb, const std::array<bool, 128>& pressed) const
{
  // C2..C7 covers most of melodic parts
  const int firstKey = 36;
  const int lastKey = 96;
  int whites = 0;
  for (int key = firstKey; key <= lastKey; ++key)
  {
    whites += !IsBlack(key);
  }
  const auto keyW = kb.width() / whites;
  painter.setRenderHint(QPainter::Antialiasing, false);
  painter.setPen(Stroke(DIM_STROKE_ALPHA, 1));
  int white = 0;
  for (int key = firstKey; key <= lastKey; ++key)
  {
    if (IsBlack(key))
    {
      continue;
    }
    const QRectF box(kb.left() + keyW * white, kb.top(), keyW, kb.height());
    painter.setBrush(pressed[key] ? QBrush(Qt::white) : QBrush(Qt::NoBrush));
    painter.drawRect(box);
    ++white;
  }
  white = 0;
  for (int key = firstKey; key <= lastKey; ++key)
  {
    if (!IsBlack(key))
    {
      ++white;
      continue;
    }
    const auto x = kb.left() + keyW * white;
    painter.setBrush(pressed[key] ? Qt::white : Qt::black);
    painter.drawRect(QRectF(x - keyW * 0.32, kb.top(), keyW * 0.64, kb.height() * 0.6));
  }
}

// 2A03: duty cycle glyph, 4 bit volume/envelope, sweep and DMC state
void ChipWidgets::DrawNes(QPainter& painter, const QRectF& rect, const VoiceGauges& g, Smooth& smooth) const
{
  const auto glyphW = std::min(rect.width() * 0.3, rect.height() * 1.6);
  const QRectF glyph(rect.left(), rect.top(), glyphW, rect.height());
  const auto x = glyph.right() + 6;
  const QRectF bar(QPointF(x, rect.top() + TextSpacing), rect.bottomRight());
  const auto y = rect.top() + TextAscent;
  switch (g.Kind())
  {
  case VoiceState::NES_PULSE:
  {
    const auto duty = g.Field(0) & 3;
    DrawPulseGlyph(painter, glyph, DUTIES[duty]);
    const auto period = g.Field(3) | (g.Field(4) << 8);
    const auto info = QString::fromLatin1("%1 %2%3 P=%4")
                          .arg(QLatin1String(DUTY_NAMES[duty]))
                          .arg(QLatin1String(g.Field(2) ? "VOL" : "ENV"))
                          .arg(QLatin1String(g.Field(6) ? " SWP" : ""))
                          .arg(QString::number(period, 16).toUpper().rightJustified(3, QLatin1Char('0')));
    DrawFit(painter, info, x, y, rect.right() - x);
    DrawSegments(painter, bar, Ease(smooth, 0, g.Field(1) / 15.0f), 15);
    break;
  }
  case VoiceState::NES_TRIANGLE:
  {
    DrawTriangleGlyph(painter, glyph);
    const auto period = g.Field(1) | (g.Field(2) << 8);
    DrawFit(painter,
            QString::fromLatin1("LIN %1 P=%2")
                .arg(QLatin1String(g.Field(0) ? "on" : "off"))
                .arg(QString::number(period, 16).toUpper().rightJustified(3, QLatin1Char('0'))),
            x, y, rect.right() - x);
    DrawSegments(painter, bar, Ease(smooth, 0, g.IsKeyOn() ? 1.0f : 0.0f), 15);
    break;
  }
  case VoiceState::NES_NOISE:
    DrawNoiseGlyph(painter, glyph, g.Field(1) != 0);
    DrawFit(painter,
            QString::fromLatin1("%1 RATE %2")
                .arg(QLatin1String(g.Field(1) ? "SHORT" : "LONG"))
                .arg(g.Field(2), 0, 16)
                .toUpper(),
            x, y, rect.right() - x);
    DrawSegments(painter, bar, Ease(smooth, 0, g.Field(0) / 15.0f), 15);
    break;
  default:
    DrawFit(painter,
            QString::fromLatin1("RATE %1%2%3")
                .arg(QString::number(g.Field(1), 16).toUpper())
                .arg(QLatin1String(g.Field(3) ? " LOOP" : ""))
                .arg(QLatin1String(g.Field(2) ? " PLAY" : "")),
            glyph.left(), y, rect.right() - glyph.left());
    DrawSegments(painter, QRectF(QPointF(glyph.left(), bar.top()), rect.bottomRight()),
                 Ease(smooth, 0, g.Field(0) / 127.0f), 32);
    break;
  }
}

void ChipWidgets::DrawPulseGlyph(QPainter& painter, const QRectF& r, float duty) const
{
  const auto top = r.top() + r.height() * 0.25;
  const auto bottom = r.bottom() - r.height() * 0.25;
  QPolygonF line;
  line << QPointF(r.left(), bottom);
  for (int period = 0; period < 2; ++period)
  {
    const auto start = r.left() + r.width() / 2 * period;
    const auto high = start + r.width() / 2 * duty;
    line << QPointF(start, bottom) << QPointF(start, top) << QPointF(high, top) << QPointF(high, bottom);
  }
  line << QPointF(r.right(), bottom);
  painter.setRenderHint(QPainter::Antialiasing);
  painter.setPen(Stroke(STROKE_ALPHA));
  painter.drawPolyline(line);
}

void ChipWidgets::DrawTriangleGlyph(QPainter& painter, const QRectF& r) const
{
  const auto top = r.top() + r.height() * 0.25;
  const auto bottom = r.bottom() - r.height() * 0.25;
  QPolygonF line;
  line << QPointF(r.left(), bottom) << QPointF(r.left() + r.width() * 0.25, top)
       << QPointF(r.left() + r.width() * 0.75, bottom) << QPointF(r.right(), top);
  painter.setRenderHint(QPainter::Antialiasing);
  painter.setPen(Stroke(STROKE_ALPHA));
  painter.drawPolyline(line);
}

// short mode has 93 steps period, so drawn regular
void ChipWidgets::DrawNoiseGlyph(QPainter& painter, const QRectF& r, bool shortMode) const
{
  const auto top = r.top() + r.height() * 0.25;
  const auto bottom = r.bottom() - r.height() * 0.25;
  const auto& pattern = shortMode ? SHORT_NOISE : LONG_NOISE;
  const int size = std::size(LONG_NOISE);
  QPolygonF line;
  for (int idx = 0; idx < size; ++idx)
  {
    const auto y = pattern[idx] ? top : bottom;
    line << QPointF(r.left() + r.width() * idx / size, y) << QPointF(r.left() + r.width() * (idx + 1) / size, y);
  }
  painter.setRenderHint(QPainter::Antialiasing);
  painter.setPen(Stroke(STROKE_ALPHA));
  painter.drawPolyline(line);
}

void ChipWidgets::DrawSegments(QPainter& painter, const QRectF& r, float value, uint_t segments) const
{
  if (r.height() < 3)
  {
    return;
  }
  const qreal gap = 1;
  const auto segW = (r.width() - gap * (segments - 1)) / segments;
  const auto lit = std::clamp(value, 0.0f, 1.0f) * segments;
  for (uint_t seg = 0; seg < segments; ++seg)
  {
    const auto part = std::clamp(lit - seg, 0.0f, 1.0f);
    painter.fillRect(QRectF(r.left() + (segW + gap) * seg, r.top(), segW, r.height()), White(int(40 + 200 * part)));
  }
}

void ChipWidgets::DrawVerticalBar(QPainter& painter, const QRectF& r, float value) const
{
  painter.setPen(Stroke(DIM_STROKE_ALPHA, 1));
  painter.setBrush(Qt::NoBrush);
  painter.drawRect(r);
  const auto filled = r.height() * std::clamp(value, 0.0f, 1.0f);
  painter.fillRect(QRectF(r.left(), r.bottom() - filled, r.width(), filled), White(200));
}

void ChipWidgets::DrawCenteredBar(QPainter& painter, const QRectF& r, float value) const
{
  painter.setPen(Stroke(DIM_STROKE_ALPHA, 1));
  painter.setBrush(Qt::NoBrush);
  painter.drawRect(r);
  const auto cx = r.center().x();
  const auto x = cx + r.width() / 2 * std::clamp(value, -1.0f, 1.0f);
  painter.fillRect(QRectF(QPointF(std::min(cx, x), r.top()), QPointF(std::max(cx, x), r.bottom())), White(200));
  painter.drawLine(QPointF(cx, r.top()), QPointF(cx, r.bottom()));
}

// shrinks text (down to 70%) and then cuts it to fit width
void ChipWidgets::DrawFit(QPainter& painter, const QString& str, qreal x, qreal y, qreal maxWidth) const
{
  if (maxWidth <= 0)
  {
    return;
  }
  painter.setPen(White(230));
  const auto width = QFontMetricsF(Text).horizontalAdvance(str);
  if (width <= maxWidth)
  {
    painter.setFont(Text);
    painter.drawText(QPointF(x, y), str);
    return;
  }
  QFont font(Text);
  font.setPointSizeF(Text.pointSizeF() * std::max(maxWidth / width, 0.7));
  painter.setFont(font);
  painter.drawText(QPointF(x, y), QFontMetricsF(font).elidedText(str, Qt::ElideRight, maxWidth));
  painter.setFont(Text);
}
