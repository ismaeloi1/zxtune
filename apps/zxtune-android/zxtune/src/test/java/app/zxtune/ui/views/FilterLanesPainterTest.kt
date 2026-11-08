package app.zxtune.ui.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import app.zxtune.R
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders filter history lanes of synthetic tune. Images are stored in directory from ZXTUNE_SCREENSHOTS environment
 * variable (as flanes-*.png) for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-night-xxhdpi")
class FilterLanesPainterTest {

    private class Note(val startMs: Long, val hz: Float, val noise: Boolean = false)

    // V1 bass, V2 unfiltered lead, V3 filtered melody with noise hit
    private val notes = arrayOf(
        arrayOf(Note(0, 98f), Note(2600, 110f)),
        arrayOf(Note(0, 440f), Note(3500, 392f)),
        arrayOf(Note(0, 1046.5f), Note(1500, 1318.5f), Note(2900, 3000f, noise = true), Note(3200, 880f)),
    )
    private val routed = booleanArrayOf(true, false, true)

    private fun fill(history: FilterHistory, mode: Int, durationMs: Long = 5000, stepMs: Long = 16) {
        var time = 0L
        while (time <= durationMs) {
            val t = time
            history.push(t) { frame ->
                // log sweep 500Hz..4.5kHz with 2.2s period
                val phase = sin(2 * PI * t / 2200.0)
                frame.cutoffHz = exp(ln(1500.0) + ln(3.0) * phase).toFloat()
                frame.resonance = ((t / 300) % 16).toInt()
                frame.mode = mode
                frame.q = 0.707f + frame.resonance / 8f
                for (idx in 0 until 3) {
                    val note = notes[idx].last { it.startMs <= t }
                    val voice = frame.voices[idx]
                    val age = (t - note.startMs) / 1000f
                    voice.active = !(idx == 2 && t in 1400L until 1500L)
                    voice.freqHz = note.hz
                    voice.noise = note.noise
                    voice.level = (1 - age * 0.4f).coerceIn(0.5f, 1f)
                    voice.routed = routed[idx]
                    voice.gainDb = SidFilterModel.responseDb(mode, note.hz.toDouble(), frame.cutoffHz.toDouble(), 1.0 / frame.q).toFloat()
                }
            }
            time += stepMs
        }
    }

    private fun render(name: String, mode: Int): Bitmap {
        val ctx = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.CustomTheme)
        val painter = FilterLanesPainter(ctx)
        val history = FilterHistory()
        fill(history, mode)
        val density = ctx.resources.displayMetrics.density
        val bitmap = Bitmap.createBitmap(1080, 720, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        val canvas = Canvas(bitmap)
        val rect = RectF(16 * density, 16 * density, bitmap.width - 16 * density, bitmap.height - 16 * density)
        painter.draw(canvas, rect, history)
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "flanes-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val voices = IntArray(3)
        for (y in 0 until bitmap.height step 2) {
            for (x in 0 until bitmap.width step 2) {
                val idx = painter.voiceColors.indexOf(bitmap.getPixel(x, y))
                if (idx >= 0) ++voices[idx]
            }
        }
        voices.forEachIndexed { idx, count -> assertTrue("no voice ${idx + 1} in $name: $count", count > 20) }

        // drawing is fast enough for display rate
        repeat(20) { painter.draw(canvas, rect, history) }
        val start = System.nanoTime()
        repeat(100) { painter.draw(canvas, rect, history) }
        println("$name draw: %.2f ms".format((System.nanoTime() - start) / 1e6 / 100))
        return bitmap
    }

    @Test
    fun lowpass() {
        render("lowpass", SidFilterModel.MODE_LOWPASS)
    }

    @Test
    fun bandpass() {
        render("bandpass", SidFilterModel.MODE_BANDPASS)
    }

    @Test
    fun highpass() {
        render("highpass", SidFilterModel.MODE_HIGHPASS)
    }
}
