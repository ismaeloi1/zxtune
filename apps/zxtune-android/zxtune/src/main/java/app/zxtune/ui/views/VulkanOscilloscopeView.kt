/**
 * @file
 * @brief Oscilloscope view component, Vulkan backend
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import app.zxtune.Logger
import app.zxtune.playback.Visualizer
import app.zxtune.preferences.VulkanDriver
import java.nio.ByteBuffer

private val LOG = Logger("VulkanOscilloscope")

/**
 * Renders on data thread (see [ScopeFeed]): samples are copied directly to GPU visible memory and prerecorded
 * commands are submitted, so per frame CPU work is minimal. Static background is uploaded only on layout changes.
 * @param driver custom Vulkan driver or null for system one
 * @param onFailure called on UI thread if Vulkan can't be used, e.g. to switch to another backend
 */
class VulkanOscilloscopeView internal constructor(
    context: Context,
    private val driver: VulkanDriver.Info?,
    private val onFailure: () -> Unit,
) : SurfaceView(context),
    ScopeDisplay,
    SurfaceHolder.Callback {

    private val labelTextSize = OscilloscopeView.labelTextSize(context)
    private val feed = ScopeFeed(context) { frame -> render(frame) }
    private val activity = ScopeActivity(this, feed)

    // following fields are accessed on feed thread only
    private var handle = 0L
    private var uploadedGeneration = -1
    private var background: Bitmap? = null
    private var backgroundPixels: ByteBuffer? = null
    private val traces = FloatArray(ScopeFeed.MAX_CHANNELS * 4)

    @Volatile
    private var description = ""

    init {
        // opaque surface on top of window may be composed as overlay without blending
        holder.setFormat(PixelFormat.RGBX_8888)
        setZOrderOnTop(true)
        holder.addCallback(this)
    }

    override val source
        get() = feed.source

    override var windowMs by feed::windowMs

    override var gauges by feed::gauges

    @Volatile
    override var gainPercent = OscilloscopeView.AUTO_GAIN

    override var maxFps
        get() = feed.maxFps
        set(value) {
            feed.maxFps = value
            activity.update()
        }

    // the same string while statistics are not changed, so dashboard doesn't split it at each frame
    private var statsSource = ""
    private var statsText = ""

    override fun getStatistics() = feed.stats.text.let { stats ->
        if (stats !== statsSource) {
            statsSource = stats
            statsText = if (stats.isNotEmpty() && description.isNotEmpty()) "$stats\nvk: $description" else stats
        }
        statsText
    }

    override fun setSource(src: Visualizer?, isLive: Boolean) {
        LOG.d { "setSource($src, live=$isLive)" }
        feed.source = src
        activity.isLive = isLive
        activity.update()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val surface = holder.surface
        var created = false
        feed.runSync {
            created = createRenderer(surface)
        }
        if (created) {
            activity.surface = surface
        } else {
            ScopeBackend.isVulkanFailed = true
            onFailure()
        }
    }

    // Custom driver may fail to load (e.g. incompatible), then system one is used
    private fun createRenderer(surface: android.view.Surface): Boolean {
        val tempDir = context.cacheDir.absolutePath
        val hooksDir = VulkanDriver.getHooksDir(context)
        handle = driver?.let {
            VulkanScopeNative.create(surface, it.dir.absolutePath, it.libraryName, hooksDir, tempDir).also { res ->
                if (res == 0L) {
                    LOG.d { "Failed to use custom driver ${it.libraryName}" }
                }
            }
        }?.takeIf { it != 0L } ?: VulkanScopeNative.create(surface, null, null, null, null)
        uploadedGeneration = -1
        description = if (handle != 0L) VulkanScopeNative.describe(handle) else ""
        LOG.d { "Created renderer: $description" }
        return handle != 0L
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        feed.runSync {
            if (handle != 0L && VulkanScopeNative.update(handle) == VulkanScopeNative.RESULT_ERROR) {
                fail()
            }
        }
        feed.setSize(width, height)
        activity.update()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        activity.surface = null
        // surface is not valid after return
        feed.runSync {
            release()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        activity.update()
    }

    override fun onDetachedFromWindow() {
        activity.detach()
        super.onDetachedFromWindow()
    }

    private fun release() {
        if (handle != 0L) {
            VulkanScopeNative.destroy(handle)
            handle = 0
        }
    }

    private fun fail() {
        LOG.d { "Vulkan renderer failed" }
        release()
        ScopeBackend.isVulkanFailed = true
        post { onFailure() }
    }

    private fun render(frame: ScopeFeed.Frame): Boolean {
        if (handle == 0L) {
            return false
        }
        val grid = frame.grid ?: return false
        val start = System.nanoTime()
        if (frame.generation != uploadedGeneration && !upload(frame, grid)) {
            fail()
            return false
        }
        var result = VulkanScopeNative.render(handle, frame.samples, frame.channels, frame.points, gainPercent)
        if (result == VulkanScopeNative.RESULT_RESIZED) {
            result = VulkanScopeNative.render(handle, frame.samples, frame.channels, frame.points, gainPercent)
        }
        return when (result) {
            VulkanScopeNative.RESULT_PRESENTED -> {
                feed.stats.frame("vulkan", start, System.nanoTime())
                true
            }

            VulkanScopeNative.RESULT_ERROR -> {
                fail()
                false
            }

            else -> false
        }
    }

    // Layout or size is changed, so static data is updated
    private fun upload(frame: ScopeFeed.Frame, grid: ScopeGrid): Boolean {
        val width = frame.width
        val height = frame.height
        val bitmap = background?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also {
                background?.recycle()
                background = it
            }
        grid.drawBackground(bitmap, labelTextSize)
        val size = bitmap.rowBytes * height
        val pixels = backgroundPixels?.takeIf { it.capacity() == size } ?: ByteBuffer.allocateDirect(size).also {
            backgroundPixels = it
        }
        pixels.rewind()
        bitmap.copyPixelsToBuffer(pixels)
        grid.traces(width, height, labelTextSize, traces)
        val channels = minOf(grid.channels, ScopeFeed.MAX_CHANNELS)
        if (VulkanScopeNative.setBackground(handle, pixels, width, height, bitmap.rowBytes) != 0 ||
            VulkanScopeNative.setTraces(handle, traces, channels, width, height) != 0
        ) {
            return false
        }
        uploadedGeneration = frame.generation
        return true
    }
}
