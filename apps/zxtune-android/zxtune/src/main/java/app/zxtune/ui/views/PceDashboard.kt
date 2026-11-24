/**
 * @file
 * @brief HuC6280 (PC Engine) wavetable dashboard
 */
package app.zxtune.ui.views

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.zxtune.playback.VoiceGauges
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Wavetable synthesis: each channel plays 32 steps of 5 bit waveform written by the music driver, so the waveform
 * itself is the instrument and is drawn large, with the step being played. All registers of the channel are shown
 * as written ($800 page of the chip) with their meaning, global registers are in the last row.
 */
internal class PceDashboard(private val density: Float, private val textSize: Float) {

    private val framePaint = Paint().apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(40, 255, 255, 255)
        strokeWidth = density
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        strokeJoin = Paint.Join.MITER
    }
    private val fillPaint = Paint().apply {
        color = Color.argb(50, 255, 255, 255)
    }
    private val levelPaint = Paint().apply {
        color = Color.rgb(0, 229, 255)
    }
    private val fillStrongPaint = Paint().apply {
        color = Color.argb(170, 255, 255, 255)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = this@PceDashboard.textSize
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
    }
    private val path = Path()
    private val rect = RectF()

    fun draw(canvas: Canvas, data: ByteArray, voices: Int, width: Float, height: Float) {
        val channels = min(voices, CHANNELS)
        // global row is lower: one line of cells
        val rowH = height / (channels + 0.6f)
        val lineH = rowH * LINE_PART
        // caption and value fit the line with descents
        captionPaint.textSize = min(textSize * 0.62f, lineH * 0.3f)
        valuePaint.textSize = min(textSize * 0.95f, lineH * 0.42f)
        dimPaint.textSize = valuePaint.textSize
        titlePaint.textSize = min(textSize, rowH * 0.22f)
        val waveW = min(rowH * 2.2f, width * 0.34f)
        val barsW = density * 30
        val rightLeft = waveW + barsW
        val rightW = width - rightLeft
        for (ch in 0 until channels) {
            val g = VoiceGauges(data, ch * VoiceGauges.SIZE)
            val top = rowH * ch
            drawWavePanel(canvas, g, ch, 0f, top, waveW, rowH)
            drawBars(canvas, g, waveW, top, barsW, rowH)
            drawValues(canvas, g, rightLeft, top, rightW, lineH)
            drawShape(canvas, g, rightLeft, top + lineH, rightW, rowH - lineH * 2)
            drawRegisters(canvas, g, ch, rightLeft, top + rowH - lineH, rightW, lineH)
            canvas.drawLine(0f, top + rowH, width, top + rowH, gridPaint)
        }
        if (channels > 0) {
            drawGlobal(canvas, VoiceGauges(data, 0), 0f, rowH * channels, width, height - rowH * channels)
        }
    }

    // level (all the attenuations) and stereo balance of channel
    private fun drawBars(canvas: Canvas, g: VoiceGauges, left: Float, top: Float, w: Float, h: Float) {
        val pad = density * 4
        val bottom = top + h - pad
        val barTop = top + pad + titlePaint.fontSpacing
        val levelW = w * 0.42f
        rect.set(left + density * 2, barTop, left + density * 2 + levelW, bottom)
        canvas.drawRect(rect, framePaint)
        val level = if (g.hasLevel) ((g.level - MIN_DB) / -MIN_DB).coerceIn(0f, 1f) else 0f
        canvas.drawRect(rect.left, rect.bottom - rect.height() * level, rect.right, rect.bottom, levelPaint)
        val balance = g.field(BALANCE)
        val sideW = (w - levelW - density * 8) / 2
        var x = rect.right + density * 3
        for (side in intArrayOf(balance shr 4, balance and 15)) {
            rect.set(x, barTop, x + sideW, bottom)
            canvas.drawRect(rect, framePaint)
            canvas.drawRect(rect.left, rect.bottom - rect.height() * side / 15f, rect.right, rect.bottom, fillStrongPaint)
            x += sideW + density * 2
        }
    }

    // Waveform in words: the chip has no reverse playback, so descending saw is a table written reversed by driver
    private fun drawShape(canvas: Canvas, g: VoiceGauges, left: Float, top: Float, w: Float, h: Float) {
        val mode = g.field(MODE)
        for (idx in 0 until WAVE_STEPS) {
            table[idx] = g.field(WAVE + idx) and 31
        }
        val id = tables.idOf(table)
        // tables are rarely changed, so their shapes are classified once
        val shape = shapes.getOrPut(id) { WaveShape.describe(table) }
        val cells = arrayOf(
            "Shape" to if (mode and M_DDA != 0) {
                "Direct samples"
            } else if (mode and M_NOISE != 0) {
                "Noise"
            } else {
                shape
            },
            "Table" to "#$id",
            "Range" to "%d–%d".format(table.min(), table.max()),
        )
        drawCellsLine(canvas, cells, left, top + (h - lineHeight()) / 2, w, lineHeight(), mode and M_ON != 0)
    }

    private val table = IntArray(WAVE_STEPS)
    private val tables = TableIds()
    private val shapes = HashMap<Int, String>()

    private fun lineHeight() = captionPaint.fontSpacing + valuePaint.fontSpacing

    private fun drawWavePanel(canvas: Canvas, g: VoiceGauges, ch: Int, left: Float, top: Float, w: Float, h: Float) {
        val pad = density * 4
        val mode = g.field(MODE)
        val titleBase = top + pad - titlePaint.ascent()
        val title = "${ch + 1} " + when {
            mode and M_ON == 0 -> "off"
            mode and M_DDA != 0 -> "DDA"
            mode and M_NOISE != 0 -> "Noise"
            else -> "Wave"
        }
        canvas.drawText(title, left + pad, titleBase, if (g.isKeyOn) titlePaint else dimPaint)
        rect.set(left + pad, titleBase + titlePaint.descent() + density * 2, left + w - pad, top + h - pad)
        canvas.drawRect(rect, framePaint)
        val midY = rect.centerY()
        canvas.drawLine(rect.left, midY, rect.right, midY, gridPaint)
        val step = rect.width() / WAVE_STEPS
        val playing = mode and M_ON != 0 && mode and (M_DDA or M_NOISE) == 0
        // waveform RAM keeps the last written table even while DDA or noise is played
        path.reset()
        for (idx in 0 until WAVE_STEPS) {
            val y = rect.bottom - (g.field(WAVE + idx) and 31) * rect.height() / 31f
            val x = rect.left + step * idx
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            path.lineTo(x + step, y)
            canvas.drawRect(x, min(y, midY), x + step, max(y, midY), fillPaint)
        }
        wavePaint.alpha = if (playing) 255 else 110
        canvas.drawPath(path, wavePaint)
    }

    // musical values: what is heard
    private fun drawValues(canvas: Canvas, g: VoiceGauges, left: Float, top: Float, w: Float, h: Float) {
        val mode = g.field(MODE)
        val on = mode and M_ON != 0
        val hz = if (g.hasFrequency) g.frequency else 0f
        val cells = arrayOf(
            "Mode" to modeName(mode),
            "Note" to (if (hz > 0f) ChipWidgets.noteName(hz).ifEmpty { "—" } else "—"),
            "Pitch" to (if (hz > 0f) hzText(hz) else "—"),
            "Level" to (if (g.hasLevel) dbText(g.level) else "—"),
        )
        drawCellsLine(canvas, cells, left, top, w, h, on)
    }

    // registers as written by driver
    private fun drawRegisters(canvas: Canvas, g: VoiceGauges, ch: Int, left: Float, top: Float, w: Float, h: Float) {
        val mode = g.field(MODE)
        val on = mode and M_ON != 0
        val divider = g.field(FREQ) or ((g.field(FREQ + 1) and 0x0f) shl 8)
        val volume = g.field(VOLUME) and 31
        val balance = g.field(BALANCE)
        val cells = arrayOf(
            "\$802/3" to "$%03X".format(divider),
            "\$804 Vol" to "%d".format(volume),
            "\$805 Pan" to "%X·%X".format(balance shr 4, balance and 15),
            "\$806 DDA" to (if (mode and M_DDA != 0) "$%02X".format(g.field(DDA)) else "—"),
            "\$807 Noise" to (if (ch >= NOISE_CHANNEL && mode and M_NOISE != 0) "$%02X".format(g.field(NOISE)) else "—"),
            "LFO" to when {
                ch == 0 && mode and M_LFO != 0 -> "← 2"
                ch == 1 && g.field(LFO_CTRL) != 0 -> "→ 1"
                else -> "—"
            },
        )
        drawCellsLine(canvas, cells, left, top, w, h, on)
    }

    private fun modeName(mode: Int) = when {
        mode and M_ON == 0 -> "Off"
        mode and M_DDA != 0 -> "DDA"
        mode and M_NOISE != 0 -> "Noise"
        else -> "Wave"
    }

    private fun drawGlobal(canvas: Canvas, g: VoiceGauges, left: Float, top: Float, w: Float, h: Float) {
        val main = g.field(MAIN)
        val lfo = g.field(LFO_CTRL)
        val cells = arrayOf(
            "\$801 Main" to "L%d R%d".format(main shr 4, main and 15),
            "\$808 LFO freq" to "$%02X".format(g.field(LFO_FREQ)),
            "\$809 LFO" to LFO_NAMES.getOrElse(lfo and 3) { "—" },
            "Synthesis" to "Wavetable 32×5 bit",
        )
        drawCellsLine(canvas, cells, left, top, w, h, true)
    }

    private fun drawCellsLine(canvas: Canvas, cells: Array<Pair<String, String>>, left: Float, top: Float, w: Float, h: Float, on: Boolean) {
        val cellW = w / cells.size
        val pad = density * 4
        val captionBase = top + density - captionPaint.ascent()
        val valueBase = captionBase + captionPaint.descent() - valuePaint.ascent()
        cells.forEachIndexed { idx, (caption, value) ->
            val x = left + cellW * idx + pad
            val avail = cellW - pad * 2
            drawFit(canvas, caption, x, captionBase, avail, captionPaint)
            drawFit(canvas, value, x, valueBase, avail, if (on) valuePaint else dimPaint)
            if (idx != 0) {
                canvas.drawLine(left + cellW * idx, top + pad, left + cellW * idx, top + h - pad, gridPaint)
            }
        }
    }

    // values are never cut: long ones are condensed
    private fun drawFit(canvas: Canvas, text: String, x: Float, y: Float, avail: Float, paint: Paint) {
        val width = paint.measureText(text)
        if (width <= avail || width <= 0f) {
            canvas.drawText(text, x, y, paint)
        } else {
            canvas.save()
            canvas.scale(avail / width, 1f, x, y)
            canvas.drawText(text, x, y, paint)
            canvas.restore()
        }
    }

    companion object {
        const val CHANNELS = 6
        private const val LINE_PART = 0.32f
        private const val MIN_DB = -48f
        private const val WAVE_STEPS = 32

        // noise is available only on channels 5 and 6
        private const val NOISE_CHANNEL = 4

        // see Module::VoiceState::PCE_*
        private const val WAVE = 0
        private const val FREQ = 33
        private const val VOLUME = 35
        private const val BALANCE = 36
        private const val MODE = 37
        private const val NOISE = 38
        private const val DDA = 39
        private const val MAIN = 40
        private const val LFO_FREQ = 41
        private const val LFO_CTRL = 42
        private const val M_ON = 1
        private const val M_DDA = 2
        private const val M_NOISE = 4
        private const val M_LFO = 8

        // frequency modulation depth of channel 1 by channel 2 waveform
        private val LFO_NAMES = arrayOf("off", "×1", "×16", "×256")

        fun isPce(data: ByteArray, voices: Int) = voices > 0 && VoiceGauges(data, 0).kind == VoiceGauges.Kind.PCE_WAVE

        private fun hzText(hz: Float) = if (hz >= 1000f) "%.2f kHz".format(hz / 1000f) else "%d Hz".format(hz.roundToInt())

        private fun dbText(db: Float) = if (db <= -90f) {
            "−∞ dB"
        } else {
            "%s dB".format(
                if (db >= -0.05f) "0" else "−%.1f".format(-db)
            )
        }
    }
}

/**
 * Numbers of distinct waveforms tables in order of appearance, so changes of instruments are seen
 */
internal class TableIds {
    private val known = HashMap<Long, Int>()
    private val buffer = StringBuilder()

    fun idOf(table: IntArray): Int {
        // 32 values of 5 bits are 160 bits: two longs hash is enough to distinguish real tables
        var lo = 0L
        var hi = 0L
        for (idx in 0 until 16) {
            lo = lo * 31 + table[idx]
            hi = hi * 31 + table[idx + 16]
        }
        val key = lo * 1000003 xor hi
        return known.getOrPut(key) { known.size + 1 }
    }
}

/**
 * Classification of 32 steps waveform by its shape, as musicians name them
 */
internal object WaveShape {
    fun describe(table: IntArray): String {
        val size = table.size
        val min = table.min()
        val max = table.max()
        if (min == max) {
            return "Flat"
        }
        val levels = table.distinct().size
        if (levels == 2) {
            val high = table.count { it == max }
            val duty = high * 100 / size
            return if (duty == 50) "Square" else "Pulse $duty%"
        }
        // direction changes over the cycle (table is looped)
        var ups = 0
        var downs = 0
        var bigUp = 0
        var bigDown = 0
        val jump = (max - min) / 2
        for (idx in 0 until size) {
            val d = table[(idx + 1) % size] - table[idx]
            when {
                d > jump -> ++bigUp
                d < -jump -> ++bigDown
                d > 0 -> ++ups
                d < 0 -> ++downs
            }
        }
        if (bigDown == 1 && bigUp == 0 && downs <= 1) {
            return "Saw ↑"
        }
        if (bigUp == 1 && bigDown == 0 && ups <= 1) {
            return "Saw ↓ (reversed)"
        }
        if (bigUp == 0 && bigDown == 0 && turns(table) == 2) {
            return if (isLinear(table)) "Triangle" else "Sine"
        }
        return "Custom"
    }

    private fun turns(table: IntArray): Int {
        var result = 0
        var last = 0
        for (idx in table.indices) {
            val d = Integer.signum(table[(idx + 1) % table.size] - table[idx])
            if (d != 0) {
                if (last != 0 && d != last) {
                    ++result
                }
                last = d
            }
        }
        // closing turn of the cycle
        val first = table.indices.map { Integer.signum(table[(it + 1) % table.size] - table[it]) }.firstOrNull { it != 0 } ?: 0
        if (first != 0 && last != 0 && first != last) {
            ++result
        }
        return result
    }

    // triangle has constant absolute slope, sine changes it
    private fun isLinear(table: IntArray): Boolean {
        val slopes = table.indices.map { kotlin.math.abs(table[(it + 1) % table.size] - table[it]) }.filter { it != 0 }
        return slopes.isNotEmpty() && slopes.max() - slopes.min() <= 1
    }
}
