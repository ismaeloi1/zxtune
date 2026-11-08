#version 450
#extension GL_GOOGLE_include_directive : require

#include "common.glsl"
#include "shape.glsl"

layout(set = 0, binding = 2) uniform sampler2D atlas;

layout(location = 0) in vec2 vLocal;
layout(location = 1) flat in vec4 vParams;
layout(location = 2) flat in vec4 vJoints;
layout(location = 3) flat in vec4 vColor;
layout(location = 0) out vec4 color;

// Coverage of pixel by shape, premultiplied output is blended over the picture
void main()
{
  float kind = vParams.x;
  float coverage;
  if (kind == SHAPE_SEGMENT)
  {
    // half width, length
    float len = vParams.z;
    if (dot(vLocal, vJoints.xy) < 0.0 || (vJoints.zw != vec2(0.0) && dot(vLocal - vec2(len, 0.0), vJoints.zw) >= 0.0))
    {
      discard;
    }
    float distance = length(vec2(max(max(-vLocal.x, vLocal.x - len), 0.0), vLocal.y));
    coverage = clamp(vParams.y + 0.5 - distance, 0.0, 1.0);
  }
  else if (kind == SHAPE_CIRCLE)
  {
    // radius, stroke width (0 for disc)
    float distance = length(vLocal);
    float radius = vParams.y;
    float stroke = vParams.z;
    coverage = stroke > 0.0 ? clamp(stroke * 0.5 + 0.5 - abs(distance - radius + stroke * 0.5), 0.0, 1.0)
                            : clamp(radius + 0.5 - distance, 0.0, 1.0);
  }
  else if (kind == SHAPE_GLYPH)
  {
    coverage = texture(atlas, vLocal).r;
  }
  else
  {
    // alpha factor at bottom
    coverage = mix(1.0, vParams.y, vLocal.y);
  }
  float alpha = vColor.a * coverage;
  color = vec4(vColor.rgb * alpha, alpha);
}
