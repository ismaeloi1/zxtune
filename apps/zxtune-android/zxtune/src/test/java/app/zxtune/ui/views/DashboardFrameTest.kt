package app.zxtune.ui.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoicesLayout
import java.io.File
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Per frame redrawing of dashboard: static layer is reused while only values change, slow history is scrolled by
 * column progress. Images are stored to directory from ZXTUNE_SCREENSHOTS environment variable for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class DashboardFrameTest {

    private val width = 1080
    private val height = 1300

    private fun spcData(voices: Int, phase: Int, shift: Int): ByteArray {
        val data = ByteArray(voices * VoiceGauges.SIZE)
        for (voice in 0 until voices) {
            val offset = voice * VoiceGauges.SIZE
            for (col in 0 until VoiceGauges.COLUMNS) {
                fun put(gauge: Int, min: Int, max: Int) {
                    data[offset + (gauge * VoiceGauges.COLUMNS + col) * 2] = min.toByte()
                    data[offset + (gauge * VoiceGauges.COLUMNS + col) * 2 + 1] = max.toByte()
                }
                // history moves left by columns as time goes
                val t = col + shift
                val level = (160 + 80 * sin(t / 7.0 + voice)).toInt().coerceIn(0, 255)
                put(VoiceGauges.WAVE, 100, 160)
                put(VoiceGauges.LEVEL, 0, level)
                val pitch = 90 + ((t / 24 + voice) % 4) * 20
                put(VoiceGauges.FREQUENCY, pitch, pitch)
            }
            val state = offset + VoiceGauges.GAUGES * VoiceGauges.COLUMNS * 2
            putFloat(data, state, 0f)
            putFloat(data, state + 4, -20f - voice)
            data[state + 8] = (1 or 4).toByte()
            data[state + 9] = VoiceGauges.Kind.SPC_DSP.toByte()
            // envx, mode, adsr1, adsr2, gain, srcn, volL, volR, pitch, flags
            intArrayOf(60 + shift % 40, 3, 0xaf, 0xe7, 0, 0x11 + voice, 28, 28, 0x00, 0x10, 1).forEachIndexed { f, v ->
                data[state + 10 + f] = v.toByte()
            }
            data[offset + VoiceGauges.SIZE - 1] = phase.toByte()
        }
        return data
    }

    private fun putFloat(data: ByteArray, at: Int, value: Float) {
        val bits = value.toRawBits()
        for (idx in 0 until 4) {
            data[at + idx] = (bits shr (idx * 8)).toByte()
        }
    }

    private fun createView() = SidDashboardView(ApplicationProvider.getApplicationContext()).also {
        it.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        it.layout(0, 0, width, height)
    }

    private fun draw(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(name: String, bitmap: Bitmap) = System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val spcLayout = VoicesLayout.parse(
        "audio\nS-DSP\tVoice 1\tVoice 2\tVoice 3\tVoice 4\tVoice 5\tVoice 6\tVoice 7\tVoice 8"
    )

    @Test
    fun `static layer is reused for new values`() {
        val view = createView()
        for (frame in 0 until 10) {
            view.setVoicesData(spcLayout, spcData(8, frame * 25, frame / 4), 8, "S-DSP 32kHz, snes_spc")
            draw(view)
        }
        // the first frame only, values don't change layout
        assertEquals(1, view.staticRedraws)
        save("display-dashboard-spc", draw(view))
    }

    // History is moved by column progress, so the end of column is (almost) the same picture as the start of the next
    // one, while there's no jump of the whole column
    @Test
    fun `history is scrolled smoothly`() {
        val view = createView()
        fun frame(phase: Int, column: Int): IntArray {
            view.setVoicesData(spcLayout, spcData(8, phase, column), 8, "")
            return rowOf(draw(view))
        }
        val start = frame(0, 0)
        val middle = frame(128, 0)
        val end = frame(255, 0)
        val next = frame(0, 1)
        val continuous = diff(end, next)
        assertTrue("end-next=$continuous start-next=${diff(start, next)}", continuous * 4 < diff(start, next))
        assertTrue("middle is between", diff(middle, next) in (continuous + 1) until diff(start, next))
    }

    // brightness of history graph area of the first card
    private fun rowOf(bitmap: Bitmap): IntArray {
        val top = (height * 0.06f).toInt()
        val rows = (height * 0.05f).toInt()
        val cols = width / 2
        return IntArray(cols * rows) { bitmap.getPixel(it % cols, top + it / cols) and 0xff }
    }

    private fun diff(a: IntArray, b: IntArray) = a.indices.sumOf { ((a[it] - b[it]) * (a[it] - b[it])).toLong() }

    @Test
    fun sid() {
        val view = createView()
        val data = ByteArray(ChipGauges.SIZE)
        for (gauge in 0 until ChipGauges.GAUGES) {
            for (col in 0 until ChipGauges.COLUMNS) {
                val value = (128 + 100 * sin(col / (5.0 + gauge))).toInt()
                data[(gauge * ChipGauges.COLUMNS + col) * 2] = (value - 20).toByte()
                data[(gauge * ChipGauges.COLUMNS + col) * 2 + 1] = value.toByte()
            }
        }
        val regs = ChipGauges.GAUGES * ChipGauges.COLUMNS * 2
        intArrayOf(0x34, 0x12, 0x00, 0x08, 0x41, 0x09, 0xa8).forEachIndexed { idx, v ->
            for (voice in 0 until 3) {
                data[regs + voice * 7 + idx] = v.toByte()
            }
        }
        data[regs + 0x18] = 0x1f
        data[regs + 0x1c] = 0x80.toByte()
        for (frame in 0 until 4) {
            data[ChipGauges.SIZE - 1] = (frame * 64).toByte()
            view.setGaugesData(data, 1, "PAL, MOS6581, reSIDfp, resample\nPlaySID one-file format (PSID), 1 SID")
            draw(view)
        }
        save("display-dashboard-sid", draw(view))
    }
}
