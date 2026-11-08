/**
 * @file
 * @brief Antialiased shapes over oscilloscope picture, OpenGL ES backend
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * The same shapes and coverage as of Vulkan backend (see src/main/vulkan/shaders/shape.vert and shape.frag), but
 * geometry of each shape is expanded on CPU: OpenGL ES 2.0 has neither storage buffers nor instancing.
 * Should be used on GL thread with current context, buffers are allocated once.
 */
internal class ScopeShapesGles {
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(ScopeShapes.MAX * CORNERS * VERTEX_FLOATS * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var program = 0
    private var posAttr = 0
    private var localAttr = 0
    private var paramsAttr = 0
    private var jointsAttr = 0
    private var colorAttr = 0
    private var atlasUniform = 0
    private var atlasTexture = 0
    private var uploadedAtlas: Bitmap? = null

    // temporary values of shape expansion
    private val joints = FloatArray(4)

    fun onSurfaceCreated(createProgram: (String, String) -> Int) {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        posAttr = GLES20.glGetAttribLocation(program, "aPos")
        localAttr = GLES20.glGetAttribLocation(program, "aLocal")
        paramsAttr = GLES20.glGetAttribLocation(program, "aParams")
        jointsAttr = GLES20.glGetAttribLocation(program, "aJoints")
        colorAttr = GLES20.glGetAttribLocation(program, "aColor")
        atlasUniform = GLES20.glGetUniformLocation(program, "uAtlas")
        atlasTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        uploadedAtlas = null
    }

    /**
     * @param shapes [ScopeShapes.FLOATS] values per shape
     */
    fun draw(shapes: FloatArray, count: Int, atlas: Bitmap?, width: Int, height: Int) {
        if (count == 0) {
            return
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)
        if (atlas != null && atlas !== uploadedAtlas) {
            uploadedAtlas = atlas
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, atlas, 0)
        }
        vertices.clear()
        val toX = 2f / width
        val toY = 2f / height
        for (idx in 0 until minOf(count, ScopeShapes.MAX)) {
            expand(shapes, idx * ScopeShapes.FLOATS, toX, toY)
        }
        vertices.flip()
        GLES20.glEnable(GLES20.GL_BLEND)
        // premultiplied colors over the picture
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(atlasUniform, 1)
        bind(posAttr, 2, 0)
        bind(localAttr, 2, 2)
        bind(paramsAttr, 4, 4)
        bind(jointsAttr, 4, 8)
        bind(colorAttr, 4, 12)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count * CORNERS)
        GLES20.glDisableVertexAttribArray(posAttr)
        GLES20.glDisableVertexAttribArray(localAttr)
        GLES20.glDisableVertexAttribArray(paramsAttr)
        GLES20.glDisableVertexAttribArray(jointsAttr)
        GLES20.glDisableVertexAttribArray(colorAttr)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun bind(attr: Int, size: Int, offset: Int) {
        GLES20.glEnableVertexAttribArray(attr)
        vertices.position(offset)
        GLES20.glVertexAttribPointer(attr, size, GLES20.GL_FLOAT, false, VERTEX_FLOATS * 4, vertices)
    }

    // see shaders/shape.vert
    private fun expand(data: FloatArray, base: Int, toX: Float, toY: Float) {
        val kind = data[base]
        val p0 = data[base + 1]
        val p1 = data[base + 2]
        for (vertex in 0 until CORNERS) {
            val cx = CORNER_X[vertex]
            val cy = CORNER_Y[vertex]
            var x: Float
            var y: Float
            var localX: Float
            var localY: Float
            var param1 = p0
            var param2 = p1
            joints.fill(0f)
            when (kind) {
                ScopeShapes.SEGMENT -> {
                    val sx = data[base + 4]
                    val sy = data[base + 5]
                    val ex = data[base + 6]
                    val ey = data[base + 7]
                    val dx = ex - sx
                    val dy = ey - sy
                    val len = sqrt(dx * dx + dy * dy)
                    val hasLen = len > 1e-4f
                    val ax = if (hasLen) dx / len else 1f
                    val ay = if (hasLen) dy / len else 0f
                    segmentJoints(data, base, sx, sy, ex, ey, if (hasLen) ax else 0f, if (hasLen) ay else 0f)
                    val extent = p0 + 0.5f
                    localX = if (cx == 0) -extent else len + extent
                    localY = cy * extent
                    x = sx + ax * localX - ay * localY
                    y = sy + ay * localX + ax * localY
                    param2 = len
                }

                ScopeShapes.CIRCLE -> {
                    val extent = p0 + 0.5f
                    localX = if (cx == 0) -extent else extent
                    localY = cy * extent
                    x = data[base + 4] + localX
                    y = data[base + 5] + localY
                }

                ScopeShapes.GLYPH -> {
                    val mx = cx.toFloat()
                    val my = (cy + 1) * 0.5f
                    x = data[base + 4] + (data[base + 6] - data[base + 4]) * mx
                    y = data[base + 5] + (data[base + 7] - data[base + 5]) * my
                    localX = data[base + 8] + (data[base + 10] - data[base + 8]) * mx
                    localY = data[base + 9] + (data[base + 11] - data[base + 9]) * my
                }

                else -> {
                    x = if (cx == 0) data[base + 4] else data[base + 6]
                    val top = if (cx == 0) data[base + 5] else data[base + 7]
                    y = if (cy < 0) {
                        top
                    } else if (cx == 0) {
                        data[base + 8]
                    } else {
                        data[base + 9]
                    }
                    localX = 0f
                    localY = if (cy < 0) 0f else 1f
                    param1 = p1
                }
            }
            vertices.put(x * toX - 1).put(1 - y * toY).put(localX).put(localY)
            vertices.put(kind).put(param1).put(param2).put(0f)
            vertices.put(joints)
            // premultiplied in shader
            vertices.put(data, base + 12, 4)
        }
    }

    // bisectors of joints in segment's coordinates, see trace.vert
    private fun segmentJoints(data: FloatArray, base: Int, sx: Float, sy: Float, ex: Float, ey: Float, ax: Float, ay: Float) {
        var inX = sx - data[base + 8]
        var inY = sy - data[base + 9]
        var len = sqrt(inX * inX + inY * inY)
        if (len > 1e-4f) {
            inX /= len
            inY /= len
        } else {
            inX = 0f
            inY = 0f
        }
        var outX = data[base + 10] - ex
        var outY = data[base + 11] - ey
        len = sqrt(outX * outX + outY * outY)
        if (len > 1e-4f) {
            outX /= len
            outY /= len
        } else {
            outX = 0f
            outY = 0f
        }
        bisector(inX, inY, ax, ay, ax, ay, 0)
        bisector(ax, ay, outX, outY, ax, ay, 2)
    }

    private fun bisector(ix: Float, iy: Float, ox: Float, oy: Float, ax: Float, ay: Float, at: Int) {
        if ((ix == 0f && iy == 0f) || (ox == 0f && oy == 0f)) {
            return
        }
        var bx = ix + ox
        var by = iy + oy
        val len = sqrt(bx * bx + by * by)
        if (len > 1e-3f) {
            bx /= len
            by /= len
        } else {
            val canonical = ix > 0f || (ix == 0f && iy > 0f)
            val kx = if (canonical) ix else -ix
            val ky = if (canonical) iy else -iy
            bx = -ky
            by = kx
        }
        // to segment's coordinates: along, across (y is down as in view)
        joints[at] = bx * ax + by * ay
        joints[at + 1] = -bx * ay + by * ax
    }

    private companion object {
        const val CORNERS = 6
        val CORNER_X = intArrayOf(0, 1, 0, 0, 1, 1)
        val CORNER_Y = intArrayOf(-1, -1, 1, 1, -1, 1)

        // position, local, parameters, joints, color
        const val VERTEX_FLOATS = 16

        const val VERTEX_SHADER = """
            attribute vec2 aPos;
            attribute vec2 aLocal;
            attribute vec4 aParams;
            attribute vec4 aJoints;
            attribute vec4 aColor;
            varying vec2 vLocal;
            varying vec4 vParams;
            varying vec4 vJoints;
            varying vec4 vColor;
            void main() {
              vLocal = aLocal;
              vParams = aParams;
              vJoints = aJoints;
              vColor = aColor;
              gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        // see shaders/shape.frag
        const val FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uAtlas;
            varying vec2 vLocal;
            varying vec4 vParams;
            varying vec4 vJoints;
            varying vec4 vColor;
            void main() {
              float kind = vParams.x;
              float coverage;
              if (kind < 0.5) {
                float len = vParams.z;
                if (dot(vLocal, vJoints.xy) < 0.0 ||
                    (vJoints.zw != vec2(0.0) && dot(vLocal - vec2(len, 0.0), vJoints.zw) >= 0.0)) {
                  discard;
                }
                float distance = length(vec2(max(max(-vLocal.x, vLocal.x - len), 0.0), vLocal.y));
                coverage = clamp(vParams.y + 0.5 - distance, 0.0, 1.0);
              } else if (kind < 1.5) {
                float distance = length(vLocal);
                float radius = vParams.y;
                float stroke = vParams.z;
                coverage = stroke > 0.0 ? clamp(stroke * 0.5 + 0.5 - abs(distance - radius + stroke * 0.5), 0.0, 1.0)
                                        : clamp(radius + 0.5 - distance, 0.0, 1.0);
              } else if (kind < 2.5) {
                coverage = texture2D(uAtlas, vLocal).a;
              } else {
                coverage = mix(1.0, vParams.y, vLocal.y);
              }
              float alpha = vColor.a * coverage;
              gl_FragColor = vec4(vColor.rgb * alpha, alpha);
            }
        """
    }
}
