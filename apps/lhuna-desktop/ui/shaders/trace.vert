#version 450
#extension GL_GOOGLE_include_directive : require

#include "frame.glsl"

// [channels][points] samples, row per channel. Texture is used instead of storage buffer as the only data
// source readable by vertex shader on every QRhi backend (OpenGL 3.3, D3D11 feature level 10+, Vulkan, Metal)
layout(binding = 1) uniform sampler2D samples;

layout(location = 0) out float vDistance;

const int MAX_RADIUS = 8;

float sampleAt(int chan, int idx)
{
  int last = int(frame.view.w) - 1;
  float raw = texelFetch(samples, ivec2(clamp(idx, 0, last), chan), 0).r;
  float value = raw;
  if (frame.format.x != 0.0)
  {
    // unsigned normalized bits of int16 value, exact for 16-bit integers in float
    float bits = floor(raw * 65535.0 + 0.5);
    value = (bits >= 32768.0 ? bits - 65536.0 : bits) / 32768.0;
  }
  // amplified peaks are limited by voice's cell
  return clamp(value * frame.params[chan].x, -1.0, 1.0);
}

vec2 pointAt(int chan, int idx)
{
  vec4 trace = frame.traces[chan];
  int last = int(frame.view.w) - 1;
  int pos = clamp(idx, 0, last);
  return vec2(trace.x + trace.y * float(pos) / float(last), trace.z - trace.w * sampleAt(chan, pos));
}

// Thick line as triangle strip, two vertices per point: X is computed from index, so only samples are uploaded.
// Dense oscillation would fold the strip into tall overlapping quads (each pixel is filled by tens of them), so it
// is drawn as band between upper and lower envelopes then: the same picture with single fill of each pixel.
void main()
{
  int chan = gl_InstanceIndex;
  int idx = gl_VertexIndex >> 1;
  float side = (gl_VertexIndex & 1) == 0 ? 1.0 : -1.0;
  float halfWidth = frame.view.z;
  if (frame.params[chan].y != 0.0)
  {
    // segments to neighbours and line's thickness are within range of points closer than half width
    vec4 trace = frame.traces[chan];
    int radius = min(int(ceil(halfWidth * (frame.view.w - 1.0) / max(trace.y, 1.0))), MAX_RADIUS) + 1;
    vec2 cur = pointAt(chan, idx);
    float top = cur.y;
    float bottom = cur.y;
    for (int delta = 1; delta <= radius; ++delta)
    {
      float left = pointAt(chan, idx - delta).y;
      float right = pointAt(chan, idx + delta).y;
      top = min(top, min(left, right));
      bottom = max(bottom, max(left, right));
    }
    // not antialiased: interpolation across columns of different height gives no distance to edge
    vDistance = 0.0;
    gl_Position = toClip(vec2(cur.x, side > 0.0 ? top - halfWidth : bottom + halfWidth));
    return;
  }
  vec2 point = pointAt(chan, idx);
  vec2 dir = pointAt(chan, idx + 1) - pointAt(chan, idx - 1);
  float len = length(dir);
  dir = len > 1e-3 ? dir / len : vec2(1.0, 0.0);
  // one more pixel for antialiased edge
  float extent = halfWidth + 1.0;
  vDistance = side * extent;
  gl_Position = toClip(point + vec2(-dir.y, dir.x) * side * extent);
}
