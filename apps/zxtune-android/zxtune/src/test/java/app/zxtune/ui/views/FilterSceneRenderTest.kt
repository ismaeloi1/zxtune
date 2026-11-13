package app.zxtune.ui.views

import android.graphics.Bitmap
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import app.zxtune.R
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.VoicesLayout
import java.io.File
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Filter response mode of oscilloscope as drawn by GPU backends (Canvas reference of shapes). Images are stored in
 * directory from ZXTUNE_SCREENSHOTS environment variable (as ux4-*.png) for review, with scene dumps (ux4-*.scene)
 * for Vulkan host test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-night-xxhdpi")
class FilterSceneRenderTest {

    class Voice(val hz: Double, val control: Int = 0x41, val envelope: Int = 0xc0)

    class Chip(val cutoff: Int, val mode: Int, val resonance: Int, val routing: Int, val voices: List<Voice?>)

    private val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.CustomTheme)
    private val res = FilterResources.of(ctx)
    private val labelTextSize = OscilloscopeView.labelTextSize(ctx)

    private fun gauges(chips: List<Chip>) = ByteArray(GaugesExchange.MAX_CHIPS * ChipGauges.SIZE).also { fill(it, chips) }

    private fun fill(data: ByteArray, chips: List<Chip>) {
        chips.forEachIndexed { idx, chip ->
            val base = idx * ChipGauges.SIZE + ChipGauges.GAUGES * ChipGauges.COLUMNS * 2
            data[base + 0x15] = (chip.cutoff and 7).toByte()
            data[base + 0x16] = (chip.cutoff shr 3).toByte()
            data[base + 0x17] = ((chip.resonance shl 4) or chip.routing).toByte()
            data[base + 0x18] = ((chip.mode shl 4) or 15).toByte()
            chip.voices.forEachIndexed { voice, v ->
                val freq = ((v?.hz ?: 0.0) * (1 shl 24) / SidDescription.PAL_CLOCK).roundToInt()
                data[base + voice * 7] = freq.toByte()
                data[base + voice * 7 + 1] = (freq shr 8).toByte()
                data[base + voice * 7 + 4] = (v?.control ?: 0).toByte()
                data[base + 0x1c + voice] = (v?.envelope ?: 0).toByte()
            }
        }
    }

    private fun voicesLayout(chips: Int) = VoicesLayout.parse(
        "registers\n" + (1..chips).joinToString("\n") { "SID $it\tVoice 1\tVoice 2\tVoice 3" }
    )

    private class Rendered(val layout: FilterLayout, val shapes: ScopeShapes, val picture: Bitmap)

    // waveforms of voices: pulse, saw and triangle-like
    private fun sample(chan: Int, idx: Int): Float {
        val phase = (idx * (3 + chan % 3) / ScopeShapesPainter.POINTS.toFloat()) % 1f
        return when (chan % 3) {
            0 -> if (phase < 0.3f) 0.8f else -0.8f
            1 -> phase * 1.6f - 0.8f
            else -> (if (phase < 0.5f) phase else 1 - phase) * 3.2f - 0.8f
        }
    }

    private fun render(
        name: String,
        chips: List<Chip>,
        width: Int = 1080,
        height: Int = 608,
        withWaves: Boolean = true,
        settings: FilterSettings = FilterSettings(withWaves = withWaves),
        frames: (FilterScene, FilterLayout, ScopeShapes) -> Unit = { scene, layout, shapes ->
            scene.build(layout, gauges(chips), chips.size, "PAL, MOS6581", 1_000_000L, false, shapes)
        },
    ): Rendered {
        val voices = voicesLayout(chips.size)
        val grid = ScopeGrid.of(voices, chips.size * 3)
        val layout = FilterLayout.of(ctx, width, height, voices, grid, labelTextSize, res, settings.withWaves)
        val scene = FilterScene(res).apply { this.settings = settings }
        val shapes = ScopeShapes()
        frames(scene, layout, shapes)
        val background = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        layout.drawBackground(background)
        val traces = FloatArray(ScopeFeed.MAX_CHANNELS * 4)
        val channels = layout.traces(traces)
        val picture = ScopeShapesPainter.picture(width, height, background, traces, channels, ::sample, shapes, layout.atlas.bitmap)
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "ux4-$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ScopeShapesPainter.dump(File(dir, "ux4-$name.scene"), background, traces, channels, shapes, layout.atlas.bitmap)
        }
        return Rendered(layout, shapes, picture)
    }

    private val lowpass = Chip(
        cutoff = 300,
        mode = 1,
        resonance = 13,
        routing = 0b101,
        voices = listOf(Voice(82.4), Voice(246.9), Voice(1975.5, control = 0x81))
    )

    @Test
    fun `resonant low-pass with waveforms`() {
        val result = render("lowpass-waves", listOf(lowpass))
        assertTrue(result.shapes.count in 100 until ScopeShapes.MAX)
        assertTrue(result.layout.withWaves)
    }

    @Test
    fun `response only`() {
        render("lowpass-only", listOf(lowpass), withWaves = false)
    }

    @Test
    fun `fullscreen landscape`() {
        render("landscape", listOf(lowpass), width = 2400, height = 1080, withWaves = false)
    }

    @Test
    fun `three chips in columns`() {
        val chips = listOf(
            lowpass,
            Chip(1400, 4, 4, 0b011, listOf(Voice(110.0), Voice(440.0), null)),
            Chip(300, 2, 15, 0b111, listOf(Voice(330.0), Voice(660.0, control = 0x21), Voice(1320.0))),
        )
        val result = render("3sid", chips)
        assertEquals(3, result.layout.lanes)
        assertTrue(result.shapes.count < ScopeShapes.MAX)
    }

    @Test
    fun `8580 notch without filtered voices`() {
        render(
            "8580-notch",
            listOf(Chip(1200, 5, 8, 0, listOf(Voice(220.0), Voice(880.0), Voice(3520.0)))),
            settings = FilterSettings(is8580 = true)
        )
    }

    // Per tick cutoff sweep with vibrato of voice: persistence and swept zone are drawn, no value is written
    @Test
    fun `sweep persistence`() {
        var glyphs = 0
        val result = render("sweep", listOf(lowpass)) { scene, layout, shapes ->
            val data = gauges(listOf(lowpass))
            val fps = 120
            for (frame in 0 until fps) {
                val time = frame.toDouble() / fps
                val tick = Math.floor(time * 50) / 50
                val cutoff = (330 + 200 * sin(2 * PI * 1.5 * tick)).roundToInt()
                val vibrato = 246.9 * 2.0.pow(0.3 / 12 * sin(2 * PI * 6 * tick))
                fill(data, listOf(Chip(cutoff, 1, 13, 0b101, listOf(Voice(82.4), Voice(vibrato), Voice(1975.5, 0x81)))))
                scene.build(layout, data, 1, "PAL, MOS6581", (time * 1e9).toLong() + 1, true, shapes)
                glyphs += (0 until shapes.count).count { shapes.data[it * ScopeShapes.FLOATS] == ScopeShapes.GLYPH }
            }
        }
        // the last frame has fading previous curves and swept zone
        val fills = (0 until result.shapes.count).count { result.shapes.data[it * ScopeShapes.FLOATS] == ScopeShapes.FILL }
        val segments = (0 until result.shapes.count).count { result.shapes.data[it * ScopeShapes.FLOATS] == ScopeShapes.SEGMENT }
        assertTrue("fills=$fills", fills > 100)
        assertTrue("segments=$segments", segments > 400)
        // response is shown by graphics only
        assertEquals(0, glyphs)
    }
}
