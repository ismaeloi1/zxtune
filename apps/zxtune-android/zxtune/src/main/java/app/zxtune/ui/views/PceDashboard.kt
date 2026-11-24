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
    private val cursorPaint = Paint().apply {
        color = Color.argb(110, 0, 229, 255)
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
        val captionSize = min(textSize * 0.62f, rowH * 0.16f)
        val valueSize = min(textSize * 0.95f, rowH * 0.24f)
        captionPaint.textSize = captionSize
        valuePaint.textSize = valueSize
        dimPaint.textSize = valueSize
        titlePaint.textSize = min(textSize, rowH * 0.22f)
        val waveW = min(rowH * 2.4f, width * 0.40f)
        for (ch in 0 until channels) {
            val g = VoiceGauges(data, ch * VoiceGauges.SIZE)
            val top = rowH * ch
            drawWavePanel(canvas, g, ch, 0f, top, waveW, rowH)
            drawChannelCells(canvas, g, ch, waveW, top, width - waveW, rowH)
            canvas.drawLine(0f, top + rowH, width, top + rowH, gridPaint)
        }
        if (channels > 0) {
            drawGlobal(canvas, VoiceGauges(data, 0), 0f, rowH * channels, width, height - rowH * channels)
        }
    }

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
        if (playing) {
            val pos = g.field(POS) and 31
            canvas.drawRect(rect.left + step * pos, rect.top, rect.left + step * (pos + 1), rect.bottom, cursorPaint)
        }
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
        if (mode and M_DDA != 0 && mode and M_ON != 0) {
            // direct value as a level line over the table
            val y = rect.bottom - (g.field(DDA) and 31) * rect.height() / 31f
            canvas.drawLine(rect.left, y, rect.right, y, cursorPaint.apply { strokeWidth = density * 3 })
        }
    }

    private fun drawChannelCells(canvas: Canvas, g: VoiceGauges, ch: Int, left: Float, top: Float, w: Float, h: Float) {
        val mode = g.field(MODE)
        val on = mode and M_ON != 0
        val divider = g.field(FREQ) or ((g.field(FREQ + 1) and 0x0f) shl 8)
        val hz = if (g.hasFrequency) g.frequency else 0f
        val volume = g.field(VOLUME) and 31
        val balance = g.field(BALANCE)
        val first = arrayOf(
            "\$804 Mode" to (if (!on) {
                "Off"
            } else if (mode and M_DDA != 0) {
                "DDA"
            } else if (mode and M_NOISE != 0) {
                "Noise"
            } else {
                "Wave"
            }),
            "Note" to (if (hz > 0f) ChipWidgets.noteName(hz).ifEmpty { "—" } else "—"),
            "\$802/3 Freq" to "$%03X".format(divider),
            "Pitch" to (if (hz > 0f) hzText(hz) else "—"),
            "\$804 Vol" to "%d · %s".format(volume, dbText(-1.5f * (31 - volume))),
        )
        val second = arrayOf(
            "\$805 Pan" to "L%d R%d".format(balance shr 4, balance and 15),
            "\$807 Noise" to (if (ch >= NOISE_CHANNEL) "$%02X".format(g.field(NOISE)) + if (mode and M_NOISE != 0) " on" else "" else "—"),
            "\$806 DDA" to (if (mode and M_DDA != 0) "$%02X".format(g.field(DDA)) else "—"),
            "Level" to (if (g.hasLevel) dbText(g.level) else "—"),
            "LFO" to when {
                ch == 0 && mode and M_LFO != 0 -> "by ch 2"
                ch == 1 && g.field(LFO_CTRL) != 0 -> "→ ch 1"
                else -> "—"
            },
        )
        val lineH = h / 2
        drawCellsLine(canvas, first, left, top, w, lineH, on)
        drawCellsLine(canvas, second, left, top + lineH, w, lineH, on)
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
        val captionBase = top + pad - captionPaint.ascent()
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
        private const val WAVE_STEPS = 32

        // noise is available only on channels 5 and 6
        private const val NOISE_CHANNEL = 4

        // see Module::VoiceState::PCE_*
        private const val WAVE = 0
        private const val POS = 32
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
