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
import app.zxtune.playback.ChipState
import app.zxtune.ui.FilterCurvePreference
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders filter curve preference layout with different settings and live states. Images are stored in directory
 * from ZXTUNE_SCREENSHOTS environment variable (as filter-*.png) for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-night-xxhdpi")
class FilterCurveRenderTest {

    class Voice(val hz: Double, val control: Int = 0x41, val envelope: Int = 0xc0)

    class Chip(
        val cutoff: Int,
        val mode: Int,
        val resonance: Int,
        val routing: Int,
        val voices: List<Voice?> = emptyList(),
    )

    // registers layout of ChipState
    private fun fill(live: FilterCurveView.Live, chips: List<Chip>) {
        live.count = chips.size
        chips.forEachIndexed { idx, chip ->
            val base = idx * ChipState.SIZE
            val regs = live.registers
            regs[base + 0x15] = (chip.cutoff and 7).toByte()
            regs[base + 0x16] = (chip.cutoff shr 3).toByte()
            regs[base + 0x17] = ((chip.resonance shl 4) or chip.routing).toByte()
            regs[base + 0x18] = ((chip.mode shl 4) or 15).toByte()
            chip.voices.forEachIndexed { voice, v ->
                val freq = ((v?.hz ?: 0.0) * (1 shl 24) / live.clock).roundToInt()
                regs[base + voice * 7] = freq.toByte()
                regs[base + voice * 7 + 1] = (freq shr 8).toByte()
                regs[base + voice * 7 + 4] = (v?.control ?: 0).toByte()
                regs[base + 0x1c + voice] = (v?.envelope ?: 0).toByte()
            }
        }
    }

    // last 4 seconds at display rate: cutoff sweeping around current value, voices changing notes each second
    private fun history(chip: Chip) = FilterHistory().apply {
        val frames = 240
        for (idx in 0 until frames) {
            push(idx * 1000L / 60) { frame ->
                val phase = idx * 2 * Math.PI / 120
                frame.cutoffHz = (200 + chip.cutoff * 2 * (1.2 + kotlin.math.sin(phase))).toFloat()
                frame.resonance = chip.resonance
                frame.mode = chip.mode
                frame.extRouted = chip.routing and 8 != 0
                chip.voices.forEachIndexed { voice, v ->
                    val out = frame.voices[voice]
                    val step = (idx * 4 / frames + voice) % 3
                    out.active = v != null && v.envelope != 0 && v.control and 0xf0 != 0
                    out.freqHz = ((v?.hz ?: 0.0) * (1.0 + 0.12 * step)).toFloat()
                    out.level = (v?.envelope ?: 0) / 255f
                    out.routed = chip.routing and (1 shl voice) != 0
                    out.noise = (v?.control ?: 0) and 0x80 != 0
                }
            }
        }
    }

    private fun render(
        name: String,
        state: FilterCurveView.State,
        chips: List<Chip> = emptyList(),
        selected: Int = 0,
        width: Int = 1080,
        dac: Boolean = false,
    ): FilterCurveView {
        val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.CustomTheme)
        val root = FrameLayout(ctx)
        root.setBackgroundColor(0xff202124.toInt())
        val item = LayoutInflater.from(ctx).inflate(R.layout.preference_filter_curve, root, false)
        root.addView(item)
        item.findViewById<TextView>(android.R.id.title).setText(R.string.pref_zxtune_sid_filter_curve_title)
        val view = item.findViewById<FilterCurveView>(R.id.filter_curve)
        view.state = state
        fill(view.live, chips)
        view.selectedChip = selected
        view.showDacCurve = dac
        chips.getOrNull(selected)?.let { view.history = history(it) }
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
            File(dir, "filter-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // grid, curves and texts are drawn over background
        var drawn = 0
        val voices = IntArray(FilterCurveView.VOICES)
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until width step 2) {
                val c = bitmap.getPixel(x, y)
                if (c != 0xff202124.toInt()) ++drawn
                val idx = FilterCurveView.VOICE_COLORS.indexOf(c)
                if (idx >= 0) ++voices[idx]
            }
        }
        assertTrue("no curve in $name", drawn > 2000)
        chips.getOrNull(selected)?.voices?.forEachIndexed { voice, v ->
            if (v != null && v.envelope != 0) {
                assertTrue("no voice ${voice + 1} in $name", voices[voice] > 20)
            }
        }
        return view
    }

    // draw time at display rate must fit into a frame with large margin
    private fun measureDraw(view: FilterCurveView): Double {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        repeat(20) { view.draw(canvas) }
        val start = System.nanoTime()
        val count = 200
        repeat(count) { view.draw(canvas) }
        return (System.nanoTime() - start) / 1e6 / count
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
    fun custom6581Idle() {
        render("custom-6581-idle", FilterCurveView.State(hint = "Play a SID tune to see how the filter changes each voice"))
    }

    @Test
    @Config(qualifiers = "fr-w411dp-h915dp-night-xxhdpi")
    fun preset6581LowpassFr() {
        val view = render(
            "preset-6581-lowpass-fr",
            FilterCurveView.State(settings = SidFilterModel.Settings(preset6581 = 22), presetName = "Trurl 6581R3 0486S"),
            listOf(
                Chip(
                    cutoff = 1100,
                    mode = 1,
                    resonance = 12,
                    routing = 0x05,
                    voices = listOf(Voice(110.0, 0x21), Voice(523.25, 0x41), Voice(2637.0, 0x11, 0x80))
                )
            ),
        )
        println("draw: %.2f ms per frame".format(measureDraw(view)))
    }

    // all three voices are filtered, the case of user report
    @Test
    @Config(qualifiers = "fr-w411dp-h915dp-night-xxhdpi")
    fun allVoicesBandpassFr() = render(
        "6581-bandpass-3voices-fr",
        FilterCurveView.State(settings = SidFilterModel.Settings(residFp = false, preset6581 = 16), presetName = "ZrX 6581R3 0384"),
        listOf(
            Chip(
                cutoff = 900,
                mode = 2,
                resonance = 15,
                routing = 0x07,
                voices = listOf(Voice(130.8, 0x41), Voice(1046.5, 0x21), Voice(4186.0, 0x81))
            )
        ),
    ).let {}

    // main plot switched to cutoff register curve by tap
    @Test
    @Config(qualifiers = "fr-w411dp-h915dp-night-xxhdpi")
    fun dacCurveFr() = render(
        "6581-dac-fr",
        FilterCurveView.State(settings = SidFilterModel.Settings(residFp = false, preset6581 = 16), presetName = "ZrX 6581R3 0384"),
        listOf(
            Chip(cutoff = 900, mode = 1, resonance = 8, routing = 0x03, voices = listOf(Voice(130.8, 0x41), Voice(1046.5, 0x21)))
        ),
        dac = true,
    ).let {}

    @Test
    fun notch8580() = render(
        "8580-notch",
        FilterCurveView.State(
            settings = SidFilterModel.Settings(preset8580 = 5),
            is8580 = true,
            presetName = "Trurl 8580R5 3691",
        ),
        listOf(
            Chip(
                cutoff = 300,
                mode = 5,
                resonance = 8,
                routing = 0x0a,
                voices = listOf(Voice(196.0, 0x11), Voice(1740.0, 0x41), Voice(0.0, 0x00, 0)),
            )
        ),
    ).let {}

    @Test
    fun highpass3Sid() = render(
        "6581-3sid-highpass",
        FilterCurveView.State(),
        listOf(
            Chip(cutoff = 500, mode = 1, resonance = 4, routing = 0x01, voices = listOf(Voice(98.0), null, null)),
            Chip(
                cutoff = 1400,
                mode = 4,
                resonance = 10,
                routing = 0x03,
                voices = listOf(Voice(440.0, 0x41), Voice(3520.0, 0x21), Voice(880.0, 0x11))
            ),
            Chip(cutoff = 2000, mode = 6, resonance = 0, routing = 0x00, voices = listOf(Voice(55.0))),
        ),
        selected = 1,
    ).let {}

    @Test
    @Config(qualifiers = "fr-w320dp-h640dp-night-xhdpi")
    fun narrowFr() = render(
        "narrow-fr",
        FilterCurveView.State(),
        listOf(
            Chip(
                cutoff = 700,
                mode = 3,
                resonance = 6,
                routing = 0x04,
                voices = listOf(Voice(146.8), Voice(293.7, 0x21), Voice(587.3, 0x41))
            )
        ),
        width = 720,
    ).let {}
}
