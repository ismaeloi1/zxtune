/**
 *
 * @file
 *
 * @brief Offscreen test of oscilloscope Vulkan renderer (e.g. with lavapipe on host)
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/zxtune-android/zxtune/src/main/vulkan/renderer.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <vector>

namespace VulkanScope
{
  // Uncompressed PNG to not depend on zlib
  class PngWriter
  {
  public:
    static void Write(const std::string& path, const uint8_t* rgba, uint32_t width, uint32_t height)
    {
      std::vector<uint8_t> raw;
      for (uint32_t y = 0; y < height; ++y)
      {
        raw.push_back(0);
        for (uint32_t x = 0; x < width; ++x)
        {
          const auto* px = rgba + (std::size_t(y) * width + x) * 4;
          raw.insert(raw.end(), px, px + 3);
        }
      }
      std::vector<uint8_t> zlib = {0x78, 0x01};
      for (std::size_t pos = 0; pos < raw.size();)
      {
        const auto block = std::min<std::size_t>(raw.size() - pos, 65535);
        const bool last = pos + block == raw.size();
        zlib.push_back(last ? 1 : 0);
        zlib.push_back(block & 0xff);
        zlib.push_back(block >> 8);
        zlib.push_back(~block & 0xff);
        zlib.push_back((~block >> 8) & 0xff);
        zlib.insert(zlib.end(), raw.begin() + pos, raw.begin() + pos + block);
        pos += block;
      }
      uint32_t a = 1, b = 0;
      for (auto v : raw)
      {
        a = (a + v) % 65521;
        b = (b + a) % 65521;
      }
      PutBe(zlib, (b << 16) | a);
      std::vector<uint8_t> out = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
      std::vector<uint8_t> header;
      PutBe(header, width);
      PutBe(header, height);
      header.insert(header.end(), {8, 2, 0, 0, 0});
      Chunk(out, "IHDR", header);
      Chunk(out, "IDAT", zlib);
      Chunk(out, "IEND", {});
      if (auto* f = std::fopen(path.c_str(), "wb"))
      {
        std::fwrite(out.data(), 1, out.size(), f);
        std::fclose(f);
      }
    }

  private:
    static void PutBe(std::vector<uint8_t>& out, uint32_t val)
    {
      for (int shift = 24; shift >= 0; shift -= 8)
      {
        out.push_back((val >> shift) & 0xff);
      }
    }

    static void Chunk(std::vector<uint8_t>& out, const char* type, const std::vector<uint8_t>& data)
    {
      PutBe(out, static_cast<uint32_t>(data.size()));
      std::vector<uint8_t> crcData(type, type + 4);
      crcData.insert(crcData.end(), data.begin(), data.end());
      out.insert(out.end(), crcData.begin(), crcData.end());
      uint32_t crc = 0xffffffff;
      for (auto v : crcData)
      {
        crc ^= v;
        for (int bit = 0; bit < 8; ++bit)
        {
          crc = (crc >> 1) ^ (0xedb88320 & (0 - (crc & 1)));
        }
      }
      PutBe(out, ~crc);
    }
  };

  // Target images with readback, as swapchain would provide
  class OffscreenTargets
  {
  public:
    OffscreenTargets(const Device& dev, VkExtent2D extent, uint32_t count)
      : Dev(dev)
      , Vk(dev.Vk())
      , Extent(extent)
    {
      for (uint32_t idx = 0; idx < count; ++idx)
      {
        VkImageCreateInfo info = {};
        info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
        info.imageType = VK_IMAGE_TYPE_2D;
        info.format = FORMAT;
        info.extent = {extent.width, extent.height, 1};
        info.mipLevels = 1;
        info.arrayLayers = 1;
        info.samples = VK_SAMPLE_COUNT_1_BIT;
        info.tiling = VK_IMAGE_TILING_OPTIMAL;
        info.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        VkImage image = VK_NULL_HANDLE;
        Check(Vk.vkCreateImage(dev.Handle(), &info, nullptr, &image), "vkCreateImage");
        VkMemoryRequirements req = {};
        Vk.vkGetImageMemoryRequirements(dev.Handle(), image, &req);
        VkMemoryAllocateInfo alloc = {};
        alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        alloc.allocationSize = req.size;
        alloc.memoryTypeIndex = dev.FindMemory(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
        VkDeviceMemory memory = VK_NULL_HANDLE;
        Check(Vk.vkAllocateMemory(dev.Handle(), &alloc, nullptr, &memory), "vkAllocateMemory");
        Check(Vk.vkBindImageMemory(dev.Handle(), image, memory, 0), "vkBindImageMemory");
        Images.push_back(image);
        Memories.push_back(memory);
        VkFenceCreateInfo fenceInfo = {};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        VkFence fence = VK_NULL_HANDLE;
        Check(Vk.vkCreateFence(dev.Handle(), &fenceInfo, nullptr, &fence), "vkCreateFence");
        Fences.push_back(fence);
      }
    }

    ~OffscreenTargets()
    {
      Vk.vkDeviceWaitIdle(Dev.Handle());
      for (std::size_t idx = 0; idx < Images.size(); ++idx)
      {
        Vk.vkDestroyFence(Dev.Handle(), Fences[idx], nullptr);
        Vk.vkDestroyImage(Dev.Handle(), Images[idx], nullptr);
        Vk.vkFreeMemory(Dev.Handle(), Memories[idx], nullptr);
      }
    }

    std::vector<uint8_t> Read(uint32_t idx) const
    {
      const auto dev = Dev.Handle();
      const VkDeviceSize size = VkDeviceSize(Extent.width) * Extent.height * 4;
      VkBufferCreateInfo bufInfo = {};
      bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
      bufInfo.size = size;
      bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
      VkBuffer buffer = VK_NULL_HANDLE;
      Check(Vk.vkCreateBuffer(dev, &bufInfo, nullptr, &buffer), "vkCreateBuffer");
      VkMemoryRequirements req = {};
      Vk.vkGetBufferMemoryRequirements(dev, buffer, &req);
      VkMemoryAllocateInfo alloc = {};
      alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
      alloc.allocationSize = req.size;
      alloc.memoryTypeIndex = Dev.FindMemory(
          req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
      VkDeviceMemory memory = VK_NULL_HANDLE;
      Check(Vk.vkAllocateMemory(dev, &alloc, nullptr, &memory), "vkAllocateMemory");
      Check(Vk.vkBindBufferMemory(dev, buffer, memory, 0), "vkBindBufferMemory");
      VkCommandPoolCreateInfo poolInfo = {};
      poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
      poolInfo.queueFamilyIndex = Dev.QueueFamily();
      VkCommandPool pool = VK_NULL_HANDLE;
      Check(Vk.vkCreateCommandPool(dev, &poolInfo, nullptr, &pool), "vkCreateCommandPool");
      VkCommandBufferAllocateInfo cmdAlloc = {};
      cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
      cmdAlloc.commandPool = pool;
      cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
      cmdAlloc.commandBufferCount = 1;
      VkCommandBuffer cmd = VK_NULL_HANDLE;
      Check(Vk.vkAllocateCommandBuffers(dev, &cmdAlloc, &cmd), "vkAllocateCommandBuffers");
      VkCommandBufferBeginInfo begin = {};
      begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
      Vk.vkBeginCommandBuffer(cmd, &begin);
      VkBufferImageCopy region = {};
      region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
      region.imageExtent = {Extent.width, Extent.height, 1};
      Vk.vkCmdCopyImageToBuffer(cmd, Images[idx], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1, &region);
      Vk.vkEndCommandBuffer(cmd);
      VkSubmitInfo submit = {};
      submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
      submit.commandBufferCount = 1;
      submit.pCommandBuffers = &cmd;
      Check(Vk.vkQueueSubmit(Dev.Queue(), 1, &submit, VK_NULL_HANDLE), "vkQueueSubmit");
      Vk.vkDeviceWaitIdle(dev);
      void* mapped = nullptr;
      Vk.vkMapMemory(dev, memory, 0, VK_WHOLE_SIZE, 0, &mapped);
      std::vector<uint8_t> result(static_cast<uint8_t*>(mapped), static_cast<uint8_t*>(mapped) + size);
      Vk.vkUnmapMemory(dev, memory);
      Vk.vkDestroyCommandPool(dev, pool, nullptr);
      Vk.vkDestroyBuffer(dev, buffer, nullptr);
      Vk.vkFreeMemory(dev, memory, nullptr);
      return result;
    }

    static const VkFormat FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

    const Device& Dev;
    const Api& Vk;
    const VkExtent2D Extent;
    std::vector<VkImage> Images;
    std::vector<VkDeviceMemory> Memories;
    std::vector<VkFence> Fences;
  };

  // Two chips of 3 voices side by side with 16px title bands, as ScopeGrid does
  struct TestScene
  {
    static const uint32_t CHANNELS = 6;
    static const uint32_t POINTS = 2048;

    std::vector<float> Traces;
    std::vector<uint8_t> Background;

    TestScene(uint32_t width, uint32_t height)
      : Traces(CHANNELS * 4)
      , Background(std::size_t(width) * height)
    {
      const float cellW = width / 2.0f;
      const float cellH = height / 3.0f;
      const float band = 16;
      for (uint32_t chan = 0; chan < CHANNELS; ++chan)
      {
        const auto col = chan / 3;
        const auto row = chan % 3;
        const float top = cellH * row + band;
        const float traceH = cellH - band;
        Traces[chan * 4] = cellW * col + cellW * 0.01f;
        Traces[chan * 4 + 1] = cellW * 0.98f;
        Traces[chan * 4 + 2] = top + traceH / 2;
        Traces[chan * 4 + 3] = traceH * 0.5f * 0.95f;
        const auto left = static_cast<uint32_t>(cellW * col);
        const auto right = static_cast<uint32_t>(cellW * (col + 1));
        HLine(width, left, right, static_cast<uint32_t>(top + traceH / 2), 38);
        HLine(width, left, right, static_cast<uint32_t>(top), 38);
        // title placeholder
        for (uint32_t y = static_cast<uint32_t>(cellH * row + 4); y < cellH * row + 12; ++y)
        {
          HLine(width, left + 4, left + 80, y, 200);
        }
      }
    }

    void HLine(uint32_t width, uint32_t left, uint32_t right, uint32_t y, uint8_t alpha)
    {
      if (y * width < Background.size())
      {
        std::fill(Background.begin() + y * width + left, Background.begin() + y * width + right, alpha);
      }
    }

    static void Samples(uint32_t frame, int16_t* target)
    {
      for (uint32_t chan = 0; chan < CHANNELS; ++chan)
      {
        const double freq = 2 + chan * 1.5;
        for (uint32_t idx = 0; idx < POINTS; ++idx)
        {
          const double phase = (idx * freq / POINTS) + frame * 0.01;
          double val = 0;
          switch (chan % 3)
          {
          case 0:
            val = std::sin(phase * 2 * M_PI);
            break;
          case 1:
            val = phase - std::floor(phase) < 0.3 ? 0.8 : -0.8;
            break;
          default:
            val = 2 * std::abs(2 * (phase - std::floor(phase + 0.5))) - 1;
            break;
          }
          target[chan * POINTS + idx] = static_cast<int16_t>(val * (chan < 3 ? 12000 : 30000));
        }
      }
    }
  };

  void Render(const Device& dev, VkExtent2D extent, Rotation rotation, const std::string& path, uint32_t frames)
  {
    OffscreenTargets targets(dev, extent, 3);
    ScopeRenderer renderer(dev, OffscreenTargets::FORMAT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    renderer.SetTargets(targets.Images, extent, rotation);
    const auto logical = renderer.LogicalSize();
    TestScene scene(logical.width, logical.height);
    renderer.SetBackground(scene.Background.data(), logical.width, logical.height, logical.width);
    renderer.SetTraces(scene.Traces.data(), TestScene::CHANNELS, logical.width, logical.height);
    std::vector<int16_t> samples(TestScene::CHANNELS * TestScene::POINTS);
    using Clock = std::chrono::steady_clock;
    Clock::duration cpu = {};
    Clock::duration total = {};
    uint32_t last = 0;
    for (uint32_t frame = 0; frame < frames; ++frame)
    {
      TestScene::Samples(frame, samples.data());
      const auto image = frame % targets.Images.size();
      const auto start = Clock::now();
      Check(dev.Vk().vkWaitForFences(dev.Handle(), 1, &targets.Fences[image], VK_TRUE, UINT64_MAX), "wait");
      const auto cpuStart = Clock::now();
      dev.Vk().vkResetFences(dev.Handle(), 1, &targets.Fences[image]);
      renderer.Write(image, samples.data(), TestScene::CHANNELS, TestScene::POINTS, ScopeRenderer::AUTO_GAIN);
      renderer.Submit(image, VK_NULL_HANDLE, VK_NULL_HANDLE, targets.Fences[image]);
      const auto end = Clock::now();
      cpu += end - cpuStart;
      total += end - start;
      last = image;
    }
    dev.Vk().vkDeviceWaitIdle(dev.Handle());
    const auto pixels = targets.Read(last);
    PngWriter::Write(path, pixels.data(), extent.width, extent.height);
    using Us = std::chrono::duration<double, std::micro>;
    std::cout << path << ": " << extent.width << "x" << extent.height << ", " << frames
              << " frames, CPU (write + submit) " << Us(cpu).count() / frames << "us/frame, with GPU wait "
              << Us(total).count() / frames << "us/frame" << std::endl;
  }
}  // namespace VulkanScope

int main(int argc, char** argv)
{
  try
  {
    const std::string dir = argc > 1 ? argv[1] : ".";
    auto lib = argc > 2 ? VulkanScope::Library::Open(argv[2]) : VulkanScope::Library::OpenSystem();
    if (!lib)
    {
      std::cout << "No Vulkan library" << std::endl;
      return 1;
    }
    const VulkanScope::Device dev(lib, {});
    std::cout << "Device: " << dev.GetDescription() << std::endl;
    VulkanScope::Render(dev, {1280, 720}, VulkanScope::Rotation::NONE, dir + "/vk-scope.png", 600);
    // landscape view on portrait-oriented display: swapchain is not rotated, picture is
    VulkanScope::Render(dev, {720, 1280}, VulkanScope::Rotation::CW90, dir + "/vk-scope-rot90.png", 60);
    VulkanScope::Render(dev, {720, 1280}, VulkanScope::Rotation::CW270, dir + "/vk-scope-rot270.png", 60);
    return 0;
  }
  catch (const std::exception& e)
  {
    std::cout << "Failed: " << e.what() << std::endl;
    return 1;
  }
}
