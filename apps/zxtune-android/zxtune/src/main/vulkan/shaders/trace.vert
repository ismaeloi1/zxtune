#version 450
#extension GL_GOOGLE_include_directive : require

#include "common.glsl"

// Per frame data, see ScopeRenderer::FrameHeader
layout(std430, set = 0, binding = 0) readonly buffer Frame
{
  // indirect draw command
  uvec4 draw;
  uint points;
  // index of the first word of segments' intensities, 8 bits each
  uint intensities;
  uint reserved1;
  uint reserved2;
  // left, width, vertical center and amplitude in pixels
  vec4 traces[64];
  float gains[64];
  // 16-bit samples [channels][points], packed by pairs, then intensities [channels][points]
  uint samples[];
} frame;

// position relative to segment's start along and across it, in pixels
layout(location = 0) out vec2 vLocal;
// length, then beam intensity
layout(location = 1) flat out vec2 vSegment;
// joints' bisectors at start and at end in segment's coordinates, zero at trace's ends
layout(location = 2) flat out vec4 vJoints;

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

float intensityAt(uint chan, int idx)
{
  uint pos = chan * frame.points + uint(idx);
  uint word = frame.samples[frame.intensities + (pos >> 2)];
  return float((word >> ((pos & 3u) * 8u)) & 255u) / 255.0;
}

vec2 direction(vec2 from, vec2 to)
{
  vec2 delta = to - from;
  float len = length(delta);
  return len > 1e-4 ? delta / len : vec2(0.0);
}

// Neighbour segments are split by bisector of joint, the same for both of them, so each pixel is lit by one of them.
// Reversal of direction (e.g. minimum and maximum at the same column) has no bisector, line itself splits them.
vec2 bisector(vec2 incoming, vec2 outgoing)
{
  if (incoming == vec2(0.0) || outgoing == vec2(0.0))
  {
    return vec2(0.0);
  }
  vec2 sum = incoming + outgoing;
  float len = length(sum);
  if (len > 1e-3)
  {
    return sum / len;
  }
  vec2 canonical = incoming.x > 0.0 || (incoming.x == 0.0 && incoming.y > 0.0) ? incoming : -incoming;
  return vec2(-canonical.y, canonical.x);
}

// Each segment between neighbour points is a separate rectangle covering the line's thickness and antialiased edge
// around it, fragment's coverage is computed by exact distance to the segment. Joined vertex normals of a strip
// pinch or fold at sharp turns (steps, minimums and maximums of long windows), which looked like jagged and
// uneven thickness, and switching to a not antialiased band for such data changed the look between frames.
// X is computed from index, so only samples are uploaded.
const ivec2 CORNERS[6] = ivec2[6](ivec2(0, -1), ivec2(1, -1), ivec2(0, 1), ivec2(0, 1), ivec2(1, -1), ivec2(1, 1));

void main()
{
  uint chan = uint(gl_InstanceIndex);
  int segment = gl_VertexIndex / 6;
  ivec2 corner = CORNERS[gl_VertexIndex % 6];
  vec2 start = pointAt(chan, segment);
  vec2 end = pointAt(chan, segment + 1);
  float len = length(end - start);
  vec2 along = len > 1e-4 ? (end - start) / len : vec2(1.0, 0.0);
  vec2 across = vec2(-along.y, along.x);
  // clamped indices at trace's ends give zero directions, so no joints there
  vec2 first = bisector(direction(pointAt(chan, segment - 1), start), along);
  vec2 last = bisector(along, direction(end, pointAt(chan, segment + 2)));
  mat2 toLocal = transpose(mat2(along, across));
  vJoints = vec4(toLocal * first, toLocal * last);
  // coverage is zero farther than half width plus half pixel
  float extent = pc.halfWidth + 0.5;
  vec2 local = vec2(corner.x == 0 ? -extent : len + extent, float(corner.y) * extent);
  vLocal = local;
  vSegment = vec2(len, intensityAt(chan, segment));
  vec2 pos = start + along * local.x + across * local.y;
  gl_Position = vec4(pc.rotation * (pos * pc.toNdc - 1.0), 0.0, 1.0);
}
