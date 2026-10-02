/**
 * @file
 * @brief Oscilloscope view component (corrscope-like, triggered per-voice waveforms)
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import app.zxtune.Logger
import app.zxtune.playback.Visualizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.sqrt

private val LOG = Logger("Oscilloscope")

/**
 * Renders waveforms provided by [Visualizer.getScope] at display refresh rate (vsync driven).
 */
class OscilloscopeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    @Volatile
    private var source: Visualizer? = null
    private val scopeRenderer = ScopeRenderer()

    /**
     * Transparent background to be placed over cover art. Should be set before attaching to window
     */
    var isTranslucent = true
        set(value) {
            field = value
            scopeRenderer.isTranslucent = value
        }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        preserveEGLContextOnPause = true
        setRenderer(scopeRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /**
     * @param src source of data or null to stop updating
     */
    fun setSource(src: Visualizer?) {
        LOG.d { "setSource($src)" }
        source = src
        renderMode = if (src != null) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY
        requestRender()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        requestMaxFrameRate(holder.surface)
    }

    private fun requestMaxFrameRate(surface: Surface) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val maxRate = display?.supportedModes?.maxOfOrNull { it.refreshRate } ?: return
                LOG.d { "Request frame rate $maxRate" }
                surface.setFrameRate(maxRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }.onFailure {
                LOG.w(it) { "Failed to set frame rate" }
            }
        }
    }

    private inner class ScopeRenderer : Renderer {
        var isTranslucent = true
        private val samples = ShortArray(MAX_CHANNELS * POINTS)
        private val vertices: FloatBuffer = ByteBuffer.allocateDirect(POINTS * 2 * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val gridVertices: FloatBuffer = ByteBuffer.allocateDirect(MAX_CHANNELS * 4 * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        private var program = 0
        private var posAttr = 0
        private var colorUniform = 0
        private var width = 1
        private var height = 1
        private var frames = 0L
        private var framesStart = 0L

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            program = createProgram()
            posAttr = GLES20.glGetAttribLocation(program, "aPos")
            colorUniform = GLES20.glGetUniformLocation(program, "uColor")
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = maxOf(w, 1)
            height = maxOf(h, 1)
            GLES20.glViewport(0, 0, width, height)
            LOG.d { "Surface ${width}x$height" }
        }

        override fun onDrawFrame(gl: GL10?) {
            if (isTranslucent) {
                GLES20.glClearColor(0f, 0f, 0f, 0f)
            } else {
                GLES20.glClearColor(0f, 0f, 0f, 1f)
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val src = source ?: return
            val channels = runCatching {
                src.getScope(samples, POINTS)
            }.getOrElse {
                LOG.w(it) { "Failed to get scope data" }
                0
            }.coerceIn(0, MAX_CHANNELS)
            if (channels == 0) {
                return
            }
            GLES20.glUseProgram(program)
            GLES20.glEnableVertexAttribArray(posAttr)
            val (cols, rows) = layout(channels, width, height)
            drawGrid(channels, cols, rows)
            for (chan in 0 until channels) {
                drawChannel(chan, cols, rows)
            }
            GLES20.glDisableVertexAttribArray(posAttr)
            countFrame()
        }

        private fun drawGrid(channels: Int, cols: Int, rows: Int) {
            gridVertices.clear()
            for (chan in 0 until channels) {
                val left = -1f + 2f * (chan % cols) / cols
                val right = left + 2f / cols
                val centerY = 1f - 2f * (chan / cols + 0.5f) / rows
                gridVertices.put(left).put(centerY).put(right).put(centerY)
            }
            gridVertices.flip()
            GLES20.glUniform4f(colorUniform, 0.5f, 0.5f, 0.5f, 0.35f)
            GLES20.glVertexAttribPointer(posAttr, 2, GLES20.GL_FLOAT, false, 0, gridVertices)
            GLES20.glLineWidth(1f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, channels * 2)
        }

        // Builds thick line as triangle strip, thickness is computed in pixels
        private fun drawChannel(chan: Int, cols: Int, rows: Int) {
            val cellW = 2f / cols
            val cellH = 2f / rows
            val left = -1f + cellW * (chan % cols)
            val centerY = 1f - cellH * (chan / cols + 0.5f)
            val ampl = cellH * 0.5f * AMPLIFICATION
            // ndc to pixels ratio
            val pxX = width / 2f
            val pxY = height / 2f
            val halfWidth = LINE_WIDTH_PX / 2f
            val offset = chan * POINTS
            vertices.clear()
            for (idx in 0 until POINTS) {
                val x = left + cellW * idx / (POINTS - 1)
                val y = centerY + ampl * samples[offset + idx] / 32768f
                val prev = maxOf(idx - 1, 0)
                val next = minOf(idx + 1, POINTS - 1)
                val dx = cellW * (next - prev) / (POINTS - 1) * pxX
                val dy = ampl * (samples[offset + next] - samples[offset + prev]) / 32768f * pxY
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
                val nx = -dy / len * halfWidth / pxX
                val ny = dx / len * halfWidth / pxY
                vertices.put(x + nx).put(y + ny).put(x - nx).put(y - ny)
            }
            vertices.flip()
            val color = COLORS[chan % COLORS.size]
            GLES20.glUniform4f(
                colorUniform,
                ((color shr 16) and 0xff) / 255f,
                ((color shr 8) and 0xff) / 255f,
                (color and 0xff) / 255f,
                1f
            )
            GLES20.glVertexAttribPointer(posAttr, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, POINTS * 2)
        }

        private fun countFrame() {
            val now = System.nanoTime()
            if (frames == 0L) {
                framesStart = now
            }
            if (++frames == 600L) {
                val fps = frames * 1_000_000_000L / (now - framesStart).coerceAtLeast(1)
                LOG.d { "$fps fps" }
                frames = 0
            }
        }
    }

    companion object {
        const val POINTS = 512
        const val MAX_CHANNELS = 32
        private const val AMPLIFICATION = 0.95f
        private const val LINE_WIDTH_PX = 3f
        private val COLORS = intArrayOf(
            0x4FC3F7, 0xFFB74D, 0x81C784, 0xE57373, 0xBA68C8, 0xFFF176, 0x4DB6AC, 0xF06292, 0xA1887F
        )

        // Choose grid with cells closest to wide aspect ratio, single column for portrait
        internal fun layout(channels: Int, width: Int, height: Int): Pair<Int, Int> {
            var best = 1 to channels
            var bestScore = Float.MAX_VALUE
            for (cols in 1..channels) {
                val rows = ceil(channels.toFloat() / cols).toInt()
                if ((rows - 1) * cols >= channels) {
                    continue
                }
                val aspect = (width.toFloat() / cols) / (height.toFloat() / rows)
                val score = abs(ln(aspect / TARGET_CELL_ASPECT))
                if (score < bestScore) {
                    bestScore = score
                    best = cols to rows
                }
            }
            return best
        }

        private const val TARGET_CELL_ASPECT = 3f

        private const val VERTEX_SHADER = """
            attribute vec2 aPos;
            void main() {
              gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
              gl_FragColor = uColor;
            }
        """

        private fun createProgram(): Int {
            val vertex = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertex)
                GLES20.glAttachShader(it, fragment)
                GLES20.glLinkProgram(it)
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            }
        }

        private fun loadShader(type: Int, code: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code)
            GLES20.glCompileShader(it)
            val status = IntArray(1)
            GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                LOG.d { "Shader compile failed: ${GLES20.glGetShaderInfoLog(it)}" }
            }
        }
    }
}
