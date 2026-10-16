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
    private val pending = ScopeFeed.Frame()
    private var hasPending = false
    private val feed = ScopeFeed { frame ->
        synchronized(pending) {
            frame.samples.copyInto(pending.samples, 0, 0, frame.channels * frame.points)
            pending.channels = frame.channels
            pending.points = frame.points
            pending.grid = frame.grid
            pending.generation = frame.generation
            hasPending = true
        }
        requestRender()
    }
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
    }

    override val source
        get() = feed.source

    override var windowMs by feed::windowMs

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
        private val values = FloatArray(ScopeGrid.MAX_POINTS)
        private val gain = AutoGain()
        private val traces = FloatArray(ScopeFeed.MAX_CHANNELS * 4)
        private val vertices: FloatBuffer = allocateFloats(ScopeGrid.MAX_POINTS * 2 * 2)
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
        private var textureProgram = 0
        private var texturePosAttr = 0
        private var textureUvAttr = 0
        private var backgroundTexture = 0
        private var backgroundGeneration = -1
        private var width = 1
        private var height = 1

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            lineProgram = createProgram(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
            linePosAttr = GLES20.glGetAttribLocation(lineProgram, "aPos")
            textureProgram = createProgram(TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
            texturePosAttr = GLES20.glGetAttribLocation(textureProgram, "aPos")
            textureUvAttr = GLES20.glGetAttribLocation(textureProgram, "aUv")
            backgroundTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            backgroundGeneration = -1
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
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
                    pending.samples.copyInto(frame.samples, 0, 0, pending.channels * pending.points)
                    frame.channels = pending.channels
                    frame.points = pending.points
                    frame.grid = pending.grid
                    frame.generation = pending.generation
                    hasPending = false
                }
            }
            // clear is cheaper than restoring of previous content for tiled GPUs
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val grid = frame.grid ?: return
            drawBackground(grid)
            drawChannels(grid)
            feed.stats.frame("gles", start, System.nanoTime())
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
            grid.drawBackground(bitmap, labelTextSize)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backgroundTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        }

        private fun drawChannels(grid: ScopeGrid) {
            val channels = minOf(frame.channels, grid.channels)
            grid.traces(width, height, labelTextSize, traces)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glUseProgram(lineProgram)
            GLES20.glEnableVertexAttribArray(linePosAttr)
            for (chan in 0 until channels) {
                drawChannel(chan)
            }
            GLES20.glDisableVertexAttribArray(linePosAttr)
            GLES20.glDisable(GLES20.GL_BLEND)
        }

        // Builds thick line as triangle strip in pixels, then converts to normalized coordinates
        private fun drawChannel(chan: Int) {
            val left = traces[chan * 4]
            val traceW = traces[chan * 4 + 1]
            val centerY = traces[chan * 4 + 2]
            val ampl = traces[chan * 4 + 3]
            val points = frame.points
            val halfWidth = LINE_WIDTH_PX / 2f
            val offset = chan * points
            val gain = gain.of(chan, frame.samples, offset, points, gainPercent)
            for (idx in 0 until points) {
                // amplified peaks are limited by voice's cell
                values[idx] = (frame.samples[offset + idx] * gain / 32768f).coerceIn(-1f, 1f)
            }
            val toX = 2f / width
            val toY = 2f / height
            val step = traceW / (points - 1)
            vertices.clear()
            for (idx in 0 until points) {
                val x = left + step * idx
                val y = centerY - ampl * values[idx]
                val prev = maxOf(idx - 1, 0)
                val next = minOf(idx + 1, points - 1)
                val dx = step * (next - prev)
                val dy = -ampl * (values[next] - values[prev])
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
                val nx = -dy / len * halfWidth
                val ny = dx / len * halfWidth
                vertices.put((x + nx) * toX - 1).put(1 - (y + ny) * toY)
                vertices.put((x - nx) * toX - 1).put(1 - (y - ny) * toY)
            }
            vertices.flip()
            GLES20.glVertexAttribPointer(linePosAttr, 2, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, points * 2)
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 40
        const val AUTO_GAIN = 0
        private const val LINE_WIDTH_PX = 3f
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
            void main() {
              gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val LINE_FRAGMENT_SHADER = """
            precision mediump float;
            void main() {
              gl_FragColor = vec4(1.0);
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
