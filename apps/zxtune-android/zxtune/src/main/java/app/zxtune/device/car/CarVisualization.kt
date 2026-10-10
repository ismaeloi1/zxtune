package app.zxtune.device.car

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import app.zxtune.Logger
import app.zxtune.Releaseable
import app.zxtune.TimeStamp
import app.zxtune.playback.Callback
import app.zxtune.playback.Item
import app.zxtune.playback.PlaybackControl
import app.zxtune.playback.PlaybackService
import app.zxtune.preferences.Preferences
import app.zxtune.ui.views.OscilloscopeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Publishes oscilloscope/dashboard pictures as media session artwork while all conditions are met:
 * - enabled in settings (off by default, see [CarVisualSettings])
 * - connected to a car (Android Auto projection or Android Automotive)
 * - playing
 *
 * Template based car hosts do not allow media apps to draw anything, so the artwork displayed on
 * the playback screen is the only place for custom picture.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarVisualization private constructor(
    private val ctx: Context,
    private val svc: PlaybackService,
    private val publish: (Bitmap?) -> Unit,
    connection: Flow<Boolean>,
) : Releaseable {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val prefs = Preferences.getDefaultSharedPreferences(ctx)
    private val settings = MutableStateFlow(CarVisualSettings.read(prefs))
    private val playing = MutableStateFlow(false)

    // to not republish metadata if live art was never shown
    @Volatile
    private var published = false

    // Preferences provider lives in the same process as service, so changes are visible immediately
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { updated, key ->
        if (key?.startsWith(PREFS_PREFIX) != false) {
            settings.value = CarVisualSettings.read(updated)
        }
    }
    private val subscription: Releaseable

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        subscription = svc.subscribe(object : Callback {
            override fun onInitialState(state: PlaybackControl.State) = onStateChanged(state, TimeStamp.EMPTY)

            override fun onStateChanged(state: PlaybackControl.State, pos: TimeStamp) {
                playing.value = state == PlaybackControl.State.PLAYING
            }

            override fun onItemChanged(item: Item) = Unit

            override fun onError(e: String) = Unit
        })
        scope.launch {
            // car connection is not tracked at all while disabled
            settings.flatMapLatest { cfg ->
                if (cfg.enabled) {
                    combine(playing, connection) { isPlaying, isConnected ->
                        cfg.takeIf { isPlaying && isConnected }
                    }
                } else {
                    flowOf(null)
                }
            }.distinctUntilChanged().collectLatest { cfg ->
                if (cfg != null) {
                    published = true
                    run(cfg)
                } else if (published) {
                    published = false
                    publish(null)
                }
            }
        }
    }

    private suspend fun run(cfg: CarVisualSettings) {
        LOG.d { "Start at ${cfg.fps}fps mode=${cfg.mode}" }
        val renderer = CarVisualRenderer()
        // published bitmap may still be parceled to controllers while next frame is drawn
        val bitmaps = Array(BUFFERS) { renderer.createBitmap() }
        val frame = CarVisualFrame()
        val windowMs = prefs.getInt(WINDOW_KEY, OscilloscopeView.DEFAULT_WINDOW_MS)
        val gaugeWindowMs = prefs.getInt(GAUGE_WINDOW_KEY, DEFAULT_GAUGE_WINDOW_MS)
        val withGauges = cfg.mode != CarVisualSettings.Mode.OSCILLOSCOPE
        var index = 0
        while (scope.isActive) {
            val start = SystemClock.elapsedRealtime()
            runCatching {
                frame.capture(svc.visualizer, windowMs, gaugeWindowMs, withGauges)
                publish(renderer.render(frame, cfg.mode, bitmaps[index]))
                index = (index + 1) % BUFFERS
            }.onFailure {
                LOG.w(it) { "Failed to render frame" }
            }
            delay((cfg.periodMs - (SystemClock.elapsedRealtime() - start)).coerceAtLeast(MIN_DELAY_MS))
        }
    }

    override fun release() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        subscription.release()
        scope.cancel()
        if (published) {
            publish(null)
        }
    }

    companion object {
        private val LOG = Logger(CarVisualization::class.java.name)
        private const val PREFS_PREFIX = "ui.car."
        private const val WINDOW_KEY = "ui.oscilloscope.window_ms"
        private const val GAUGE_WINDOW_KEY = "ui.oscilloscope.gauge_window_ms"

        // same as phone dashboard
        private const val DEFAULT_GAUGE_WINDOW_MS = 10
        private const val BUFFERS = 3
        private const val MIN_DELAY_MS = 10L

        fun create(ctx: Context, svc: PlaybackService, publish: (Bitmap?) -> Unit): Releaseable = CarVisualization(ctx, svc, publish, CarConnection.observe(ctx))
    }
}
