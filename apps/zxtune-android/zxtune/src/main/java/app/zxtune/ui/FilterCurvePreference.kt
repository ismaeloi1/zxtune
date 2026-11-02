/**
 * @file
 * @brief Preference displaying SID filter response of playing tune and cutoff curve of current settings
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.PlaybackStateCompat
import android.util.AttributeSet
import android.view.View
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import app.zxtune.R
import app.zxtune.device.media.MediaModel
import app.zxtune.ui.views.FilterCurveView
import app.zxtune.ui.views.SidFilterModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Placed right under filter presets, so the effect of any SID filter setting is visible immediately.
 * Settings are polled from preferences data store (cheap, cached), so no change listeners are required for all the
 * related preferences. Live chips state is delivered at display rate by [FilterCurveFeed].
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
    private var feed: FilterCurveFeed? = null
    private var liveJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, SETTINGS_PERIOD_MS)
        }
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val curve = holder.findViewById(R.id.filter_curve) as FilterCurveView
        if (view !== curve) {
            stop()
            view = curve
            feed = FilterCurveFeed(curve)
            curve.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = start(v)
                override fun onViewDetachedFromWindow(v: View) = stop()
            })
            if (ViewCompat.isAttachedToWindow(curve)) {
                start(curve)
            }
        }
        update()
    }

    // Preference outlives its views while hosting fragment is in back stack, so all the references are dropped here
    override fun onDetached() {
        stop()
        view = null
        feed = null
        super.onDetached()
    }

    private fun start(attached: View) {
        feed?.isAttached = true
        connectLive(attached)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun stop() {
        handler.removeCallbacks(tick)
        liveJob?.cancel()
        liveJob = null
        feed?.run {
            isAttached = false
            source = null
        }
    }

    // Bound to hosting fragment's view lifecycle (view tree owner), not to activity: activity scope retained the
    // preference with its fragment after onDestroy (detected by LeakCanary)
    private fun connectLive(attached: View) {
        if (liveJob?.isActive == true) {
            return
        }
        val owner = attached.findViewTreeLifecycleOwner() ?: return
        val activity = findActivity(context) ?: return
        val model = MediaModel.of(activity)
        val target = feed ?: return
        liveJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    model.playbackState.collect { target.isPlaying = it?.state == PlaybackStateCompat.STATE_PLAYING }
                }
                try {
                    model.visualizer.collect { target.source = it }
                } finally {
                    target.source = null
                }
            }
        }
    }

    private fun update() {
        val curve = view ?: return
        val settings = readSettings()
        val status = feed?.status ?: ""
        val is8580 = if (status.isNotEmpty()) status.contains("8580") else readInt(MODEL_KEY, 0) == 1
        val preset = if (is8580) settings.preset8580 else settings.preset6581
        val names = context.resources.getStringArray(
            if (is8580) R.array.pref_zxtune_sid_filter_8580_preset_entries else R.array.pref_zxtune_sid_filter_6581_preset_entries
        )
        curve.state = FilterCurveView.State(
            settings = settings,
            is8580 = is8580,
            presetName = names.getOrNull(preset)?.takeIf { preset != 0 } ?: "",
            hint = context.getString(R.string.pref_zxtune_sid_filter_curve_idle),
        )
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
        // settings are changed by user, so it's enough to follow seek bars, live state has own pace
        private const val SETTINGS_PERIOD_MS = 250L
        private const val ENGINE_KEY = "zxtune.core.sid.engine"
        private const val MODEL_KEY = "zxtune.core.sid.model"

        private tailrec fun findActivity(ctx: Context?): FragmentActivity? = when (ctx) {
            is FragmentActivity -> ctx
            is ContextWrapper -> findActivity(ctx.baseContext)
            else -> null
        }
    }
}
