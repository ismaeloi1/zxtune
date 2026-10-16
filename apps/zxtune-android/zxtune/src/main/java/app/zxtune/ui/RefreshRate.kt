package app.zxtune.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Surface
import android.view.Window

/**
 * Use the highest refresh rate of display not exceeding [maxFps] (0 - no limit) for current resolution.
 * Some vendors (e.g. Nothing OS) run apps at reduced rate by default and ignore per-surface frame rate hints, while
 * scope animation needs every frame. Requested only while animation is active, see [resetRefreshRate]: high refresh
 * rate costs display and composition power even for static content.
 */
fun Window.requestRefreshRate(maxFps: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        return
    }
    runCatching {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay ?: return
        val current = display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        // small tolerance for rates like 59.94
        val limited = if (maxFps > 0) sameSize.filter { it.refreshRate <= maxFps + 1f } else sameSize
        val best = (limited.ifEmpty { sameSize }).let { modes ->
            if (maxFps > 0 && limited.isEmpty()) modes.minByOrNull { it.refreshRate } else modes.maxByOrNull { it.refreshRate }
        } ?: return
        if (attributes.preferredDisplayModeId != best.modeId) {
            attributes = attributes.apply {
                preferredDisplayModeId = best.modeId
            }
        }
    }
}

/**
 * Let system choose refresh rate (e.g. lower one for static content)
 */
fun Window.resetRefreshRate() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        return
    }
    runCatching {
        if (attributes.preferredDisplayModeId != 0) {
            attributes = attributes.apply {
                preferredDisplayModeId = 0
            }
        }
    }
}

/**
 * Per-surface frame rate vote, 0 to clear it while surface is visible but not animated
 */
fun Surface.voteFrameRate(fps: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && isValid) {
        runCatching {
            setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }
}

tailrec fun Context.findWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findWindow()
    else -> null
}
