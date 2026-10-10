package app.zxtune.device.car

import android.content.SharedPreferences

/**
 * Live oscilloscope/dashboard in car settings, see 'ui.car.*' keys in preferences_car.xml
 */
data class CarVisualSettings(
    val enabled: Boolean,
    val fps: Int,
    val mode: Mode,
) {
    enum class Mode(val key: String) {
        OSCILLOSCOPE("scope"),
        DASHBOARD("dashboard"),
        BOTH("both"),
        ;

        companion object {
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: OSCILLOSCOPE
        }
    }

    val periodMs
        get() = 1000L / fps

    companion object {
        const val ENABLED_KEY = "ui.car.visualization"
        const val FPS_KEY = "ui.car.visualization.fps"
        const val MODE_KEY = "ui.car.visualization.mode"

        // Disabled by default: moving pictures in driver's field of view are illegal in many countries
        const val DEFAULT_ENABLED = false
        const val DEFAULT_FPS = 5

        // Each frame is a metadata update sent to every media controller via binder, so higher
        // rates load system and car connection for nothing (hosts animate artwork changes anyway)
        const val MIN_FPS = 1
        const val MAX_FPS = 15

        fun read(prefs: SharedPreferences) = CarVisualSettings(
            enabled = prefs.getBoolean(ENABLED_KEY, DEFAULT_ENABLED),
            fps = parseFps(prefs.all[FPS_KEY]),
            mode = Mode.of(prefs.getString(MODE_KEY, null)),
        )

        // List preferences store strings, but keep tolerant to numeric values
        internal fun parseFps(value: Any?) = when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }?.coerceIn(MIN_FPS, MAX_FPS) ?: DEFAULT_FPS
    }
}
