#version 450
#extension GL_GOOGLE_include_directive : require

#include "frame.glsl"

layout(location = 0) out vec2 vUv;

// Single triangle covering the whole view, texture coordinates have origin at top-left on every backend
void main()
{
  vec2 uv = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
  vUv = uv;
  gl_Position = toClip(uv / frame.view.xy * 2.0);
}
