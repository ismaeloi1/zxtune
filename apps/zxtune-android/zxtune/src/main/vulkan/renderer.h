/**
 *
 * @file
 *
 * @brief Oscilloscope rendering with Vulkan, independent of presentation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#pragma once

#include "apps/zxtune-android/zxtune/src/main/vulkan/device.h"

#include <array>
#include <vector>

namespace VulkanScope
{
  enum class Rotation
  {
    NONE,
    CW90,
    CW180,
    CW270,
  };

  /*
   * Per frame work: samples copy to persistently mapped buffer of target image and submit of prerecorded command buffer
   * (static background texture and single instanced draw of all the traces with parameters in the same buffer).
   */
  class ScopeRenderer
  {
  public:
    static const uint32_t MAX_CHANNELS = 32;
    static const uint32_t MAX_POINTS = 2048;
    static const int AUTO_GAIN = 0;

    //! @param finalLayout layout of targets after rendering (e.g. for presentation or copying)
    ScopeRenderer(const Device& device, VkFormat format, VkImageLayout finalLayout);
    ~ScopeRenderer();

    ScopeRenderer(const ScopeRenderer&) = delete;
    ScopeRenderer& operator=(const ScopeRenderer&) = delete;

    //! @param extent size of images
    //! @param rotation of logical view relative to images (logical size is swapped for 90/270 degrees)
    void SetTargets(const std::vector<VkImage>& images, VkExtent2D extent, Rotation rotation);

    //! @brief Static part of picture, white with alpha of logical view size
    void SetBackground(const uint8_t* alpha, uint32_t width, uint32_t height, uint32_t stride);

    //! @param traces 4 values per channel in view pixels: left, width, vertical center, amplitude
    //! @param width,height view size, may differ from logical size of targets during resizing
    void SetTraces(const float* traces, uint32_t channels, uint32_t width, uint32_t height);

    //! @brief Fill data for target image, previous usage of it should be completed
    void Write(uint32_t image, const int16_t* samples, uint32_t channels, uint32_t points, int gainPercent);

    void Submit(uint32_t image, VkSemaphore wait, VkSemaphore signal, VkFence fence);

    VkExtent2D LogicalSize() const
    {
      return Logical;
    }

  private:
    static bool IsDense(const int16_t* data, uint32_t points);

    struct Frame
    {
      VkBuffer Buffer = VK_NULL_HANDLE;
      VkDeviceMemory Memory = VK_NULL_HANDLE;
      void* Mapped = nullptr;
      VkImageView View = VK_NULL_HANDLE;
      VkFramebuffer Framebuffer = VK_NULL_HANDLE;
      VkDescriptorSet Descriptors = VK_NULL_HANDLE;
      VkCommandBuffer Commands = VK_NULL_HANDLE;
    };

    void Destroy();
    void CreatePipelines();
    VkPipeline CreatePipeline(const uint32_t* vertex, std::size_t vertexSize, const uint32_t* fragment,
                              std::size_t fragmentSize, VkPrimitiveTopology topology, bool blend);
    void DestroyTargets();
    void CreateBackground(uint32_t width, uint32_t height);
    void DestroyBackground();
    void UpdateDescriptors();
    void Record();
    VkCommandBuffer BeginOneTime();
    void EndOneTime(VkCommandBuffer cmd);

  private:
    const Device& Dev;
    const Api& Vk;
    const VkFormat Format;
    VkRenderPass RenderPass = VK_NULL_HANDLE;
    VkDescriptorSetLayout SetLayout = VK_NULL_HANDLE;
    VkPipelineLayout Layout = VK_NULL_HANDLE;
    VkPipeline TracePipeline = VK_NULL_HANDLE;
    VkPipeline BackgroundPipeline = VK_NULL_HANDLE;
    VkSampler Sampler = VK_NULL_HANDLE;
    VkCommandPool CommandPool = VK_NULL_HANDLE;
    VkDescriptorPool DescriptorPool = VK_NULL_HANDLE;

    VkImage Background = VK_NULL_HANDLE;
    VkDeviceMemory BackgroundMemory = VK_NULL_HANDLE;
    VkImageView BackgroundView = VK_NULL_HANDLE;
    VkExtent2D BackgroundSize = {};

    std::vector<Frame> Frames;
    VkExtent2D Extent = {};
    VkExtent2D Logical = {};
    Rotation Rotate = Rotation::NONE;
    VkExtent2D View = {};

    std::array<float, MAX_CHANNELS* 4> Traces = {};
    uint32_t TracesCount = 0;
    std::array<float, MAX_CHANNELS> SmoothedPeaks = {};
  };
}  // namespace VulkanScope
