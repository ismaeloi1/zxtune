#version 450
#extension GL_GOOGLE_include_directive : require

#include "common.glsl"

layout(location = 0) out vec2 vUv;

// Single triangle covering the whole view
void main()
{
  vec2 uv = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
  vUv = uv;
  gl_Position = vec4(pc.rotation * (uv * 2.0 - 1.0), 0.0, 1.0);
}
