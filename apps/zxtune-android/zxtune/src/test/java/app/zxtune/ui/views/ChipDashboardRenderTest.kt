package app.zxtune.ui.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoicesLayout
import java.io.File
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders dashboards of different chips with synthetic state, to check layout and (optionally) to review images
 * stored in directory from ZXTUNE_SCREENSHOTS environment variable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class ChipDashboardRenderTest {

    private class Voice(
        val kind: Int,
        val fields: IntArray,
        val freq: Float = 0f,
        val level: Float = -12f,
        val flags: Int = 1 or 2 or 4,
        val text: String = ""
    )

    private fun makeData(voices: List<Voice>): ByteArray {
        val data = ByteArray(voices.size * VoiceGauges.SIZE)
        voices.forEachIndexed { idx, voice ->
            val offset = idx * VoiceGauges.SIZE
            for (col in 0 until VoiceGauges.COLUMNS) {
                fun put(gauge: Int, min: Int, max: Int) {
                    data[offset + (gauge * VoiceGauges.COLUMNS + col) * 2] = min.toByte()
                    data[offset + (gauge * VoiceGauges.COLUMNS + col) * 2 + 1] = max.toByte()
                }
                val level = (180 + 60 * sin(col / 9.0 + idx)).toInt().coerceIn(0, 255)
                put(VoiceGauges.WAVE, 100, 160)
                put(VoiceGauges.LEVEL, level - 10, level)
                val pitch = 100 + ((col / 16 + idx) % 5) * 12
                put(VoiceGauges.FREQUENCY, pitch, pitch)
            }
            val state = offset + VoiceGauges.GAUGES * VoiceGauges.COLUMNS * 2
            putFloat(data, state, voice.freq)
            putFloat(data, state + 4, voice.level)
            data[state + 8] = voice.flags.toByte()
            data[state + 9] = voice.kind.toByte()
            voice.fields.forEachIndexed { f, value -> data[state + 10 + f] = value.toByte() }
            voice.text.toByteArray().forEachIndexed { c, b -> data[state + 22 + c] = b }
        }
        return data
    }

    private fun putFloat(data: ByteArray, at: Int, value: Float) {
        val bits = value.toRawBits()
        for (idx in 0 until 4) {
            data[at + idx] = (bits shr (idx * 8)).toByte()
        }
    }

    private fun render(name: String, layout: String, voices: List<Voice>, width: Int = 1080, height: Int = 1700) {
        val view = SidDashboardView(ApplicationProvider.getApplicationContext())
        val parsed = VoicesLayout.parse(layout)
        view.setVoicesData(parsed, makeData(voices), voices.size, "Emulation description\nscope: statistics")
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // values are eased between frames
        repeat(12) {
            bitmap.eraseColor(0)
            view.draw(Canvas(bitmap))
        }
        // something is drawn
        var lit = 0
        for (y in 0 until height step 4) {
            for (x in 0 until width step 4) {
                if (bitmap.getPixel(x, y) and 0xffffff != 0) {
                    ++lit
                }
            }
        }
        assertTrue(name, lit > 1000)
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun megaDrive() = render(
        "megadrive",
        "audio\nSEGA PSG\t1\t2\t3\tNoise\nYM2612\tFM 1\tFM 2\tFM 3\tFM 4\tFM 5\tFM 6\tDAC",
        listOf(
            Voice(VoiceGauges.Kind.PSG_TONE, intArrayOf(0, 0xfe, 0, 3), 440.4f, 0f),
            Voice(VoiceGauges.Kind.PSG_TONE, intArrayOf(4, 0x7f, 0, 3), 880.8f, -8f),
            Voice(VoiceGauges.Kind.PSG_TONE, intArrayOf(15, 0, 0, 3), 109f, -96f, 2 or 4),
            Voice(VoiceGauges.Kind.PSG_NOISE, intArrayOf(2, 1, 0, 3), 6991f, -4f, 1 or 2 or 4 or 8),
        ) + (0 until 6).map {
            Voice(
                VoiceGauges.Kind.OPN_FM,
                intArrayOf(it + 1, 3, 200, 120, 255 - it * 30, 230, intArrayOf(8, 8, 8, 8, 10, 14, 14, 15)[it + 1], 3, 0, 0, 15, 4),
                220f * (it + 1),
                -10f
            )
        } + listOf(Voice(VoiceGauges.Kind.OPN_DAC, intArrayOf(1, 200), 0f, 0f, 1))
    )

    @Test
    fun opl3() = render(
        "opl3",
        "audio\nYMF262\t1\t2\t3\t4\t5\t6\t7\t8\t9\tBass Drum\tSnare Drum\tTom Tom\tCymbal\tHi-Hat",
        (0 until 4).map {
            Voice(VoiceGauges.Kind.OPL_4OP, intArrayOf(it, 5, 230, 150, 200, 255, 0, 1, 2, 6, 1, 3), 330f, -6f)
        } + (4 until 9).map {
            Voice(VoiceGauges.Kind.OPL_2OP, intArrayOf(it % 2, 2, 180, 240, it % 4, (it + 1) % 4, 1, 2, 4), 196f, -12f)
        } + (0 until 5).map {
            Voice(VoiceGauges.Kind.OPL_RHYTHM, intArrayOf(it, 200 - it * 30, 1, 1), 110f, -3f, if (it == 0) 7 else 1 or 4 or 8)
        }
    )

    @Test
    fun snes() = render(
        "snes",
        "audio\nS-DSP\tVoice 1\tVoice 2\tVoice 3\tVoice 4\tVoice 5\tVoice 6\tVoice 7\tVoice 8",
        (0 until 8).map {
            Voice(
                VoiceGauges.Kind.SPC_DSP,
                intArrayOf(100 - it * 10, it % 5, 0x8f, 0xe3, 0, it * 3, 100, -60, 0x00, 0x10, it % 8),
                0f,
                -12f,
                1 or 4
            )
        }
    )

    @Test
    fun mt32Parts() = render(
        "mt32parts",
        "audio\nMT-32\tPart 1\tPart 2\tPart 3\tPart 4\tPart 5\tPart 6\tPart 7\tPart 8\tRhythm",
        (0 until 9).map {
            Voice(
                VoiceGauges.Kind.MT32_PART,
                intArrayOf(3, 60, 64, 67, 0, 0, 0, 0, 0, 6),
                261.6f,
                0f,
                1 or 2,
                listOf("Slap Bass 1", "Str Sect 1", "Trumpet 1", "Brs Sect 1", "Fantasy", "Pipe Org 1", "Harp 1",
                    "Choir", "Rhythm")[it]
            )
        }
    )

    @Test
    fun mt32Partials() = render(
        "mt32partials",
        "audio\n" + (0 until 4).joinToString("\n") { g ->
            "Partials ${g * 8 + 1}-${g * 8 + 8}\t" + (1..8).joinToString("\t") { "${g * 8 + it}" }
        },
        (0 until 32).map { Voice(VoiceGauges.Kind.MT32_PARTIAL, intArrayOf(it % 4, it % 9 + 1, 60 + it), 261.6f, 0f, 1 or 2) }
    )

    @Test
    fun nes() = render(
        "nes",
        "audio\nNintendo NES\tSquare 1\tSquare 2\tTriangle\tNoise\tDMC",
        listOf(
            Voice(VoiceGauges.Kind.NES_PULSE, intArrayOf(2, 12, 0, 0xfd, 0, 1, 1), 440f, -2f),
            Voice(VoiceGauges.Kind.NES_PULSE, intArrayOf(1, 7, 1, 0x7e, 1, 1, 0), 330f, -6f),
            Voice(VoiceGauges.Kind.NES_TRIANGLE, intArrayOf(1, 0x50, 1, 1), 110f, 0f),
            Voice(VoiceGauges.Kind.NES_NOISE, intArrayOf(9, 0, 4, 1), 0f, -4f, 1 or 4 or 8),
            Voice(VoiceGauges.Kind.NES_DMC, intArrayOf(64, 15, 1, 0), 0f, 0f, 0),
        )
    )
}
