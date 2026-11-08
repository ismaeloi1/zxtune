/**
 * @file
 * @brief Oscilloscope view component (corrscope-like, triggered per-voice waveforms), OpenGL ES backend
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.SurfaceHolder
import app.zxtune.Logger
import app.zxtune.playback.Visualizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

private val LOG = Logger("Oscilloscope")

/**
 * Renders waveforms provided by [Visualizer.getScope] when they change (see [ScopeFeed]).
 * Separate voices are placed by column per chip (long chips are split to several columns), voice per row.
 */
class OscilloscopeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs),
    ScopeDisplay {

    private val labelTextSize = labelTextSize(context)

    // filter response is built on feed thread, as by Vulkan backend
    private class Pending {
        val frame = ScopeFeed.Frame()
        val shapes = FloatArray(ScopeShapes.MAX * ScopeShapes.FLOATS)
        var shapesCount = 0
        var filter: FilterLayout? = null
    }

    private val pending = Pending()
    private var hasPending = false
    private val filterResources = FilterResources.of(context)
    private val filterScene = FilterScene(filterResources)
    private val feedShapes = ScopeShapes()
    private var feedLayout: FilterLayout? = null
    private var feedGeneration = -1
    private val feed = ScopeFeed(context) { frame -> onFrame(frame) }
    private val activity = ScopeActivity(this, feed)

    init {
        setEGLContextClientVersion(2)
        // opaque surface on top of window may be composed as overlay without blending
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        holder.setFormat(PixelFormat.RGBX_8888)
        setZOrderOnTop(true)
        preserveEGLContextOnPause = true
        setRenderer(ScopeRenderer())
        renderMode = RENDERMODE_WHEN_DIRTY
        feed.onFilterSupport = { supported -> post { onFilterSupport?.invoke(supported) } }
    }

    // feed thread
    private fun onFrame(frame: ScopeFeed.Frame): Boolean {
        val layout = if (frame.isFilter) {
            if (frame.generation != feedGeneration || feedLayout == null) {
                feedLayout = frame.grid?.let { grid ->
                    FilterLayout.of(
                        context,
                        frame.width,
                        frame.height,
                        frame.voicesLayout,
                        grid,
                        labelTextSize,
                        filterResources,
                        filterScene.settings.withWaves
                    )
                }
            }
            feedLayout?.also {
                filterScene.build(it, frame.gauges, frame.gaugesCount, frame.status, frame.nanos, frame.isLive, feedShapes)
            }
        } else {
            feedShapes.clear()
            null
        }
        feedGeneration = frame.generation
        synchronized(pending) {
            val target = pending.frame
            frame.samples.copyInto(target.samples, 0, 0, frame.channels * frame.points)
            target.channels = frame.channels
            target.points = frame.points
            target.grid = frame.grid
            target.generation = frame.generation
            feedShapes.data.copyInto(pending.shapes, 0, 0, feedShapes.count * ScopeShapes.FLOATS)
            pending.shapesCount = feedShapes.count
            pending.filter = layout
            hasPending = true
        }
        requestRender()
        return true
    }

    override val source
        get() = feed.source

    override var windowMs by feed::windowMs

    override var gauges by feed::gauges

    override var showFilter by feed::showFilter

    override var filterSettings by filterScene::settings

    override var onFilterSupport: ((Boolean) -> Unit)? = null

    @Volatile
    override var gainPercent = AUTO_GAIN

    override var maxFps
        get() = feed.maxFps
        set(value) {
            feed.maxFps = value
            activity.update()
        }

    override fun getStatistics() = feed.stats.text

    override fun setSource(src: Visualizer?, isLive: Boolean) {
        LOG.d { "setSource($src, live=$isLive)" }
        feed.source = src
        activity.isLive = isLive
        activity.update()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        activity.surface = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        super.surfaceChanged(holder, format, w, h)
        feed.setSize(w, h)
        activity.update()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        activity.surface = null
        super.surfaceDestroyed(holder)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        activity.update()
    }

    override fun onDetachedFromWindow() {
        activity.detach()
        super.onDetachedFromWindow()
    }

    private inner class ScopeRenderer : Renderer {
        private val frame = ScopeFeed.Frame()
        private val shapes = FloatArray(ScopeShapes.MAX * ScopeShapes.FLOATS)
        private var shapesCount = 0
        private var filter: FilterLayout? = null
        private val shapesRenderer = ScopeShapesGles()
        private val values = FloatArray(ScopeGrid.MAX_POINTS)
        private val gain = AutoGain()
        private val traces = FloatArray(ScopeFeed.MAX_CHANNELS * 4)
        private val vertices: FloatBuffer = allocateFloats(ScopeGrid.MAX_POINTS * CORNERS.size * VERTEX_FLOATS)
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
        private var lineLocalAttr = 0
        private var lineHalfWidth = 0
        private var textureProgram = 0
        private var texturePosAttr = 0
        private var textureUvAttr = 0
        private var backgroundTexture = 0
        private var isMaxBlending = false
        private var backgroundGeneration = -1
        private var width = 1
        private var height = 1
        private val hint = WorkHint(context)

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            lineProgram = createProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
            linePosAttr = GLES20.glGetAttribLocation(lineProgram, "aPos")
            lineLocalAttr = GLES20.glGetAttribLocation(lineProgram, "aLocal")
            lineHalfWidth = GLES20.glGetUniformLocation(lineProgram, "uHalfWidth")
            textureProgram = createProgram(TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
            texturePosAttr = GLES20.glGetAttribLocation(textureProgram, "aPos")
            textureUvAttr = GLES20.glGetAttribLocation(textureProgram, "aUv")
            backgroundTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            backgroundGeneration = -1
            // white traces over gray background: maximum is the same as alpha blending, but overlapped edges of
            // neighbour segments are not accumulated into brighter dots at joints (EXT_blend_minmax is supported by
            // almost all the devices, otherwise alpha blending is kept)
            isMaxBlending = GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("GL_EXT_blend_minmax") == true
            shapesRenderer.onSurfaceCreated(::createProgram)
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            width = maxOf(w, 1)
            height = maxOf(h, 1)
            GLES20.glViewport(0, 0, width, height)
            backgroundGeneration = -1
            LOG.d { "Surface ${width}x$height" }
        }

        override fun onDrawFrame(gl: GL10?) {
            val start = System.nanoTime()
            synchronized(pending) {
                if (hasPending) {
                    val src = pending.frame
                    src.samples.copyInto(frame.samples, 0, 0, src.channels * src.points)
                    frame.channels = src.channels
                    frame.points = src.points
                    frame.grid = src.grid
                    frame.generation = src.generation
                    pending.shapes.copyInto(shapes, 0, 0, pending.shapesCount * ScopeShapes.FLOATS)
                    shapesCount = pending.shapesCount
                    filter = pending.filter
                    hasPending = false
                }
            }
            // clear is cheaper than restoring of previous content for tiled GPUs
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val grid = frame.grid ?: return
            drawBackground(grid)
            val layout = filter
            drawChannels(grid, layout)
            if (layout != null) {
                shapesRenderer.draw(shapes, shapesCount, layout.atlas.bitmap, width, height)
            }
            val end = System.nanoTime()
            feed.stats.frame("gles", start, end)
            hint.report(feed.targetNanos, end - start)
        }

        private fun drawBackground(grid: ScopeGrid) {
            if (backgroundGeneration != frame.generation) {
                updateBackground(grid)
                backgroundGeneration = frame.generation
            }
            GLES20.glUseProgram(textureProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backgroundTexture)
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

        // Single channel texture, 4 times less memory traffic than RGBA one
        private fun updateBackground(grid: ScopeGrid) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            filter?.drawBackground(bitmap) ?: grid.drawBackground(bitmap, labelTextSize)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backgroundTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        }

        private fun drawChannels(grid: ScopeGrid, layout: FilterLayout?) {
            val channels = if (layout != null) {
                minOf(frame.channels, layout.traces(traces))
            } else {
                grid.traces(width, height, labelTextSize, traces)
                minOf(frame.channels, grid.channels)
            }
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            if (isMaxBlending) {
                GLES20.glBlendEquation(GL_MAX_EXT)
            }
            GLES20.glUseProgram(lineProgram)
            GLES20.glUniform1f(lineHalfWidth, LINE_WIDTH_PX / 2f)
            GLES20.glEnableVertexAttribArray(linePosAttr)
            GLES20.glEnableVertexAttribArray(lineLocalAttr)
            for (chan in 0 until channels) {
                drawChannel(chan)
            }
            GLES20.glDisableVertexAttribArray(linePosAttr)
            GLES20.glDisableVertexAttribArray(lineLocalAttr)
            if (isMaxBlending) {
                GLES20.glBlendEquation(GLES20.GL_FUNC_ADD)
            }
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        // Each segment is a separate quad covering line's thickness and antialiased edge, fragment's coverage is
        // computed by distance to segment: joined vertex normals of a strip pinch or fold at sharp turns (steps,
        // minimums and maximums of long windows), which looked jagged and of uneven thickness
        private fun drawChannel(chan: Int) {
            val left = traces[chan * 4]
            val traceW = traces[chan * 4 + 1]
            val centerY = traces[chan * 4 + 2]
            val ampl = traces[chan * 4 + 3]
            val points = frame.points
            val offset = chan * points
            val gain = gain.of(chan, frame.samples, offset, points, gainPercent)
            for (idx in 0 until points) {
                // amplified peaks are limited by voice's cell
                values[idx] = (frame.samples[offset + idx] * gain / 32768f).coerceIn(-1f, 1f)
            }
            val toX = 2f / width
            val toY = 2f / height
            val step = traceW / (points - 1)
            val extent = LINE_WIDTH_PX / 2f + 0.5f
            vertices.clear()
            var x0 = left
            var y0 = centerY - ampl * values[0]
            for (idx in 1 until points) {
                val x1 = left + step * idx
                val y1 = centerY - ampl * values[idx]
                val dx = x1 - x0
                val dy = y1 - y0
                val len = sqrt(dx * dx + dy * dy)
                val ax = if (len > 1e-4f) dx / len else 1f
                val ay = if (len > 1e-4f) dy / len else 0f
                for (corner in CORNERS) {
                    val along = if (corner < 2) -extent else len + extent
                    val across = if (corner % 2 == 0) -extent else extent
                    val x = x0 + ax * along - ay * across
                    val y = y0 + ay * along + ax * across
                    vertices.put(x * toX - 1).put(1 - y * toY).put(along).put(across).put(len)
                }
                x0 = x1
                y0 = y1
            }
            vertices.flip()
            vertices.position(0)
            GLES20.glVertexAttribPointer(linePosAttr, 2, GLES20.GL_FLOAT, false, VERTEX_SIZE, vertices)
            vertices.position(2)
            GLES20.glVertexAttribPointer(lineLocalAttr, 3, GLES20.GL_FLOAT, false, VERTEX_SIZE, vertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, (points - 1) * CORNERS.size)
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 40
        const val AUTO_GAIN = 0

        // Thin line keeps steps, spikes and noise of waveform distinguishable, antialiased edges keep it smooth
        private const val LINE_WIDTH_PX = 1.5f
        private const val GL_MAX_EXT = 0x8008

        // quad of segment as two triangles: corners 0, 1 at start, 2, 3 at end, even ones at the same side
        private val CORNERS = intArrayOf(0, 2, 1, 1, 2, 3)

        // position, then along, across and length of segment
        private const val VERTEX_FLOATS = 5
        private const val VERTEX_SIZE = VERTEX_FLOATS * 4
        private const val LABEL_TEXT_SIZE_SP = 12f

        /**
         * @return text size of labels in cell of specified height, 0 if there's no space for labels
         */
        internal fun labelSize(cellHeight: Float, textSize: Float) = ScopeGrid.labelSize(cellHeight, textSize)

        internal fun labelTextSize(context: Context) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            LABEL_TEXT_SIZE_SP,
            context.resources.displayMetrics
        )

        private const val LINE_VERTEX_SHADER = """
            attribute vec2 aPos;
            attribute vec3 aLocal;
            varying vec3 vLocal;
            void main() {
              vLocal = aLocal;
              gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        // Coverage by distance to segment, length is the same for all the vertices of segment
        private const val LINE_FRAGMENT_SHADER = """
            precision highp float;
            uniform float uHalfWidth;
            varying vec3 vLocal;
            void main() {
              float distance = length(vec2(max(max(-vLocal.x, vLocal.x - vLocal.z), 0.0), vLocal.y));
              // premultiplied white, so both maximum and alpha blending work
              gl_FragColor = vec4(clamp(uHalfWidth + 0.5 - distance, 0.0, 1.0));
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

        // White with alpha over black background, so no blending is required
        private const val TEXTURE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            void main() {
              gl_FragColor = vec4(vec3(texture2D(uTexture, vUv).a), 1.0);
            }
        """

        private fun allocateFloats(count: Int): FloatBuffer = ByteBuffer.allocateDirect(count * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        internal fun createProgram(vertexCode: String, fragmentCode: String): Int {
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
