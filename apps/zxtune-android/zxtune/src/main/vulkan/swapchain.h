/**
 *
 * @file
 *
 * @brief Oscilloscope presentation to surface
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "apps/zxtune-android/zxtune/src/main/vulkan/renderer.h"

#include <memory>

namespace VulkanScope
{
  /*
   * FIFO (vsync) presentation of 2 frames in flight with pre-rotation, so compositor doesn't rotate the picture.
   * Frames are rendered only on request (when data changed), last presented picture is kept by compositor.
   */
  class Swapchain
  {
  public:
    //! @param device with surface
    explicit Swapchain(std::unique_ptr<Device> device);
    ~Swapchain();

    enum class Result
    {
      PRESENTED,
      //! swapchain recreated with different logical size, background should be updated
      RESIZED,
      //! surface is temporarily unusable (e.g. zero size)
      SKIPPED,
    };

    //! @brief Recreate swapchain on surface changes
    Result Update();

    Result Render(const int16_t* samples, uint32_t channels, uint32_t points, int gainPercent);

    ScopeRenderer& Renderer()
    {
      return *ScopeRender;
    }

    std::string GetDescription() const;

  private:
    void Create();
    void DestroySync();

  private:
    static const uint32_t FRAMES_IN_FLIGHT = 2;

    const std::unique_ptr<Device> Dev;
    const Api& Vk;
    VkSurfaceFormatKHR Format = {};
    std::unique_ptr<ScopeRenderer> ScopeRender;
    VkSwapchainKHR Handle = VK_NULL_HANDLE;
    std::vector<VkImage> Images;
    std::vector<VkSemaphore> RenderDone;
    std::vector<VkFence> ImageFences;
    VkSemaphore Acquired[FRAMES_IN_FLIGHT] = {};
    VkFence InFlight[FRAMES_IN_FLIGHT] = {};
    uint32_t Slot = 0;
    VkExtent2D Extent = {};
    VkSurfaceTransformFlagBitsKHR Transform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    uint32_t ImagesCount = 0;
    bool NeedRecreate = false;
  };
}  // namespace VulkanScope
