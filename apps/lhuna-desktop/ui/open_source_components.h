/**
 *
 * @file
 *
 * @brief Free and open source components used by desktop player
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <string>
#include <vector>

namespace Lhuna::OpenSourceComponents
{
  enum class Category
  {
    PLAYBACK,
    CODECS,
    SYSTEM,
    DESKTOP,
    REFERENCE
  };

  struct Component
  {
    std::string Name;
    std::string Authors;
    std::string License;
    std::string Url;
    Category Kind;
    //! 3rdparty/ subdirectory if any
    std::string Dir;
  };

  /**
   * Port of Android's ui/about/OpenSourceComponents.kt with desktop specific libraries instead of Android ones.
   * Keep in sync with 3rdparty/ directory and libraries linked by core/Makefile.
   */
  const std::vector<Component>& All();

  std::string Title(Category kind);

  //! Two lines per component: name with authors, license with link (as OpenSourceComponentsFormat.kt)
  std::string ToHtml(const std::vector<Component>& components);
}  // namespace Lhuna::OpenSourceComponents
