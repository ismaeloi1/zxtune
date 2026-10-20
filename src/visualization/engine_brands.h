/**
 *
 * @file
 *
 * @brief Engine brands and original driver lines of playback status
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include <functional>
#include <string>
#include <vector>

namespace Visualization
{
  /**
   * Port of Android's EngineBrands.kt, so all the frontends display the same "Running on <brand>" line.
   * Keep in sync when renaming an engine or moving format/chip to another engine.
   */
  namespace EngineBrands
  {
    //! @return engine brand of system tag (e.g. "C64"), brand of default system for unknown tag
    std::string Brand(const std::string& system);

    //! @param type module type (core plugin id)
    //! @param description emulation description, the first line of Sound::Scope::GetStatus
    //! @return system tag (e.g. "C64", "AY", "PCM")
    std::string SystemOf(const std::string& type, const std::string& description);

    std::string BrandOf(const std::string& type, const std::string& description);
  }  // namespace EngineBrands

  //! Port of Android's StatusHeader.kt: original driver and engine lines displayed instead of tune format line
  namespace StatusHeader
  {
    constexpr auto DRIVER_PREFIX = "Original driver: ";
    constexpr auto ENGINE_PREFIX = "Running on ";

    //! @param type module type
    //! @param program module program property (tracker, detected playroutine or game name)
    //! @param formatName known format description, fallback for unknown program
    std::string DriverOf(const std::string& type, const std::string& program,
                         const std::function<std::string()>& formatName);

    bool IsHeader(const std::string& line);

    //! @return driver and engine lines
    std::vector<std::string> Lines(const std::string& type, const std::string& program, const std::string& description,
                                   const std::function<std::string()>& formatName);

    //! Inserts header right after emulation description line (if any) of scope status, so the first line is still a
    //! description
    std::string Insert(const std::string& status, const std::string& type, const std::string& program,
                       const std::function<std::string()>& formatName);
  }  // namespace StatusHeader
}  // namespace Visualization
