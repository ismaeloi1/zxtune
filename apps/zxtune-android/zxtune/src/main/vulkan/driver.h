/**
 *
 * @file
 *
 * @brief Vulkan driver selection
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "apps/zxtune-android/zxtune/src/main/vulkan/api.h"

namespace VulkanScope
{
  struct DriverParameters
  {
    //! directory with custom driver library, empty for system driver
    std::string Dir;
    //! custom driver library file name
    std::string Name;
    //! directory with hook libraries (application's native libraries dir)
    std::string HooksDir;
    //! writable directory for temporary files
    std::string TempDir;
  };

  //! @throws Error if driver cannot be loaded
  Library::Ptr OpenDriver(const DriverParameters& params);
}  // namespace VulkanScope
