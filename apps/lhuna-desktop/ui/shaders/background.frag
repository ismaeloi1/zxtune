#version 450

layout(binding = 2) uniform sampler2D background;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 color;

// White with alpha over black is stored as luminance, so no blending is required and widget stays opaque
void main()
{
  color = vec4(vec3(texture(background, vUv).r), 1.0);
}
