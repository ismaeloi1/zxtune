package app.zxtune.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import app.zxtune.R
import app.zxtune.databinding.AboutBinding
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.StatusHeader
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoicesLayout
import app.zxtune.ui.views.SidDashboardView
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
    // locale may be changed by test
    private val context
        get() = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.CustomTheme)

    private fun about() = DataBindingUtil.inflate<AboutBinding>(
        LayoutInflater.from(context),
        R.layout.about,
        null,
        false,
    ).also { AboutFragment.fillCredits(it) }

    @Test
    fun credits() {
        for (locale in listOf("en", "fr", "de", "ru", "cs")) {
            RuntimeEnvironment.setQualifiers("$locale-w411dp-h915dp-xxhdpi")
            val binding = about()
            binding.aboutSystem.text = "Android 9 (API v28)\nPixel 3 (arm64-v8a/armeabi-v7a)\nnormal long port w411dp h915dp sw411dp xxhdpi $locale"
            val brands = (0 until binding.aboutEngines.childCount).map {
                binding.aboutEngines.getChildAt(it).findViewById<TextView>(R.id.engine_brand).text.toString()
            }
            assertEquals(listOf("NebulaWeave3", "NebulaWeaveSPC"), brands)
            render("about-credits-$locale", binding.aboutMain.getChildAt(0), 1080)
        }
    }

    @Test
    fun licenses() {
        val binding = about()
        AboutFragment.showPage(binding, 1)
        val names = (0 until binding.aboutLicenses.adapter!!.itemCount).map {
            val holder = binding.aboutLicenses.adapter!!.createViewHolder(binding.aboutLicenses, binding.aboutLicenses.adapter!!.getItemViewType(it))
            @Suppress("UNCHECKED_CAST")
            (binding.aboutLicenses.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>).onBindViewHolder(holder, it)
            holder.itemView.findViewById<TextView>(R.id.license_name)?.text?.toString() ?: ""
        }
        assertTrue(names.toString(), names.any { it.startsWith("SIDId") })
        render("about-licenses", binding.root, 1080, 2400)
    }

    @Test
    fun plugins() {
        val binding = about()
        AboutFragment.fillPlugins(
            binding,
            listOf(
                context.getString(R.string.plugin_player_sid) to listOf("Commodore 64 SID tunes (PSID, RSID)"),
                context.getString(R.string.plugin_archive) to listOf("Zip", "Rar", "LHA", "7-Zip"),
            ),
        )
        AboutFragment.showPage(binding, 2)
        render("about-plugins", binding.root, 1080, 2400)
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
            bitmap.eraseColor(Color.BLACK)
            view.draw(Canvas(bitmap))
        }
        assertTrue(name, h > 100)
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
