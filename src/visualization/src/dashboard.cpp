/**
 *
 * @file
 *
 * @brief Chips state gauges and diagnostics implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "visualization/dashboard.h"

#include "visualization/sid_description.h"

#include <algorithm>
#include <functional>
#include <numeric>

namespace Visualization
{
  namespace DashboardDetails
  {
    const float TRACE_WIDTH = 1;
    const float TITLE_SIZE = 13;
    const float MIN_TITLE_SIZE = 9;
    const float TEXT_SIZE = 12;
    const float CELL_SIZE = 13;
    const float CAPTION_SIZE = 10;
    // 13 -> 11, still readable
    const std::vector<float> CELL_SCALES = {1.f, 0.92f, 0.85f};
    // more lines make gauges too small, it's better to drop less important cells
    const int MAX_CELL_LINES = 2;
    const float MIN_GAUGE_DP = 72;
    // relative to text size
    const float CELL_GAP = 0.8f;
    const float MAX_JUSTIFY = 2.f;
    const float MIN_VALUE_SCALE = 0.8f;
    const float SHRINKED_TITLE = 0.85f;
    const int MAX_CHIPS = 3;
    const int MAX_VOICES = 64;

    const Color TITLE_COLOR = Color::White(230);
    const Color TEXT_COLOR = Color::White(230);
    const Color FRAME_COLOR = Color::White(70);
    const Color TRACE_COLOR = Color::White(255);
    const Color SEPARATOR_COLOR = Color::White(160);
    const Color SUMMARY_COLOR = Color::White(200);
    // human readable values of registers are greyed to separate them from raw values
    const Color VALUE_COLOR = Color::White(160);
    // cells values are the main information for non technical user, captions are just hints
    const Color CELL_COLOR = Color::White(225);
    const Color CAPTION_COLOR = Color::White(130);
    const Color CELL_FRAME_COLOR = Color::White(50);
    const Color DIM_TRACE_COLOR = Color::White(110);

    //! Widest title and value parts of gauges in a row, measured once to keep layout stable regardless of texts
    struct TitleTemplate
    {
      std::string Title;
      std::string Main;
      std::vector<std::string> Tails;
      Align TailAlign = Align::RIGHT;
    };

    std::vector<TitleTemplate> VoiceTemplate()
    {
      std::vector<std::string> phases;
      for (int idx = 0; idx < SidDescription::PHASES_COUNT; ++idx)
      {
        phases.emplace_back(SidDescription::PhaseShort(static_cast<SidDescription::Phase>(idx)));
      }
      return {{"S1V1 Wave F SRTF", "Pulse+Tri Sync Ring", {std::string(SidDescription::GATE_MARK)}, Align::RIGHT},
              {"Env FFFF", "100%", phases, Align::LEFT},
              {"Freq FFFF", "G#8", {"1047Hz", "3.8kHz"}, Align::RIGHT}};
    }

    std::vector<TitleTemplate> GlobalTemplate()
    {
      return {{"Vol 15", "", {"100%"}, Align::RIGHT},
              {"Res 15", "", {"100%"}, Align::RIGHT},
              {"Filter LBH 2047", "BP+HP", {"~9.9kHz", "~18kHz", "~880Hz"}, Align::RIGHT}};
    }

    std::vector<std::string> SplitLines(const std::string& text)
    {
      std::vector<std::string> result;
      std::size_t pos = 0;
      while (pos <= text.size())
      {
        const auto end = std::min(text.find('\n', pos), text.size());
        const auto line = text.substr(pos, end - pos);
        if (line.find_first_not_of(" \t\r") != std::string::npos)
        {
          result.push_back(line);
        }
        pos = end + 1;
      }
      return result;
    }
  }  // namespace DashboardDetails

  using namespace DashboardDetails;

  class Dashboard::Impl
  {
  public:
    explicit Impl(float density)
      : Density(density)
      , TitleFont{TITLE_SIZE * density, FontFace::DEFAULT, true}
      , TextFont{TEXT_SIZE * density, FontFace::MONOSPACE, false}
      , SummaryFont{TEXT_SIZE * density, FontFace::MONOSPACE, false}
      , ValueFont{TEXT_SIZE * density, FontFace::DEFAULT, true}
      , CellFont{CELL_SIZE * density, FontFace::DEFAULT, true}
      , CaptionFont{CAPTION_SIZE * density, FontFace::DEFAULT, true}
      , Widgets(density, TEXT_SIZE * density)
      , VoiceGrid(*this, SidCells::Voice())
      , GlobalGrid(*this, SidCells::Global())
      , VoiceTemplates(VoiceTemplate())
      , GlobalTemplates(GlobalTemplate())
    {
      for (auto& chip : VoiceTexts)
      {
        chip.resize(3);
      }
      for (auto& values : Smooth)
      {
        values.fill(0.f);
      }
    }

    void SetStatus(const std::string& status, const std::string& stats)
    {
      Status = status;
      TextLines = SplitLines(status);
      const auto statsLines = SplitLines(stats);
      TextLines.insert(TextLines.end(), statsLines.begin(), statsLines.end());
    }

    uint64_t StaticKey(const GaugesFrame& frame, int width, int height) const
    {
      uint64_t key = width;
      key = key * 31 + height;
      key = key * 31 + static_cast<int>(frame.Kind);
      key = key * 31 + frame.Count;
      key = key * 31 + reinterpret_cast<uintptr_t>(frame.Layout.get());
      key = key * 31 + TextLines.size();
      if (frame.Kind == GaugesKind::VOICES)
      {
        // cards layout depends on voice kind
        const auto voices = std::min<std::size_t>(frame.Count, frame.Bytes.size() / VoiceGauges::SIZE);
        for (std::size_t voice = 0; voice < voices; ++voice)
        {
          key = key * 31 + VoiceGauges(frame.Bytes.data() + voice * VoiceGauges::SIZE).Kind();
        }
      }
      return key;
    }

    void Draw(Canvas& canvas, const GaugesFrame& frame, int width, int height, Pass pass)
    {
      Width = static_cast<float>(width);
      Measure(canvas);
      const auto textMetrics = canvas.GetMetrics(TextFont);
      const auto textHeight = textMetrics.Spacing * TextLines.size() + Padding();
      const auto areaHeight = height - textHeight;
      Current = pass;
      if (frame.Kind == GaugesKind::VOICES)
      {
        DrawVoices(canvas, frame, areaHeight);
      }
      else if (frame.Kind == GaugesKind::CHIPS)
      {
        DrawGauges(canvas, frame, areaHeight);
      }
      if (IsDynamic())
      {
        auto y = areaHeight + Padding() / 2 - textMetrics.Ascent;
        for (const auto& line : TextLines)
        {
          canvas.DrawText(line, Padding(), y, TextFont, TEXT_COLOR, Align::LEFT);
          y += textMetrics.Spacing;
        }
      }
      Current = Pass::ALL;
    }

  private:
    bool IsStatic() const
    {
      return Current != Pass::DYNAMIC;
    }

    bool IsDynamic() const
    {
      return Current != Pass::STATIC;
    }

    float Padding() const
    {
      return Density * 4;
    }

    float CellsPadding() const
    {
      return Density * 4;
    }

    float TitleGap() const
    {
      return Density * 6;
    }

    static Font Scaled(Font font, float scale)
    {
      font.Size *= scale;
      return font;
    }

    struct TitleTemplates
    {
      explicit TitleTemplates(std::vector<TitleTemplate> items)
        : Items(std::move(items))
      {}

      void Measure(const Canvas& canvas, const Font& titleFont, const Font& valueFont)
      {
        TitleWidth.clear();
        MainWidth.clear();
        TailWidth.clear();
        for (const auto& item : Items)
        {
          // hex digits are not of the same width even with tabular figures
          auto zeroes = item.Title;
          std::replace(zeroes.begin(), zeroes.end(), 'F', '0');
          TitleWidth.push_back(
              std::max(canvas.MeasureText(item.Title, titleFont), canvas.MeasureText(zeroes, titleFont)));
          MainWidth.push_back(canvas.MeasureText(item.Main, valueFont));
          float tail = 0;
          for (const auto& t : item.Tails)
          {
            tail = std::max(tail, canvas.MeasureText(t, valueFont));
          }
          TailWidth.push_back(tail);
        }
        SpaceWidth = canvas.MeasureText(" ", valueFont);
      }

      float ValueWidth(int idx) const
      {
        return MainWidth[idx] + (MainWidth[idx] > 0.f ? SpaceWidth : 0.f) + TailWidth[idx];
      }

      const std::vector<TitleTemplate> Items;
      std::vector<float> TitleWidth;
      std::vector<float> MainWidth;
      std::vector<float> TailWidth;
      float SpaceWidth = 0;
    };

    // Texts are measured by canvas only, so templates are measured at the first frame
    void Measure(const Canvas& canvas)
    {
      if (Measured)
      {
        return;
      }
      Measured = true;
      VoiceTemplates.Measure(canvas, TitleFont, ValueFont);
      GlobalTemplates.Measure(canvas, TitleFont, ValueFont);
      const auto caption = canvas.GetMetrics(CaptionFont);
      const auto cell = canvas.GetMetrics(CellFont);
      CellLineBase = caption.Descent - caption.Ascent + cell.Spacing;
      MeasureCanvas = &canvas;
    }

    float CellLineHeight(float scale) const
    {
      return CellLineBase * scale + Density * 3;
    }

    /**
     * Fixed positions of cells (see CellSpec) for given width and available height. Sizes are taken from templates,
     * so neither positions nor text size depend on current values: change of one value doesn't move others, and the
     * same cells of all voices are aligned in columns (as in trackers). Cells are wrapped to several lines and the
     * least important ones are dropped if there's not enough space, but never overlap.
     */
    class CellGrid
    {
    public:
      CellGrid(Impl& owner, const std::vector<CellSpec>& specs)
        : Owner(owner)
        , Specs(specs)
        , Widths(specs.size())
        , Importance(specs.size())
        , Visible(specs.size())
        , Left(specs.size())
        , Right(specs.size())
        , Line(specs.size())
      {
        // the most important first
        std::iota(Importance.begin(), Importance.end(), 0);
        std::stable_sort(Importance.begin(), Importance.end(),
                         [&specs](int lh, int rh) { return specs[lh].Priority < specs[rh].Priority; });
      }

      void Update(const Canvas& canvas, float w, float space, const std::vector<float>& scales)
      {
        // the biggest text which allows to show the most cells
        auto best = scales[0];
        int bestShown = -1;
        for (const auto candidate : scales)
        {
          const auto shown = Fit(canvas, w, space, candidate);
          if (shown > bestShown)
          {
            best = candidate;
            bestShown = shown;
          }
        }
        Fit(canvas, w, space, best);
      }

    private:
      // @return visible cells count
      int Fit(const Canvas& canvas, float w, float space, float candidate)
      {
        Scale = candidate;
        Gap = Owner.CellFont.Size * CELL_GAP * Scale;
        MeasureCells(canvas);
        const auto maxLines =
            w <= 0.f ? 0 : std::clamp(static_cast<int>(space / Owner.CellLineHeight(Scale)), 0, MAX_CELL_LINES);
        int shown = maxLines > 0 ? static_cast<int>(Specs.size()) : 0;
        std::fill(Visible.begin(), Visible.end(), shown != 0);
        while (Pack(w) > maxLines)
        {
          Visible[Importance[--shown]] = false;
        }
        return shown;
      }

      // Text width is not exactly proportional to size (hinting), so templates are measured at the actual size
      void MeasureCells(const Canvas& canvas)
      {
        const auto cell = Scaled(Owner.CellFont, Scale);
        const auto caption = Scaled(Owner.CaptionFont, Scale);
        for (std::size_t idx = 0; idx < Specs.size(); ++idx)
        {
          const auto& spec = Specs[idx];
          auto width = canvas.MeasureText(spec.Caption, caption);
          for (const auto& tmpl : spec.Templates)
          {
            width = std::max(width, canvas.MeasureText(tmpl, cell));
          }
          Widths[idx] = width + Owner.Density;
        }
      }

      // @return lines count
      int Pack(float w)
      {
        float x = 0;
        int current = -1;
        for (std::size_t idx = 0; idx < Specs.size(); ++idx)
        {
          if (!Visible[idx])
          {
            continue;
          }
          const auto width = std::min(Widths[idx], w);
          if (current < 0 || x + width > w)
          {
            ++current;
            x = 0;
          }
          Line[idx] = current;
          Left[idx] = x;
          Right[idx] = x + width;
          x += width + Gap;
        }
        Lines = current + 1;
        for (int ln = 0; ln < Lines; ++ln)
        {
          Justify(ln, w);
        }
        return Lines;
      }

      // spare space is distributed between cells to use the whole width, but not too much to keep them grouped
      void Justify(int ln, float w)
      {
        int count = 0;
        float end = 0;
        for (std::size_t idx = 0; idx < Specs.size(); ++idx)
        {
          if (Visible[idx] && Line[idx] == ln)
          {
            ++count;
            end = Right[idx];
          }
        }
        if (count < 2)
        {
          return;
        }
        const auto add = std::min((w - end) / (count - 1), Gap * MAX_JUSTIFY);
        int pos = 0;
        for (std::size_t idx = 0; idx < Specs.size(); ++idx)
        {
          if (Visible[idx] && Line[idx] == ln)
          {
            Left[idx] += add * pos;
            Right[idx] += add * pos;
            ++pos;
          }
        }
      }

    private:
      Impl& Owner;

    public:
      const std::vector<CellSpec>& Specs;

    private:
      std::vector<float> Widths;
      std::vector<int> Importance;

    public:
      std::vector<bool> Visible;
      std::vector<float> Left;
      std::vector<float> Right;
      std::vector<int> Line;
      int Lines = 0;
      float Scale = 1.f;
      float Gap = 0.f;
    };

    void UpdateGrids(const Canvas& canvas, float w, float space)
    {
      if (w == GridWidth && space == GridSpace)
      {
        return;
      }
      GridWidth = w;
      GridSpace = space;
      VoiceGrid.Update(canvas, w, space, CELL_SCALES);
      // the same text size for all rows
      GlobalGrid.Update(canvas, w, space, {VoiceGrid.Scale});
    }

    // all rows have the same height to keep gauges aligned
    float GridHeight() const
    {
      const auto lines = std::max(VoiceGrid.Lines, GlobalGrid.Lines);
      return lines == 0 ? 0.f : CellLineHeight(VoiceGrid.Scale) * lines + Density * 2;
    }

    // Chips are placed side by side as in oscilloscope above: 3 gauges columns per chip.
    // Each row (voice or global filter/volume state) ends with grid of value cells, if fits.
    void DrawGauges(Canvas& canvas, const GaugesFrame& frame, float areaHeight)
    {
      const auto chips =
          std::min<int>({frame.Count, static_cast<int>(frame.Bytes.size() / ChipGauges::SIZE), MAX_CHIPS});
      if (chips == 0 || areaHeight < Density * 40)
      {
        return;
      }
      const auto chip = SidDescription::Chip::FromStatus(Status);
      const auto groupW = Width / chips;
      const auto cellW = groupW / 3;
      const auto rowH = areaHeight / 4;
      // cells are less important than gauges themselves, so take only space above gauges minimal height;
      // space is reserved regardless of current values to avoid layout jumping
      UpdateGrids(canvas, groupW - CellsPadding() * 2, rowH - Density * MIN_GAUGE_DP);
      const auto cellH = rowH - GridHeight();
      for (int idx = 0; idx < chips; ++idx)
      {
        const ChipGauges gauges(frame.Bytes.data() + idx * ChipGauges::SIZE);
        const auto last = gauges.State();
        const auto left = groupW * idx;
        const auto chipLabel = chips > 1 ? idx + 1 : 0;
        for (int voice = 0; voice < 3; ++voice)
        {
          auto& texts = VoiceTexts[idx][voice];
          const auto row = idx * 4 + voice;
          if (IsDynamic())
          {
            texts.Update(last, voice, EnvelopeTrend(gauges, last, voice), chip, chipLabel);
          }
          const int ids[] = {ChipGauges::Wave(voice), ChipGauges::Envelope(voice), ChipGauges::Frequency(voice)};
          DrawGaugesRow(canvas, gauges, ids, texts.Titles, VoiceTemplates, left, rowH * voice, cellW, cellH, row);
          DrawCells(canvas, VoiceGrid, texts.Cells, left, rowH * voice + cellH);
        }
        auto& texts = GlobalTexts[idx];
        if (IsDynamic())
        {
          texts.Update(last, chip);
        }
        const int ids[] = {ChipGauges::VOLUME, ChipGauges::RESONANCE, ChipGauges::CUTOFF};
        DrawGaugesRow(canvas, gauges, ids, texts.Titles, GlobalTemplates, left, rowH * 3, cellW, cellH, idx * 4 + 3);
        DrawCells(canvas, GlobalGrid, texts.Cells, left, rowH * 3 + cellH);
        if (idx != 0 && IsStatic())
        {
          canvas.DrawLine(left, 0, left, areaHeight, SEPARATOR_COLOR, Density * 2);
        }
      }
    }

    // Registers snapshot is not enough to distinguish attack from decay, so last history column is used
    static int EnvelopeTrend(const ChipGauges& gauges, const ChipState& state, int voice)
    {
      const auto gauge = ChipGauges::Envelope(voice);
      const auto col = ChipGauges::COLUMNS - 1;
      const auto min = gauges.Min(gauge, col);
      const auto max = gauges.Max(gauge, col);
      const auto current = SidDescription::EnvelopeGaugeLevel(state.Envelope(voice));
      if (min == max)
      {
        return 0;
      }
      return current >= max ? 1 : (current <= min ? -1 : 0);
    }

    // Gauges in a row have the same title band layout to keep plots aligned. Layout is selected by templates only,
    // so it's not changed with values. Raw title and value have separate regions: value is placed at the same line
    // if both fit, at the second line if there's enough height, or is not displayed at all.
    void DrawGaugesRow(Canvas& canvas, const GaugesData& gauges, const int* ids, const LiveTexts& texts,
                       const TitleTemplates& templates, float left, float top, float cellW, float cellH, int /*row*/)
    {
      // see DrawGauge and DrawGaugeTitleBand paddings
      const auto inner = cellW - Density * 11 - TitleGap();
      const auto minTitle = MIN_TITLE_SIZE / TITLE_SIZE;
      const auto fits = [&](float titleScale, float valueScale) {
        for (int idx = 0; idx < 3; ++idx)
        {
          if (templates.TitleWidth[idx] * titleScale + templates.ValueWidth(idx) * valueScale > inner)
          {
            return false;
          }
        }
        return true;
      };
      const auto titleSpacing = MeasureCanvas->GetMetrics(TitleFont).Spacing;
      const auto valueSpacing = MeasureCanvas->GetMetrics(ValueFont).Spacing;
      bool secondLine = false;
      float valueScale = 0.f;
      if (fits(SHRINKED_TITLE, 1.f))
      {
        valueScale = 1.f;
      }
      // second line is used only if plot is still visible enough
      else if (cellH > (titleSpacing + valueSpacing) * 1.8f)
      {
        secondLine = true;
        // the same size for all gauges in a row, see DrawGaugeTitleBand
        const auto room = cellW - Density * 8;
        valueScale = 1.f;
        for (int idx = 0; idx < 3; ++idx)
        {
          valueScale = std::min(valueScale, room / templates.ValueWidth(idx));
        }
        valueScale = std::clamp(valueScale, MIN_VALUE_SCALE, 1.f);
      }
      else if (fits(minTitle, MIN_VALUE_SCALE))
      {
        valueScale = MIN_VALUE_SCALE;
      }
      for (int idx = 0; idx < 3; ++idx)
      {
        DrawGauge(canvas, gauges, ids[idx], left + cellW * idx, top, cellW, cellH, texts, templates, idx, secondLine,
                  valueScale);
      }
    }

    // Caption and value of each cell are cut to cell's own width to never overlap neighbours, numbers are
    // right-aligned to keep digits in place
    void DrawCells(Canvas& canvas, const CellGrid& grid, const LiveTexts& texts, float left, float top)
    {
      if (grid.Lines == 0)
      {
        return;
      }
      const auto cell = Scaled(CellFont, grid.Scale);
      const auto caption = Scaled(CaptionFont, grid.Scale);
      const auto cellMetrics = canvas.GetMetrics(cell);
      const auto captionMetrics = canvas.GetMetrics(caption);
      const auto lineH = CellLineHeight(grid.Scale);
      const auto x0 = left + CellsPadding();
      const auto y0 = top + Density * 2;
      for (std::size_t idx = 0; idx < grid.Specs.size(); ++idx)
      {
        if (!grid.Visible[idx])
        {
          continue;
        }
        const auto cellLeft = x0 + grid.Left[idx];
        const auto cellRight = x0 + grid.Right[idx];
        const auto cellTop = y0 + lineH * grid.Line[idx];
        const auto captionBaseline = cellTop - captionMetrics.Ascent;
        const auto valueBaseline = captionBaseline + captionMetrics.Descent - cellMetrics.Ascent;
        if (grid.Left[idx] > 0.f && IsStatic())
        {
          // thin separator in the middle of gap makes cells look as separate boxes
          const auto x = cellLeft - grid.Gap / 2;
          canvas.DrawLine(x, cellTop + Density, x, cellTop + lineH - Density * 3, CELL_FRAME_COLOR, Density);
        }
        const auto numeric = grid.Specs[idx].Numeric;
        const auto align = numeric ? Align::RIGHT : Align::LEFT;
        const auto x = numeric ? cellRight : cellLeft;
        const auto w = cellRight - cellLeft;
        if (IsStatic())
        {
          canvas.DrawText(Ellipsize(canvas, grid.Specs[idx].Caption, w, caption), x, captionBaseline, caption,
                          CAPTION_COLOR, align);
        }
        const auto& value = texts[idx];
        if (!value.empty() && IsDynamic())
        {
          canvas.DrawText(Ellipsize(canvas, value, w, cell), x, valueBaseline, cell, CELL_COLOR, align);
        }
      }
    }

    // Chips are placed side by side as in oscilloscope above, see ChipColumns, card per voice
    void DrawVoices(Canvas& canvas, const GaugesFrame& frame, float areaHeight)
    {
      const auto& current = frame.Layout;
      const auto voices = std::min<int>(frame.Count, static_cast<int>(frame.Bytes.size() / VoiceGauges::SIZE));
      if (voices == 0 || areaHeight < Density * 40 || !current)
      {
        return;
      }
      if (ColumnsLayout != current)
      {
        Columns = std::make_unique<ChipColumns>(current->Groups);
        ColumnsLayout = current;
      }
      int voice = 0;
      for (std::size_t groupIdx = 0; groupIdx < current->Groups.size(); ++groupIdx)
      {
        const auto& group = current->Groups[groupIdx];
        const auto rows = Columns->Rows(static_cast<int>(groupIdx));
        // chips with less voices get taller cards
        const auto cellH = areaHeight / rows;
        const auto first = Columns->Column(static_cast<int>(groupIdx), 0);
        if (first != 0 && IsStatic())
        {
          const auto x = Width * Columns->Left(first);
          canvas.DrawLine(x, 0, x, areaHeight, SEPARATOR_COLOR, Density * 2);
        }
        for (std::size_t idx = 0; idx < group.Voices.size(); ++idx)
        {
          if (voice >= voices)
          {
            return;
          }
          const VoiceGauges gauges(frame.Bytes.data() + voice * VoiceGauges::SIZE);
          const auto col = Columns->Column(static_cast<int>(groupIdx), static_cast<int>(idx));
          const auto left = Width * Columns->Left(col);
          const auto top = cellH * Columns->Row(static_cast<int>(groupIdx), static_cast<int>(idx));
          DrawVoiceCard(canvas, gauges, group.Name, group.Voices[idx], left, top, Width * Columns->Width(col), cellH,
                        voice % MAX_VOICES);
          ++voice;
        }
      }
    }

    void DrawVoiceCard(Canvas& canvas, const VoiceGauges& gauges, const std::string& group, const std::string& name,
                       float left, float top, float w, float h, int voice)
    {
      auto* smoothValues = Smooth[voice].data();
      const auto pad = Density * 2;
      RectF rect;
      rect.Set(left + pad, top + pad, left + w - pad, top + h - pad);
      if (IsStatic())
      {
        canvas.StrokeRect(rect, FRAME_COLOR, Density);
      }
      // title band is separated from graphs to keep both readable
      auto& stable = Summaries[voice];
      if (IsDynamic())
      {
        stable.Update(gauges);
      }
      const auto& summary = stable.Text();
      if (SummaryKinds[voice] != gauges.Kind())
      {
        SummaryKinds[voice] = gauges.Kind();
        SummaryWidths[voice] = 0;
      }
      // summary width is reserved by widest one to not shift title with each summary change
      SummaryWidths[voice] = std::max(SummaryWidths[voice], canvas.MeasureText(summary, SummaryFont));
      const auto reserved = SummaryWidths[voice];
      const auto keyOn = gauges.IsKeyOn();
      // group name is omitted if there's no space for it
      const auto full = CardTitle(group, name, keyOn, true);
      const auto available = rect.Width() - Density * 9 - reserved;
      const auto title = canvas.MeasureText(full, TitleFont) <= available ? full : CardTitle(group, name, keyOn, false);
      const auto band = DrawTitleBand(canvas, rect, title, summary, reserved);
      RectF body;
      body.Set(rect.Left + pad * 2, band + pad, rect.Right - pad * 2, rect.Bottom - pad * 2);
      if (body.Height() < Density * 18)
      {
        // no space for details, only current level
        if (IsDynamic())
        {
          DrawHistory(canvas, body, gauges, false);
        }
        return;
      }
      RectF history;
      RectF widget;
      if (body.Width() > body.Height() * 2.2f)
      {
        // operators schemas need more space
        const auto kind = gauges.Kind();
        const auto schema = kind == VoiceKind::OPN_FM || kind == VoiceKind::OPL_2OP || kind == VoiceKind::OPL_4OP;
        const auto split = body.Left + body.Width() * (schema ? 0.35f : 0.45f);
        history.Set(body.Left, body.Top, split - pad * 2, body.Bottom);
        widget.Set(split + pad * 2, body.Top, body.Right, body.Bottom);
      }
      else
      {
        const auto split = body.Top + body.Height() * 0.4f;
        history.Set(body.Left, body.Top, body.Right, split - pad);
        widget.Set(body.Left, split + pad * 2, body.Right, body.Bottom);
      }
      if (IsDynamic())
      {
        if (Widgets.Draw(canvas, widget, gauges, smoothValues, WidgetTexts[voice]))
        {
          DrawHistory(canvas, history, gauges, true);
        }
        else
        {
          DrawHistory(canvas, body, gauges, true);
        }
      }
    }

    static std::string CardTitle(const std::string& group, const std::string& name, bool keyOn, bool withGroup)
    {
      std::string result;
      if (withGroup)
      {
        result = group + ' ';
      }
      result += name;
      if (keyOn)
      {
        result += " ●";
      }
      return result;
    }

    // Title has priority over summary: summary is shrunk and cut if both don't fit
    // @param reserved width of summary to keep title and summary size independent of the current summary
    // @return bottom of title band
    float DrawTitleBand(Canvas& canvas, const RectF& card, const std::string& title, const std::string& summary,
                        float reserved)
    {
      const auto pad = Density * 3;
      const auto titleMetrics = canvas.GetMetrics(TitleFont);
      const auto baseline = card.Top + pad - titleMetrics.Ascent;
      const auto baseSize = TitleFont.Size;
      const auto minSize = MIN_TITLE_SIZE * Density;
      const auto titleWidth = canvas.MeasureText(title, TitleFont);
      const auto inner = card.Width() - pad * 3;
      const auto room = inner - std::min(titleWidth, titleWidth * minSize / baseSize) - TitleGap();
      auto summaryFont = SummaryFont;
      if (reserved > room)
      {
        summaryFont.Size *= std::max(room / reserved, 0.8f);
      }
      const auto value = Ellipsize(canvas, summary, room, summaryFont);
      // gap keeps title and summary regions apart
      const auto available = inner - std::min(reserved * summaryFont.Size / SummaryFont.Size, room) - TitleGap();
      if (IsDynamic())
      {
        DrawCardTitle(canvas, title, card.Left + pad, baseline, available, titleWidth);
        if (value.size() >= 4 || value == summary)
        {
          canvas.DrawText(value, card.Right - pad, baseline, summaryFont, SUMMARY_COLOR, Align::RIGHT);
        }
      }
      const auto bottom = baseline + titleMetrics.Descent + pad;
      if (IsStatic())
      {
        canvas.DrawLine(card.Left, bottom, card.Right, bottom, FRAME_COLOR, Density);
      }
      return bottom;
    }

    // Raw title is at the left, value at the right in its own region of template width (or at the second line).
    // @param valueScale value text scale or 0 if not displayed
    // @return bottom of title band
    float DrawGaugeTitleBand(Canvas& canvas, const RectF& card, const LiveTexts& texts, const TitleTemplates& templates,
                             int idx, bool secondLine, float valueScale)
    {
      const auto pad = Density * 3;
      const auto titleMetrics = canvas.GetMetrics(TitleFont);
      const auto valueMetrics = canvas.GetMetrics(ValueFont);
      const auto baseline = card.Top + pad - titleMetrics.Ascent;
      const auto inner = card.Width() - pad * 3;
      const auto& title = texts[SidRowText::Title(idx)];
      float bottom = 0;
      if (!secondLine)
      {
        const auto valueWidth = valueScale > 0.f ? templates.ValueWidth(idx) * valueScale + TitleGap() : 0.f;
        if (IsDynamic())
        {
          DrawTitle(canvas, title, card.Left + pad, baseline, inner - valueWidth, templates.TitleWidth[idx]);
          if (valueScale > 0.f)
          {
            DrawGaugeValue(canvas, texts, templates, idx, card.Right - pad, baseline, valueScale,
                           templates.ValueWidth(idx) * valueScale);
          }
        }
        bottom = baseline + titleMetrics.Descent + pad;
      }
      else
      {
        const auto valueBaseline = baseline + titleMetrics.Descent - valueMetrics.Ascent;
        if (IsDynamic())
        {
          DrawTitle(canvas, title, card.Left + pad, baseline, inner, templates.TitleWidth[idx]);
          DrawGaugeValue(canvas, texts, templates, idx, card.Right - pad, valueBaseline, valueScale,
                         card.Width() - pad * 2);
        }
        bottom = valueBaseline + valueMetrics.Descent + pad;
      }
      if (IsStatic())
      {
        canvas.DrawLine(card.Left, bottom, card.Right, bottom, FRAME_COLOR, Density);
      }
      return bottom;
    }

    // Tail is aligned in its own fixed slot at the right, main part is right-aligned before it and cut to the rest
    // of value region
    void DrawGaugeValue(Canvas& canvas, const LiveTexts& texts, const TitleTemplates& templates, int idx, float right,
                        float baseline, float scale, float room)
    {
      const auto font = Scaled(ValueFont, scale);
      const auto tailWidth = templates.TailWidth[idx] * scale;
      const auto& tail = texts[SidRowText::Tail(idx)];
      if (!tail.empty())
      {
        if (templates.Items[idx].TailAlign == Align::LEFT)
        {
          canvas.DrawText(tail, right - tailWidth, baseline, font, VALUE_COLOR, Align::LEFT);
        }
        else
        {
          canvas.DrawText(tail, right, baseline, font, VALUE_COLOR, Align::RIGHT);
        }
      }
      const auto& main = texts[SidRowText::Main(idx)];
      if (!main.empty())
      {
        const auto mainRight = right - tailWidth - (tailWidth > 0.f ? templates.SpaceWidth * scale : 0.f);
        const auto available = room - (right - mainRight);
        if (available > 0.f)
        {
          canvas.DrawText(Ellipsize(canvas, main, available, font), mainRight, baseline, font, VALUE_COLOR,
                          Align::RIGHT);
        }
      }
    }

    // Shrinks title down to minimal size and then cuts it. Size is selected by widest title to not change it with
    // each value.
    void DrawTitle(Canvas& canvas, const std::string& fullTitle, float x, float y, float maxWidth, float templateWidth)
    {
      const auto baseSize = TitleFont.Size;
      const auto minSize = MIN_TITLE_SIZE * Density;
      // raw register values are more important than gauge name in narrow cells
      const auto shorten = templateWidth * minSize / baseSize > maxWidth;
      auto title = fullTitle;
      if (shorten)
      {
        const auto pos = title.find(" Wave ");
        if (pos != std::string::npos)
        {
          title.replace(pos, 6, " ");
        }
      }
      const auto actual = canvas.MeasureText(title, TitleFont);
      const auto fullWidth = canvas.MeasureText(fullTitle, TitleFont);
      const auto tmpl = shorten && fullWidth > 0 ? templateWidth * actual / fullWidth : templateWidth;
      // template may be narrower for some values, cutting them is worse than rare size change
      const auto width = std::max(tmpl, actual);
      auto font = TitleFont;
      font.Size = width > maxWidth ? std::max(baseSize * maxWidth / width, minSize) : baseSize;
      canvas.DrawText(Ellipsize(canvas, title, maxWidth, font), x, y, font, TITLE_COLOR, Align::LEFT);
    }

    // Shrinks title down to minimal size and then cuts it
    void DrawCardTitle(Canvas& canvas, const std::string& title, float x, float y, float available, float titleWidth)
    {
      auto font = TitleFont;
      if (titleWidth > available)
      {
        font.Size = std::max(TitleFont.Size * available / titleWidth, MIN_TITLE_SIZE * Density);
      }
      canvas.DrawText(Ellipsize(canvas, title, available, font), x, y, font, TITLE_COLOR, Align::LEFT);
    }

    // level (dB) as filled columns, pitch (log scale) as line over it
    void DrawHistory(Canvas& canvas, const RectF& area, const VoiceGauges& gauges, bool withPitch)
    {
      if (area.Height() <= 0 || area.Width() <= 0)
      {
        return;
      }
      const auto columnWidth = area.Width() / VoiceGauges::COLUMNS;
      // the last column is in progress, so history is scrolled smoothly by its part
      const auto shift = columnWidth * (1 - gauges.Phase());
      canvas.Save();
      canvas.ClipRect(area);
      for (int col = 0; col < VoiceGauges::COLUMNS; ++col)
      {
        const auto x = area.Left + columnWidth * (col + 0.5f) + shift;
        const auto idx = col * 4;
        Lines[idx] = x;
        Lines[idx + 1] = area.Bottom - area.Height() * gauges.Max(VoiceGauges::LEVEL, col) / 255.f;
        Lines[idx + 2] = x;
        Lines[idx + 3] = area.Bottom;
      }
      canvas.DrawSegments(Lines.data(), VoiceGauges::COLUMNS, DIM_TRACE_COLOR, std::max(columnWidth, Density));
      if (withPitch)
      {
        std::size_t count = 0;
        for (int col = 0; col < VoiceGauges::COLUMNS; ++col)
        {
          const auto value = gauges.Max(VoiceGauges::FREQUENCY, col);
          if (value == 0)
          {
            continue;
          }
          const auto x = area.Left + columnWidth * (col + 0.5f) + shift;
          const auto yMax = area.Bottom - area.Height() * value / 255.f;
          const auto yMin = area.Bottom - area.Height() * gauges.Min(VoiceGauges::FREQUENCY, col) / 255.f;
          Lines[count++] = x;
          Lines[count++] = yMax - Density;
          Lines[count++] = x;
          Lines[count++] = std::max(yMin, yMax) + Density;
        }
        canvas.DrawSegments(Lines.data(), count / 4, TRACE_COLOR, std::max(columnWidth, Density * 1.5f));
      }
      canvas.Restore();
    }

    // Each column is vertical line between min and max values, as in JSIDPlay2
    void DrawGauge(Canvas& canvas, const GaugesData& gauges, int gauge, float left, float top, float w, float h,
                   const LiveTexts& texts, const TitleTemplates& templates, int idx, bool secondLine, float valueScale)
    {
      const auto pad = Density;
      RectF rect;
      rect.Set(left + pad, top + pad, left + w - pad, top + h - pad);
      if (IsStatic())
      {
        canvas.StrokeRect(rect, FRAME_COLOR, Density);
      }
      const auto plotTop = DrawGaugeTitleBand(canvas, rect, texts, templates, idx, secondLine, valueScale) + pad * 2;
      const auto plotHeight = rect.Bottom - pad * 2 - plotTop;
      if (plotHeight <= 0 || !IsDynamic())
      {
        return;
      }
      const auto plotLeft = rect.Left + pad;
      const auto columnWidth = (rect.Width() - pad * 2) / ChipGauges::COLUMNS;
      // slow gauges' last column is in progress, so history is scrolled smoothly by its part
      const auto* chip = dynamic_cast<const ChipGauges*>(&gauges);
      const auto slow = chip && ChipGauges::IsSlow(gauge);
      const auto shift = slow ? columnWidth * (1 - chip->Phase()) : 0.f;
      if (slow)
      {
        canvas.Save();
        RectF clip;
        clip.Set(plotLeft, plotTop - pad, rect.Right - pad, rect.Bottom - pad);
        canvas.ClipRect(clip);
      }
      // minimal visible segment for flat parts
      const auto minHeight = TRACE_WIDTH * Density;
      for (int col = 0; col < ChipGauges::COLUMNS; ++col)
      {
        const auto x = plotLeft + columnWidth * (col + 0.5f) + shift;
        const auto yMax = plotTop + plotHeight * (1 - gauges.Max(gauge, col) / 255.f);
        const auto yMin = plotTop + plotHeight * (1 - gauges.Min(gauge, col) / 255.f);
        const auto center = (yMin + yMax) / 2;
        const auto half = std::max((yMin - yMax) / 2, minHeight / 2);
        const auto pos = col * 4;
        Lines[pos] = x;
        Lines[pos + 1] = center - half;
        Lines[pos + 2] = x;
        Lines[pos + 3] = center + half;
      }
      canvas.DrawSegments(Lines.data(), ChipGauges::COLUMNS, TRACE_COLOR, std::max(columnWidth, Density));
      if (slow)
      {
        canvas.Restore();
      }
    }

  private:
    const float Density;
    const Font TitleFont;
    const Font TextFont;
    const Font SummaryFont;
    const Font ValueFont;
    const Font CellFont;
    const Font CaptionFont;
    ChipWidgets Widgets;
    CellGrid VoiceGrid;
    CellGrid GlobalGrid;
    TitleTemplates VoiceTemplates;
    TitleTemplates GlobalTemplates;
    bool Measured = false;
    const Canvas* MeasureCanvas = nullptr;
    float CellLineBase = 0;
    float GridWidth = -1;
    float GridSpace = -1;
    float Width = 0;
    Pass Current = Pass::ALL;
    std::string Status;
    std::vector<std::string> TextLines;
    std::unique_ptr<ChipColumns> Columns;
    VoicesLayout::Ptr ColumnsLayout;
    std::array<float, ChipGauges::COLUMNS* 4> Lines = {};
    std::array<std::array<float, ChipWidgets::SMOOTH_SIZE>, MAX_VOICES> Smooth;
    std::array<ChipWidgets::TextMemo, MAX_VOICES> WidgetTexts;
    std::array<ChipWidgets::LiveSummary, MAX_VOICES> Summaries;
    std::array<float, MAX_VOICES> SummaryWidths = {};
    std::array<int, MAX_VOICES> SummaryKinds = {};
    std::array<std::vector<SidVoiceText>, MAX_CHIPS> VoiceTexts;
    std::array<SidGlobalText, MAX_CHIPS> GlobalTexts;
  };

  Dashboard::Dashboard(float density)
    : Delegate(std::make_unique<Impl>(density))
  {}

  Dashboard::~Dashboard() = default;

  void Dashboard::SetStatus(const std::string& status, const std::string& stats)
  {
    Delegate->SetStatus(status, stats);
  }

  uint64_t Dashboard::StaticKey(const GaugesFrame& frame, int width, int height) const
  {
    return Delegate->StaticKey(frame, width, height);
  }

  void Dashboard::Draw(Canvas& canvas, const GaugesFrame& frame, int width, int height, Pass pass)
  {
    Delegate->Draw(canvas, frame, width, height, pass);
  }
}  // namespace Visualization
