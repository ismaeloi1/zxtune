/**
 *
 * @file
 *
 * @brief Oscilloscope rendering with Vulkan implementation
 *
 * @author vitamin.caig@gmail.com
 *
 **/

#include "apps/zxtune-android/zxtune/src/main/vulkan/renderer.h"

#include "apps/zxtune-android/zxtune/src/main/vulkan/shaders.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace VulkanScope
{
  // See shaders/common.glsl
  struct PushConstants
  {
    float ToNdc[2];
    float HalfWidth;
    float Reserved;
    // column-major mat2
    float Rotation[4];
  };

  // See shaders/trace.vert, std430 layout
  struct FrameHeader
  {
    VkDrawIndirectCommand Draw;
    uint32_t Points;
    // see trace.vert
    uint32_t Dense;
    uint32_t Reserved[2];
    float Traces[ScopeRenderer::MAX_CHANNELS * 4];
    float Gains[ScopeRenderer::MAX_CHANNELS];
  };

  static_assert(offsetof(FrameHeader, Traces) == 32, "Layout mismatch");
  static_assert(sizeof(FrameHeader) % 4 == 0, "Layout mismatch");

  const VkDeviceSize FRAME_SIZE =
      sizeof(FrameHeader) + ScopeRenderer::MAX_CHANNELS * ScopeRenderer::MAX_POINTS * sizeof(int16_t);
  // swapchains rarely have more images
  const uint32_t MAX_IMAGES = 8;
  const float LINE_WIDTH = 3.0f;
  // see ScopeRenderer::IsDense
  const uint32_t DENSE_REVERSALS_PART = 8;

  // Automatic gain follows peak level: immediate attack to avoid clipping,
  // slow release to avoid visible pumping. Limited to not amplify noise of silent voices
  const float AUTO_GAIN_TARGET = 0.9f;
  const float AUTO_GAIN_MAX = 8.0f;
  // per frame, ~0.5s to rise at 120Hz
  const float AUTO_GAIN_RELEASE = 0.04f;

  ScopeRenderer::ScopeRenderer(const Device& device, VkFormat format, VkImageLayout finalLayout)
    : Dev(device)
    , Vk(device.Vk())
    , Format(format)
  {
    try
    {
      VkAttachmentDescription color = {};
      color.format = Format;
      color.samples = VK_SAMPLE_COUNT_1_BIT;
      // whole picture is overwritten by opaque background, so neither clearing nor loading of tile memory is required
      color.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
      color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
      color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
      color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
      color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      color.finalLayout = finalLayout;
      VkAttachmentReference ref = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
      VkSubpassDescription subpass = {};
      subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
      subpass.colorAttachmentCount = 1;
      subpass.pColorAttachments = &ref;
      // image acquisition is waited at this stage
      VkSubpassDependency dependency = {};
      dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
      dependency.dstSubpass = 0;
      dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
      dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
      dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
      VkRenderPassCreateInfo passInfo = {};
      passInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
      passInfo.attachmentCount = 1;
      passInfo.pAttachments = &color;
      passInfo.subpassCount = 1;
      passInfo.pSubpasses = &subpass;
      passInfo.dependencyCount = 1;
      passInfo.pDependencies = &dependency;
      Check(Vk.vkCreateRenderPass(Dev.Handle(), &passInfo, nullptr, &RenderPass), "vkCreateRenderPass");

      VkDescriptorSetLayoutBinding bindings[2] = {};
      bindings[0].binding = 0;
      bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
      bindings[0].descriptorCount = 1;
      bindings[0].stageFlags = VK_SHADER_STAGE_VERTEX_BIT;
      bindings[1].binding = 1;
      bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
      bindings[1].descriptorCount = 1;
      bindings[1].stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
      VkDescriptorSetLayoutCreateInfo setInfo = {};
      setInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
      setInfo.bindingCount = 2;
      setInfo.pBindings = bindings;
      Check(Vk.vkCreateDescriptorSetLayout(Dev.Handle(), &setInfo, nullptr, &SetLayout), "vkCreateDescriptorSetLayout");

      VkPushConstantRange push = {};
      push.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
      push.size = sizeof(PushConstants);
      VkPipelineLayoutCreateInfo layoutInfo = {};
      layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
      layoutInfo.setLayoutCount = 1;
      layoutInfo.pSetLayouts = &SetLayout;
      layoutInfo.pushConstantRangeCount = 1;
      layoutInfo.pPushConstantRanges = &push;
      Check(Vk.vkCreatePipelineLayout(Dev.Handle(), &layoutInfo, nullptr, &Layout), "vkCreatePipelineLayout");

      CreatePipelines();

      VkSamplerCreateInfo samplerInfo = {};
      samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
      samplerInfo.magFilter = VK_FILTER_NEAREST;
      samplerInfo.minFilter = VK_FILTER_NEAREST;
      samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
      samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
      samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
      samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
      Check(Vk.vkCreateSampler(Dev.Handle(), &samplerInfo, nullptr, &Sampler), "vkCreateSampler");

      VkCommandPoolCreateInfo poolInfo = {};
      poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
      poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
      poolInfo.queueFamilyIndex = Dev.QueueFamily();
      Check(Vk.vkCreateCommandPool(Dev.Handle(), &poolInfo, nullptr, &CommandPool), "vkCreateCommandPool");

      VkDescriptorPoolSize sizes[2] = {{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, MAX_IMAGES},
                                       {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, MAX_IMAGES}};
      VkDescriptorPoolCreateInfo descInfo = {};
      descInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
      descInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
      descInfo.maxSets = MAX_IMAGES;
      descInfo.poolSizeCount = 2;
      descInfo.pPoolSizes = sizes;
      Check(Vk.vkCreateDescriptorPool(Dev.Handle(), &descInfo, nullptr, &DescriptorPool), "vkCreateDescriptorPool");

      // descriptors should be valid before the first real background
      CreateBackground(1, 1);
    }
    catch (...)
    {
      Destroy();
      throw;
    }
  }

  ScopeRenderer::~ScopeRenderer()
  {
    Destroy();
  }

  void ScopeRenderer::Destroy()
  {
    const auto dev = Dev.Handle();
    Vk.vkDeviceWaitIdle(dev);
    DestroyTargets();
    DestroyBackground();
    if (DescriptorPool)
    {
      Vk.vkDestroyDescriptorPool(dev, DescriptorPool, nullptr);
      DescriptorPool = VK_NULL_HANDLE;
    }
    if (CommandPool)
    {
      Vk.vkDestroyCommandPool(dev, CommandPool, nullptr);
      CommandPool = VK_NULL_HANDLE;
    }
    if (Sampler)
    {
      Vk.vkDestroySampler(dev, Sampler, nullptr);
      Sampler = VK_NULL_HANDLE;
    }
    for (auto* pipeline : {&TracePipeline, &BackgroundPipeline})
    {
      if (*pipeline)
      {
        Vk.vkDestroyPipeline(dev, *pipeline, nullptr);
        *pipeline = VK_NULL_HANDLE;
      }
    }
    if (Layout)
    {
      Vk.vkDestroyPipelineLayout(dev, Layout, nullptr);
      Layout = VK_NULL_HANDLE;
    }
    if (SetLayout)
    {
      Vk.vkDestroyDescriptorSetLayout(dev, SetLayout, nullptr);
      SetLayout = VK_NULL_HANDLE;
    }
    if (RenderPass)
    {
      Vk.vkDestroyRenderPass(dev, RenderPass, nullptr);
      RenderPass = VK_NULL_HANDLE;
    }
  }

  void ScopeRenderer::CreatePipelines()
  {
    TracePipeline = CreatePipeline(Shaders::TRACE_VERT, sizeof(Shaders::TRACE_VERT), Shaders::TRACE_FRAG,
                                   sizeof(Shaders::TRACE_FRAG), VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP, true);
    BackgroundPipeline = CreatePipeline(Shaders::BACKGROUND_VERT, sizeof(Shaders::BACKGROUND_VERT),
                                        Shaders::BACKGROUND_FRAG, sizeof(Shaders::BACKGROUND_FRAG),
                                        VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, false);
  }

  VkPipeline ScopeRenderer::CreatePipeline(const uint32_t* vertex, std::size_t vertexSize, const uint32_t* fragment,
                                           std::size_t fragmentSize, VkPrimitiveTopology topology, bool blend)
  {
    VkShaderModule modules[2] = {};
    const uint32_t* codes[2] = {vertex, fragment};
    const std::size_t sizes[2] = {vertexSize, fragmentSize};
    VkPipelineShaderStageCreateInfo stages[2] = {};
    VkPipeline result = VK_NULL_HANDLE;
    VkResult res = VK_SUCCESS;
    for (int idx = 0; idx < 2 && res == VK_SUCCESS; ++idx)
    {
      VkShaderModuleCreateInfo info = {};
      info.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
      info.codeSize = sizes[idx];
      info.pCode = codes[idx];
      res = Vk.vkCreateShaderModule(Dev.Handle(), &info, nullptr, &modules[idx]);
      stages[idx].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
      stages[idx].stage = idx == 0 ? VK_SHADER_STAGE_VERTEX_BIT : VK_SHADER_STAGE_FRAGMENT_BIT;
      stages[idx].module = modules[idx];
      stages[idx].pName = "main";
    }
    if (res == VK_SUCCESS)
    {
      VkPipelineVertexInputStateCreateInfo input = {};
      input.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
      VkPipelineInputAssemblyStateCreateInfo assembly = {};
      assembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
      assembly.topology = topology;
      VkPipelineViewportStateCreateInfo viewport = {};
      viewport.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
      viewport.viewportCount = 1;
      viewport.scissorCount = 1;
      VkPipelineRasterizationStateCreateInfo raster = {};
      raster.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
      raster.polygonMode = VK_POLYGON_MODE_FILL;
      raster.cullMode = VK_CULL_MODE_NONE;
      raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
      raster.lineWidth = 1.0f;
      VkPipelineMultisampleStateCreateInfo multisample = {};
      multisample.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
      multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
      VkPipelineColorBlendAttachmentState attachment = {};
      attachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT
                                  | VK_COLOR_COMPONENT_A_BIT;
      if (blend)
      {
        attachment.blendEnable = VK_TRUE;
        attachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
        attachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
        attachment.colorBlendOp = VK_BLEND_OP_ADD;
        // surface is opaque
        attachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
        attachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
        attachment.alphaBlendOp = VK_BLEND_OP_ADD;
      }
      VkPipelineColorBlendStateCreateInfo blending = {};
      blending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
      blending.attachmentCount = 1;
      blending.pAttachments = &attachment;
      const VkDynamicState dynamics[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
      VkPipelineDynamicStateCreateInfo dynamic = {};
      dynamic.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
      dynamic.dynamicStateCount = 2;
      dynamic.pDynamicStates = dynamics;
      VkGraphicsPipelineCreateInfo info = {};
      info.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
      info.stageCount = 2;
      info.pStages = stages;
      info.pVertexInputState = &input;
      info.pInputAssemblyState = &assembly;
      info.pViewportState = &viewport;
      info.pRasterizationState = &raster;
      info.pMultisampleState = &multisample;
      info.pColorBlendState = &blending;
      info.pDynamicState = &dynamic;
      info.layout = Layout;
      info.renderPass = RenderPass;
      res = Vk.vkCreateGraphicsPipelines(Dev.Handle(), VK_NULL_HANDLE, 1, &info, nullptr, &result);
    }
    for (auto mod : modules)
    {
      if (mod)
      {
        Vk.vkDestroyShaderModule(Dev.Handle(), mod, nullptr);
      }
    }
    Check(res, "vkCreateGraphicsPipelines");
    return result;
  }

  void ScopeRenderer::DestroyTargets()
  {
    const auto dev = Dev.Handle();
    for (auto& frame : Frames)
    {
      if (frame.Commands)
      {
        Vk.vkFreeCommandBuffers(dev, CommandPool, 1, &frame.Commands);
      }
      if (frame.Descriptors)
      {
        Vk.vkFreeDescriptorSets(dev, DescriptorPool, 1, &frame.Descriptors);
      }
      if (frame.Framebuffer)
      {
        Vk.vkDestroyFramebuffer(dev, frame.Framebuffer, nullptr);
      }
      if (frame.View)
      {
        Vk.vkDestroyImageView(dev, frame.View, nullptr);
      }
      if (frame.Buffer)
      {
        Vk.vkDestroyBuffer(dev, frame.Buffer, nullptr);
      }
      if (frame.Memory)
      {
        Vk.vkFreeMemory(dev, frame.Memory, nullptr);
      }
    }
    Frames.clear();
  }

  void ScopeRenderer::SetTargets(const std::vector<VkImage>& images, VkExtent2D extent, Rotation rotation)
  {
    if (images.size() > MAX_IMAGES)
    {
      throw Error("Too many images");
    }
    const auto dev = Dev.Handle();
    Vk.vkDeviceWaitIdle(dev);
    DestroyTargets();
    Extent = extent;
    Rotate = rotation;
    const bool swapped = rotation == Rotation::CW90 || rotation == Rotation::CW270;
    Logical = swapped ? VkExtent2D{extent.height, extent.width} : extent;
    Frames.resize(images.size());
    for (std::size_t idx = 0; idx < images.size(); ++idx)
    {
      auto& frame = Frames[idx];
      VkImageViewCreateInfo viewInfo = {};
      viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
      viewInfo.image = images[idx];
      viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
      viewInfo.format = Format;
      viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      Check(Vk.vkCreateImageView(dev, &viewInfo, nullptr, &frame.View), "vkCreateImageView");
      VkFramebufferCreateInfo fbInfo = {};
      fbInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
      fbInfo.renderPass = RenderPass;
      fbInfo.attachmentCount = 1;
      fbInfo.pAttachments = &frame.View;
      fbInfo.width = extent.width;
      fbInfo.height = extent.height;
      fbInfo.layers = 1;
      Check(Vk.vkCreateFramebuffer(dev, &fbInfo, nullptr, &frame.Framebuffer), "vkCreateFramebuffer");

      VkBufferCreateInfo bufInfo = {};
      bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
      bufInfo.size = FRAME_SIZE;
      bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
      bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
      Check(Vk.vkCreateBuffer(dev, &bufInfo, nullptr, &frame.Buffer), "vkCreateBuffer");
      VkMemoryRequirements req = {};
      Vk.vkGetBufferMemoryRequirements(dev, frame.Buffer, &req);
      VkMemoryAllocateInfo alloc = {};
      alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
      alloc.allocationSize = req.size;
      // coherent memory doesn't require flushes; mobile GPUs share memory, so it's device local as well
      alloc.memoryTypeIndex = Dev.FindMemory(req.memoryTypeBits,
                                             VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                                             VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
      Check(Vk.vkAllocateMemory(dev, &alloc, nullptr, &frame.Memory), "vkAllocateMemory");
      Check(Vk.vkBindBufferMemory(dev, frame.Buffer, frame.Memory, 0), "vkBindBufferMemory");
      Check(Vk.vkMapMemory(dev, frame.Memory, 0, VK_WHOLE_SIZE, 0, &frame.Mapped), "vkMapMemory");
      auto* header = static_cast<FrameHeader*>(frame.Mapped);
      std::memset(header, 0, sizeof(*header));

      VkDescriptorSetAllocateInfo setAlloc = {};
      setAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
      setAlloc.descriptorPool = DescriptorPool;
      setAlloc.descriptorSetCount = 1;
      setAlloc.pSetLayouts = &SetLayout;
      Check(Vk.vkAllocateDescriptorSets(dev, &setAlloc, &frame.Descriptors), "vkAllocateDescriptorSets");

      VkCommandBufferAllocateInfo cmdAlloc = {};
      cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
      cmdAlloc.commandPool = CommandPool;
      cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
      cmdAlloc.commandBufferCount = 1;
      Check(Vk.vkAllocateCommandBuffers(dev, &cmdAlloc, &frame.Commands), "vkAllocateCommandBuffers");
    }
    UpdateDescriptors();
    Record();
  }

  void ScopeRenderer::CreateBackground(uint32_t width, uint32_t height)
  {
    const auto dev = Dev.Handle();
    VkImageCreateInfo info = {};
    info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    info.imageType = VK_IMAGE_TYPE_2D;
    // single channel, 4 times less memory traffic than RGBA
    info.format = VK_FORMAT_R8_UNORM;
    info.extent = {width, height, 1};
    info.mipLevels = 1;
    info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    Check(Vk.vkCreateImage(dev, &info, nullptr, &Background), "vkCreateImage");
    VkMemoryRequirements req = {};
    Vk.vkGetImageMemoryRequirements(dev, Background, &req);
    VkMemoryAllocateInfo alloc = {};
    alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    alloc.allocationSize = req.size;
    alloc.memoryTypeIndex = Dev.FindMemory(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
    Check(Vk.vkAllocateMemory(dev, &alloc, nullptr, &BackgroundMemory), "vkAllocateMemory");
    Check(Vk.vkBindImageMemory(dev, Background, BackgroundMemory, 0), "vkBindImageMemory");
    VkImageViewCreateInfo viewInfo = {};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = Background;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    Check(Vk.vkCreateImageView(dev, &viewInfo, nullptr, &BackgroundView), "vkCreateImageView");
    BackgroundSize = {width, height};
    // initial content is undefined
    auto* cmd = BeginOneTime();
    VkImageMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = Background;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    Vk.vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0,
                            nullptr, 0, nullptr, 1, &barrier);
    EndOneTime(cmd);
  }

  void ScopeRenderer::DestroyBackground()
  {
    const auto dev = Dev.Handle();
    if (BackgroundView)
    {
      Vk.vkDestroyImageView(dev, BackgroundView, nullptr);
      BackgroundView = VK_NULL_HANDLE;
    }
    if (Background)
    {
      Vk.vkDestroyImage(dev, Background, nullptr);
      Background = VK_NULL_HANDLE;
    }
    if (BackgroundMemory)
    {
      Vk.vkFreeMemory(dev, BackgroundMemory, nullptr);
      BackgroundMemory = VK_NULL_HANDLE;
    }
  }

  void ScopeRenderer::SetBackground(const uint8_t* alpha, uint32_t width, uint32_t height, uint32_t stride)
  {
    if (!width || !height)
    {
      return;
    }
    const auto dev = Dev.Handle();
    // rare operation, so simple synchronization is used
    Vk.vkDeviceWaitIdle(dev);
    if (BackgroundSize.width != width || BackgroundSize.height != height)
    {
      DestroyBackground();
      CreateBackground(width, height);
      UpdateDescriptors();
      Record();
    }
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkBufferCreateInfo bufInfo = {};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = VkDeviceSize(width) * height;
    bufInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    Check(Vk.vkCreateBuffer(dev, &bufInfo, nullptr, &staging), "vkCreateBuffer");
    try
    {
      VkMemoryRequirements req = {};
      Vk.vkGetBufferMemoryRequirements(dev, staging, &req);
      VkMemoryAllocateInfo alloc = {};
      alloc.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
      alloc.allocationSize = req.size;
      alloc.memoryTypeIndex = Dev.FindMemory(
          req.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
      Check(Vk.vkAllocateMemory(dev, &alloc, nullptr, &memory), "vkAllocateMemory");
      Check(Vk.vkBindBufferMemory(dev, staging, memory, 0), "vkBindBufferMemory");
      void* mapped = nullptr;
      Check(Vk.vkMapMemory(dev, memory, 0, VK_WHOLE_SIZE, 0, &mapped), "vkMapMemory");
      for (uint32_t row = 0; row < height; ++row)
      {
        std::memcpy(static_cast<uint8_t*>(mapped) + row * width, alpha + std::size_t(row) * stride, width);
      }
      Vk.vkUnmapMemory(dev, memory);

      auto* cmd = BeginOneTime();
      VkImageMemoryBarrier barrier = {};
      barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
      barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
      barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
      barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
      barrier.image = Background;
      barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
      barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
      Vk.vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                              nullptr, 1, &barrier);
      VkBufferImageCopy region = {};
      region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
      region.imageExtent = {width, height, 1};
      Vk.vkCmdCopyBufferToImage(cmd, staging, Background, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
      barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
      barrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
      barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
      barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
      Vk.vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr,
                              0, nullptr, 1, &barrier);
      EndOneTime(cmd);
    }
    catch (...)
    {
      Vk.vkDestroyBuffer(dev, staging, nullptr);
      if (memory)
      {
        Vk.vkFreeMemory(dev, memory, nullptr);
      }
      throw;
    }
    Vk.vkDestroyBuffer(dev, staging, nullptr);
    Vk.vkFreeMemory(dev, memory, nullptr);
  }

  void ScopeRenderer::SetTraces(const float* traces, uint32_t channels, uint32_t width, uint32_t height)
  {
    TracesCount = std::min(channels, MAX_CHANNELS);
    std::copy(traces, traces + TracesCount * 4, Traces.begin());
    if (View.width != width || View.height != height)
    {
      // scale is stored in prerecorded commands
      Dev.Vk().vkDeviceWaitIdle(Dev.Handle());
      View = {width, height};
      Record();
    }
  }

  VkCommandBuffer ScopeRenderer::BeginOneTime()
  {
    VkCommandBufferAllocateInfo cmdAlloc = {};
    cmdAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cmdAlloc.commandPool = CommandPool;
    cmdAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cmdAlloc.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    Check(Vk.vkAllocateCommandBuffers(Dev.Handle(), &cmdAlloc, &cmd), "vkAllocateCommandBuffers");
    VkCommandBufferBeginInfo begin = {};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    Check(Vk.vkBeginCommandBuffer(cmd, &begin), "vkBeginCommandBuffer");
    return cmd;
  }

  void ScopeRenderer::EndOneTime(VkCommandBuffer cmd)
  {
    const auto dev = Dev.Handle();
    VkFence fence = VK_NULL_HANDLE;
    VkResult res = Vk.vkEndCommandBuffer(cmd);
    if (res == VK_SUCCESS)
    {
      VkFenceCreateInfo fenceInfo = {};
      fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
      res = Vk.vkCreateFence(dev, &fenceInfo, nullptr, &fence);
    }
    if (res == VK_SUCCESS)
    {
      VkSubmitInfo submit = {};
      submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
      submit.commandBufferCount = 1;
      submit.pCommandBuffers = &cmd;
      res = Vk.vkQueueSubmit(Dev.Queue(), 1, &submit, fence);
    }
    if (res == VK_SUCCESS)
    {
      res = Vk.vkWaitForFences(dev, 1, &fence, VK_TRUE, UINT64_MAX);
    }
    if (fence)
    {
      Vk.vkDestroyFence(dev, fence, nullptr);
    }
    Vk.vkFreeCommandBuffers(dev, CommandPool, 1, &cmd);
    Check(res, "one time submit");
  }

  void ScopeRenderer::UpdateDescriptors()
  {
    for (auto& frame : Frames)
    {
      VkDescriptorBufferInfo bufferInfo = {frame.Buffer, 0, FRAME_SIZE};
      VkDescriptorImageInfo imageInfo = {Sampler, BackgroundView, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
      VkWriteDescriptorSet writes[2] = {};
      writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      writes[0].dstSet = frame.Descriptors;
      writes[0].dstBinding = 0;
      writes[0].descriptorCount = 1;
      writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
      writes[0].pBufferInfo = &bufferInfo;
      writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      writes[1].dstSet = frame.Descriptors;
      writes[1].dstBinding = 1;
      writes[1].descriptorCount = 1;
      writes[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
      writes[1].pImageInfo = &imageInfo;
      Vk.vkUpdateDescriptorSets(Dev.Handle(), 2, writes, 0, nullptr);
    }
  }

  // Commands depend only on targets and background, data is taken from buffer (including draw parameters)
  void ScopeRenderer::Record()
  {
    PushConstants push = {};
    const auto& view = View.width && View.height ? View : Logical;
    push.ToNdc[0] = 2.0f / std::max(view.width, 1u);
    push.ToNdc[1] = 2.0f / std::max(view.height, 1u);
    push.HalfWidth = LINE_WIDTH / 2;
    // clip space rotation as in https://developer.android.com/games/optimize/vulkan-prerotation
    switch (Rotate)
    {
    case Rotation::NONE:
      std::copy_n(std::array<float, 4>{1, 0, 0, 1}.data(), 4, push.Rotation);
      break;
    case Rotation::CW90:
      std::copy_n(std::array<float, 4>{0, 1, -1, 0}.data(), 4, push.Rotation);
      break;
    case Rotation::CW180:
      std::copy_n(std::array<float, 4>{-1, 0, 0, -1}.data(), 4, push.Rotation);
      break;
    case Rotation::CW270:
      std::copy_n(std::array<float, 4>{0, -1, 1, 0}.data(), 4, push.Rotation);
      break;
    }
    for (auto& frame : Frames)
    {
      auto* cmd = frame.Commands;
      Check(Vk.vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
      VkCommandBufferBeginInfo begin = {};
      begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
      Check(Vk.vkBeginCommandBuffer(cmd, &begin), "vkBeginCommandBuffer");
      VkRenderPassBeginInfo pass = {};
      pass.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
      pass.renderPass = RenderPass;
      pass.framebuffer = frame.Framebuffer;
      pass.renderArea.extent = Extent;
      Vk.vkCmdBeginRenderPass(cmd, &pass, VK_SUBPASS_CONTENTS_INLINE);
      const VkViewport viewport = {0, 0, float(Extent.width), float(Extent.height), 0, 1};
      const VkRect2D scissor = {{0, 0}, Extent};
      Vk.vkCmdSetViewport(cmd, 0, 1, &viewport);
      Vk.vkCmdSetScissor(cmd, 0, 1, &scissor);
      Vk.vkCmdPushConstants(cmd, Layout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(push),
                            &push);
      Vk.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, Layout, 0, 1, &frame.Descriptors, 0, nullptr);
      Vk.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, BackgroundPipeline);
      Vk.vkCmdDraw(cmd, 3, 1, 0, 0);
      Vk.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, TracePipeline);
      Vk.vkCmdDrawIndirect(cmd, frame.Buffer, offsetof(FrameHeader, Draw), 1, sizeof(VkDrawIndirectCommand));
      Vk.vkCmdEndRenderPass(cmd);
      Check(Vk.vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
    }
  }

  // Long windows are delivered as minimums and maximums (see Sound::Scope::Get), so direction of trace is changed at
  // most of points. Smooth waves at short windows change it rarely.
  bool ScopeRenderer::IsDense(const int16_t* data, uint32_t points)
  {
    uint32_t reversals = 0;
    int prevDelta = 0;
    for (uint32_t idx = 1; idx < points; ++idx)
    {
      const int delta = int(data[idx]) - data[idx - 1];
      if (delta != 0)
      {
        reversals += (delta > 0) != (prevDelta > 0) && prevDelta != 0;
        prevDelta = delta;
      }
    }
    return reversals > points / DENSE_REVERSALS_PART;
  }

  void ScopeRenderer::Write(uint32_t image, const int16_t* samples, uint32_t channels, uint32_t points, int gainPercent)
  {
    auto& frame = Frames.at(image);
    auto* header = static_cast<FrameHeader*>(frame.Mapped);
    channels = std::min({channels, TracesCount, MAX_CHANNELS});
    points = std::min(points, MAX_POINTS);
    if (points < 2)
    {
      channels = 0;
    }
    std::memcpy(header->Traces, Traces.data(), channels * 4 * sizeof(float));
    uint32_t dense = 0;
    for (uint32_t chan = 0; chan < channels; ++chan)
    {
      const auto* data = samples + chan * points;
      if (IsDense(data, points))
      {
        dense |= 1u << chan;
      }
      float gain = gainPercent / 100.0f;
      if (gainPercent == AUTO_GAIN)
      {
        int peak = 0;
        for (uint32_t idx = 0; idx < points; ++idx)
        {
          peak = std::max(peak, std::abs(int(data[idx])));
        }
        const float level = peak / 32768.0f;
        auto& smoothed = SmoothedPeaks[chan];
        smoothed = level > smoothed ? level : smoothed + (level - smoothed) * AUTO_GAIN_RELEASE;
        gain = std::max(AUTO_GAIN_TARGET / std::max(smoothed, AUTO_GAIN_TARGET / AUTO_GAIN_MAX), 1.0f);
      }
      header->Gains[chan] = gain;
    }
    std::memcpy(header + 1, samples, std::size_t(channels) * points * sizeof(int16_t));
    header->Points = points;
    header->Dense = dense;
    header->Draw.vertexCount = points * 2;
    header->Draw.instanceCount = channels;
    header->Draw.firstVertex = 0;
    header->Draw.firstInstance = 0;
  }

  void ScopeRenderer::Submit(uint32_t image, VkSemaphore wait, VkSemaphore signal, VkFence fence)
  {
    const VkPipelineStageFlags stage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit = {};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.waitSemaphoreCount = wait ? 1 : 0;
    submit.pWaitSemaphores = &wait;
    submit.pWaitDstStageMask = &stage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &Frames.at(image).Commands;
    submit.signalSemaphoreCount = signal ? 1 : 0;
    submit.pSignalSemaphores = &signal;
    Check(Vk.vkQueueSubmit(Dev.Queue(), 1, &submit, fence), "vkQueueSubmit");
  }
}  // namespace VulkanScope
