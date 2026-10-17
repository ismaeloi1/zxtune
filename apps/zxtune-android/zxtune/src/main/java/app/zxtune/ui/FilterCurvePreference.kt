/**
 * @file
 * @brief Preference displaying SID filter curve of current settings with live cutoff of playing tune
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import app.zxtune.Logger
import app.zxtune.R
import app.zxtune.device.media.MediaModel
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.Visualizer
import app.zxtune.ui.views.FilterCurveView
import app.zxtune.ui.views.SidFilterModel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

/**
 * Placed right under filter presets, so the effect of any SID filter setting is visible immediately.
 * Settings are polled from preferences data store (cheap, cached) together with live chips state, so no
 * change listeners are required for all the related preferences.
 */
class FilterCurvePreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.preference_filter_curve
        isSelectable = false
    }

    private var view: FilterCurveView? = null

    @Volatile
    private var visualizer: Visualizer? = null
    private var liveConnected = false
    private val handler = Handler(Looper.getMainLooper())
    private val pending = AtomicBoolean(false)
    private var gaugesBuffer = ByteArray(0)
    private var status = ""
    private var lastStatusTime = 0L

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, UPDATE_PERIOD_MS)
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val curve = holder.findViewById(R.id.filter_curve) as FilterCurveView
        if (view !== curve) {
            view = curve
            curve.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = start()
                override fun onViewDetachedFromWindow(v: View) = stop()
            })
            if (ViewCompat.isAttachedToWindow(curve)) {
                start()
            }
        }
        update()
    }

    private fun start() {
        connectLive()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun stop() = handler.removeCallbacks(tick)

    private fun connectLive() {
        if (liveConnected) {
            return
        }
        val activity = findActivity(context) ?: return
        liveConnected = true
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MediaModel.of(activity).visualizer.collect { visualizer = it }
            }
        }
    }

    private fun update() {
        val curve = view ?: return
        val settings = readSettings()
        val names6581 = context.resources.getStringArray(R.array.pref_zxtune_sid_filter_6581_preset_entries)
        val names8580 = context.resources.getStringArray(R.array.pref_zxtune_sid_filter_8580_preset_entries)
        val live = liveStates
        val is8580 = if (status.isNotEmpty()) status.contains("8580") else readInt(MODEL_KEY, 0) == 1
        val preset = if (is8580) settings.preset8580 else settings.preset6581
        val names = if (is8580) names8580 else names6581
        curve.state = FilterCurveView.State(
            settings = settings,
            is8580 = is8580,
            presetName = names.getOrNull(preset)?.takeIf { preset != 0 } ?: "",
            live = live,
            hint = context.getString(R.string.pref_zxtune_sid_filter_curve_idle),
        )
        requestLive()
    }

    @Volatile
    private var liveStates: List<FilterCurveView.LiveState> = emptyList()

    // Binder calls are made out of main thread, results are picked by next tick
    private fun requestLive() {
        val src = visualizer ?: run {
            liveStates = emptyList()
            status = ""
            return
        }
        if (!pending.compareAndSet(false, true)) {
            return
        }
        executor.execute {
            try {
                val now = System.currentTimeMillis()
                if (now - lastStatusTime > STATUS_PERIOD_MS) {
                    lastStatusTime = now
                    status = runCatching { src.getStatus() }.getOrDefault("").takeIf { it.contains("MOS") } ?: ""
                }
                if (gaugesBuffer.size != MAX_CHIPS * ChipGauges.SIZE) {
                    gaugesBuffer = ByteArray(MAX_CHIPS * ChipGauges.SIZE)
                }
                val chips = if (status.isEmpty()) 0 else runCatching { src.getGauges(gaugesBuffer, 10) }.getOrDefault(0)
                liveStates = (0 until chips.coerceAtMost(MAX_CHIPS)).map { chip ->
                    val state = ChipGauges(gaugesBuffer, chip * ChipGauges.SIZE).state
                    FilterCurveView.LiveState(state.cutoff, state.mode, state.resonance, state.routing)
                }
            } catch (e: Exception) {
                LOG.w(e) { "Failed to get live state" }
            } finally {
                pending.set(false)
            }
        }
    }

    private fun readSettings() = SidFilterModel.Settings(
        residFp = readInt(ENGINE_KEY, 1) == 1,
        preset6581 = readInt("zxtune.core.sid.filter_6581_preset", 0),
        preset8580 = readInt("zxtune.core.sid.filter_8580_preset", 0),
        curve6581 = readInt("zxtune.core.sid.filter_6581_curve", 50) / 100.0,
        range6581 = readInt("zxtune.core.sid.filter_6581_range", 50) / 100.0,
        curve8580 = readInt("zxtune.core.sid.filter_8580_curve", 50) / 100.0,
        biasMv = readInt("zxtune.core.sid.filter_bias", 0),
    )

    // Drop-down lists store strings, seek bars store integers
    private fun readInt(key: String, def: Int): Int {
        val store = preferenceDataStore
        if (store != null) {
            runCatching { store.getString(key, null) }.getOrNull()?.toIntOrNull()?.let { return it }
            return runCatching { store.getInt(key, def) }.getOrDefault(def)
        }
        val prefs = sharedPreferences ?: return def
        return runCatching { prefs.getInt(key, def) }.getOrElse {
            prefs.getString(key, null)?.toIntOrNull() ?: def
        }
    }

    companion object {
        private val LOG = Logger(FilterCurvePreference::class.java.name)
        private const val UPDATE_PERIOD_MS = 100L
        private const val STATUS_PERIOD_MS = 1000L
        private const val MAX_CHIPS = 3
        private const val ENGINE_KEY = "zxtune.core.sid.engine"
        private const val MODEL_KEY = "zxtune.core.sid.model"
        private val executor = Executors.newSingleThreadExecutor()

        private tailrec fun findActivity(ctx: Context?): FragmentActivity? = when (ctx) {
            is FragmentActivity -> ctx
            is ContextWrapper -> findActivity(ctx.baseContext)
            else -> null
        }
    }
}
