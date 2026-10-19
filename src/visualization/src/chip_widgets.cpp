/**
 *
 * @file
 *
 * @brief Hardware specific visualization of sound chips voices state implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "visualization/chip_widgets.h"

#include "visualization/format.h"

#include <algorithm>
#include <cmath>
#include <numbers>
#include <vector>

namespace Visualization
{
  // for ~30fps updates of dashboard
  const float WIDGETS_EASING = 0.8f;
  const int MAX_SCHEMA_ROWS = 2;
  // minimal height of operator box with readable label
  const float MIN_BOX_DP = 16;
  // the widest line of algorithm and feedback info
  const char* const INFO_TEMPLATE = "FB7 -";
  /*
   * Operators are numbered as in Furnace tracker (OP1..OP4), Nuked-OPN2 and YM2151 datasheet (M1 C1 M2 C2): by
   * connection order (OP1 > OP2 > OP3 > OP4 in ALG 0), not registers order. Emulation cores report levels and keys
   * already reordered. OPL operators follow registers order (OPL3 datasheet).
   */
  const char* const OP_PREFIX = "OP";

  struct ChipWidgets::Schema
  {
    //! operators placement on grid
    std::vector<float> Col;
    std::vector<float> Row;
    //! modulation connections (source shl 4 or destination)
    std::vector<int> Edges;
    int Carriers = 0;

    int Cols() const
    {
      return static_cast<int>(*std::max_element(Col.begin(), Col.end())) + 1;
    }

    int Rows() const
    {
      return std::max(static_cast<int>(*std::max_element(Row.begin(), Row.end()) + 1.f), 1);
    }
  };

  /*
   * Furnace drawAlgorithm, MAME fmopn.c setup_connection, Nuked-OPN2 fm_algorithm. Cards are wide and short, so at
   * most two rows are used: algorithms with operators in parallel (5..7) are drawn transposed - modulator above,
   * carriers side by side with output bus below.
   */
  const std::vector<ChipWidgets::Schema>& OpnAlgorithms()
  {
    using Schema = ChipWidgets::Schema;
    static const std::vector<Schema> INSTANCE = {
        // 1 > 2 > 3 > 4
        Schema{{0, 1, 2, 3}, {0, 0, 0, 0}, {0x01, 0x12, 0x23}},
        // (1 + 2) > 3 > 4
        Schema{{0, 0, 1, 2}, {0, 1, 0.5f, 0.5f}, {0x02, 0x12, 0x23}},
        // 1 + (2 > 3) > 4
        Schema{{1, 0, 1, 2}, {0, 1, 1, 0.5f}, {0x03, 0x12, 0x23}},
        // (1 > 2) + 3 > 4
        Schema{{0, 1, 1, 2}, {0, 0, 1, 0.5f}, {0x01, 0x13, 0x23}},
        // (1 > 2) + (3 > 4)
        Schema{{0, 1, 0, 1}, {0, 0, 1, 1}, {0x01, 0x23}},
        // 1 > (2 + 3 + 4)
        Schema{{1, 0, 1, 2}, {0, 1, 1, 1}, {0x01, 0x02, 0x03}},
        // (1 > 2) + 3 + 4
        Schema{{0, 0, 1, 2}, {0, 1, 1, 1}, {0x01}},
        // 1 + 2 + 3 + 4
        Schema{{0, 1, 2, 3}, {0, 0, 0, 0}, {}},
    };
    return INSTANCE;
  }

  // OPL 2 operators: FM (modulator -> carrier) or AM (both heard)
  const std::vector<ChipWidgets::Schema>& Opl2Algorithms()
  {
    using Schema = ChipWidgets::Schema;
    static const std::vector<Schema> INSTANCE = {
        Schema{{0, 1}, {0, 0}, {0x01}, 2},
        Schema{{0, 0}, {0, 1}, {}, 3},
    };
    return INSTANCE;
  }

  // OPL3 4 operators (CNT of the first and second pairs), see OPL3 datasheet and Furnace FM_ALGS_4OP_OPL
  const std::vector<ChipWidgets::Schema>& Opl4Algorithms()
  {
    using Schema = ChipWidgets::Schema;
    static const std::vector<Schema> INSTANCE = {
        // FM-FM: 1 > 2 > 3 > 4
        Schema{{0, 1, 2, 3}, {0, 0, 0, 0}, {0x01, 0x12, 0x23}, 8},
        // AM-FM: 1 + (2 > 3 > 4)
        Schema{{0, 0, 1, 2}, {0, 1, 1, 1}, {0x12, 0x23}, 9},
        // FM-AM: (1 > 2) + (3 > 4)
        Schema{{0, 1, 0, 1}, {0, 0, 1, 1}, {0x01, 0x23}, 10},
        // AM-AM: 1 + (2 > 3) + 4
        Schema{{0, 0, 1, 2}, {0, 1, 1, 1}, {0x12}, 13},
    };
    return INSTANCE;
  }

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

  // exponential approach gives fluid motion at any refresh rate
  float Ease(float* smooth, int idx, float target)
  {
    const auto value = smooth[idx] + (target - smooth[idx]) * WIDGETS_EASING;
    smooth[idx] = value;
    return value;
  }

  const char* PanText(int pan)
  {
    switch (pan & 3)
    {
    case 2:
      return " L";
    case 1:
      return " R";
    case 0:
      return " -";
    default:
      return "";
    }
  }

  const char* StereoText(int bits)
  {
    switch (bits & 3)
    {
    case 3:
      return "";
    case 2:
      return " L";
    case 1:
      return " R";
    default:
      return " -";
    }
  }

  bool IsBlackKey(int key)
  {
    const auto note = key % 12;
    return note == 1 || note == 3 || note == 6 || note == 8 || note == 10;
  }

  ChipWidgets::ChipWidgets(float density, float textSize)
    : Density(density)
    , StrokeWidth(density * 1.2f)
    , TextFont{textSize, FontFace::MONOSPACE, false}
    , LabelFont{textSize * 0.85f, FontFace::BOLD, false}
    , BaseTextSize(textSize)
    , BaseLabelSize(textSize * 0.85f)
  {}

  bool ChipWidgets::Draw(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth, TextMemo& texts)
  {
    Memo = &texts;
    switch (g.Kind())
    {
    case VoiceKind::OPN_FM:
      DrawOpnFm(canvas, rect, g, smooth);
      break;
    case VoiceKind::OPN_DAC:
      DrawDac(canvas, rect, g, smooth);
      break;
    case VoiceKind::PSG_TONE:
    case VoiceKind::PSG_NOISE:
      DrawPsg(canvas, rect, g, smooth);
      break;
    case VoiceKind::OPL_2OP:
    case VoiceKind::OPL_4OP:
      DrawOpl(canvas, rect, g, smooth);
      break;
    case VoiceKind::OPL_RHYTHM:
      DrawSegments(canvas, rect, std::clamp(Ease(smooth, 0, g.Field(1) / 255.f), 0.f, 1.f), 24);
      break;
    case VoiceKind::SPC_DSP:
      DrawSpc(canvas, rect, g, smooth);
      break;
    case VoiceKind::MT32_PART:
      DrawMt32Part(canvas, rect, g);
      break;
    case VoiceKind::MT32_PARTIAL:
      DrawMt32Partial(canvas, rect, g, smooth);
      break;
    case VoiceKind::NES_PULSE:
    case VoiceKind::NES_TRIANGLE:
    case VoiceKind::NES_NOISE:
    case VoiceKind::NES_DMC:
      DrawNes(canvas, rect, g, smooth);
      break;
    default:
      return false;
    }
    return true;
  }

  // FM operators schema of OPN/OPM algorithms
  void ChipWidgets::DrawOpnFm(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth)
  {
    const auto alg = g.Field(0) & 7;
    float levels[4];
    for (int op = 0; op < 4; ++op)
    {
      levels[op] = Ease(smooth, op, g.Field(2 + op) / 255.f);
    }
    const auto& info = Memo->Get(0, alg | (g.Field(1) << 8) | (g.Field(7) << 16),
                                 [&] { return Format("ALG%d FB%d%s", alg, g.Field(1), PanText(g.Field(7))); });
    DrawSchema(canvas, rect, OpnAlgorithms()[alg], g.Field(6), levels, 4, g.Field(10), g.Field(1), info, nullptr);
  }

  // OPL2/OPL3 2 and 4 operators channels, FM or AM (additive) connections
  void ChipWidgets::DrawOpl(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth)
  {
    float levels[4];
    int waves[4];
    if (g.Kind() == VoiceKind::OPL_4OP)
    {
      const auto alg = g.Field(0) & 3;
      for (int op = 0; op < 4; ++op)
      {
        levels[op] = Ease(smooth, op, g.Field(2 + op) / 255.f);
        waves[op] = g.Field(6 + op);
      }
      const auto& info = Memo->Get(0, alg | (g.Field(1) << 8) | (g.Field(11) << 16), [&] {
        return Format("%s FB%d%s", OPL4_NAMES[alg], g.Field(1), PanText(g.Field(11)));
      });
      const auto& schema = Opl4Algorithms()[alg];
      DrawSchema(canvas, rect, schema, schema.Carriers, levels, 4, g.Field(10) != 0 ? 0xf : 0, g.Field(1), info, waves);
    }
    else
    {
      const auto additive = g.Field(0) & 1;
      for (int op = 0; op < 2; ++op)
      {
        levels[op] = Ease(smooth, op, g.Field(2 + op) / 255.f);
        waves[op] = g.Field(4 + op);
      }
      const auto& info = Memo->Get(1, additive | (g.Field(1) << 8) | (g.Field(7) << 16), [&] {
        return Format("%s FB%d%s", additive ? "AM" : "FM", g.Field(1), PanText(g.Field(7)));
      });
      const auto& schema = Opl2Algorithms()[additive];
      DrawSchema(canvas, rect, schema, schema.Carriers, levels, 2, g.Field(6) != 0 ? 3 : 0, g.Field(1), info, waves);
    }
  }

  /**
   * Operators as boxes filled by envelope level, modulation connections as lines, carriers connected to output bus,
   * feedback loop on the first operator.
   */
  void ChipWidgets::DrawSchema(Canvas& canvas, const RectF& rect, const Schema& schema, int carriers,
                               const float* levels, int ops, int keys, int feedback, const std::string& info,
                               const int* waves)
  {
    // space for buses and feedback loop is reserved always to keep boxes of all algorithms the same
    const auto busGap = Density * 5;
    const auto topGap = Density * 5;
    const auto loopGap = Density * 6;
    auto area = DrawSchemaInfo(canvas, rect, info);
    area.Left += loopGap;
    area.Top += topGap;
    area.Right -= Density * 14;
    area.Bottom -= busGap;
    if (area.Height() < Density * 12 || area.Width() < Density * 24)
    {
      return;
    }
    // Boxes are the same for all algorithms of chip (as if all operators are in a row), so live algorithm changes
    // do not resize them. Short cards cannot fit two rows of readable boxes with connections between them, so all
    // operators are drawn in a row in numbers order then.
    const auto boxW = area.Width() / ops * 0.7f;
    const auto rowH = area.Height() / MAX_SCHEMA_ROWS * 0.68f;
    const auto compact = rowH < Density * MIN_BOX_DP;
    const auto boxH = std::min(boxW, compact ? area.Height() * 0.85f : rowH);
    const auto cols = compact ? ops : schema.Cols();
    const auto rows = compact ? 1 : schema.Rows();
    const auto cellW = area.Width() / cols;
    const auto cellH = area.Height() / rows;
    // short boxes are wider to fit labels
    const auto width = std::min(boxW, boxH * 2.2f);
    for (int op = 0; op < ops; ++op)
    {
      const auto cx = area.Left + cellW * ((compact ? static_cast<float>(op) : schema.Col[op]) + 0.5f);
      const auto cy = area.Top + cellH * ((compact ? 0.f : schema.Row[op]) + 0.5f);
      Boxes[op].Set(cx - width / 2, cy - boxH / 2, cx + width / 2, cy + boxH / 2);
    }
    // Several carriers side by side (e.g. ALG 5..7 drawn as transposed datasheet figures) cannot reach the bus at
    // right horizontally, so all carriers of such row go down to a bus under the boxes.
    int downRows = 0;
    const auto sameRowRight = [&](int op, int mask) {
      for (int other = 0; other < ops; ++other)
      {
        if ((mask & (1 << other)) && Boxes[other].CenterY() == Boxes[op].CenterY()
            && Boxes[other].Left > Boxes[op].Left)
        {
          return true;
        }
      }
      return false;
    };
    for (int op = 0; op < ops; ++op)
    {
      if ((carriers & (1 << op)) && sameRowRight(op, 0xf))
      {
        downRows |= 1 << op;
      }
    }
    if (downRows != 0)
    {
      for (int op = 0; op < ops; ++op)
      {
        if (!(carriers & (1 << op)))
        {
          continue;
        }
        for (int other = 0; other < ops; ++other)
        {
          if ((downRows & (1 << other)) && Boxes[other].CenterY() == Boxes[op].CenterY())
          {
            downRows |= 1 << op;
          }
        }
      }
    }
    // modulation connections
    const auto arcY = area.Top - topGap * 0.7f;
    for (const auto edge : schema.Edges)
    {
      const auto& src = Boxes[edge >> 4];
      const auto& dst = Boxes[edge & 15];
      Shape.Reset();
      if (dst.Top >= src.Bottom)
      {
        // modulator above modulated operators, as in transposed datasheet figures
        const auto mid = (src.Bottom + dst.Top) / 2;
        Shape.MoveTo(src.CenterX(), src.Bottom);
        Shape.LineTo(src.CenterX(), mid);
        Shape.LineTo(dst.CenterX(), mid);
        Shape.LineTo(dst.CenterX(), dst.Top);
      }
      else if (src.CenterY() == dst.CenterY() && dst.Left - src.Right > cellW)
      {
        // over the operators between
        const auto x = src.Right + (cellW - width) / 2;
        const auto y = src.CenterY() - src.Height() / 4;
        Shape.MoveTo(src.Right, y);
        Shape.LineTo(x, y);
        Shape.LineTo(x, arcY);
        Shape.LineTo(dst.CenterX(), arcY);
        Shape.LineTo(dst.CenterX(), dst.Top);
      }
      else
      {
        const auto mid = (src.Right + dst.Left) / 2;
        Shape.MoveTo(src.Right, src.CenterY());
        Shape.LineTo(mid, src.CenterY());
        Shape.LineTo(mid, dst.CenterY());
        Shape.LineTo(dst.Left, dst.CenterY());
      }
      canvas.StrokePath(Shape, DimColor, StrokeWidth);
    }
    // output bus of carriers
    const auto busX = area.Right + Density * 6;
    auto busTop = 1e9f;
    auto busBottom = -1e9f;
    const auto lowBusY = area.Bottom + busGap * 0.6f;
    for (int op = 0; op < ops; ++op)
    {
      const auto& b = Boxes[op];
      if (!(carriers & (1 << op)))
      {
        continue;
      }
      if (downRows & (1 << op))
      {
        canvas.DrawLine(b.CenterX(), b.Bottom, b.CenterX(), lowBusY, StrokeColor, StrokeWidth);
        canvas.DrawLine(b.CenterX(), lowBusY, busX, lowBusY, StrokeColor, StrokeWidth);
        busTop = std::min(busTop, lowBusY);
        busBottom = std::max(busBottom, lowBusY);
      }
      else
      {
        canvas.DrawLine(b.Right, b.CenterY(), busX, b.CenterY(), StrokeColor, StrokeWidth);
        busTop = std::min(busTop, b.CenterY());
        busBottom = std::max(busBottom, b.CenterY());
      }
    }
    if (busTop <= busBottom)
    {
      canvas.DrawLine(busX, busTop, busX, busBottom, StrokeColor, StrokeWidth);
      const auto outY = (busTop + busBottom) / 2;
      canvas.DrawLine(busX, outY, rect.Right, outY, StrokeColor, StrokeWidth);
      // arrow marks output of the channel
      const auto arrow = Density * 3;
      Shape.Reset();
      Shape.MoveTo(rect.Right - arrow, outY - arrow);
      Shape.LineTo(rect.Right, outY);
      Shape.LineTo(rect.Right - arrow, outY + arrow);
      canvas.StrokePath(Shape, StrokeColor, StrokeWidth);
    }
    // Feedback loop from output (top) back to input (left) of the first operator. Connections from it start at
    // right or bottom sides, so they never cross the loop.
    if (feedback != 0)
    {
      const auto& b = Boxes[0];
      const auto top = b.Top - topGap * 0.45f;
      const auto left = b.Left - loopGap * 0.7f;
      Shape.Reset();
      Shape.MoveTo(b.CenterX(), b.Top);
      Shape.LineTo(b.CenterX(), top);
      Shape.LineTo(left, top);
      Shape.LineTo(left, b.CenterY());
      Shape.LineTo(b.Left, b.CenterY());
      canvas.StrokePath(Shape, DimColor, StrokeWidth);
    }
    for (int op = 0; op < ops; ++op)
    {
      DrawOperator(canvas, Boxes[op], levels[op], carriers & (1 << op), keys & (1 << op), op,
                   waves ? waves + op : nullptr);
    }
  }

  /**
   * Algorithm and feedback are placed at top or (if there's no vertical space to spare) at left in two lines
   * @return area left for schema
   */
  RectF ChipWidgets::DrawSchemaInfo(Canvas& canvas, const RectF& rect, const std::string& info)
  {
    const auto metrics = canvas.GetMetrics(TextFont);
    RectF area;
    if (rect.Width() < rect.Height() * 3)
    {
      DrawFit(canvas, info, rect.Left, rect.Top - metrics.Ascent, rect.Width());
      area.Set(rect.Left, rect.Top + metrics.Spacing, rect.Right, rect.Bottom);
      return area;
    }
    const auto scale = std::min(1.f, rect.Height() / 2 / metrics.Spacing);
    auto font = TextFont;
    font.Size = BaseTextSize * scale;
    const auto infoWidth = canvas.MeasureText(INFO_TEMPLATE, font) + Density * 4;
    const auto split = std::min(info.find(' '), info.size());
    const auto lineHeight = metrics.Spacing * scale;
    const auto y = rect.CenterY() - lineHeight - metrics.Ascent * scale;
    const std::string_view view(info);
    canvas.DrawText(view.substr(0, split), rect.Left, y, font, Color::White(230), Align::LEFT);
    if (split < view.size())
    {
      canvas.DrawText(view.substr(split + 1), rect.Left, y + lineHeight, font, Color::White(230), Align::LEFT);
    }
    area.Set(rect.Left + infoWidth, rect.Top, rect.Right, rect.Bottom);
    return area;
  }

  void ChipWidgets::DrawOperator(Canvas& canvas, const RectF& b, float level, bool carrier, bool key, int op,
                                 const int* wave)
  {
    // envelope level fill from bottom, carriers are brighter as they are heard directly
    RectF box;
    box.Set(b.Left, b.Bottom - b.Height() * std::clamp(level, 0.f, 1.f), b.Right, b.Bottom);
    canvas.FillRect(box, Color::White(carrier ? 150 : 80));
    // pressed key makes operator outline bold
    canvas.StrokeRect(b, carrier ? StrokeColor : DimColor, StrokeWidth * (key ? 2 : 1));
    const auto withWave = wave != nullptr && b.Height() > BaseLabelSize * 2.2f;
    auto font = LabelFont;
    font.Size = std::min(BaseLabelSize, b.Height() * (withWave ? 0.42f : 0.6f));
    const auto name = OP_PREFIX + std::to_string(op + 1);
    // number only (as in Furnace algorithm figures) for narrow boxes
    const auto shown = canvas.MeasureText(name, font) <= b.Width() - Density * 4 ? name : std::to_string(op + 1);
    if (withWave)
    {
      DrawOplWave(canvas, b, *wave);
      canvas.DrawText(shown, b.CenterX(), b.Bottom - Density * 3, font, Color::White(255), Align::CENTER);
    }
    else
    {
      const auto metrics = canvas.GetMetrics(font);
      canvas.DrawText(shown, b.CenterX(), b.CenterY() - (metrics.Ascent + metrics.Descent) / 2, font, Color::White(255),
                      Align::CENTER);
    }
  }

  // OPL waveforms: sine, half sine, abs sine, quarter sine, and OPL3 extensions
  void ChipWidgets::DrawOplWave(Canvas& canvas, const RectF& b, int wave)
  {
    const auto w = b.Width() * 0.7f;
    const auto h = b.Height() * 0.22f;
    const auto left = b.CenterX() - w / 2;
    const auto cy = b.Top + b.Height() * 0.32f;
    Shape.Reset();
    const int steps = 24;
    for (int step = 0; step <= steps; ++step)
    {
      const auto phase = static_cast<float>(step) / steps;
      const auto y = OplWaveform(wave & 7, phase);
      const auto x = left + w * phase;
      if (step == 0)
      {
        Shape.MoveTo(x, cy - y * h);
      }
      else
      {
        Shape.LineTo(x, cy - y * h);
      }
    }
    canvas.StrokePath(Shape, StrokeColor, StrokeWidth);
  }

  void ChipWidgets::DrawDac(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth)
  {
    const auto metrics = canvas.GetMetrics(TextFont);
    const auto value = (g.Field(1) - 128) / 128.f;
    DrawFit(canvas, g.Field(0) != 0 ? "DAC on" : "DAC off", rect.Left, rect.Top - metrics.Ascent, rect.Width());
    RectF bar;
    bar.Set(rect.Left, rect.Top + metrics.Spacing, rect.Right, rect.Bottom);
    DrawCenteredBar(canvas, bar, Ease(smooth, 0, value));
  }

  // SN76489: 4 bit attenuation in 2dB steps, 10 bit period or noise mode
  void ChipWidgets::DrawPsg(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth)
  {
    const auto metrics = canvas.GetMetrics(TextFont);
    const auto att = g.Field(0);
    const int64_t key = g.Kind() | (att << 8) | (g.Field(1) << 16) | (int64_t(g.Field(2)) << 24)
                        | (int64_t(g.Field(3)) << 32);
    const auto& info = Memo->Get(0, key, [&] {
      if (g.Kind() == VoiceKind::PSG_TONE)
      {
        const auto period = g.Field(1) | (g.Field(2) << 8);
        return Format("N=%03X ATT %d%s", period, att, StereoText(g.Field(3)));
      }
      return Format("%s %s ATT %d%s", g.Field(1) != 0 ? "WHITE" : "PERIODIC",
                    g.Field(2) < 4 ? NOISE_RATES[g.Field(2)] : "?", att, StereoText(g.Field(3)));
    });
    DrawFit(canvas, info, rect.Left, rect.Top - metrics.Ascent, rect.Width());
    RectF bar;
    bar.Set(rect.Left, rect.Top + metrics.Spacing, rect.Right, rect.Bottom);
    DrawSegments(canvas, bar, Ease(smooth, 0, (15 - att) / 15.f), 15);
  }

  // S-DSP: ENVX with envelope phase, ADSR or GAIN registers, sample source and rate
  void ChipWidgets::DrawSpc(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth)
  {
    const auto metrics = canvas.GetMetrics(TextFont);
    const auto envx = Ease(smooth, 0, g.Field(0) / 127.f);
    const auto mode = g.Field(1);
    const auto adsr1 = g.Field(2);
    const auto adsr2 = g.Field(3);
    const auto pitch = g.Field(8) | (g.Field(9) << 8);
    const auto flags = g.Field(10);
    const auto barW = std::min(rect.Width() * 0.18f, Density * 18);
    RectF bar;
    bar.Set(rect.Left, rect.Top, rect.Left + barW, rect.Bottom);
    DrawVerticalBar(canvas, bar, envx);
    const auto x = bar.Right + Density * 6;
    auto y = rect.Top - metrics.Ascent;
    const int64_t envKey = mode | (g.Field(4) << 8) | (adsr1 << 16) | (int64_t(adsr2) << 24);
    const auto& envelope = Memo->Get(0, envKey, [&] {
      if (mode == 4)
      {
        return Format("GAIN %02X", g.Field(4));
      }
      return Format("%s A%X D%X S%X R%02X", mode < 4 ? ENV_MODES[mode] : "?", adsr1 & 15, (adsr1 >> 4) & 7, adsr2 >> 5,
                    adsr2 & 31);
    });
    DrawFit(canvas, envelope, x, y, rect.Right - x);
    y += metrics.Spacing;
    const auto& source = Memo->Get(1, g.Field(5) | (int64_t(pitch) << 8),
                                   [&] { return Format("SRC %02X %.1fkHz", g.Field(5), pitch * 32.f / 4096.f); });
    DrawFit(canvas, source, x, y, rect.Right - x);
    y += metrics.Spacing;
    if (y < rect.Bottom)
    {
      const int64_t panKey = flags | (g.Field(6) << 8) | (g.Field(7) << 16);
      const auto& pan = Memo->Get(2, panKey, [&] {
        std::string badges;
        if (flags & 1)
        {
          badges += "ECHO ";
        }
        if (flags & 2)
        {
          badges += "PMOD ";
        }
        if (flags & 4)
        {
          badges += "NOISE ";
        }
        return Format("L%+d R%+d %s", g.SignedField(6), g.SignedField(7), badges.c_str());
      });
      DrawFit(canvas, pan, x, y, rect.Right - x);
    }
  }

  void ChipWidgets::DrawMt32Part(Canvas& canvas, const RectF& rect, const VoiceGauges& g)
  {
    const auto metrics = canvas.GetMetrics(TextFont);
    const auto notes = g.Field(0);
    const auto partials = g.Field(9);
    const auto& keys =
        Memo->Get(0, notes | (partials << 8), [&] { return Format("KEYS %d PARTIALS %d", notes, partials); });
    DrawFit(canvas, keys, rect.Left, rect.Top - metrics.Ascent, rect.Width());
    RectF kb;
    kb.Set(rect.Left, rect.Top + metrics.Spacing, rect.Right, rect.Bottom);
    if (kb.Height() < Density * 8)
    {
      return;
    }
    Pressed.fill(false);
    for (int idx = 1; idx <= std::min(notes, 8); ++idx)
    {
      if (const auto note = g.Field(idx))
      {
        Pressed[note & 127] = true;
      }
    }
    DrawKeyboard(canvas, kb);
  }

  void ChipWidgets::DrawKeyboard(Canvas& canvas, const RectF& kb)
  {
    // C2..C7 covers most of melodic parts
    const int firstKey = 36;
    const int lastKey = 96;
    int whites = 0;
    for (int key = firstKey; key <= lastKey; ++key)
    {
      whites += !IsBlackKey(key);
    }
    const auto keyW = kb.Width() / whites;
    RectF box;
    int white = 0;
    for (int key = firstKey; key <= lastKey; ++key)
    {
      if (IsBlackKey(key))
      {
        continue;
      }
      box.Set(kb.Left + keyW * white, kb.Top, kb.Left + keyW * (white + 1), kb.Bottom);
      if (Pressed[key])
      {
        canvas.FillRect(box, Color::White(255));
      }
      canvas.StrokeRect(box, DimColor, StrokeWidth);
      ++white;
    }
    white = 0;
    for (int key = firstKey; key <= lastKey; ++key)
    {
      if (!IsBlackKey(key))
      {
        ++white;
        continue;
      }
      const auto x = kb.Left + keyW * white;
      box.Set(x - keyW * 0.32f, kb.Top, x + keyW * 0.32f, kb.Top + kb.Height() * 0.6f);
      canvas.FillRect(box, Pressed[key] ? Color::White(255) : Color::Black());
      canvas.StrokeRect(box, DimColor, StrokeWidth);
    }
  }

  void ChipWidgets::DrawMt32Partial(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth)
  {
    // brightness follows envelope phase
    static const float TARGETS[] = {0.f, 1.f, 0.7f, 0.3f};
    const auto state = g.Field(0);
    DrawSegments(canvas, rect, Ease(smooth, 0, state < 4 ? TARGETS[state] : 0.f), 10);
  }

  // 2A03: duty cycle glyph, 4 bit volume/envelope, sweep and DMC state
  void ChipWidgets::DrawNes(Canvas& canvas, const RectF& rect, const VoiceGauges& g, float* smooth)
  {
    const auto metrics = canvas.GetMetrics(TextFont);
    const auto glyphW = std::min(rect.Width() * 0.3f, rect.Height() * 1.6f);
    RectF glyph;
    glyph.Set(rect.Left, rect.Top, rect.Left + glyphW, rect.Bottom);
    const auto x = glyph.Right + Density * 6;
    RectF bar;
    bar.Set(x, rect.Top + metrics.Spacing, rect.Right, rect.Bottom);
    const auto baseline = rect.Top - metrics.Ascent;
    switch (g.Kind())
    {
    case VoiceKind::NES_PULSE:
    {
      const auto duty = g.Field(0) & 3;
      DrawPulseGlyph(canvas, glyph, DUTIES[duty]);
      const auto period = g.Field(3) | (g.Field(4) << 8);
      const int64_t key = duty | (g.Field(2) << 8) | (g.Field(6) << 16) | (int64_t(period) << 24);
      const auto& info = Memo->Get(0, key, [&] {
        return Format("%s %s%s P=%03X", DUTY_NAMES[duty], g.Field(2) != 0 ? "VOL" : "ENV",
                      g.Field(6) != 0 ? " SWP" : "", period);
      });
      DrawFit(canvas, info, x, baseline, rect.Right - x);
      DrawSegments(canvas, bar, Ease(smooth, 0, g.Field(1) / 15.f), 15);
      break;
    }
    case VoiceKind::NES_TRIANGLE:
    {
      DrawTriangleGlyph(canvas, glyph);
      const auto period = g.Field(1) | (g.Field(2) << 8);
      const auto& info = Memo->Get(1, g.Field(0) | (int64_t(period) << 8),
                                   [&] { return Format("LIN %s P=%03X", g.Field(0) != 0 ? "on" : "off", period); });
      DrawFit(canvas, info, x, baseline, rect.Right - x);
      DrawSegments(canvas, bar, Ease(smooth, 0, g.IsKeyOn() ? 1.f : 0.f), 15);
      break;
    }
    case VoiceKind::NES_NOISE:
    {
      DrawNoiseGlyph(canvas, glyph, g.Field(1) != 0);
      const auto& info = Memo->Get(2, g.Field(1) | (g.Field(2) << 8), [&] {
        return Format("%s RATE %X", g.Field(1) != 0 ? "SHORT" : "LONG", g.Field(2));
      });
      DrawFit(canvas, info, x, baseline, rect.Right - x);
      DrawSegments(canvas, bar, Ease(smooth, 0, g.Field(0) / 15.f), 15);
      break;
    }
    default:
    {
      const int64_t key = g.Field(1) | (g.Field(2) << 8) | (g.Field(3) << 16);
      const auto& info = Memo->Get(3, key, [&] {
        return Format("RATE %X%s%s", g.Field(1), g.Field(3) != 0 ? " LOOP" : "", g.Field(2) != 0 ? " PLAY" : "");
      });
      DrawFit(canvas, info, glyph.Left, baseline, rect.Right - glyph.Left);
      bar.Set(glyph.Left, bar.Top, rect.Right, rect.Bottom);
      DrawSegments(canvas, bar, std::clamp(Ease(smooth, 0, g.Field(0) / 127.f), 0.f, 1.f), 32);
      break;
    }
    }
  }

  void ChipWidgets::DrawPulseGlyph(Canvas& canvas, const RectF& r, float duty)
  {
    const auto top = r.Top + r.Height() * 0.25f;
    const auto bottom = r.Bottom - r.Height() * 0.25f;
    Shape.Reset();
    Shape.MoveTo(r.Left, bottom);
    for (int period = 0; period < 2; ++period)
    {
      const auto start = r.Left + r.Width() / 2 * period;
      const auto high = start + r.Width() / 2 * duty;
      Shape.LineTo(start, bottom);
      Shape.LineTo(start, top);
      Shape.LineTo(high, top);
      Shape.LineTo(high, bottom);
    }
    Shape.LineTo(r.Right, bottom);
    canvas.StrokePath(Shape, StrokeColor, StrokeWidth);
  }

  void ChipWidgets::DrawTriangleGlyph(Canvas& canvas, const RectF& r)
  {
    const auto top = r.Top + r.Height() * 0.25f;
    const auto bottom = r.Bottom - r.Height() * 0.25f;
    Shape.Reset();
    Shape.MoveTo(r.Left, bottom);
    Shape.LineTo(r.Left + r.Width() * 0.25f, top);
    Shape.LineTo(r.Left + r.Width() * 0.75f, bottom);
    Shape.LineTo(r.Right, top);
    canvas.StrokePath(Shape, StrokeColor, StrokeWidth);
  }

  // short mode has 93 steps period, so drawn regular
  void ChipWidgets::DrawNoiseGlyph(Canvas& canvas, const RectF& r, bool isShort)
  {
    const auto top = r.Top + r.Height() * 0.25f;
    const auto bottom = r.Bottom - r.Height() * 0.25f;
    const auto* pattern = isShort ? SHORT_NOISE : LONG_NOISE;
    const int size = 16;
    Shape.Reset();
    for (int idx = 0; idx < size; ++idx)
    {
      const auto x = r.Left + r.Width() * idx / size;
      const auto y = pattern[idx] ? top : bottom;
      if (idx == 0)
      {
        Shape.MoveTo(x, y);
      }
      else
      {
        Shape.LineTo(x, y);
      }
      Shape.LineTo(r.Left + r.Width() * (idx + 1) / size, y);
    }
    canvas.StrokePath(Shape, StrokeColor, StrokeWidth);
  }

  void ChipWidgets::DrawSegments(Canvas& canvas, const RectF& r, float value, int segments)
  {
    if (r.Height() < Density * 3)
    {
      return;
    }
    const auto gap = Density;
    const auto segW = (r.Width() - gap * (segments - 1)) / segments;
    const auto lit = value * segments;
    RectF box;
    for (int seg = 0; seg < segments; ++seg)
    {
      const auto left = r.Left + (segW + gap) * seg;
      box.Set(left, r.Top, left + segW, r.Bottom);
      const auto part = std::clamp(lit - seg, 0.f, 1.f);
      canvas.FillRect(box, Color::White(static_cast<int>(40 + 200 * part)));
    }
  }

  void ChipWidgets::DrawVerticalBar(Canvas& canvas, const RectF& r, float value)
  {
    canvas.StrokeRect(r, DimColor, StrokeWidth);
    RectF box;
    box.Set(r.Left, r.Bottom - r.Height() * std::clamp(value, 0.f, 1.f), r.Right, r.Bottom);
    canvas.FillRect(box, Color::White(200));
  }

  void ChipWidgets::DrawCenteredBar(Canvas& canvas, const RectF& r, float value)
  {
    canvas.StrokeRect(r, DimColor, StrokeWidth);
    const auto cx = r.CenterX();
    const auto x = cx + r.Width() / 2 * std::clamp(value, -1.f, 1.f);
    RectF box;
    box.Set(std::min(cx, x), r.Top, std::max(cx, x), r.Bottom);
    canvas.FillRect(box, Color::White(200));
    canvas.DrawLine(cx, r.Top, cx, r.Bottom, DimColor, StrokeWidth);
  }

  // shrinks text (down to 70%) and then cuts it to fit width
  void ChipWidgets::DrawFit(Canvas& canvas, const std::string& str, float x, float y, float maxWidth)
  {
    const auto width = canvas.MeasureText(str, TextFont);
    if (width <= maxWidth)
    {
      canvas.DrawText(str, x, y, TextFont, Color::White(230), Align::LEFT);
      return;
    }
    auto font = TextFont;
    font.Size = BaseTextSize * std::max(maxWidth / width, 0.7f);
    const auto count = BreakText(canvas, str, maxWidth, font);
    canvas.DrawText(std::string_view(str).substr(0, count), x, y, font, Color::White(230), Align::LEFT);
  }

  float ChipWidgets::OplWaveform(int wave, float phase)
  {
    constexpr auto PI = std::numbers::pi_v<float>;
    const auto s = std::sin(2 * PI * phase);
    switch (wave)
    {
    case 0:
      return s;
    case 1:
      return std::max(s, 0.f);
    case 2:
      return std::abs(s);
    case 3:
      return static_cast<int>(phase * 4) % 2 == 0 ? std::abs(s) : 0.f;
    case 4:
      return phase < 0.5f ? std::sin(4 * PI * phase) : 0.f;
    case 5:
      return phase < 0.5f ? std::abs(std::sin(4 * PI * phase)) : 0.f;
    case 6:
      return phase < 0.5f ? 1.f : -1.f;
    default:
      return phase < 0.5f ? 1.f - phase * 2 : -(phase - 0.5f) * 2;
    }
  }

  enum SummaryMode
  {
    SUMMARY_NOISE,
    SUMMARY_LEVEL,
    SUMMARY_NONE,
    SUMMARY_NOISE_RATE,
    SUMMARY_NOTE
  };

  SummaryMode SummaryModeOf(const VoiceGauges& g)
  {
    const auto noPitch = !g.HasFrequency() || g.Frequency() <= 0.f;
    if (g.IsNoise() && !g.HasFrequency())
    {
      return SUMMARY_NOISE;
    }
    else if (noPitch && g.HasLevel())
    {
      return SUMMARY_LEVEL;
    }
    else if (noPitch)
    {
      return SUMMARY_NONE;
    }
    return g.IsNoise() ? SUMMARY_NOISE_RATE : SUMMARY_NOTE;
  }

  std::string NoteSummary(SummaryMode mode, int hz, const std::string& note, int levelDb)
  {
    switch (mode)
    {
    case SUMMARY_NOISE:
      return "noise";
    case SUMMARY_LEVEL:
      return levelDb > -96 ? std::to_string(levelDb) + "dB" : "off";
    case SUMMARY_NONE:
      return {};
    case SUMMARY_NOISE_RATE:
      return "noise " + std::to_string(hz) + "Hz";
    default:
      return note + " " + std::to_string(hz) + "Hz";
    }
  }

  std::string PartialSummary(const VoiceGauges& g)
  {
    const std::string state = g.Field(0) < 4 ? PARTIAL_STATES[g.Field(0)] : "";
    const auto owner = g.Field(1);
    if (g.Field(0) == 0)
    {
      return state;
    }
    else if (owner == 9)
    {
      return state + " rhythm";
    }
    else if (g.HasFrequency())
    {
      return state + " P" + std::to_string(owner) + " " + Notes::NameOf(g.Frequency());
    }
    return state + " P" + std::to_string(owner);
  }

  std::string ChipWidgets::SummaryOf(const VoiceGauges& g)
  {
    switch (g.Kind())
    {
    case VoiceKind::MT32_PART:
    {
      auto text = g.Text();
      return text.empty() ? "-" : text;
    }
    case VoiceKind::MT32_PARTIAL:
      return PartialSummary(g);
    case VoiceKind::OPL_RHYTHM:
      return g.Field(0) < 5 ? RHYTHM_NAMES[g.Field(0)] : "";
    case VoiceKind::OPN_DAC:
      return g.Field(0) != 0 ? "PCM" : "off";
    default:
      return NoteSummary(SummaryModeOf(g), static_cast<int>(std::lround(g.Frequency())), Notes::NameOf(g.Frequency()),
                         static_cast<int>(std::lround(g.Level())));
    }
  }

  void ChipWidgets::LiveSummary::Update(const VoiceGauges& g)
  {
    const auto kind = g.Kind();
    if (kind == VoiceKind::MT32_PART || kind == VoiceKind::MT32_PARTIAL || kind == VoiceKind::OPL_RHYTHM
        || kind == VoiceKind::OPN_DAC)
    {
      Value = SummaryOf(g);
      Key = INT64_MIN;
      return;
    }
    const auto mode = SummaryModeOf(g);
    const auto hasPitch = g.HasFrequency() && g.Frequency() > 0.f;
    Note = hasPitch ? Notes::Quantize(Notes::Semitones(g.Frequency()), Note) : Notes::NONE;
    const auto hz = hasPitch ? static_cast<int>(std::lround(g.Frequency())) : 0;
    const auto level = g.HasLevel() ? static_cast<int>(std::lround(g.Level())) : 0;
    const int64_t current = (int64_t(kind) << 56) | (int64_t(mode) << 48) | ((int64_t(Note) & 0xff) << 40)
                            | ((int64_t(hz) & 0xffffff) << 16) | (int64_t(level) & 0xffff);
    if (current != Key)
    {
      Key = current;
      Value = NoteSummary(mode, hz, Note == Notes::NONE ? std::string() : Notes::Name(Note), level);
    }
  }
}  // namespace Visualization
