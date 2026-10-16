/**
 *
 * @file
 *
 * @brief Vulkan instance and device implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/zxtune-android/zxtune/src/main/vulkan/device.h"

#include <cstdio>

namespace VulkanScope
{
  Device::Device(Library::Ptr library, const Options& options)
    : Lib(std::move(library))
  {
    Functions.vkGetInstanceProcAddr = Lib->GetInstanceProcAddr();
#define VULKAN_SCOPE_LOAD(name)                                                                                        \
  if (!(Functions.name = reinterpret_cast<PFN_##name>(Functions.vkGetInstanceProcAddr(VK_NULL_HANDLE, #name))))        \
  {                                                                                                                    \
    throw Error("Missing " #name);                                                                                     \
  }
    VULKAN_SCOPE_GLOBAL_FUNCTIONS(VULKAN_SCOPE_LOAD)
#undef VULKAN_SCOPE_LOAD

    VkApplicationInfo app = {};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "zxtune";
    // only 1.0 features are used to support the oldest drivers (Android 7)
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo instanceInfo = {};
    instanceInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.pApplicationInfo = &app;
    instanceInfo.enabledExtensionCount = static_cast<uint32_t>(options.InstanceExtensions.size());
    instanceInfo.ppEnabledExtensionNames = options.InstanceExtensions.data();
    Check(Functions.vkCreateInstance(&instanceInfo, nullptr, &InstanceHandle), "vkCreateInstance");
    try
    {
      Functions.LoadInstance(InstanceHandle);
      if (options.CreateSurface)
      {
        SurfaceHandle = options.CreateSurface(Functions, InstanceHandle);
      }

      uint32_t count = 0;
      Check(Functions.vkEnumeratePhysicalDevices(InstanceHandle, &count, nullptr), "vkEnumeratePhysicalDevices");
      std::vector<VkPhysicalDevice> devices(count);
      Check(Functions.vkEnumeratePhysicalDevices(InstanceHandle, &count, devices.data()), "vkEnumeratePhysicalDevices");
      for (auto dev : devices)
      {
        uint32_t families = 0;
        Functions.vkGetPhysicalDeviceQueueFamilyProperties(dev, &families, nullptr);
        std::vector<VkQueueFamilyProperties> props(families);
        Functions.vkGetPhysicalDeviceQueueFamilyProperties(dev, &families, props.data());
        for (uint32_t idx = 0; idx < families; ++idx)
        {
          if (0 == (props[idx].queueFlags & VK_QUEUE_GRAPHICS_BIT))
          {
            continue;
          }
          if (SurfaceHandle)
          {
            VkBool32 supported = VK_FALSE;
            if (Functions.vkGetPhysicalDeviceSurfaceSupportKHR(dev, idx, SurfaceHandle, &supported) != VK_SUCCESS
                || !supported)
            {
              continue;
            }
          }
          PhysicalHandle = dev;
          Family = idx;
          break;
        }
        if (PhysicalHandle)
        {
          break;
        }
      }
      if (!PhysicalHandle)
      {
        throw Error("No suitable device");
      }
      Functions.vkGetPhysicalDeviceProperties(PhysicalHandle, &DeviceProperties);
      Functions.vkGetPhysicalDeviceMemoryProperties(PhysicalHandle, &Memory);

      const float priority = 1.0f;
      VkDeviceQueueCreateInfo queueInfo = {};
      queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
      queueInfo.queueFamilyIndex = Family;
      queueInfo.queueCount = 1;
      queueInfo.pQueuePriorities = &priority;
      const char* swapchainExtension = VK_KHR_SWAPCHAIN_EXTENSION_NAME;
      VkDeviceCreateInfo deviceInfo = {};
      deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
      deviceInfo.queueCreateInfoCount = 1;
      deviceInfo.pQueueCreateInfos = &queueInfo;
      if (SurfaceHandle)
      {
        deviceInfo.enabledExtensionCount = 1;
        deviceInfo.ppEnabledExtensionNames = &swapchainExtension;
      }
      Check(Functions.vkCreateDevice(PhysicalHandle, &deviceInfo, nullptr, &DeviceHandle), "vkCreateDevice");
      Functions.LoadDevice(DeviceHandle);
      Functions.vkGetDeviceQueue(DeviceHandle, Family, 0, &QueueHandle);
    }
    catch (...)
    {
      Destroy();
      throw;
    }
  }

  Device::~Device()
  {
    Destroy();
  }

  void Device::Destroy()
  {
    if (DeviceHandle)
    {
      Functions.vkDeviceWaitIdle(DeviceHandle);
      Functions.vkDestroyDevice(DeviceHandle, nullptr);
      DeviceHandle = VK_NULL_HANDLE;
    }
    if (SurfaceHandle)
    {
      Functions.vkDestroySurfaceKHR(InstanceHandle, SurfaceHandle, nullptr);
      SurfaceHandle = VK_NULL_HANDLE;
    }
    if (InstanceHandle)
    {
      Functions.vkDestroyInstance(InstanceHandle, nullptr);
      InstanceHandle = VK_NULL_HANDLE;
    }
  }

  std::string Device::GetDescription() const
  {
    const auto api = DeviceProperties.apiVersion;
    const auto drv = DeviceProperties.driverVersion;
    return std::string(DeviceProperties.deviceName) + " (Vulkan " + std::to_string(VK_VERSION_MAJOR(api)) + "."
           + std::to_string(VK_VERSION_MINOR(api)) + "." + std::to_string(VK_VERSION_PATCH(api)) + ", driver 0x" +
           [drv]() {
             char buf[16];
             std::snprintf(buf, sizeof(buf), "%x", drv);
             return std::string(buf);
           }()
           + ", " + Lib->GetDescription() + ")";
  }

  uint32_t Device::FindMemory(uint32_t typeBits, VkMemoryPropertyFlags required, VkMemoryPropertyFlags preferred) const
  {
    for (const auto flags : {required | preferred, required})
    {
      for (uint32_t idx = 0; idx < Memory.memoryTypeCount; ++idx)
      {
        if ((typeBits & (1u << idx)) && (Memory.memoryTypes[idx].propertyFlags & flags) == flags)
        {
          return idx;
        }
      }
    }
    throw Error("No suitable memory type");
  }
}  // namespace VulkanScope
