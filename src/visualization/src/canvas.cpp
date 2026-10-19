/**
 *
 * @file
 *
 * @brief Toolkit independent text fitting helpers
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "visualization/canvas.h"

#include <algorithm>

namespace Visualization
{
  // UTF-8 continuation bytes are skipped to never cut a character in the middle
  std::size_t NextCharacter(std::string_view text, std::size_t pos)
  {
    ++pos;
    while (pos < text.size() && (static_cast<uint8_t>(text[pos]) & 0xc0) == 0x80)
    {
      ++pos;
    }
    return pos;
  }

  std::size_t BreakText(const Canvas& canvas, std::string_view text, float maxWidth, const Font& font)
  {
    if (canvas.MeasureText(text, font) <= maxWidth)
    {
      return text.size();
    }
    // texts are short, linear search is simpler than binary one with characters boundaries
    std::size_t fit = 0;
    for (std::size_t pos = NextCharacter(text, 0); pos <= text.size(); pos = NextCharacter(text, pos))
    {
      if (canvas.MeasureText(text.substr(0, pos), font) > maxWidth)
      {
        break;
      }
      fit = pos;
      if (pos == text.size())
      {
        break;
      }
    }
    return fit;
  }

  std::string Ellipsize(const Canvas& canvas, std::string_view text, float maxWidth, const Font& font)
  {
    if (canvas.MeasureText(text, font) <= maxWidth)
    {
      return std::string(text);
    }
    constexpr std::string_view ELLIPSIS = "…";
    const auto room = std::max(maxWidth - canvas.MeasureText(ELLIPSIS, font), 0.f);
    auto count = BreakText(canvas, text, room, font);
    if (count == 0)
    {
      return {};
    }
    while (count > 0 && (text[count - 1] == ' ' || text[count - 1] == ','))
    {
      --count;
    }
    std::string result(text.substr(0, count));
    result += ELLIPSIS;
    return result;
  }
}  // namespace Visualization
