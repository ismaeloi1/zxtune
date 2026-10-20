// Per frame parameters shared by all the stages, see GpuScopeView::Uniforms
layout(std140, binding = 0) uniform Frame
{
  // QRhi::clipSpaceCorrMatrix: the same picture with Y up (OpenGL) and Y down (Vulkan, D3D) clip spaces
  mat4 clipCorrection;
  // x,y: pixels to normalized coordinates scale, z: half of line width in pixels, w: points per channel
  vec4 view;
  // x: 1 if samples texture keeps raw 16-bit values (R16), 0 if they are normalized floats (R32F)
  vec4 format;
  // left, width, vertical center and amplitude in pixels
  vec4 traces[64];
  // x: gain, y: 1 if points are minimums and maximums of long window (dense oscillation)
  vec4 params[64];
} frame;

// Pixels (Y down from top-left) to clip space of current backend
vec4 toClip(vec2 pixels)
{
  return frame.clipCorrection * vec4(pixels.x * frame.view.x - 1.0, 1.0 - pixels.y * frame.view.y, 0.0, 1.0);
}
