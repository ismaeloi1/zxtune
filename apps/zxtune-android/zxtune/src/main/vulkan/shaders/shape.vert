#version 450
#extension GL_GOOGLE_include_directive : require

#include "common.glsl"
#include "shape.glsl"

// Shapes of the frame, see ScopeRenderer::Write and app.zxtune.ui.views.ScopeShapes: 4 vectors per shape
layout(std430, set = 0, binding = 3) readonly buffer Shapes
{
  // indirect draw command
  uvec4 draw;
  vec4 data[];
} shapes;

// position relative to segment's start along and across it, to circle's center or in atlas, in pixels
layout(location = 0) out vec2 vLocal;
// kind, then kind specific parameters, see shape.frag
layout(location = 1) flat out vec4 vParams;
// joints' bisectors of segment at start and at end in segment's coordinates, zero if no joint
layout(location = 2) flat out vec4 vJoints;
// straight alpha color
layout(location = 3) flat out vec4 vColor;

const ivec2 CORNERS[6] = ivec2[6](ivec2(0, -1), ivec2(1, -1), ivec2(0, 1), ivec2(0, 1), ivec2(1, -1), ivec2(1, 1));

vec2 direction(vec2 from, vec2 to)
{
  vec2 delta = to - from;
  float len = length(delta);
  return len > 1e-4 ? delta / len : vec2(0.0);
}

// see trace.vert
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

void main()
{
  int index = gl_VertexIndex / 6;
  ivec2 corner = CORNERS[gl_VertexIndex % 6];
  vec4 head = shapes.data[index * 4];
  vec4 first = shapes.data[index * 4 + 1];
  vec4 second = shapes.data[index * 4 + 2];
  vColor = shapes.data[index * 4 + 3];
  float kind = head.x;
  vec2 pos;
  vJoints = vec4(0.0);
  if (kind == SHAPE_SEGMENT)
  {
    // the same antialiasing as of traces: exact distance to segment, joints split by bisectors
    vec2 start = first.xy;
    vec2 end = first.zw;
    float len = length(end - start);
    vec2 along = len > 1e-4 ? (end - start) / len : vec2(1.0, 0.0);
    vec2 across = vec2(-along.y, along.x);
    vec2 jointStart = bisector(direction(second.xy, start), len > 1e-4 ? along : vec2(0.0));
    vec2 jointEnd = bisector(len > 1e-4 ? along : vec2(0.0), direction(end, second.zw));
    mat2 toLocal = transpose(mat2(along, across));
    vJoints = vec4(toLocal * jointStart, toLocal * jointEnd);
    float extent = head.y + 0.5;
    vLocal = vec2(corner.x == 0 ? -extent : len + extent, float(corner.y) * extent);
    pos = start + along * vLocal.x + across * vLocal.y;
    vParams = vec4(kind, head.y, len, 0.0);
  }
  else if (kind == SHAPE_CIRCLE)
  {
    float extent = head.y + 0.5;
    vLocal = vec2(corner.x == 0 ? -extent : extent, float(corner.y) * extent);
    pos = first.xy + vLocal;
    vParams = vec4(kind, head.y, head.z, 0.0);
  }
  else if (kind == SHAPE_GLYPH)
  {
    vec2 mixer = vec2(float(corner.x), float(corner.y + 1) * 0.5);
    pos = mix(first.xy, first.zw, mixer);
    vLocal = mix(second.xy, second.zw, mixer);
    vParams = vec4(kind, 0.0, 0.0, 0.0);
  }
  else
  {
    // area between top and bottom segments of the same x range (e.g. under curve or between two curves), vertical
    // gradient by local y (0 at top, 1 at bottom)
    float x = corner.x == 0 ? first.x : first.z;
    float top = corner.x == 0 ? first.y : first.w;
    float bottom = corner.x == 0 ? second.x : second.y;
    pos = vec2(x, corner.y < 0 ? top : bottom);
    vLocal = vec2(0.0, corner.y < 0 ? 0.0 : 1.0);
    vParams = vec4(kind, head.z, 0.0, 0.0);
  }
  gl_Position = vec4(pc.rotation * (pos * pc.toNdc - 1.0), 0.0, 1.0);
}
