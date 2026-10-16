// Shared by all the stages, see ScopeRenderer::PushConstants
layout(push_constant) uniform Push
{
  // pixels to normalized coordinates scale of logical (not rotated) view
  vec2 toNdc;
  // half of line width in pixels
  float halfWidth;
  float reserved;
  // swapchain pre-rotation, so compositor doesn't rotate the picture itself
  mat2 rotation;
} pc;
