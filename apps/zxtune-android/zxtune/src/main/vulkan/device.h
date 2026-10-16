/**
 *
 * @file
 *
 * @brief Vulkan instance and device
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "apps/zxtune-android/zxtune/src/main/vulkan/api.h"

#include <functional>
#include <vector>

namespace VulkanScope
{
  class Device
  {
  public:
    struct Options
    {
      //! e.g. surface extensions, all required
      std::vector<const char*> InstanceExtensions;
      //! creates surface to check presentation support, may be empty for offscreen rendering
      std::function<VkSurfaceKHR(const Api&, VkInstance)> CreateSurface;
    };

    Device(Library::Ptr library, const Options& options);
    ~Device();

    Device(const Device&) = delete;
    Device& operator=(const Device&) = delete;

    const Api& Vk() const
    {
      return Functions;
    }

    VkInstance Instance() const
    {
      return InstanceHandle;
    }

    VkPhysicalDevice Physical() const
    {
      return PhysicalHandle;
    }

    VkDevice Handle() const
    {
      return DeviceHandle;
    }

    VkQueue Queue() const
    {
      return QueueHandle;
    }

    uint32_t QueueFamily() const
    {
      return Family;
    }

    //! surface created by Options::CreateSurface, owned by device
    VkSurfaceKHR Surface() const
    {
      return SurfaceHandle;
    }

    const VkPhysicalDeviceProperties& Properties() const
    {
      return DeviceProperties;
    }

    std::string GetDescription() const;

    //! @return memory type index with all the required flags, preferred ones are tried first
    uint32_t FindMemory(uint32_t typeBits, VkMemoryPropertyFlags required, VkMemoryPropertyFlags preferred) const;

  private:
    void Destroy();

  private:
    const Library::Ptr Lib;
    Api Functions;
    VkInstance InstanceHandle = VK_NULL_HANDLE;
    VkSurfaceKHR SurfaceHandle = VK_NULL_HANDLE;
    VkPhysicalDevice PhysicalHandle = VK_NULL_HANDLE;
    VkDevice DeviceHandle = VK_NULL_HANDLE;
    VkQueue QueueHandle = VK_NULL_HANDLE;
    uint32_t Family = 0;
    VkPhysicalDeviceProperties DeviceProperties = {};
    VkPhysicalDeviceMemoryProperties Memory = {};
  };
}  // namespace VulkanScope
