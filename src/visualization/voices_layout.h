/**
 *
 * @file
 *
 * @brief Decoded voices layout (Sound::Scope::GetLayout) and its placement by columns
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <memory>
#include <string>
#include <string_view>
#include <vector>

namespace Visualization
{
  struct VoicesLayout
  {
    using Ptr = std::shared_ptr<const VoicesLayout>;

    struct Group
    {
      std::string Name;
      std::vector<std::string> Voices;

      //! multiple instances of the same chip are suffixed by '/N'
      std::string_view ChipName() const;
    };

    //! SID-like chips with registers state (Sound::Scope::GetGauges), else Sound::Scope::GetVoiceGauges
    bool HasRegisters = false;
    //! empty for single master channel
    std::vector<Group> Groups;

    std::size_t VoicesCount() const;

    static Ptr Parse(std::string_view description);
    static Ptr Master();
  };

  /**
   * Chips (voices groups) are placed side by side, long chips are split to several columns of at most MAX_ROWS
   * voices. Both oscilloscope and dashboard use it, so voices of the same chip are aligned vertically.
   */
  class ChipColumns
  {
  public:
    static const int MAX_ROWS = 8;

    explicit ChipColumns(const std::vector<VoicesLayout::Group>& groups);

    int Columns() const
    {
      return ColumnsCount;
    }

    int MaxRows() const
    {
      return RowsCount;
    }

    int Rows(int group) const
    {
      return GroupRows[group];
    }

    int Column(int group, int voice) const
    {
      return FirstColumn[group] + voice / GroupRows[group];
    }

    int Row(int group, int voice) const
    {
      return voice % GroupRows[group];
    }

    //! relative (0..1) edges of columns
    float Left(int column) const
    {
      return Edges[column];
    }

    float Right(int column) const
    {
      return Edges[column + 1];
    }

    float Width(int column) const
    {
      return Edges[column + 1] - Edges[column];
    }

    float Narrowest() const;

  private:
    std::vector<int> FirstColumn;
    std::vector<int> GroupRows;
    std::vector<float> Edges;
    int ColumnsCount = 1;
    int RowsCount = 1;
  };
}  // namespace Visualization
