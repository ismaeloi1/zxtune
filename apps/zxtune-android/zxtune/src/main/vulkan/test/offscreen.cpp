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

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
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

    // Long window (e.g. 200ms of 48kHz) as delivered by Sound::Scope: minimum and maximum of each pair of points
    static void DenseSamples(uint32_t frame, int16_t* target)
    {
      const uint32_t window = 9600;
      for (uint32_t chan = 0; chan < CHANNELS; ++chan)
      {
        // high notes, several periods per pixel column
        const double freq = 1500.0 * (chan + 1) / 48000;
        for (uint32_t bin = 0; bin < POINTS / 2; ++bin)
        {
          double lo = 1;
          double hi = -1;
          for (uint32_t idx = window * bin / (POINTS / 2); idx < window * (bin + 1) / (POINTS / 2); ++idx)
          {
            const double phase = idx * freq + frame * 0.01;
            const double val = phase - std::floor(phase) < 0.3 ? 0.8 : -0.8 + 0.2 * std::sin(phase * 7);
            lo = std::min(lo, val);
            hi = std::max(hi, val);
          }
          target[chan * POINTS + bin * 2] = static_cast<int16_t>(lo * 30000);
          target[chan * POINTS + bin * 2 + 1] = static_cast<int16_t>(hi * 30000);
        }
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

  // Hard cases of SID voices as seen on phones, 40ms window of 48kHz delivered as Sound::Scope does: steps of combined
  // waveforms, resonant filter ringing after edges, tens of periods of high notes, noise
  struct SidScene
  {
    static const uint32_t RATE = 48000;
    static const uint32_t WINDOW = RATE * 40 / 1000;

    // Sound::Scope's resampling: interpolation of short windows, minimums and maximums of long ones
    static void Resample(const std::vector<int16_t>& in, uint32_t points, int16_t* out)
    {
      const auto count = static_cast<uint32_t>(in.size());
      if (count <= points)
      {
        for (uint32_t idx = 0; idx < points; ++idx)
        {
          const double pos = double(idx) * (count - 1) / (points - 1);
          const auto lo = static_cast<uint32_t>(pos);
          const auto hi = std::min(lo + 1, count - 1);
          out[idx] = static_cast<int16_t>(in[lo] + std::lround((in[hi] - in[lo]) * (pos - lo)));
        }
        return;
      }
      for (uint32_t bin = 0; bin < points / 2; ++bin)
      {
        const auto from = in.begin() + uint64_t(count) * bin / (points / 2);
        const auto to = in.begin() + uint64_t(count) * (bin + 1) / (points / 2);
        const auto [lo, hi] = std::minmax_element(from, to);
        out[bin * 2] = lo < hi ? *lo : *hi;
        out[bin * 2 + 1] = lo < hi ? *hi : *lo;
      }
    }

    static uint32_t Triangle(uint32_t acc, bool ring)
    {
      const auto msb = (acc >> 23) & 1;
      return ((acc >> 11) ^ ((msb ^ ring) ? 0xfff : 0)) & 0xfff;
    }

    static int16_t ToSample(uint32_t level12)
    {
      return static_cast<int16_t>((int(level12) - 2048) * 14);
    }

    static void Samples(uint32_t channels, uint32_t points, uint32_t frame, int16_t* target)
    {
      std::vector<int16_t> wave(WINDOW);
      const auto begin = uint64_t(frame) * RATE / 120;
      for (uint32_t chan = 0; chan < channels; ++chan)
      {
        uint32_t noise = 0x7ffff8;
        for (uint32_t idx = 0; idx < WINDOW; ++idx)
        {
          const auto t = begin + idx;
          // 24-bit phase accumulators of note and of ring/sync modulator
          const auto acc = static_cast<uint32_t>(t * ((1u << 24) * 63.0 / RATE)) & 0xffffff;
          const auto mod = static_cast<uint32_t>(t * ((1u << 24) * 440.0 / RATE)) & 0xffffff;
          uint32_t level = 0;
          switch (chan % 3)
          {
          case 0:
          {
            // pulse+triangle with ring modulation: combined waveform keeps only some bits, staircase with spikes
            const auto tri = Triangle(acc, (mod >> 23) & 1);
            const bool pulse = (acc >> 12) >= 0x800;
            level = pulse ? (tri & (tri << 1) & (tri << 2) & 0xfff) | ((tri >> 8) << 8) : 0;
            break;
          }
          case 1:
          {
            // pulse through resonant band/high pass: decaying ringing after each edge
            const double phase = std::fmod(double(t) * 62 / RATE, 1.0);
            const double sinceEdge = (phase < 0.5 ? phase : phase - 0.5) * RATE / 62;
            const double ring = std::exp(-sinceEdge / 30) * std::sin(sinceEdge * 2 * M_PI * 2000 / RATE);
            level = static_cast<uint32_t>(2048 + (phase < 0.5 ? 900 : -900) + 1100 * ring);
            break;
          }
          default:
          {
            // high note, ~79 periods in window
            const auto fast = static_cast<uint32_t>(t * ((1u << 24) * 1976.0 / RATE)) & 0xffffff;
            level = (fast >> 12) >= 0x570 ? 0xfff : 0;
            if (chan >= 3)
            {
              // noise
              noise = (noise << 1) | (((noise >> 22) ^ (noise >> 17)) & 1);
              level = (noise >> 11) & 0xfff;
            }
            break;
          }
          }
          wave[idx] = ToSample(std::min<uint32_t>(level, 0xfff));
        }
        Resample(wave, points, target + chan * points);
      }
    }
  };

  // Traces of each frame as int16 [frames][channels][points], e.g. dumped by module_test_scope_bench, scene's channels
  // are taken starting from specified one
  class DumpScene
  {
  public:
    DumpScene(const std::string& path, uint32_t channels, uint32_t first, uint32_t points)
      : Points(points)
      , FrameSize(std::size_t(channels) * points)
      , First(std::min(first, channels - 1))
      , Count(std::min(channels - First, TestScene::CHANNELS))
    {
      std::ifstream in(path, std::ios::binary);
      Data.resize(std::filesystem::file_size(path) / sizeof(int16_t));
      in.read(reinterpret_cast<char*>(Data.data()), Data.size() * sizeof(int16_t));
      Frames = static_cast<uint32_t>(Data.size() / FrameSize);
    }

    uint32_t Size() const
    {
      return Frames;
    }

    void Samples(uint32_t frame, int16_t* target) const
    {
      std::fill_n(target, TestScene::CHANNELS * Points, 0);
      std::memcpy(target, Data.data() + (frame % Frames) * FrameSize + First * Points,
                  std::size_t(Count) * Points * sizeof(int16_t));
    }

  private:
    const uint32_t Points;
    const std::size_t FrameSize;
    const uint32_t First;
    const uint32_t Count;
    std::vector<int16_t> Data;
    uint32_t Frames = 0;
  };

  // Shapes pass as drawn by filter response view (see app.zxtune.ui.views.FilterScene): resonant curve of segments with
  // fading tail and gradient fill, dots and rings of voices, ticks, glyphs of a synthetic atlas
  struct ShapesScene
  {
    static const uint32_t SEGMENT = 0;
    static const uint32_t CIRCLE = 1;
    static const uint32_t GLYPH = 2;
    static const uint32_t FILL = 3;

    std::vector<float> Shapes;
    std::vector<uint8_t> Atlas;
    std::vector<uint8_t> Background;
    static const uint32_t ATLAS_SIZE = 16;

    ShapesScene(uint32_t width, uint32_t height)
      : Atlas(ATLAS_SIZE * ATLAS_SIZE)
      , Background(std::size_t(width) * height)
    {
      // glyph is a ring of 1px
      for (uint32_t y = 0; y < ATLAS_SIZE; ++y)
      {
        for (uint32_t x = 0; x < ATLAS_SIZE; ++x)
        {
          const auto d = std::hypot(x + 0.5 - ATLAS_SIZE / 2.0, y + 0.5 - ATLAS_SIZE / 2.0);
          Atlas[y * ATLAS_SIZE + x] = static_cast<uint8_t>(255 * std::clamp(1 - std::abs(d - 6), 0.0, 1.0));
        }
      }
      for (uint32_t x = 0; x < width; x += width / 10)
      {
        for (uint32_t y = 0; y < height; ++y)
        {
          Background[y * width + x] = 38;
        }
      }
      const float left = 10;
      const float right = width - 10.0f;
      const float top = 40;
      const float bottom = height - 10.0f;
      const auto respY = [&](float x) {
        // resonant low-pass by log frequency
        const double ratio = std::pow(1000.0, (x - left) / (right - left)) / 30;
        const double dRe = 1 - ratio * ratio;
        const double dIm = ratio / 4;
        const double db = std::max(-60.0, -10 * std::log10(dRe * dRe + dIm * dIm));
        return std::make_pair(static_cast<float>(top + (bottom - top) * (24 - db) / 60), db);
      };
      float prevX = left;
      for (float x = left; x < right; x += 3)
      {
        const auto x1 = std::min(x + 3, right);
        const auto [y0, db0] = respY(x);
        const auto [y1, db1] = respY(x1);
        const auto [yp, dbp] = respY(std::max(prevX, left));
        const auto [yn, dbn] = respY(std::min(x1 + 3, right));
        const float alpha = static_cast<float>(std::clamp((db0 + 36) / 12, 0.0, 1.0));
        Add(FILL, {0, 0.0f, 0}, {x, y0, x1, y1}, {bottom, bottom, 0, 0}, {1, 1, 1, 0.14f * alpha});
        Add(SEGMENT, {0.8f, 0, 0}, {x, y0, x1, y1},
            {prevX < x ? prevX : x, prevX < x ? yp : y0, x1 + 3 <= right ? x1 + 3 : x1, x1 + 3 <= right ? yn : y1},
            {1, 1, 1, alpha});
        prevX = x;
      }
      const float colors[3][3] = {{1.0f, 0.71f, 0.28f}, {0.37f, 0.83f, 0.55f}, {1.0f, 0.44f, 0.65f}};
      for (uint32_t voice = 0; voice < 3; ++voice)
      {
        const float x = left + (right - left) * (0.2f + 0.25f * voice);
        const auto y = respY(x).first;
        const auto* c = colors[voice];
        Add(CIRCLE, {5.5f, voice == 1 ? 2.0f : 0.0f, 0}, {x, y, 0, 0}, {0, 0, 0, 0}, {c[0], c[1], c[2], 1});
        Add(SEGMENT, {1.0f, 0, 0}, {x, bottom - 12, x, bottom}, {x, bottom - 12, x, bottom}, {c[0], c[1], c[2], 1});
        Add(GLYPH, {0, 0, 0}, {x + 8, y - 24, x + 8 + ATLAS_SIZE, y - 24 + ATLAS_SIZE}, {0, 0, 1, 1},
            {c[0], c[1], c[2], 1});
      }
    }

    void Add(uint32_t kind, std::array<float, 3> head, std::array<float, 4> first, std::array<float, 4> second,
             std::array<float, 4> color)
    {
      Shapes.push_back(static_cast<float>(kind));
      Shapes.insert(Shapes.end(), head.begin(), head.end());
      Shapes.insert(Shapes.end(), first.begin(), first.end());
      Shapes.insert(Shapes.end(), second.begin(), second.end());
      Shapes.insert(Shapes.end(), color.begin(), color.end());
    }

    uint32_t Count() const
    {
      return static_cast<uint32_t>(Shapes.size() / ScopeRenderer::SHAPE_FLOATS);
    }
  };

  using SamplesSource = std::function<void(uint32_t frame, int16_t* target)>;

  void RenderShapes(const Device& dev, VkExtent2D extent, Rotation rotation, const std::string& path,
                    const ShapesScene& scene, uint32_t frames)
  {
    OffscreenTargets targets(dev, extent, 3);
    ScopeRenderer renderer(dev, OffscreenTargets::FORMAT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    renderer.SetTargets(targets.Images, extent, rotation);
    const auto logical = renderer.LogicalSize();
    renderer.SetBackground(scene.Background.data(), logical.width, logical.height, logical.width);
    renderer.SetAtlas(scene.Atlas.data(), ShapesScene::ATLAS_SIZE, ShapesScene::ATLAS_SIZE, ShapesScene::ATLAS_SIZE);
    renderer.SetTraces(nullptr, 0, logical.width, logical.height);
    using Clock = std::chrono::steady_clock;
    Clock::duration cpu = {};
    uint32_t last = 0;
    for (uint32_t frame = 0; frame < frames; ++frame)
    {
      const auto image = frame % targets.Images.size();
      Check(dev.Vk().vkWaitForFences(dev.Handle(), 1, &targets.Fences[image], VK_TRUE, UINT64_MAX), "wait");
      const auto start = Clock::now();
      dev.Vk().vkResetFences(dev.Handle(), 1, &targets.Fences[image]);
      renderer.Write(image, nullptr, 0, 0, ScopeRenderer::AUTO_GAIN, scene.Shapes.data(), scene.Count());
      renderer.Submit(image, VK_NULL_HANDLE, VK_NULL_HANDLE, targets.Fences[image]);
      cpu += Clock::now() - start;
      last = image;
    }
    dev.Vk().vkDeviceWaitIdle(dev.Handle());
    const auto pixels = targets.Read(last);
    PngWriter::Write(path, pixels.data(), extent.width, extent.height);
    using Us = std::chrono::duration<double, std::micro>;
    std::cout << path << ": " << extent.width << "x" << extent.height << ", " << scene.Count() << " shapes, CPU "
              << Us(cpu).count() / frames << "us/frame" << std::endl;
  }

  void Render(const Device& dev, VkExtent2D extent, Rotation rotation, const std::string& path, uint32_t frames,
              const SamplesSource& source, uint32_t points = TestScene::POINTS)
  {
    OffscreenTargets targets(dev, extent, 3);
    ScopeRenderer renderer(dev, OffscreenTargets::FORMAT, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
    renderer.SetTargets(targets.Images, extent, rotation);
    const auto logical = renderer.LogicalSize();
    TestScene scene(logical.width, logical.height);
    renderer.SetBackground(scene.Background.data(), logical.width, logical.height, logical.width);
    renderer.SetTraces(scene.Traces.data(), TestScene::CHANNELS, logical.width, logical.height);
    std::vector<int16_t> samples(TestScene::CHANNELS * points);
    using Clock = std::chrono::steady_clock;
    Clock::duration cpu = {};
    Clock::duration write = {};
    Clock::duration total = {};
    uint32_t last = 0;
    for (uint32_t frame = 0; frame < frames; ++frame)
    {
      source(frame, samples.data());
      const auto image = frame % targets.Images.size();
      const auto start = Clock::now();
      Check(dev.Vk().vkWaitForFences(dev.Handle(), 1, &targets.Fences[image], VK_TRUE, UINT64_MAX), "wait");
      const auto cpuStart = Clock::now();
      dev.Vk().vkResetFences(dev.Handle(), 1, &targets.Fences[image]);
      const auto writeStart = Clock::now();
      renderer.Write(image, samples.data(), TestScene::CHANNELS, points, ScopeRenderer::AUTO_GAIN);
      write += Clock::now() - writeStart;
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
              << " frames, CPU (write + submit) " << Us(cpu).count() / frames << "us/frame (write "
              << Us(write).count() / frames << "us), with GPU wait " << Us(total).count() / frames << "us/frame"
              << std::endl;
  }
}  // namespace VulkanScope

int main(int argc, char** argv)
{
  try
  {
    const std::string dir = argc > 1 ? argv[1] : ".";
    auto lib = argc > 2 && std::strcmp(argv[2], "-") != 0 ? VulkanScope::Library::Open(argv[2])
                                                          : VulkanScope::Library::OpenSystem();
    if (!lib)
    {
      std::cout << "No Vulkan library" << std::endl;
      return 1;
    }
    const VulkanScope::Device dev(lib, {});
    std::cout << "Device: " << dev.GetDescription() << std::endl;
    using namespace VulkanScope;
    const SamplesSource smooth = &TestScene::Samples;
    const SamplesSource dense = &TestScene::DenseSamples;
    Render(dev, {1280, 720}, Rotation::NONE, dir + "/vk-scope.png", 600, smooth);
    // landscape view on portrait-oriented display: swapchain is not rotated, picture is
    Render(dev, {720, 1280}, Rotation::CW90, dir + "/vk-scope-rot90.png", 60, smooth);
    Render(dev, {720, 1280}, Rotation::CW270, dir + "/vk-scope-rot270.png", 60, smooth);
    // fullscreen landscape phone, short and long windows: cost should not depend on window
    Render(dev, {2400, 1080}, Rotation::NONE, dir + "/vk-scope-wide.png", 120, smooth);
    Render(dev, {2400, 1080}, Rotation::NONE, dir + "/vk-scope-wide-dense.png", 120, dense);
    // portrait phone: cells of 540px wide, so 1080 points as ScopeGrid requests
    const uint32_t cellPoints = 1080;
    Render(
        dev, {1080, 1200}, Rotation::NONE, dir + "/vk-scope-sid.png", 120,
        [cellPoints](uint32_t frame, int16_t* target) {
          SidScene::Samples(TestScene::CHANNELS, cellPoints, frame, target);
        },
        cellPoints);
    // filter response view: shapes over background without traces, upright and pre-rotated
    RenderShapes(dev, {1080, 608}, Rotation::NONE, dir + "/vk-shapes.png", ShapesScene(1080, 608), 60);
    RenderShapes(dev, {608, 1080}, Rotation::CW90, dir + "/vk-shapes-rot90.png", ShapesScene(1080, 608), 10);
    // <dir> <driver or -> <dump> <points> <channels> <first>: traces dumped by module_test_scope_bench
    if (argc > 6)
    {
      const auto points = static_cast<uint32_t>(std::atoi(argv[4]));
      const DumpScene dump(argv[3], static_cast<uint32_t>(std::atoi(argv[5])),
                           static_cast<uint32_t>(std::atoi(argv[6])), points);
      if (dump.Size())
      {
        Render(
            dev, {1080, 1200}, Rotation::NONE, dir + "/vk-scope-dump.png", dump.Size(),
            [&dump](uint32_t frame, int16_t* target) { dump.Samples(frame, target); }, points);
      }
    }
    return 0;
  }
  catch (const std::exception& e)
  {
    std::cout << "Failed: " << e.what() << std::endl;
    return 1;
  }
}
