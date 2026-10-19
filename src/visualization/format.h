/**
 *
 * @file
 *
 * @brief printf-like formatting to std::string
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <algorithm>
#include <cstdio>
#include <string>

namespace Visualization
{
  // texts are short (titles, cells), so fixed buffer is enough and avoids double formatting
  template<class... Args>
  std::string Format(const char* format, Args... args)
  {
    char buf[128];
    const int size = std::snprintf(buf, sizeof(buf), format, args...);
    return size <= 0 ? std::string() : std::string(buf, std::min<std::size_t>(size, sizeof(buf) - 1));
  }
}  // namespace Visualization
