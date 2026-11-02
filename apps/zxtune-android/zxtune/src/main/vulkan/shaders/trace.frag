#version 450
#extension GL_GOOGLE_include_directive : require

#include "common.glsl"

layout(location = 0) in vec2 vLocal;
layout(location = 1) flat in vec2 vSegment;
layout(location = 2) flat in vec4 vJoints;
layout(location = 0) out vec4 color;

// Pixel's coverage by distance to segment, cheaper than multisampling for tiled GPUs. Trace is white, so coverage is
// written as gray level and summed: joints' bisectors split pixels between neighbour segments (see trace.vert), so a
// line is lit once, while distinct passes of beam over the same place (dense oscillation) add up as on phosphor.
void main()
{
  float len = vSegment.x;
  // the same bisector is used by both segments, so pixel exactly on it is assigned to the next one only
  if (dot(vLocal, vJoints.xy) < 0.0 || (vJoints.zw != vec2(0.0) && dot(vLocal - vec2(len, 0.0), vJoints.zw) >= 0.0))
  {
    discard;
  }
  float distance = length(vec2(max(max(-vLocal.x, vLocal.x - len), 0.0), vLocal.y));
  color = vec4(clamp(pc.halfWidth + 0.5 - distance, 0.0, 1.0) * vSegment.y);
}
