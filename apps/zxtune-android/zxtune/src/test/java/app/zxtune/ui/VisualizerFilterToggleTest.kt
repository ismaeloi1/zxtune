package app.zxtune.ui

import android.app.Application
import android.content.ComponentName
import android.os.Looper
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import app.zxtune.MainActivity
import app.zxtune.MainService
import app.zxtune.R
import app.zxtune.ui.views.OscilloscopeFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Filter response toggle is placed next to visualizer mode toggle and shown only in oscilloscope mode for chips with
 * filter model, its icon and title show the next view
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class VisualizerFilterToggleTest {

    @Test
    fun `toggle follows mode and filter support`() {
        // no playback service in tests
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MainService::class.java))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val toolbar = activity.findViewById<Toolbar>(R.id.main_top_panel)
        val filter = toolbar.menu.findItem(R.id.action_visualizer_filter)
        val mode = toolbar.menu.findItem(R.id.action_visualizer_mode)
        val oscilloscope = activity.findViewById<OscilloscopeFrame>(R.id.oscilloscope)
        assertFalse(filter.isVisible)

        // oscilloscope mode, then SID is played
        if (mode.title != activity.getString(R.string.visualizer_spectrum)) {
            toolbar.menu.performIdentifierAction(R.id.action_visualizer_mode, 0)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(activity.getString(R.string.visualizer_spectrum), mode.title)
        assertFalse(filter.isVisible)
        reportSupport(oscilloscope, true)
        assertTrue(filter.isVisible)
        assertEquals(activity.getString(R.string.visualizer_filter), filter.title)

        oscilloscope.showFilter = true
        reportSupport(oscilloscope, true)
        assertEquals(activity.getString(R.string.visualizer_waveforms), filter.title)

        // another chip without filter
        reportSupport(oscilloscope, false)
        assertFalse(filter.isVisible)

        // spectrum mode hides oscilloscope controls
        reportSupport(oscilloscope, true)
        toolbar.menu.performIdentifierAction(R.id.action_visualizer_mode, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(filter.isVisible)
    }

    // as display reports detected chips, see ScopeFeed.onFilterSupport
    private fun reportSupport(frame: OscilloscopeFrame, supported: Boolean) {
        OscilloscopeFrame::class.java.getDeclaredField("isFilterSupported").apply { isAccessible = true }.setBoolean(frame, supported)
        frame.onFilterSupport?.invoke(supported)
    }
}
