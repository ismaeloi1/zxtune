package app.zxtune.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.databinding.DataBindingUtil
import androidx.test.core.app.ApplicationProvider
import app.zxtune.R
import app.zxtune.databinding.AboutBinding
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.StatusHeader
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoicesLayout
import app.zxtune.ui.views.SidDashboardView
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders About pages and status text of dashboards, images are stored to directory from ZXTUNE_SCREENSHOTS
 * environment variable for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class AboutRenderTest {
    private val context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext(),
        R.style.CustomTheme,
    )

    private fun about() = DataBindingUtil.inflate<AboutBinding>(
        LayoutInflater.from(context),
        R.layout.about,
        null,
        false,
    ).also { AboutFragment.fillCredits(it) }

    @Test
    fun credits() {
        val binding = about()
        binding.aboutSystem.text = "Android 9"
        render("about-credits", binding.aboutCoauthor.parent as View, 1080)
    }

    @Test
    fun licenses() {
        val binding = about()
        assertTrue(binding.aboutLicenses.text.contains("SIDId"))
        render("about-licenses", binding.aboutLicenses, 1080)
    }

    @Test
    fun sidStatus() {
        val native = "PAL, MOS6581, reSIDfp, resample\n" +
            "timing: CIA 100.3 Hz (PAL), speed 2x, song 1/3\n" +
            "scope: 3/3 ch, trigger 1024us (max 2048, wait 0), sync device"
        val view = SidDashboardView(context)
        view.setGaugesData(ByteArray(ChipGauges.SIZE), 1, status(native, "SID", "GoatTracker V2.x"))
        render("about-status-sid", view, 1080, 1700)
    }

    @Test
    fun snesStatus() {
        val native = "S-DSP 32kHz, snes_spc\nscope: 8/8 ch, trigger 512us (max 900, wait 0), sync device"
        val layout = VoicesLayout.parse("audio\nS-DSP\t1\t2\t3\t4\t5\t6\t7\t8")
        val view = SidDashboardView(context)
        view.setVoicesData(layout, ByteArray(8 * VoiceGauges.SIZE), 8, status(native, "SPC", "Chrono Trigger"))
        render("about-status-snes", view, 1080, 1700)
    }

    private fun status(native: String, type: String, program: String) = StatusHeader.insert(native, type, program) { null } +
        "\noutput: 48000Hz buffer=160ms sync=timestamp\nrender: 213% realtime"

    private fun render(name: String, view: View, width: Int, height: Int = 0) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            if (height > 0) {
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            } else {
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            },
        )
        val h = view.measuredHeight
        view.layout(0, 0, width, h)
        val bitmap = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        repeat(4) {
            // dialogs background of dark theme
            bitmap.eraseColor(if (height > 0) 0 else Color.rgb(0x30, 0x30, 0x30))
            view.draw(Canvas(bitmap))
        }
        assertTrue(name, h > 100)
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
