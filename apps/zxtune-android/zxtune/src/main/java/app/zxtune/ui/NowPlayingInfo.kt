package app.zxtune.ui

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.zxtune.preferences.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whether now playing information (title, chips, stream and emulation lines) is expanded. It's collapsed by default
 * to give the height to the visualizer. Shared by the information block and its toggle in the transport row.
 */
object NowPlayingInfo {
    const val KEY = "ui.brief.expanded"

    // reads and writes are serialized to never read a value older than the last toggle
    @OptIn(ExperimentalCoroutinesApi::class)
    private val storage = Dispatchers.IO.limitedParallelism(1)

    // writes outlive the screen
    private val scope = CoroutineScope(SupervisorJob() + storage)

    private val state = MutableStateFlow<Boolean?>(null)

    // changed and read on main thread only
    private var togglesCount = 0

    /**
     * null until loaded
     */
    val isExpanded = state.asStateFlow()

    /**
     * Re-read on each start, may be changed in preferences
     */
    suspend fun reload(ctx: Context) {
        val app = ctx.applicationContext
        val toggles = togglesCount
        val stored = withContext(storage) {
            Preferences.getDataStore(app).getBoolean(KEY, false)
        }
        // user's choice made while reading is newer
        if (toggles == togglesCount) {
            state.value = stored
        }
    }

    @VisibleForTesting
    internal fun reset() {
        state.value = null
        togglesCount = 0
    }

    fun toggle(ctx: Context) {
        val app = ctx.applicationContext
        val expanded = state.value != true
        ++togglesCount
        state.value = expanded
        scope.launch {
            Preferences.getDataStore(app).putBoolean(KEY, expanded)
        }
    }
}
