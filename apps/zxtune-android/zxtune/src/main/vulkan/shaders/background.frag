#version 450

layout(set = 0, binding = 1) uniform sampler2D background;

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 color;

// White with alpha over black, so no blending and clearing are required
void main()
{
  color = vec4(vec3(texture(background, vUv).r), 1.0);
}
