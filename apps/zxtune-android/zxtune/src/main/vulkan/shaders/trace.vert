#version 450
#extension GL_GOOGLE_include_directive : require

#include "common.glsl"

// Per frame data, see ScopeRenderer::FrameHeader
layout(std430, set = 0, binding = 0) readonly buffer Frame
{
  // indirect draw command
  uvec4 draw;
  uint points;
  uint reserved0;
  uint reserved1;
  uint reserved2;
  // left, width, vertical center and amplitude in pixels
  vec4 traces[32];
  float gains[32];
  // 16-bit samples [channels][points], packed by pairs
  uint samples[];
} frame;

layout(location = 0) out float vDistance;

float sampleAt(uint chan, int idx)
{
  uint pos = chan * frame.points + uint(clamp(idx, 0, int(frame.points) - 1));
  uint pair = frame.samples[pos >> 1];
  // sign extension of low or high half
  int value = (pos & 1u) == 0u ? (int(pair << 16) >> 16) : (int(pair) >> 16);
  // amplified peaks are limited by voice's cell
  return clamp(float(value) * frame.gains[chan] / 32768.0, -1.0, 1.0);
}

vec2 pointAt(uint chan, int idx)
{
  vec4 trace = frame.traces[chan];
  int last = int(frame.points) - 1;
  int pos = clamp(idx, 0, last);
  return vec2(trace.x + trace.y * float(pos) / float(last), trace.z - trace.w * sampleAt(chan, pos));
}

// Thick line as triangle strip, two vertices per point: X is computed from index, so only samples are uploaded
void main()
{
  uint chan = uint(gl_InstanceIndex);
  int idx = gl_VertexIndex >> 1;
  float side = (gl_VertexIndex & 1) == 0 ? 1.0 : -1.0;
  vec2 point = pointAt(chan, idx);
  vec2 dir = pointAt(chan, idx + 1) - pointAt(chan, idx - 1);
  float len = length(dir);
  dir = len > 1e-3 ? dir / len : vec2(1.0, 0.0);
  // one more pixel for antialiased edge
  float extent = pc.halfWidth + 1.0;
  vec2 pos = point + vec2(-dir.y, dir.x) * side * extent;
  vDistance = side * extent;
  gl_Position = vec4(pc.rotation * (pos * pc.toNdc - 1.0), 0.0, 1.0);
}
