package app.zxtune.ui

import android.os.Build
import android.view.Window

/**
 * Use maximal refresh rate of display for current resolution (e.g. 120Hz). Some vendors (e.g. Nothing OS) run
 * apps at reduced rate by default and ignore per-surface frame rate hints, while scope animation needs every frame.
 */
fun Window.requestHighestRefreshRate() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        return
    }
    runCatching {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay ?: return
        val current = display.mode
        val best = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }.maxByOrNull { it.refreshRate } ?: return
        attributes = attributes.apply {
            preferredDisplayModeId = best.modeId
        }
    }
}
