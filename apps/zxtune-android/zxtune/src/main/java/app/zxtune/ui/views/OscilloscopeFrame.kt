/**
 * @file
 * @brief Oscilloscope view with selectable rendering backend
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import app.zxtune.Logger
import app.zxtune.playback.Visualizer
import app.zxtune.preferences.VulkanDriver

private val LOG = Logger("OscilloscopeFrame")

/**
 * Hosts Vulkan or OpenGL ES oscilloscope (see [ScopeBackend]), falls back to the latter on Vulkan failures.
 */
class OscilloscopeFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs),
    ScopeDisplay {

    private var backendPreference = ScopeBackend.PREF_AUTO
    private var driver: VulkanDriver.Info? = null
    private var display: ScopeDisplay? = null
    private var displayBackend: ScopeBackend? = null
    private var isLive = true
    private var isResumed = true

    override var source: Visualizer? = null
        private set

    override var windowMs = OscilloscopeView.DEFAULT_WINDOW_MS
        set(value) {
            field = value
            display?.windowMs = value
        }

    override var gainPercent = OscilloscopeView.AUTO_GAIN
        set(value) {
            field = value
            display?.gainPercent = value
        }

    override var maxFps = 0
        set(value) {
            field = value
            display?.maxFps = value
        }

    override var gauges: GaugesExchange? = null
        set(value) {
            field = value
            display?.gauges = value
        }

    override var showFilter = false
        set(value) {
            field = value
            display?.showFilter = value
        }

    override var filterSettings = FilterSettings()
        set(value) {
            val relayout = value.withWaves != field.withWaves
            field = value
            display?.filterSettings = value
            if (relayout) {
                // forces layout update
                display?.showFilter = showFilter
            }
        }

    override var onFilterSupport: ((Boolean) -> Unit)? = null

    /**
     * Playing source supports filter response, see [showFilter]
     */
    var isFilterSupported = false
        private set

    /**
     * @param preference see [ScopeBackend.select]
     * @param customDriver Vulkan driver to use instead of system one
     */
    fun setBackend(preference: String, customDriver: VulkanDriver.Info?) {
        val driverChanged = customDriver?.libraryName != driver?.libraryName || customDriver?.dir != driver?.dir
        backendPreference = preference
        driver = customDriver
        updateDisplay(driverChanged)
    }

    override fun getStatistics() = display?.getStatistics() ?: ""

    override fun setSource(src: Visualizer?, isLive: Boolean) {
        source = src
        this.isLive = isLive
        display?.setSource(src, isLive)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (display == null) {
            updateDisplay(false)
        }
    }

    /**
     * See [android.opengl.GLSurfaceView.onResume]
     */
    fun onResume() {
        isResumed = true
        (display as? OscilloscopeView)?.onResume()
    }

    /**
     * See [android.opengl.GLSurfaceView.onPause]
     */
    fun onPause() {
        isResumed = false
        (display as? OscilloscopeView)?.onPause()
    }

    private fun updateDisplay(force: Boolean) {
        val backend = ScopeBackend.select(
            backendPreference,
            ScopeBackend.isVulkanSupported(context),
            ScopeBackend.isVulkanFailed
        )
        if (backend == displayBackend && !force) {
            return
        }
        LOG.d { "Use $backend backend" }
        display?.setSource(null)
        removeAllViews()
        val view = when (backend) {
            ScopeBackend.VULKAN -> VulkanOscilloscopeView(context, driver) {
                // failed backend is not selected anymore
                updateDisplay(false)
            }

            ScopeBackend.GLES -> OscilloscopeView(context).apply {
                if (!isResumed) {
                    onPause()
                }
            }
        }
        (view as View).isClickable = false
        view.isFocusable = false
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        display = view
        displayBackend = backend
        view.windowMs = windowMs
        view.gainPercent = gainPercent
        view.maxFps = maxFps
        view.gauges = gauges
        view.showFilter = showFilter
        view.filterSettings = filterSettings
        view.onFilterSupport = { supported ->
            if (supported != isFilterSupported) {
                isFilterSupported = supported
                onFilterSupport?.invoke(supported)
            }
        }
        view.setSource(source, isLive)
    }
}
