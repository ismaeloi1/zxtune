#version 450
#extension GL_GOOGLE_include_directive : require

#include "common.glsl"

layout(location = 0) in float vDistance;
layout(location = 0) out vec4 color;

// Smooth edges by distance from line center: cheaper than multisampling for tiled GPUs
void main()
{
  color = vec4(1.0, 1.0, 1.0, clamp(pc.halfWidth + 0.5 - abs(vDistance), 0.0, 1.0));
}
