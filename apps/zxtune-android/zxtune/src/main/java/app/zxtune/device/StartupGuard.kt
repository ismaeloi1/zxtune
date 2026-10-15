package app.zxtune.device

import android.content.Context
import android.content.SharedPreferences

/**
 * Breaks crash loops of the playback service: system and media controllers restart it immediately,
 * so a crash in optional startup work (e.g. restoring the last played item from data left by
 * another build) would make playback unusable forever.
 *
 * Counts consecutive starts that did not reach healthy state. Uses own preferences file, not the
 * preferences provider, since it should work even if regular preferences are broken.
 */
class StartupGuard(private val prefs: SharedPreferences) {

    /**
     * Should be called on each start.
     * @return true if previous starts kept failing, so optional startup work should be skipped
     */
    fun begin(): Boolean {
        val failures = runCatching { prefs.getInt(KEY, 0) }.getOrDefault(0)
        // synchronously, since the process may die right after this point
        runCatching { prefs.edit().putInt(KEY, failures + 1).commit() }
        return failures >= THRESHOLD
    }

    fun healthy() {
        runCatching { prefs.edit().putInt(KEY, 0).commit() }
    }

    companion object {
        private const val PREFS_NAME = "startup_guard"
        private const val KEY = "unhealthy_starts"
        const val THRESHOLD = 2
        const val HEALTHY_DELAY_MS = 10_000L

        fun create(ctx: Context) = StartupGuard(ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}
