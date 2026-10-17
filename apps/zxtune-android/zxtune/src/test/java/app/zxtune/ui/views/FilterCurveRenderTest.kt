package app.zxtune.ui.views

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import app.zxtune.R
import app.zxtune.ui.FilterCurvePreference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders filter curve preference layout with different settings and live states. Images are stored in directory
 * from ZXTUNE_SCREENSHOTS environment variable (as sidfilter-*.png) for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-night-xxhdpi")
class FilterCurveRenderTest {

    private fun render(name: String, state: FilterCurveView.State) {
        val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.CustomTheme)
        val root = FrameLayout(ctx)
        root.setBackgroundColor(0xff202124.toInt())
        val item = LayoutInflater.from(ctx).inflate(R.layout.preference_filter_curve, root, false)
        root.addView(item)
        item.findViewById<TextView>(android.R.id.title).setText(R.string.pref_zxtune_sid_filter_curve_title)
        val view = item.findViewById<FilterCurveView>(R.id.filter_curve)
        view.state = state
        val width = 1080
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        assertTrue(view.height > 300)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "sidfilter-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // grid, curves and texts are drawn over background
        var drawn = 0
        var marker = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until width step 2) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                if (c != 0xff202124.toInt()) ++drawn
                if (r > 200 && g in 80..150) ++marker
            }
        }
        val colored = if (state.live.isEmpty()) drawn / 20 else marker
        assertTrue("no curve in $name", colored > 100)
    }

    @Test
    fun `preference is declared under presets`() {
        val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.CustomTheme)

        @SuppressLint("RestrictedApi")
        val screen = PreferenceManager(ctx).inflateFromResource(ctx, R.xml.preferences, null)
        val curve = screen.findPreference<Preference>("ui.sid.filter_curve")
        assertTrue(curve is FilterCurvePreference)
        val category = curve!!.parent!!
        val idx = (0 until category.preferenceCount).first { category.getPreference(it) === curve }
        assertEquals("zxtune.core.sid.filter_8580_preset", category.getPreference(idx - 1).key)
        assertEquals("zxtune.core.sid.filter_6581_preset", category.getPreference(idx - 2).key)
    }

    @Test
    fun custom6581Idle() = render(
        "custom-6581",
        FilterCurveView.State(hint = "Cutoff register → frequency. Play a SID tune to see live cutoff")
    )

    @Test
    fun preset6581Live() = render(
        "preset-6581-live",
        FilterCurveView.State(
            settings = SidFilterModel.Settings(preset6581 = 22),
            presetName = "Trurl 6581R3 0486S",
            live = listOf(FilterCurveView.LiveState(cutoff = 1100, mode = 1, resonance = 12, routing = 0x05)),
        )
    )

    @Test
    fun residPreset6581Live() = render(
        "resid-preset-6581-live",
        FilterCurveView.State(
            settings = SidFilterModel.Settings(residFp = false, preset6581 = 16),
            presetName = "ZrX 6581R3 0384",
            live = listOf(
                FilterCurveView.LiveState(cutoff = 700, mode = 3, resonance = 4, routing = 0x01),
                FilterCurveView.LiveState(cutoff = 1500, mode = 4, resonance = 15, routing = 0x00),
            ),
        )
    )

    @Test
    fun preset8580Live() = render(
        "preset-8580-live",
        FilterCurveView.State(
            settings = SidFilterModel.Settings(preset8580 = 5),
            is8580 = true,
            presetName = "Trurl 8580R5 3691",
            live = listOf(FilterCurveView.LiveState(cutoff = 300, mode = 1, resonance = 0, routing = 0x02)),
        )
    )
}
