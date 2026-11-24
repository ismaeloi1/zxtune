package app.zxtune.ui.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.zxtune.playback.VoiceGauges
import java.io.File
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * PC Engine wavetable channels: tables of all voices, played step and registers
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class PceDashboardRenderTest {

    private fun voice(data: ByteArray, idx: Int, wave: (Int) -> Int, freq: Int, vol: Int, mode: Int, pos: Int) {
        val state = idx * VoiceGauges.SIZE + VoiceGauges.GAUGES * VoiceGauges.COLUMNS * 2
        val hz = 3579545f / 32f / freq
        val level = -1.5f * (31 - vol)
        fun putFloat(at: Int, v: Float) {
            val bits = v.toRawBits()
            for (b in 0 until 4) data[at + b] = (bits shr (b * 8)).toByte()
        }
        putFloat(state, hz)
        putFloat(state + 4, level)
        data[state + 8] = (1 or 4 or (if (mode and 6 == 0) 2 else 0) or (if (mode and 4 != 0) 8 else 0)).toByte()
        data[state + 9] = VoiceGauges.Kind.PCE_WAVE.toByte()
        val f = state + 10
        for (s in 0 until 32) data[f + s] = wave(s).coerceIn(0, 31).toByte()
        data[f + 32] = pos.toByte()
        data[f + 33] = (freq and 0xff).toByte()
        data[f + 34] = (freq shr 8).toByte()
        data[f + 35] = vol.toByte()
        data[f + 36] = 0xfc.toByte()
        data[f + 37] = mode.toByte()
        data[f + 38] = 0x1a
        data[f + 39] = 0x18
        data[f + 40] = 0xff.toByte()
        data[f + 41] = 0
        data[f + 42] = 0
    }

    @Test
    fun channels() {
        val data = ByteArray(6 * VoiceGauges.SIZE)
        voice(data, 0, { (15.5 + 15.5 * sin(2 * PI * it / 32)).roundToInt() }, 0x1AC, 31, 1, 5)
        voice(data, 1, { if (it < 8) 31 else 0 }, 0x0D6, 26, 1, 20)
        voice(data, 2, { it }, 0x358, 28, 1, 30)
        voice(data, 3, { if (it < 16) it * 2 else (31 - it) * 2 }, 0x23A, 24, 1, 12)
        voice(data, 4, { (it * 7) % 32 }, 0x100, 20, 5, 0)
        voice(data, 5, { 16 }, 0x100, 30, 3, 0)
        val w = 1080
        val h = 1500
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val density = 2.625f
        PceDashboard(density, 16 * density).draw(canvas, data, 6, w.toFloat(), h.toFloat())
        // waveform of the first channel is drawn
        var lit = 0
        for (y in 0 until h / 6) for (x in 0 until w / 3) if (bitmap.getPixel(x, y) == Color.WHITE) ++lit
        assertTrue("lit=$lit", lit > 100)
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "pce-dashboard.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
