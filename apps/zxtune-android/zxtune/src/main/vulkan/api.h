/**
 *
 * @file
 *
 * @brief Dynamically loaded Vulkan API
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

// Vulkan library is loaded at runtime, so application still works with OpenGL ES on systems without it
#define VK_NO_PROTOTYPES
#ifdef __ANDROID__
#  define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include <vulkan/vulkan.h>

#include <memory>
#include <stdexcept>
#include <string>

namespace VulkanScope
{
#define VULKAN_SCOPE_GLOBAL_FUNCTIONS(F)                                                                               \
  F(vkCreateInstance)                                                                                                  \
  F(vkEnumerateInstanceExtensionProperties)

#define VULKAN_SCOPE_INSTANCE_FUNCTIONS(F)                                                                             \
  F(vkDestroyInstance)                                                                                                 \
  F(vkEnumeratePhysicalDevices)                                                                                        \
  F(vkGetPhysicalDeviceProperties)                                                                                     \
  F(vkGetPhysicalDeviceQueueFamilyProperties)                                                                          \
  F(vkGetPhysicalDeviceMemoryProperties)                                                                               \
  F(vkCreateDevice)                                                                                                    \
  F(vkGetDeviceProcAddr)                                                                                               \
  F(vkEnumerateDeviceExtensionProperties)

// may be missing for headless instance
#define VULKAN_SCOPE_SURFACE_FUNCTIONS(F)                                                                              \
  F(vkDestroySurfaceKHR)                                                                                               \
  F(vkGetPhysicalDeviceSurfaceSupportKHR)                                                                              \
  F(vkGetPhysicalDeviceSurfaceCapabilitiesKHR)                                                                         \
  F(vkGetPhysicalDeviceSurfaceFormatsKHR)                                                                              \
  F(vkGetPhysicalDeviceSurfacePresentModesKHR)

#define VULKAN_SCOPE_DEVICE_FUNCTIONS(F)                                                                               \
  F(vkDestroyDevice)                                                                                                   \
  F(vkGetDeviceQueue)                                                                                                  \
  F(vkDeviceWaitIdle)                                                                                                  \
  F(vkQueueSubmit)                                                                                                     \
  F(vkCreateBuffer)                                                                                                    \
  F(vkDestroyBuffer)                                                                                                   \
  F(vkGetBufferMemoryRequirements)                                                                                     \
  F(vkAllocateMemory)                                                                                                  \
  F(vkFreeMemory)                                                                                                      \
  F(vkBindBufferMemory)                                                                                                \
  F(vkMapMemory)                                                                                                       \
  F(vkUnmapMemory)                                                                                                     \
  F(vkCreateImage)                                                                                                     \
  F(vkDestroyImage)                                                                                                    \
  F(vkGetImageMemoryRequirements)                                                                                      \
  F(vkBindImageMemory)                                                                                                 \
  F(vkCreateImageView)                                                                                                 \
  F(vkDestroyImageView)                                                                                                \
  F(vkCreateSampler)                                                                                                   \
  F(vkDestroySampler)                                                                                                  \
  F(vkCreateShaderModule)                                                                                              \
  F(vkDestroyShaderModule)                                                                                             \
  F(vkCreatePipelineLayout)                                                                                            \
  F(vkDestroyPipelineLayout)                                                                                           \
  F(vkCreateGraphicsPipelines)                                                                                         \
  F(vkDestroyPipeline)                                                                                                 \
  F(vkCreateRenderPass)                                                                                                \
  F(vkDestroyRenderPass)                                                                                               \
  F(vkCreateFramebuffer)                                                                                               \
  F(vkDestroyFramebuffer)                                                                                              \
  F(vkCreateDescriptorSetLayout)                                                                                       \
  F(vkDestroyDescriptorSetLayout)                                                                                      \
  F(vkCreateDescriptorPool)                                                                                            \
  F(vkDestroyDescriptorPool)                                                                                           \
  F(vkAllocateDescriptorSets)                                                                                          \
  F(vkFreeDescriptorSets)                                                                                              \
  F(vkUpdateDescriptorSets)                                                                                            \
  F(vkCreateCommandPool)                                                                                               \
  F(vkDestroyCommandPool)                                                                                              \
  F(vkAllocateCommandBuffers)                                                                                          \
  F(vkFreeCommandBuffers)                                                                                              \
  F(vkBeginCommandBuffer)                                                                                              \
  F(vkEndCommandBuffer)                                                                                                \
  F(vkResetCommandBuffer)                                                                                              \
  F(vkCmdBeginRenderPass)                                                                                              \
  F(vkCmdEndRenderPass)                                                                                                \
  F(vkCmdBindPipeline)                                                                                                 \
  F(vkCmdBindDescriptorSets)                                                                                           \
  F(vkCmdPushConstants)                                                                                                \
  F(vkCmdSetViewport)                                                                                                  \
  F(vkCmdSetScissor)                                                                                                   \
  F(vkCmdDraw)                                                                                                         \
  F(vkCmdDrawIndirect)                                                                                                 \
  F(vkCmdPipelineBarrier)                                                                                              \
  F(vkCmdCopyBufferToImage)                                                                                            \
  F(vkCmdCopyImageToBuffer)                                                                                            \
  F(vkCreateFence)                                                                                                     \
  F(vkDestroyFence)                                                                                                    \
  F(vkWaitForFences)                                                                                                   \
  F(vkResetFences)                                                                                                     \
  F(vkCreateSemaphore)                                                                                                 \
  F(vkDestroySemaphore)

#define VULKAN_SCOPE_SWAPCHAIN_FUNCTIONS(F)                                                                            \
  F(vkCreateSwapchainKHR)                                                                                              \
  F(vkDestroySwapchainKHR)                                                                                             \
  F(vkGetSwapchainImagesKHR)                                                                                           \
  F(vkAcquireNextImageKHR)                                                                                             \
  F(vkQueuePresentKHR)

#define VULKAN_SCOPE_DECLARE(name) PFN_##name name = nullptr;

  //! @brief Functions table, filled step by step: library, instance, device
  struct Api
  {
    PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr = nullptr;
    VULKAN_SCOPE_GLOBAL_FUNCTIONS(VULKAN_SCOPE_DECLARE)
    VULKAN_SCOPE_INSTANCE_FUNCTIONS(VULKAN_SCOPE_DECLARE)
    VULKAN_SCOPE_SURFACE_FUNCTIONS(VULKAN_SCOPE_DECLARE)
#ifdef VK_USE_PLATFORM_ANDROID_KHR
    PFN_vkCreateAndroidSurfaceKHR vkCreateAndroidSurfaceKHR = nullptr;
#endif
    VULKAN_SCOPE_DEVICE_FUNCTIONS(VULKAN_SCOPE_DECLARE)
    VULKAN_SCOPE_SWAPCHAIN_FUNCTIONS(VULKAN_SCOPE_DECLARE)

    void LoadInstance(VkInstance instance);
    void LoadDevice(VkDevice device);
  };

#undef VULKAN_SCOPE_DECLARE

  class Error : public std::runtime_error
  {
  public:
    Error(const char* call, VkResult result)
      : std::runtime_error(std::string(call) + " failed: " + std::to_string(result))
      , Result(result)
    {}

    explicit Error(const std::string& msg)
      : std::runtime_error(msg)
      , Result(VK_ERROR_UNKNOWN)
    {}

    const VkResult Result;
  };

  inline void Check(VkResult res, const char* call)
  {
    if (res != VK_SUCCESS)
    {
      throw Error(call, res);
    }
  }

  //! @brief Vulkan loader library (system one or custom driver)
  class Library
  {
  public:
    using Ptr = std::shared_ptr<const Library>;

    virtual ~Library() = default;

    virtual PFN_vkGetInstanceProcAddr GetInstanceProcAddr() const = 0;
    //! @return human readable description (e.g. path)
    virtual std::string GetDescription() const = 0;

    //! @brief System Vulkan loader, nullptr if not available
    static Ptr OpenSystem();
    //! @brief Library at specified path (e.g. ICD for tests)
    static Ptr Open(const std::string& path);
    //! @param handle dlopen()-ed library handle, closed on destruction
    static Ptr FromHandle(void* handle, const std::string& description);
  };
}  // namespace VulkanScope
