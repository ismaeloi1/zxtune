/**
 *
 * @file
 *
 * @brief Oscilloscope presentation to surface implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/zxtune-android/zxtune/src/main/vulkan/swapchain.h"

#include <algorithm>

namespace VulkanScope
{
  Swapchain::Swapchain(std::unique_ptr<Device> device)
    : Dev(std::move(device))
    , Vk(Dev->Vk())
  {
    if (!Vk.vkCreateSwapchainKHR || !Dev->Surface())
    {
      throw Error("No swapchain support");
    }
    uint32_t count = 0;
    Check(Vk.vkGetPhysicalDeviceSurfaceFormatsKHR(Dev->Physical(), Dev->Surface(), &count, nullptr),
          "vkGetPhysicalDeviceSurfaceFormatsKHR");
    std::vector<VkSurfaceFormatKHR> formats(count);
    Check(Vk.vkGetPhysicalDeviceSurfaceFormatsKHR(Dev->Physical(), Dev->Surface(), &count, formats.data()),
          "vkGetPhysicalDeviceSurfaceFormatsKHR");
    if (formats.empty())
    {
      throw Error("No surface formats");
    }
    // linear values as in OpenGL ES backend
    Format = formats.front();
    for (const auto preferred : {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM})
    {
      const auto it = std::find_if(formats.begin(), formats.end(),
                                   [preferred](const VkSurfaceFormatKHR& fmt) { return fmt.format == preferred; });
      if (it != formats.end())
      {
        Format = *it;
        break;
      }
    }
    ScopeRender = std::make_unique<ScopeRenderer>(*Dev, Format.format, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
    try
    {
      VkSemaphoreCreateInfo semInfo = {};
      semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
      VkFenceCreateInfo fenceInfo = {};
      fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
      fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
      for (uint32_t idx = 0; idx < FRAMES_IN_FLIGHT; ++idx)
      {
        Check(Vk.vkCreateSemaphore(Dev->Handle(), &semInfo, nullptr, &Acquired[idx]), "vkCreateSemaphore");
        Check(Vk.vkCreateFence(Dev->Handle(), &fenceInfo, nullptr, &InFlight[idx]), "vkCreateFence");
      }
      Create();
    }
    catch (...)
    {
      DestroySync();
      throw;
    }
  }

  Swapchain::~Swapchain()
  {
    Vk.vkDeviceWaitIdle(Dev->Handle());
    // targets are destroyed before swapchain
    ScopeRender.reset();
    DestroySync();
  }

  void Swapchain::DestroySync()
  {
    const auto dev = Dev->Handle();
    Vk.vkDeviceWaitIdle(dev);
    for (auto sem : RenderDone)
    {
      Vk.vkDestroySemaphore(dev, sem, nullptr);
    }
    RenderDone.clear();
    for (uint32_t idx = 0; idx < FRAMES_IN_FLIGHT; ++idx)
    {
      if (Acquired[idx])
      {
        Vk.vkDestroySemaphore(dev, Acquired[idx], nullptr);
        Acquired[idx] = VK_NULL_HANDLE;
      }
      if (InFlight[idx])
      {
        Vk.vkDestroyFence(dev, InFlight[idx], nullptr);
        InFlight[idx] = VK_NULL_HANDLE;
      }
    }
    if (Handle)
    {
      Vk.vkDestroySwapchainKHR(dev, Handle, nullptr);
      Handle = VK_NULL_HANDLE;
    }
  }

  void Swapchain::Create()
  {
    const auto dev = Dev->Handle();
    VkSurfaceCapabilitiesKHR caps = {};
    Check(Vk.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(Dev->Physical(), Dev->Surface(), &caps),
          "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
    // Pre-rotation: swapchain of display's native orientation with rotation done by renderer,
    // see https://developer.android.com/games/optimize/vulkan-prerotation
    auto transform = caps.currentTransform;
    if (0 == (caps.supportedTransforms & transform))
    {
      transform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    }
    auto extent = caps.currentExtent;
    if (extent.width == 0xffffffff)
    {
      extent = caps.minImageExtent;
    }
    const bool swapped =
        0 != (transform & (VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR | VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR));
    if (swapped)
    {
      std::swap(extent.width, extent.height);
    }
    if (!extent.width || !extent.height)
    {
      NeedRecreate = true;
      return;
    }
    // one more than minimal to not wait for presentation engine when acquiring image
    auto count = caps.minImageCount + 1;
    if (caps.maxImageCount)
    {
      count = std::min(count, caps.maxImageCount);
    }
    VkSwapchainCreateInfoKHR info = {};
    info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    info.surface = Dev->Surface();
    info.minImageCount = count;
    info.imageFormat = Format.format;
    info.imageColorSpace = Format.colorSpace;
    info.imageExtent = extent;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.preTransform = transform;
    // opaque layer is composed without blending
    info.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                              ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
                              : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    // vsync, the only mode without busy rendering of not displayed frames
    info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    info.clipped = VK_TRUE;
    info.oldSwapchain = Handle;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    Check(Vk.vkCreateSwapchainKHR(dev, &info, nullptr, &swapchain), "vkCreateSwapchainKHR");
    Vk.vkDeviceWaitIdle(dev);
    if (Handle)
    {
      Vk.vkDestroySwapchainKHR(dev, Handle, nullptr);
    }
    Handle = swapchain;
    uint32_t images = 0;
    Check(Vk.vkGetSwapchainImagesKHR(dev, Handle, &images, nullptr), "vkGetSwapchainImagesKHR");
    Images.resize(images);
    Check(Vk.vkGetSwapchainImagesKHR(dev, Handle, &images, Images.data()), "vkGetSwapchainImagesKHR");
    const auto rotation = transform == VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR    ? Rotation::CW90
                          : transform == VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR ? Rotation::CW180
                          : transform == VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR ? Rotation::CW270
                                                                                 : Rotation::NONE;
    ScopeRender->SetTargets(Images, extent, rotation);
    for (auto sem : RenderDone)
    {
      Vk.vkDestroySemaphore(dev, sem, nullptr);
    }
    RenderDone.assign(images, VK_NULL_HANDLE);
    VkSemaphoreCreateInfo semInfo = {};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    for (auto& sem : RenderDone)
    {
      Check(Vk.vkCreateSemaphore(dev, &semInfo, nullptr, &sem), "vkCreateSemaphore");
    }
    ImageFences.assign(images, VK_NULL_HANDLE);
    Extent = extent;
    Transform = transform;
    ImagesCount = images;
    NeedRecreate = false;
  }

  Swapchain::Result Swapchain::Update()
  {
    const auto prev = ScopeRender->LogicalSize();
    Create();
    if (NeedRecreate)
    {
      return Result::SKIPPED;
    }
    const auto cur = ScopeRender->LogicalSize();
    return prev.width != cur.width || prev.height != cur.height ? Result::RESIZED : Result::PRESENTED;
  }

  Swapchain::Result Swapchain::Render(const int16_t* samples, uint32_t channels, uint32_t points, int gainPercent)
  {
    if (NeedRecreate || !Handle)
    {
      const auto res = Update();
      if (res != Result::PRESENTED)
      {
        return res;
      }
    }
    const auto dev = Dev->Handle();
    // limits CPU to be not more than 2 frames ahead of GPU, usually doesn't wait at all
    Check(Vk.vkWaitForFences(dev, 1, &InFlight[Slot], VK_TRUE, UINT64_MAX), "vkWaitForFences");
    uint32_t image = 0;
    auto res = Vk.vkAcquireNextImageKHR(dev, Handle, UINT64_MAX, Acquired[Slot], VK_NULL_HANDLE, &image);
    if (res == VK_ERROR_OUT_OF_DATE_KHR)
    {
      // semaphore is not signaled
      const auto upd = Update();
      return upd == Result::PRESENTED ? Result::SKIPPED : upd;
    }
    if (res != VK_SUBOPTIMAL_KHR)
    {
      Check(res, "vkAcquireNextImageKHR");
    }
    // image may be still used by frame from another slot
    if (ImageFences[image] && ImageFences[image] != InFlight[Slot])
    {
      Check(Vk.vkWaitForFences(dev, 1, &ImageFences[image], VK_TRUE, UINT64_MAX), "vkWaitForFences");
    }
    ImageFences[image] = InFlight[Slot];
    Check(Vk.vkResetFences(dev, 1, &InFlight[Slot]), "vkResetFences");
    ScopeRender->Write(image, samples, channels, points, gainPercent);
    ScopeRender->Submit(image, Acquired[Slot], RenderDone[image], InFlight[Slot]);
    VkPresentInfoKHR present = {};
    present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    present.waitSemaphoreCount = 1;
    present.pWaitSemaphores = &RenderDone[image];
    present.swapchainCount = 1;
    present.pSwapchains = &Handle;
    present.pImageIndices = &image;
    res = Vk.vkQueuePresentKHR(Dev->Queue(), &present);
    Slot = (Slot + 1) % FRAMES_IN_FLIGHT;
    // Android 10+ reports rotation changes as suboptimal
    if (res == VK_ERROR_OUT_OF_DATE_KHR || res == VK_SUBOPTIMAL_KHR)
    {
      NeedRecreate = true;
      return Result::PRESENTED;
    }
    Check(res, "vkQueuePresentKHR");
    return Result::PRESENTED;
  }

  std::string Swapchain::GetDescription() const
  {
    return Dev->GetDescription() + ", " + std::to_string(Extent.width) + "x" + std::to_string(Extent.height) + "x"
           + std::to_string(ImagesCount) + ", transform " + std::to_string(Transform);
  }
}  // namespace VulkanScope
