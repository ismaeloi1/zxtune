/**
 *
 * @file
 *
 * @brief Vulkan driver selection implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/zxtune-android/zxtune/src/main/vulkan/driver.h"

#ifdef VULKAN_SCOPE_ADRENOTOOLS
#  include "3rdparty/adrenotools/include/adrenotools/driver.h"

#  include <dlfcn.h>
#endif

namespace VulkanScope
{
  Library::Ptr OpenCustomDriver(const DriverParameters& params)
  {
#ifdef VULKAN_SCOPE_ADRENOTOOLS
    // System loader with hooked driver loading (as emulators do), so window system integration is still provided by
    // loader while driver is replaced. Directories should end with slash
    const auto tempDir = params.TempDir + '/';
    const auto hooksDir = params.HooksDir + '/';
    const auto driverDir = params.Dir + '/';
    auto* handle = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM, tempDir.c_str(),
                                              hooksDir.c_str(), driverDir.c_str(), params.Name.c_str(), nullptr,
                                              nullptr);
    if (auto lib = Library::FromHandle(handle, "custom driver " + params.Name))
    {
      return lib;
    }
    throw Error("Failed to load custom driver " + params.Name);
#else
    throw Error("Custom drivers are not supported on this platform");
#endif
  }

  Library::Ptr OpenDriver(const DriverParameters& params)
  {
    if (!params.Name.empty())
    {
      return OpenCustomDriver(params);
    }
    if (auto lib = Library::OpenSystem())
    {
      return lib;
    }
    throw Error("No Vulkan library");
  }
}  // namespace VulkanScope
