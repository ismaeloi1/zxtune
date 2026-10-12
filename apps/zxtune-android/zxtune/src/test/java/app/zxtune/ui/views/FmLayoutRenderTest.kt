package app.zxtune.ui.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoicesLayout
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FM operators schemas of all algorithms and chips columns shared by oscilloscope and dashboard.
 * Images are stored to directory from ZXTUNE_SCREENSHOTS environment variable for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 1080x2400 phone
@Config(qualifiers = "w411dp-h914dp-420dpi")
class FmLayoutRenderTest {

    private val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val density = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    private class Voice(val kind: Int, val fields: IntArray, val freq: Float = 220f, val level: Float = -6f)

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
                put(VoiceGauges.FREQUENCY, 120, 120)
            }
            val state = offset + VoiceGauges.GAUGES * VoiceGauges.COLUMNS * 2
            putFloat(data, state, voice.freq)
            putFloat(data, state + 4, voice.level)
            data[state + 8] = (1 or 2 or 4).toByte()
            data[state + 9] = voice.kind.toByte()
            voice.fields.forEachIndexed { f, value -> data[state + 10 + f] = value.toByte() }
        }
        return data
    }

    private fun putFloat(data: ByteArray, at: Int, value: Float) {
        val bits = value.toRawBits()
        for (idx in 0 until 4) {
            data[at + idx] = (bits shr (idx * 8)).toByte()
        }
    }

    private fun opn(alg: Int, keys: Int = 15) = Voice(
        VoiceGauges.Kind.OPN_FM,
        intArrayOf(alg, if (alg % 2 == 0) 5 else 0, 255, 170, 110, 60, OPN_CARRIERS[alg], 3, 0, 0, keys, 4)
    )

    private fun save(name: String, bitmap: Bitmap) {
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    // Size of schema area in card of Mega Drive's FM chip at phone in portrait
    @Test
    fun algorithmsCompact() = renderAlgorithms("fmview-algorithms-compact", 110)

    // Two rows layouts as in datasheets, for bigger cards
    @Test
    fun algorithms() = renderAlgorithms("fmview-algorithms", 230)

    private fun renderAlgorithms(name: String, cellH: Int) {
        val voices = (0 until 8).map { opn(it, 1 shl (it % 4)) } + (0 until 4).map {
            Voice(VoiceGauges.Kind.OPL_4OP, intArrayOf(it, it, 230, 150, 200, 255, 0, 1, 2, 6, 1, 3))
        } + (0 until 2).map {
            Voice(VoiceGauges.Kind.OPL_2OP, intArrayOf(it, 2, 180, 240, 0, 1, 1, 3, 4))
        }
        val data = makeData(voices)
        val cellW = 520
        val gap = 20
        val width = 1080
        val height = (voices.size + 1) / 2 * (cellH + gap)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val widgets = ChipWidgets(density, 12 * sp)
        val frame = Paint().apply {
            color = Color.rgb(60, 0, 90)
            style = Paint.Style.STROKE
        }
        val smooth = Array(voices.size) { FloatArray(ChipWidgets.SMOOTH_SIZE) }
        // values are eased between frames
        repeat(12) {
            canvas.drawColor(Color.BLACK)
            voices.indices.forEach { idx ->
                val left = (idx % 2) * (width / 2) + gap / 2f
                val top = (idx / 2) * (cellH + gap) + gap / 2f
                val rect = RectF(left, top, left + cellW, top + cellH)
                canvas.drawRect(rect, frame)
                assertTrue(widgets.draw(canvas, rect, VoiceGauges(data, idx * VoiceGauges.SIZE), smooth[idx]))
            }
        }
        save(name, bitmap)
    }

    @Test
    fun megaDriveColumns() {
        val columns = ChipColumns(VoicesLayout.parse(MEGADRIVE).groups)
        assertEquals(2, columns.columns)
        assertEquals(1 / 3f, columns.width(0), 1e-6f)
        assertEquals(2 / 3f, columns.width(1), 1e-6f)
        assertEquals(7, columns.maxRows)
        // AdLib/Sound Blaster and others are not affected
        val opl = ChipColumns(VoicesLayout.parse("audio\nYM3812\t1\t2\nYM3812/2\t1\t2\nAY-3-8910\tA\tB\tC").groups)
        assertEquals(1 / 3f, opl.width(0), 1e-6f)
        assertEquals(1 / 3f, opl.width(2), 1e-6f)
        val opl3 = ChipColumns(VoicesLayout.parse(OPL3).groups)
        assertEquals(2, opl3.columns)
        assertEquals(7, opl3.rows(0))
        assertEquals(1, opl3.column(0, 7))
        assertEquals(0, opl3.row(0, 7))
    }

    @Test
    fun scopeLabels() {
        val text = 12 * sp
        assertEquals(text, OscilloscopeView.labelSize(1000f, text), 1e-3f)
        // shrinks in short cells, so band takes 40% of cell at most
        val short = OscilloscopeView.labelSize(text * 2.6f, text)
        assertTrue(short < text && short * 1.4f <= text * 2.6f * 0.4f + 1e-3f)
        assertEquals(0f, OscilloscopeView.labelSize(text, text), 0f)
    }

    // Oscilloscope (drawn by geometry of OscilloscopeView) above dashboard as at phone in portrait
    private fun renderWithScope(name: String, layout: String, voices: List<Voice>) {
        val width = 1080
        val scopeH = 570
        val dashH = 1210
        val bitmap = Bitmap.createBitmap(width, scopeH + dashH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        val parsed = VoicesLayout.parse(layout)
        drawScope(canvas, width, scopeH, parsed)
        val view = SidDashboardView(ApplicationProvider.getApplicationContext())
        view.setVoicesData(parsed, makeData(voices), voices.size, "Emulation description\nscope: statistics")
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dashH, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, dashH)
        val dash = Bitmap.createBitmap(width, dashH, Bitmap.Config.ARGB_8888)
        repeat(12) {
            dash.eraseColor(0)
            view.draw(Canvas(dash))
        }
        canvas.drawBitmap(dash, 0f, scopeH.toFloat(), null)
        save(name, bitmap)
    }

    private fun drawScope(canvas: Canvas, width: Int, height: Int, layout: VoicesLayout) {
        val columns = ChipColumns(layout.groups)
        val cellH = height.toFloat() / columns.maxRows
        val size = OscilloscopeView.labelSize(cellH, 12 * sp)
        val band = size * 1.4f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
            textSize = size
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        val dim = Paint(line).apply { color = Color.argb(40, 255, 255, 255) }
        layout.groups.forEachIndexed { group, desc ->
            desc.voices.forEachIndexed { idx, voice ->
                val col = columns.column(group, idx)
                val left = width * columns.left(col)
                val w = width * columns.width(col)
                val top = cellH * columns.row(group, idx)
                if (size > 0f) {
                    canvas.drawText("${desc.name} - $voice", left + size * 0.4f, top + size * 0.2f - paint.ascent(), paint)
                    canvas.drawLine(left, top + band, left + w, top + band, dim)
                }
                val traceTop = top + band
                val traceH = cellH - band
                val path = android.graphics.Path()
                for (x in 0..100) {
                    val y = traceTop + traceH / 2 * (1 - 0.95f * sin(2 * PI * x / 100 * (idx + 2)).toFloat())
                    val px = left + w * (0.01f + 0.98f * x / 100)
                    if (x == 0) path.moveTo(px, y) else path.lineTo(px, y)
                }
                canvas.drawPath(path, line)
            }
        }
    }

    @Test
    fun megaDrive() = renderWithScope(
        "fmview-megadrive",
        MEGADRIVE,
        listOf(
            Voice(VoiceGauges.Kind.PSG_TONE, intArrayOf(0, 0xfe, 0, 3), 440.4f, 0f),
            Voice(VoiceGauges.Kind.PSG_TONE, intArrayOf(4, 0x7f, 0, 3), 880.8f, -8f),
            Voice(VoiceGauges.Kind.PSG_TONE, intArrayOf(15, 0, 0, 3), 109f, -96f),
            Voice(VoiceGauges.Kind.PSG_NOISE, intArrayOf(2, 1, 0, 3), 6991f, -4f),
        ) + listOf(0, 2, 4, 5, 6, 7).map { opn(it) } + listOf(Voice(VoiceGauges.Kind.OPN_DAC, intArrayOf(1, 200), 0f, 0f))
    )

    @Test
    fun opl3() = renderWithScope(
        "fmview-opl3",
        OPL3,
        (0 until 4).map {
            Voice(VoiceGauges.Kind.OPL_4OP, intArrayOf(it, 5, 230, 150, 200, 255, 0, 1, 2, 6, 1, 3))
        } + (4 until 9).map {
            Voice(VoiceGauges.Kind.OPL_2OP, intArrayOf(it % 2, 2, 180, 240, it % 4, (it + 1) % 4, 1, 2, 4))
        } + (0 until 5).map {
            Voice(VoiceGauges.Kind.OPL_RHYTHM, intArrayOf(it, 200 - it * 30, 1, 1), 110f, -3f)
        }
    )

    @Test
    fun ym2151() = renderWithScope(
        "fmview-ym2151",
        "audio\nYM2151\t1\t2\t3\t4\t5\t6\t7\t8",
        (0 until 8).map { opn(it) }
    )

    @Test
    fun ym2203() = renderWithScope(
        "fmview-ym2203",
        "audio\nYM2203\tFM 1\tFM 2\tFM 3\tSSG A\tSSG B\tSSG C",
        (0 until 3).map { opn(it * 3) } + (0 until 3).map { Voice(VoiceGauges.Kind.GENERIC, intArrayOf()) }
    )

    private companion object {
        const val MEGADRIVE = "audio\nSEGA PSG\t1\t2\t3\tNoise\nYM2612\tFM 1\tFM 2\tFM 3\tFM 4\tFM 5\tFM 6\tDAC"
        const val OPL3 = "audio\nYMF262\t1\t2\t3\t4\t5\t6\t7\t8\t9\tBass Drum\tSnare Drum\tTom Tom\tCymbal\tHi-Hat"

        // as reported by emulation cores
        val OPN_CARRIERS = intArrayOf(8, 8, 8, 8, 10, 14, 14, 15)
    }
}
