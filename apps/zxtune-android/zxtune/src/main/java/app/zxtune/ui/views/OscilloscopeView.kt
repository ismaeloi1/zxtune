/**
 * @file
 * @brief Oscilloscope view component (corrscope-like, triggered per-voice waveforms)
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Surface
import android.view.SurfaceHolder
import app.zxtune.Logger
import app.zxtune.R
import app.zxtune.playback.ScopeLayout
import app.zxtune.playback.Visualizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

private val LOG = Logger("Oscilloscope")

/**
 * Renders waveforms provided by [Visualizer.getScope] at display refresh rate (vsync driven).
 * Separate voices are placed by column per chip, voice per row.
 */
class OscilloscopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    @Volatile
    private var source: Visualizer? = null
    private val labelTextSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        LABEL_TEXT_SIZE_SP,
        resources.displayMetrics
    )
    private val labelPadding = labelTextSize / 2

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setZOrderOnTop(true)
        preserveEGLContextOnPause = true
        setRenderer(ScopeRenderer())
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    @Volatile
    private var statistics = ""

    /**
     * @return rendering statistics: real fps, frame interval, drawing time (including data request)
     */
    fun getStatistics() = statistics

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

    // Grid of cells: column per chip, row per voice
    private data class Grid(val channels: Int, val cols: Int, val rows: Int) {
        fun column(chan: Int) = chan / rows
        fun row(chan: Int) = chan % rows

        companion object {
            fun of(layout: ScopeLayout): Grid {
                val channels = layout.channels
                val perChip = layout.channelsPerChip
                return if (perChip in 1..channels) {
                    Grid(channels, (channels + perChip - 1) / perChip, perChip)
                } else {
                    Grid(channels, 1, channels)
                }
            }
        }
    }

    private fun getLabel(layout: ScopeLayout, chan: Int) = layout.channelsPerChip.takeIf { it > 0 }?.let {
        resources.getString(R.string.visualizer_oscilloscope_voice, chan / it + 1, chan % it + 1)
    }

    private inner class ScopeRenderer : Renderer {
        private val samples = ShortArray(MAX_CHANNELS * POINTS)
        private val vertices: FloatBuffer = allocateFloats(POINTS * 2 * 2)
        private val gridVertices: FloatBuffer = allocateFloats(MAX_CHANNELS * 4 * 2)
        private val quadVertices: FloatBuffer = allocateFloats(4 * 4).apply {
            // x, y, u, v for fullscreen quad
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 1f,
                    1f, -1f, 1f, 1f,
                    -1f, 1f, 0f, 0f,
                    1f, 1f, 1f, 0f,
                )
            )
            flip()
        }
        private var lineProgram = 0
        private var linePosAttr = 0
        private var lineColorUniform = 0
        private var textureProgram = 0
        private var texturePosAttr = 0
        private var textureUvAttr = 0
        private var labelsTexture = 0
        private var labelsLayout: ScopeLayout? = null
        private var width = 1
        private var height = 1
        private var frames = 0
        private var framesStart = 0L
        private var drawNanos = 0L
        private var maxDrawNanos = 0L
        private var maxIntervalNanos = 0L
        private var lastFrameStart = 0L

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            lineProgram = createProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
            linePosAttr = GLES20.glGetAttribLocation(lineProgram, "aPos")
            lineColorUniform = GLES20.glGetUniformLocation(lineProgram, "uColor")
            textureProgram = createProgram(TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
            texturePosAttr = GLES20.glGetAttribLocation(textureProgram, "aPos")
            textureUvAttr = GLES20.glGetAttribLocation(textureProgram, "aUv")
            labelsTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            labelsLayout = null
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = maxOf(w, 1)
            height = maxOf(h, 1)
            GLES20.glViewport(0, 0, width, height)
            labelsLayout = null
            LOG.d { "Surface ${width}x$height" }
        }

        override fun onDrawFrame(gl: GL10?) {
            val start = System.nanoTime()
            drawFrame()
            countFrame(start, System.nanoTime())
        }

        private fun drawFrame() {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val src = source ?: return
            val layout = runCatching {
                ScopeLayout(src.getScope(samples, POINTS))
            }.getOrElse {
                LOG.w(it) { "Failed to get scope data" }
                return
            }
            val channels = layout.channels.coerceAtMost(MAX_CHANNELS)
            if (channels == 0) {
                return
            }
            val grid = Grid.of(layout)
            GLES20.glUseProgram(lineProgram)
            GLES20.glEnableVertexAttribArray(linePosAttr)
            drawGrid(grid)
            GLES20.glUniform4f(lineColorUniform, 1f, 1f, 1f, 1f)
            for (chan in 0 until channels) {
                drawChannel(grid, chan)
            }
            GLES20.glDisableVertexAttribArray(linePosAttr)
            drawLabels(layout, grid)
        }

        // Center line of each cell
        private fun drawGrid(grid: Grid) {
            gridVertices.clear()
            for (chan in 0 until grid.channels) {
                val left = -1f + 2f * grid.column(chan) / grid.cols
                val right = left + 2f / grid.cols
                val centerY = 1f - 2f * (grid.row(chan) + 0.5f) / grid.rows
                gridVertices.put(left).put(centerY).put(right).put(centerY)
            }
            gridVertices.flip()
            GLES20.glUniform4f(lineColorUniform, 1f, 1f, 1f, 0.15f)
            GLES20.glVertexAttribPointer(linePosAttr, 2, GLES20.GL_FLOAT, false, 0, gridVertices)
            GLES20.glLineWidth(1f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, grid.channels * 2)
        }

        // Builds thick line as triangle strip, thickness is computed in pixels
        private fun drawChannel(grid: Grid, chan: Int) {
            val cellW = 2f / grid.cols
            val cellH = 2f / grid.rows
            val left = -1f + cellW * grid.column(chan) + cellW * CELL_MARGIN
            val width = cellW * (1 - 2 * CELL_MARGIN)
            val centerY = 1f - cellH * (grid.row(chan) + 0.5f)
            val ampl = cellH * 0.5f * AMPLIFICATION
            // ndc to pixels ratio
            val pxX = this.width / 2f
            val pxY = height / 2f
            val halfWidth = LINE_WIDTH_PX / 2f
            val offset = chan * POINTS
            vertices.clear()
            for (idx in 0 until POINTS) {
                val x = left + width * idx / (POINTS - 1)
                val y = centerY + ampl * samples[offset + idx] / 32768f
                val prev = maxOf(idx - 1, 0)
                val next = minOf(idx + 1, POINTS - 1)
                val dx = width * (next - prev) / (POINTS - 1) * pxX
                val dy = ampl * (samples[offset + next] - samples[offset + prev]) / 32768f * pxY
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
                val nx = -dy / len * halfWidth / pxX
                val ny = dx / len * halfWidth / pxY
                vertices.put(x + nx).put(y + ny).put(x - nx).put(y - ny)
            }
            vertices.flip()
            GLES20.glVertexAttribPointer(linePosAttr, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, POINTS * 2)
        }

        private fun drawLabels(layout: ScopeLayout, grid: Grid) {
            if (layout.channelsPerChip == 0) {
                return
            }
            if (labelsLayout != layout) {
                updateLabels(layout, grid)
                labelsLayout = layout
            }
            GLES20.glUseProgram(textureProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelsTexture)
            GLES20.glEnableVertexAttribArray(texturePosAttr)
            GLES20.glEnableVertexAttribArray(textureUvAttr)
            quadVertices.position(0)
            GLES20.glVertexAttribPointer(texturePosAttr, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
            quadVertices.position(2)
            GLES20.glVertexAttribPointer(textureUvAttr, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(texturePosAttr)
            GLES20.glDisableVertexAttribArray(textureUvAttr)
        }

        // Labels are rendered once per layout change into screen-sized texture
        private fun updateLabels(layout: ScopeLayout, grid: Grid) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(LABEL_ALPHA, 255, 255, 255)
                textSize = labelTextSize
            }
            val cellW = width.toFloat() / grid.cols
            val cellH = height.toFloat() / grid.rows
            for (chan in 0 until grid.channels) {
                val label = getLabel(layout, chan) ?: continue
                val x = cellW * grid.column(chan) + labelPadding
                val y = cellH * grid.row(chan) + labelPadding - paint.ascent()
                canvas.drawText(label, x, y, paint)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, labelsTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        }

        private fun countFrame(start: Long, end: Long) {
            if (lastFrameStart != 0L) {
                maxIntervalNanos = maxOf(maxIntervalNanos, start - lastFrameStart)
            }
            lastFrameStart = start
            if (frames == 0) {
                framesStart = start
            }
            ++frames
            drawNanos += end - start
            maxDrawNanos = maxOf(maxDrawNanos, end - start)
            val period = end - framesStart
            if (period >= STATS_PERIOD_NS) {
                val fps = frames * 1e9 / period
                statistics = String.format(
                    java.util.Locale.US,
                    "gpu: %.1f fps, frame %.2f ms (max %.1f), draw %.2f ms (max %.1f)",
                    fps,
                    1000 / fps,
                    maxIntervalNanos / 1e6,
                    drawNanos / 1e6 / frames,
                    maxDrawNanos / 1e6
                )
                frames = 0
                drawNanos = 0
                maxDrawNanos = 0
                maxIntervalNanos = 0
            }
        }
    }

    companion object {
        const val POINTS = 512
        const val MAX_CHANNELS = 32
        private const val AMPLIFICATION = 0.95f
        private const val LINE_WIDTH_PX = 3f
        private const val CELL_MARGIN = 0.01f
        private const val LABEL_TEXT_SIZE_SP = 12f
        private const val LABEL_ALPHA = 200
        private const val STATS_PERIOD_NS = 1_000_000_000L

        private const val LINE_VERTEX_SHADER = """
            attribute vec2 aPos;
            void main() {
              gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val LINE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
              gl_FragColor = uColor;
            }
        """

        private const val TEXTURE_VERTEX_SHADER = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
              vUv = aUv;
              gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        // Bitmap is uploaded with premultiplied alpha
        private const val TEXTURE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            void main() {
              vec4 color = texture2D(uTexture, vUv);
              gl_FragColor = color.a > 0.0 ? vec4(color.rgb / color.a, color.a) : vec4(0.0);
            }
        """

        private fun allocateFloats(count: Int): FloatBuffer = ByteBuffer.allocateDirect(count * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        private fun createProgram(vertexCode: String, fragmentCode: String): Int {
            val vertex = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
            val fragment = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertex)
                GLES20.glAttachShader(it, fragment)
                GLES20.glLinkProgram(it)
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
