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
import app.zxtune.playback.ScopeLayout
import app.zxtune.playback.Visualizer
import app.zxtune.playback.VoicesLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.sqrt

private val LOG = Logger("Oscilloscope")

/**
 * Renders waveforms provided by [Visualizer.getScope] at display refresh rate (vsync driven).
 * Separate voices are placed by column per chip (long chips are split to several columns), voice per row.
 */
class OscilloscopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    @Volatile
    var source: Visualizer? = null
        private set
    private val labelTextSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        LABEL_TEXT_SIZE_SP,
        resources.displayMetrics
    )

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
     * Displayed duration of waveforms, as corrscope's render_ms
     */
    @Volatile
    var windowMs = DEFAULT_WINDOW_MS

    /**
     * Visual amplification in percents, [AUTO_GAIN] for automatic per-voice gain
     */
    @Volatile
    var gainPercent = AUTO_GAIN

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

    // Grid of cells: column per chip, row per voice. Columns are shared with dashboard, see [ChipColumns]
    private class Grid(
        val columns: ChipColumns,
        val rows: Int,
        private val cellColumns: IntArray,
        private val cellRows: IntArray,
        val labels: List<String>,
    ) {
        val channels
            get() = cellColumns.size

        fun row(chan: Int) = cellRows[chan]

        // relative (0..1) horizontal placement of channel's cell
        fun left(chan: Int) = columns.left(cellColumns[chan])
        fun width(chan: Int) = columns.width(cellColumns[chan])

        companion object {
            fun of(layout: VoicesLayout, channels: Int): Grid {
                val columns = ChipColumns(layout.groups)
                if (layout.groups.isEmpty()) {
                    return Grid(columns, channels, IntArray(channels), IntArray(channels) { it }, emptyList())
                }
                val cellColumns = ArrayList<Int>()
                val rows = ArrayList<Int>()
                val labels = ArrayList<String>()
                layout.groups.forEachIndexed { group, desc ->
                    desc.voices.forEachIndexed { idx, voice ->
                        cellColumns.add(columns.column(group, idx))
                        rows.add(columns.row(group, idx))
                        labels.add("${desc.name} - $voice")
                    }
                }
                val count = minOf(channels, cellColumns.size)
                return Grid(
                    columns,
                    columns.maxRows,
                    cellColumns.take(count).toIntArray(),
                    rows.take(count).toIntArray(),
                    labels.take(count)
                )
            }
        }
    }

    private inner class ScopeRenderer : Renderer {
        private val samples = ShortArray(MAX_CHANNELS * POINTS)
        private val values = FloatArray(POINTS)
        private val smoothedPeaks = FloatArray(MAX_CHANNELS)
        private val vertices: FloatBuffer = allocateFloats(POINTS * 2 * 2)
        private val gridVertices: FloatBuffer = allocateFloats(MAX_CHANNELS * 4 * 2 * 2)
        private var points = POINTS
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
        private var labelsGrid: Grid? = null
        private var layoutSource: Visualizer? = null
        private var layoutId = -1
        private var voicesLayout = VoicesLayout.MASTER
        private var grid: Grid? = null
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
            labelsGrid = null
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = maxOf(w, 1)
            height = maxOf(h, 1)
            GLES20.glViewport(0, 0, width, height)
            labelsGrid = null
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
            // narrow cells don't need many points, less data to transfer and draw for many voices
            val points = (width * (grid?.columns?.narrowest ?: 1f)).toInt().coerceIn(MIN_POINTS, POINTS)
            this.points = points
            val layout = runCatching {
                ScopeLayout(src.getScope(samples, points, windowMs))
            }.getOrElse {
                LOG.w(it) { "Failed to get scope data" }
                return
            }
            val channels = layout.channels.coerceAtMost(MAX_CHANNELS)
            if (channels == 0) {
                return
            }
            val grid = getGrid(src, layout, channels) ?: return
            GLES20.glUseProgram(lineProgram)
            GLES20.glEnableVertexAttribArray(linePosAttr)
            drawGrid(grid)
            GLES20.glUniform4f(lineColorUniform, 1f, 1f, 1f, 1f)
            for (chan in 0 until channels) {
                drawChannel(grid, chan)
            }
            GLES20.glDisableVertexAttribArray(linePosAttr)
            drawLabels(grid)
        }

        // Layout description is requested only on change
        private fun getGrid(src: Visualizer, layout: ScopeLayout, channels: Int): Grid? {
            if (src !== layoutSource || layout.id != layoutId) {
                voicesLayout = runCatching {
                    VoicesLayout.parse(src.getLayout())
                }.getOrElse {
                    LOG.w(it) { "Failed to get voices layout" }
                    return null
                }
                layoutSource = src
                layoutId = layout.id
                grid = null
            }
            return grid?.takeIf { it.channels == channels } ?: Grid.of(voicesLayout, channels).also {
                grid = it
            }
        }

        // Height of labels band in pixels, see [labelSize]
        private fun labelBand(grid: Grid) = if (grid.labels.isEmpty()) {
            0f
        } else {
            labelSize(height.toFloat() / grid.rows, labelTextSize) * LABEL_BAND_TO_TEXT
        }

        // Center line of trace area and bottom border of each cell
        private fun drawGrid(grid: Grid) {
            gridVertices.clear()
            val band = 2f * labelBand(grid) / height
            val cellH = 2f / grid.rows
            for (chan in 0 until grid.channels) {
                val left = -1f + 2f * grid.left(chan)
                val right = left + 2f * grid.width(chan)
                val top = 1f - cellH * grid.row(chan)
                val centerY = (top - band + top - cellH) / 2
                gridVertices.put(left).put(centerY).put(right).put(centerY)
                if (band != 0f) {
                    gridVertices.put(left).put(top - band).put(right).put(top - band)
                }
            }
            gridVertices.flip()
            GLES20.glUniform4f(lineColorUniform, 1f, 1f, 1f, 0.15f)
            GLES20.glVertexAttribPointer(linePosAttr, 2, GLES20.GL_FLOAT, false, 0, gridVertices)
            GLES20.glLineWidth(1f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridVertices.limit() / 2)
        }

        // Builds thick line as triangle strip, thickness is computed in pixels
        private fun drawChannel(grid: Grid, chan: Int) {
            val cellW = 2f * grid.width(chan)
            val cellH = 2f / grid.rows
            val left = -1f + 2f * grid.left(chan) + cellW * CELL_MARGIN
            val width = cellW * (1 - 2 * CELL_MARGIN)
            // trace area is below the label band
            val band = 2f * labelBand(grid) / height
            val top = 1f - cellH * grid.row(chan) - band
            val traceH = cellH - band
            val centerY = top - traceH / 2
            val ampl = traceH * 0.5f * AMPLIFICATION
            val points = this@ScopeRenderer.points
            // ndc to pixels ratio
            val pxX = this.width / 2f
            val pxY = height / 2f
            val halfWidth = LINE_WIDTH_PX / 2f
            val offset = chan * points
            val gain = channelGain(chan, offset, points)
            for (idx in 0 until points) {
                // amplified peaks are limited by voice's cell
                values[idx] = (samples[offset + idx] * gain / 32768f).coerceIn(-1f, 1f)
            }
            vertices.clear()
            for (idx in 0 until points) {
                val x = left + width * idx / (points - 1)
                val y = centerY + ampl * values[idx]
                val prev = maxOf(idx - 1, 0)
                val next = minOf(idx + 1, points - 1)
                val dx = width * (next - prev) / (points - 1) * pxX
                val dy = ampl * (values[next] - values[prev]) * pxY
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
                val nx = -dy / len * halfWidth / pxX
                val ny = dx / len * halfWidth / pxY
                vertices.put(x + nx).put(y + ny).put(x - nx).put(y - ny)
            }
            vertices.flip()
            GLES20.glVertexAttribPointer(linePosAttr, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, points * 2)
        }

        // Automatic gain follows peak level: immediate attack to avoid clipping,
        // slow release to avoid visible pumping. Limited to not amplify noise of silent voices
        private fun channelGain(chan: Int, offset: Int, points: Int): Float {
            val fixed = gainPercent
            if (fixed != AUTO_GAIN) {
                return fixed / 100f
            }
            var peak = 0
            for (idx in 0 until points) {
                peak = maxOf(peak, abs(samples[offset + idx].toInt()))
            }
            val level = peak / 32768f
            val prev = smoothedPeaks[chan]
            val smoothed = if (level > prev) level else prev + (level - prev) * AUTO_GAIN_RELEASE
            smoothedPeaks[chan] = smoothed
            return (AUTO_GAIN_TARGET / maxOf(smoothed, AUTO_GAIN_TARGET / AUTO_GAIN_MAX)).coerceAtLeast(1f)
        }

        private fun drawLabels(grid: Grid) {
            if (grid.labels.isEmpty()) {
                return
            }
            if (labelsGrid !== grid) {
                updateLabels(grid)
                labelsGrid = grid
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
        private fun updateLabels(grid: Grid) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val cellH = height.toFloat() / grid.rows
            val size = labelSize(cellH, labelTextSize)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(LABEL_ALPHA, 255, 255, 255)
                textSize = size
            }
            val padding = size * (LABEL_BAND_TO_TEXT - 1)
            for (chan in 0 until if (size > 0f) grid.channels else 0) {
                val label = grid.labels[chan]
                val x = width * grid.left(chan) + padding
                // centered in the band
                val y = cellH * grid.row(chan) + padding / 2 - paint.ascent()
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
        private const val MIN_POINTS = 128

        // labels are never drawn over traces, so they are shrunk for short cells to keep traces high enough
        private const val MAX_BAND_PART = 0.4f
        private const val LABEL_BAND_TO_TEXT = 1.4f
        private const val MIN_LABEL_SCALE = 0.6f

        /**
         * @return text size of labels in cell of specified height, 0 if there's no space for labels
         */
        internal fun labelSize(cellHeight: Float, textSize: Float): Float {
            val size = minOf(textSize, cellHeight * MAX_BAND_PART / LABEL_BAND_TO_TEXT)
            return if (size >= textSize * MIN_LABEL_SCALE) size else 0f
        }
        const val DEFAULT_WINDOW_MS = 40
        const val AUTO_GAIN = 0
        private const val AUTO_GAIN_TARGET = 0.9f
        private const val AUTO_GAIN_MAX = 8f

        // per frame, ~0.5s to rise at 120Hz
        private const val AUTO_GAIN_RELEASE = 0.04f
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
