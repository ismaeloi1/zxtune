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
    private val filterResources = FilterResources.of(context)
    private val filterScene = FilterScene(filterResources)
    private var filterLayout: FilterLayout? = null
    private var filterChannels = 0
    private var atlasPixels: ByteBuffer? = null
    private val shapes = ScopeShapes()

    @Volatile
    private var description = ""

    init {
        // opaque surface on top of window may be composed as overlay without blending
        holder.setFormat(PixelFormat.RGBX_8888)
        setZOrderOnTop(true)
        holder.addCallback(this)
        feed.onFilterSupport = { supported -> post { onFilterSupport?.invoke(supported) } }
    }

    override val source
        get() = feed.source

    override var windowMs by feed::windowMs

    override var gauges by feed::gauges

    override var showFilter by feed::showFilter

    override var filterSettings by filterScene::settings

    override var onFilterSupport: ((Boolean) -> Unit)? = null

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
        val layout = filterLayout?.takeIf { frame.isFilter }
        if (layout != null) {
            filterScene.build(layout, frame.gauges, frame.gaugesCount, frame.status, frame.nanos, frame.isLive, shapes)
        } else {
            shapes.clear()
        }
        val channels = if (layout != null) minOf(frame.channels, filterChannels) else frame.channels
        var result = renderNative(frame, channels)
        if (result == VulkanScopeNative.RESULT_RESIZED) {
            result = renderNative(frame, channels)
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

    private fun renderNative(frame: ScopeFeed.Frame, channels: Int) = VulkanScopeNative.render(
        handle,
        frame.samples,
        channels,
        frame.points,
        gainPercent,
        shapes.data,
        shapes.count
    )

    // Layout, size or displayed mode is changed, so static data is updated
    private fun upload(frame: ScopeFeed.Frame, grid: ScopeGrid): Boolean {
        val width = frame.width
        val height = frame.height
        val bitmap = background?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also {
                background?.recycle()
                background = it
            }
        val layout = if (frame.isFilter) {
            FilterLayout.of(
                context,
                width,
                height,
                frame.voicesLayout,
                grid,
                labelTextSize,
                filterResources,
                filterScene.settings.withWaves
            )
        } else {
            null
        }
        filterLayout = layout
        if (layout != null) {
            layout.drawBackground(bitmap)
            val atlas = layout.atlas.bitmap
            val atlasData = copyPixels(atlas, atlasPixels).also { atlasPixels = it }
            if (VulkanScopeNative.setAtlas(handle, atlasData, atlas.width, atlas.height, atlas.rowBytes) != 0) {
                return false
            }
        } else {
            grid.drawBackground(bitmap, labelTextSize)
        }
        val pixels = copyPixels(bitmap, backgroundPixels).also { backgroundPixels = it }
        val channels = if (layout != null) {
            layout.traces(traces).also { filterChannels = it }
        } else {
            grid.traces(width, height, labelTextSize, traces)
            grid.channels
        }.coerceAtMost(ScopeFeed.MAX_CHANNELS)
        if (VulkanScopeNative.setBackground(handle, pixels, width, height, bitmap.rowBytes) != 0 ||
            VulkanScopeNative.setTraces(handle, traces, channels, width, height) != 0
        ) {
            return false
        }
        uploadedGeneration = frame.generation
        return true
    }

    private fun copyPixels(bitmap: Bitmap, reused: ByteBuffer?): ByteBuffer {
        val size = bitmap.rowBytes * bitmap.height
        val pixels = reused?.takeIf { it.capacity() == size } ?: ByteBuffer.allocateDirect(size)
        pixels.rewind()
        bitmap.copyPixelsToBuffer(pixels)
        return pixels
    }
}
