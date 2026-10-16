/**
 *
 * @file
 *
 * @brief Dynamically loaded Vulkan API implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/zxtune-android/zxtune/src/main/vulkan/api.h"

#include <dlfcn.h>

namespace VulkanScope
{
  void Api::LoadInstance(VkInstance instance)
  {
#define VULKAN_SCOPE_LOAD(name) name = reinterpret_cast<PFN_##name>(vkGetInstanceProcAddr(instance, #name));
    VULKAN_SCOPE_INSTANCE_FUNCTIONS(VULKAN_SCOPE_LOAD)
    VULKAN_SCOPE_SURFACE_FUNCTIONS(VULKAN_SCOPE_LOAD)
#ifdef VK_USE_PLATFORM_ANDROID_KHR
    VULKAN_SCOPE_LOAD(vkCreateAndroidSurfaceKHR)
#endif
#undef VULKAN_SCOPE_LOAD
    if (!vkCreateDevice || !vkGetDeviceProcAddr || !vkEnumeratePhysicalDevices)
    {
      throw Error("Incomplete Vulkan instance");
    }
  }

  void Api::LoadDevice(VkDevice device)
  {
    // device level functions bypass loader's dispatch
#define VULKAN_SCOPE_LOAD(name)                                                                                        \
  if (!(name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(device, #name))))                                      \
  {                                                                                                                    \
    throw Error("Missing " #name);                                                                                     \
  }
    VULKAN_SCOPE_DEVICE_FUNCTIONS(VULKAN_SCOPE_LOAD)
#undef VULKAN_SCOPE_LOAD
#define VULKAN_SCOPE_LOAD(name) name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(device, #name));
    VULKAN_SCOPE_SWAPCHAIN_FUNCTIONS(VULKAN_SCOPE_LOAD)
#undef VULKAN_SCOPE_LOAD
  }

  class DynamicLibrary : public Library
  {
  public:
    DynamicLibrary(void* handle, std::string description)
      : Handle(handle)
      , Description(std::move(description))
      , GetProcAddr(reinterpret_cast<PFN_vkGetInstanceProcAddr>(::dlsym(handle, "vkGetInstanceProcAddr")))
    {}

    ~DynamicLibrary() override
    {
      ::dlclose(Handle);
    }

    PFN_vkGetInstanceProcAddr GetInstanceProcAddr() const override
    {
      return GetProcAddr;
    }

    std::string GetDescription() const override
    {
      return Description;
    }

  private:
    void* const Handle;
    const std::string Description;
    const PFN_vkGetInstanceProcAddr GetProcAddr;
  };

  Library::Ptr Library::FromHandle(void* handle, const std::string& description)
  {
    if (!handle)
    {
      return {};
    }
    auto result = std::make_shared<DynamicLibrary>(handle, description);
    return result->GetInstanceProcAddr() ? result : Ptr();
  }

  Library::Ptr Library::Open(const std::string& path)
  {
    return FromHandle(::dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL), path);
  }

  Library::Ptr Library::OpenSystem()
  {
#ifdef __ANDROID__
    return Open("libvulkan.so");
#else
    return Open("libvulkan.so.1");
#endif
  }
}  // namespace VulkanScope
