/**
 *
 * @file
 *
 * @brief Voices layout decoding and columns placement
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "visualization/voices_layout.h"

#include "visualization/gauges.h"

#include <algorithm>
#include <numeric>

namespace Visualization
{
  std::string_view VoicesLayout::Group::ChipName() const
  {
    const std::string_view name(Name);
    return name.substr(0, name.find('/'));
  }

  std::size_t VoicesLayout::VoicesCount() const
  {
    std::size_t result = 0;
    for (const auto& group : Groups)
    {
      result += group.Voices.size();
    }
    return result;
  }

  std::vector<std::string_view> SplitNonEmpty(std::string_view text, char delimiter, bool keepEmpty)
  {
    std::vector<std::string_view> result;
    for (std::size_t pos = 0; pos <= text.size();)
    {
      const auto end = std::min(text.find(delimiter, pos), text.size());
      if (keepEmpty || end != pos)
      {
        result.emplace_back(text.substr(pos, end - pos));
      }
      pos = end + 1;
    }
    return result;
  }

  VoicesLayout::Ptr VoicesLayout::Parse(std::string_view description)
  {
    const auto lines = SplitNonEmpty(description, '\n', false);
    if (lines.size() < 2)
    {
      return Master();
    }
    auto result = std::make_shared<VoicesLayout>();
    result->HasRegisters = lines.front() == "registers";
    for (auto it = lines.begin() + 1; it != lines.end(); ++it)
    {
      const auto fields = SplitNonEmpty(*it, '\t', true);
      Group group;
      group.Name = fields.front();
      for (auto field = fields.begin() + 1; field != fields.end(); ++field)
      {
        group.Voices.emplace_back(*field);
      }
      result->Groups.emplace_back(std::move(group));
    }
    return result;
  }

  VoicesLayout::Ptr VoicesLayout::Master()
  {
    static const auto INSTANCE = std::make_shared<VoicesLayout>();
    return INSTANCE;
  }

  /*
   * Mega Drive's FM chip has much more state to show (operators schemas) than its PSG, so it gets 2/3 of width.
   * Other combinations (e.g. OPL with rhythm, YM2203 FM and SSG parts) keep equal columns.
   */
  int ColumnWeightOf(const VoicesLayout::Group& group)
  {
    const auto name = group.ChipName();
    return name == "YM2612" || name == "YM3438" ? 2 : 1;
  }

  ChipColumns::ChipColumns(const std::vector<VoicesLayout::Group>& groups)
    : FirstColumn(groups.size())
    , GroupRows(groups.size())
  {
    int cols = 0;
    int rows = 1;
    std::vector<int> weights;
    for (std::size_t idx = 0; idx < groups.size(); ++idx)
    {
      const int voices = static_cast<int>(groups[idx].Voices.size());
      // split long chips (e.g. OPL3 with 23 voices) to equal columns
      const int groupCols = std::max((voices + MAX_ROWS - 1) / MAX_ROWS, 1);
      FirstColumn[idx] = cols;
      GroupRows[idx] = std::max((voices + groupCols - 1) / groupCols, 1);
      weights.insert(weights.end(), groupCols, ColumnWeightOf(groups[idx]));
      cols += groupCols;
      rows = std::max(rows, GroupRows[idx]);
    }
    ColumnsCount = std::max(cols, 1);
    RowsCount = rows;
    const auto total = static_cast<float>(std::max(std::accumulate(weights.begin(), weights.end(), 0), 1));
    Edges.resize(ColumnsCount + 1);
    int sum = 0;
    for (std::size_t col = 0; col < weights.size(); ++col)
    {
      Edges[col] = sum / total;
      sum += weights[col];
    }
    Edges[ColumnsCount] = 1.f;
  }

  float ChipColumns::Narrowest() const
  {
    float result = 1.f;
    for (int col = 0; col < ColumnsCount; ++col)
    {
      result = std::min(result, Width(col));
    }
    return result;
  }

  std::string VoiceGauges::Text() const
  {
    const int start = STATE + 10 + FIELDS;
    std::string result;
    for (int pos = start; pos < start + TEXT && Data[pos] != 0; ++pos)
    {
      // ISO-8859-1 to UTF-8
      const auto chr = Data[pos];
      if (chr < 0x80)
      {
        result += static_cast<char>(chr);
      }
      else
      {
        result += static_cast<char>(0xc0 | (chr >> 6));
        result += static_cast<char>(0x80 | (chr & 0x3f));
      }
    }
    return result;
  }
}  // namespace Visualization
