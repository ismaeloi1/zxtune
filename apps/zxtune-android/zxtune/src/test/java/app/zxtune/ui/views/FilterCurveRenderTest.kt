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
 * Renders static filter cutoff curve preference with different settings. Images are stored in directory from
 * ZXTUNE_SCREENSHOTS environment variable (as filter-*.png) for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-night-xxhdpi")
class FilterCurveRenderTest {

    private fun render(name: String, state: FilterCurveView.State, width: Int = 1080) {
        val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.CustomTheme)
        val root = FrameLayout(ctx)
        root.setBackgroundColor(BACKGROUND)
        val item = LayoutInflater.from(ctx).inflate(R.layout.preference_filter_curve, root, false)
        root.addView(item)
        item.findViewById<TextView>(android.R.id.title).setText(R.string.pref_zxtune_sid_filter_curve_title)
        val view = item.findViewById<FilterCurveView>(R.id.filter_curve)
        view.state = state
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        assertTrue(view.height > 200)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "filter-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        var drawn = 0
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until width step 2) {
                if (bitmap.getPixel(x, y) != BACKGROUND) ++drawn
            }
        }
        assertTrue("no curve in $name", drawn > 2000)
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
    fun custom6581() = render("custom-6581", FilterCurveView.State())

    @Test
    @Config(qualifiers = "fr-w411dp-h915dp-night-xxhdpi")
    fun preset6581Fr() = render(
        "preset-6581-fr",
        FilterCurveView.State(settings = SidFilterModel.Settings(preset6581 = 22), presetName = "Trurl 6581R3 0486S"),
    )

    @Test
    fun preset8580() = render(
        "preset-8580",
        FilterCurveView.State(settings = SidFilterModel.Settings(preset8580 = 1), is8580 = true, presetName = "8580"),
    )

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp-night-xhdpi")
    fun narrowFr() = render(
        "narrow-fr",
        FilterCurveView.State(settings = SidFilterModel.Settings(residFp = false, preset6581 = 16), presetName = "ZrX 6581R3 0384"),
        width = 640,
    )

    private companion object {
        const val BACKGROUND = 0xff202124.toInt()
    }
}
