#version 450
#extension GL_GOOGLE_include_directive : require

#include "frame.glsl"

layout(location = 0) in float vDistance;
layout(location = 0) out vec4 color;

// Smooth edges by distance from line center: no multisampled color buffer is required
void main()
{
  color = vec4(1.0, 1.0, 1.0, clamp(frame.view.z + 0.5 - abs(vDistance), 0.0, 1.0));
}
