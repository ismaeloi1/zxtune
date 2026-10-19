/**
 * @file
 * @brief Hardware specific visualization of sound chips voices state
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import app.zxtune.playback.VoiceGauges
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws live state of a single voice according to its chip:
 * - FM (YM2612/OPN, OPL2/3): operators connection schema (as in chips datasheets) with envelope levels
 * - PSG (SN76489), NES 2A03: volume steps, duty/mode glyphs, periods
 * - SPC700 S-DSP: envelope phase, ADSR/GAIN, sample source and flags
 * - MT-32: patch name, played keys, partials states
 * Values are smoothed between frames to avoid flicker of rapidly changing registers.
 */
internal class ChipWidgets(private val density: Float, textSizePx: Float) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = density * 1.2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dimStroke = Paint(stroke).apply {
        color = Color.argb(110, 255, 255, 255)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = textSizePx
        typeface = Typeface.MONOSPACE
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = textSizePx * 0.85f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val baseTextSize = textSizePx
    private val baseCaptionSize = textSizePx * 0.8f
    private val baseValueSize = textSizePx * 1.05f

    // cells captions are hints, values are the main information (as in SID cells)
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 255, 255)
        textSize = baseCaptionSize
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 255, 255, 255)
        textSize = baseValueSize
    }
    private val boldStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = density * 2.2f
        strokeCap = Paint.Cap.ROUND
    }
    private val baseLabelSize = textSizePx * 0.85f
    private val path = Path()
    private val box = RectF()
    private val boxes = Array(4) { RectF() }

    // following are not allocated at each frame
    private val area = RectF()
    private val bar = RectF()
    private val glyph = RectF()
    private val levels2 = FloatArray(2)
    private val levels4 = FloatArray(4)
    private val waves2 = IntArray(2)
    private val waves4 = IntArray(4)
    private val pressed = BooleanArray(128)
    private var memo = TextMemo()

    /**
     * Texts of widget built from registers values, rebuilt only on values change to not allocate them at each frame
     */
    class TextMemo {
        @PublishedApi
        internal val keys = LongArray(SLOTS) { Long.MIN_VALUE }

        @PublishedApi
        internal val texts = Array(SLOTS) { "" }

        inline fun get(slot: Int, key: Long, make: () -> String): String {
            if (keys[slot] != key) {
                keys[slot] = key
                texts[slot] = make()
            }
            return texts[slot]
        }

        companion object {
            const val SLOTS = 4
        }
    }

    /**
     * Short text describing current state, displayed in title band
     */
    fun summary(g: VoiceGauges) = summaryOf(g)

    /**
     * @param smooth per voice storage of displayed values, at least SMOOTH_SIZE
     * @param texts per voice storage of displayed texts
     * @return true if widget is drawn
     */
    fun draw(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray, texts: TextMemo = TextMemo()): Boolean {
        memo = texts
        when (g.kind) {
            VoiceGauges.Kind.OPN_FM -> drawOpnFm(canvas, rect, g, smooth)

            VoiceGauges.Kind.OPN_DAC -> drawDac(canvas, rect, g, smooth)

            VoiceGauges.Kind.PSG_TONE, VoiceGauges.Kind.PSG_NOISE -> drawPsg(canvas, rect, g, smooth)

            VoiceGauges.Kind.OPL_2OP, VoiceGauges.Kind.OPL_4OP -> drawOpl(canvas, rect, g, smooth)

            VoiceGauges.Kind.OPL_RHYTHM -> drawLevelBar(canvas, rect, ease(smooth, 0, g.field(1) / 255f), 24)

            VoiceGauges.Kind.SPC_DSP -> drawSpc(canvas, rect, g, smooth)

            VoiceGauges.Kind.MT32_PART -> drawMt32Part(canvas, rect, g)

            VoiceGauges.Kind.MT32_PARTIAL -> drawMt32Partial(canvas, rect, g, smooth)

            VoiceGauges.Kind.NES_PULSE, VoiceGauges.Kind.NES_TRIANGLE,
            VoiceGauges.Kind.NES_NOISE, VoiceGauges.Kind.NES_DMC -> drawNes(canvas, rect, g, smooth)

            else -> return false
        }
        return true
    }

    // FM operators schema of OPN/OPM algorithms, see OP_NAMES for operators order
    private fun drawOpnFm(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray) {
        val alg = g.field(0) and 7
        val levels = levels4
        for (op in 0 until 4) {
            levels[op] = ease(smooth, op, g.field(2 + op) / 255f)
        }
        val keys = g.field(10)
        val info = memo.get(0, alg.toLong() or (g.field(1).toLong() shl 8) or (g.field(7).toLong() shl 16)) {
            "ALG$alg FB${g.field(1)}${panText(g.field(7))}"
        }
        val schema = OPN_ALGORITHMS[alg]
        drawSchema(canvas, rect, schema, OPN_GRID, levels, keys, g.field(1), info, OP_NAMES, null)
    }

    // OPL2/OPL3 2 and 4 operators channels, FM or AM (additive) connections
    private fun drawOpl(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray) {
        val is4op = g.kind == VoiceGauges.Kind.OPL_4OP
        if (is4op) {
            val alg = g.field(0) and 3
            val levels = levels4
            val waves = waves4
            for (op in 0 until 4) {
                levels[op] = ease(smooth, op, g.field(2 + op) / 255f)
                waves[op] = g.field(6 + op)
            }
            val info = memo.get(0, alg.toLong() or (g.field(1).toLong() shl 8) or (g.field(11).toLong() shl 16)) {
                "${OPL4_NAMES[alg]} FB${g.field(1)}${panText(g.field(11))}"
            }
            val schema = OPL4_ALGORITHMS[alg]
            drawSchema(canvas, rect, schema, OPL4_GRID, levels, if (g.field(10) != 0) 0xf else 0, g.field(1), info,
                OP_NAMES, waves)
        } else {
            val additive = g.field(0) and 1
            val levels = levels2
            val waves = waves2
            for (op in 0 until 2) {
                levels[op] = ease(smooth, op, g.field(2 + op) / 255f)
                waves[op] = g.field(4 + op)
            }
            val info = memo.get(1, additive.toLong() or (g.field(1).toLong() shl 8) or (g.field(7).toLong() shl 16)) {
                "${if (additive != 0) "AM" else "FM"} FB${g.field(1)}${panText(g.field(7))}"
            }
            val schema = OPL2_ALGORITHMS[additive]
            drawSchema(canvas, rect, schema, OPL2_GRID, levels, if (g.field(6) != 0) 3 else 0, g.field(1), info,
                OP_NAMES, waves)
        }
    }

    private fun panText(pan: Int) = when (pan and 3) {
        2 -> " L"
        1 -> " R"
        0 -> " -"
        else -> ""
    }

    /**
     * Yamaha datasheet like figure: operators as boxes filled by envelope level, modulation as arrows (chains run
     * left to right, a modulator feeds operators of the row below from above), carriers are bright and connected to
     * output, feedback loop on the first operator.
     */
    private fun drawSchema(
        canvas: Canvas,
        rect: RectF,
        schema: Schema,
        grid: Schema.Grid,
        levels: FloatArray,
        keys: Int,
        feedback: Int,
        info: String,
        names: Array<String>,
        waves: IntArray?
    ) {
        val ops = levels.size
        val carriers = schema.carriers
        // space for buses and feedback loop is reserved always to keep boxes of all algorithms at the same place
        val area = drawSchemaInfo(canvas, rect, info)
        // gaps are small part of short cards, operators boxes are the main content
        val loopGap = min(density * 7, area.width() * 0.04f)
        val topGap = min(density * 5, area.height() * 0.07f)
        val busGap = min(density * 6, area.height() * 0.09f)
        area.left += loopGap
        area.top += topGap
        area.right -= density * 10
        area.bottom -= busGap
        if (area.height() < density * 12 || area.width() < density * 24) {
            return
        }
        // Box size depends only on the biggest layout of chip's algorithms, so live algorithm changes do not
        // resize boxes. The rest of cell is for arrows, they should be visible at small cards too.
        val boxH = area.height() / grid.rows * 0.72f
        val boxW = min(area.width() / grid.cols * 0.62f, boxH * 2.2f)
        val cellW = area.width() / schema.cols
        // single row figures are centered vertically
        val cellH = area.height() / grid.rows
        val rowsTop = area.top + cellH * (grid.rows - schema.rows) / 2
        for (op in 0 until ops) {
            val cx = area.left + cellW * (schema.col[op] + 0.5f)
            val cy = rowsTop + cellH * (schema.row[op] + 0.5f)
            boxes[op].set(cx - boxW / 2, cy - boxH / 2, cx + boxW / 2, cy + boxH / 2)
        }
        val arrow = min(density * 3.5f, boxH / 4)
        for (edge in schema.edges) {
            val src = boxes[edge shr 4]
            val dst = boxes[edge and 15]
            path.reset()
            if (dst.centerY() == src.centerY()) {
                // chain in a row
                path.moveTo(src.right, src.centerY())
                path.lineTo(dst.left - arrow * 0.5f, dst.centerY())
                canvas.drawPath(path, dimStroke)
                drawArrowHead(canvas, dst.left, dst.centerY(), 1f, 0f, arrow)
            } else {
                // modulator above: leaves by side facing destination (or bottom if right above) and enters it from top
                val x = dst.centerX()
                when {
                    x > src.right -> path.moveTo(src.right, src.centerY())
                    x < src.left -> path.moveTo(src.left, src.centerY())
                    else -> path.moveTo(x, src.bottom)
                }
                if (x > src.right || x < src.left) {
                    path.lineTo(x, src.centerY())
                }
                path.lineTo(x, dst.top - arrow * 0.5f)
                canvas.drawPath(path, dimStroke)
                drawArrowHead(canvas, x, dst.top, 0f, 1f, arrow)
            }
        }
        // Carriers which are the last in their rows go to the bus at right, else all carriers go down to the bus
        // under the figure (layouts keep such carriers at the bottom row, so lines never cross operators)
        val rightBus = (0 until ops).all { op ->
            carriers and (1 shl op) == 0 || (0 until ops).none {
                boxes[it].centerY() == boxes[op].centerY() && boxes[it].left > boxes[op].left
            }
        }
        var outY = 0f
        var outX = 0f
        if (rightBus) {
            val busX = area.right + density * 5
            var busTop = Float.MAX_VALUE
            var busBottom = -Float.MAX_VALUE
            for (op in 0 until ops) {
                if (carriers and (1 shl op) != 0) {
                    val b = boxes[op]
                    canvas.drawLine(b.right, b.centerY(), busX, b.centerY(), stroke)
                    busTop = min(busTop, b.centerY())
                    busBottom = max(busBottom, b.centerY())
                }
            }
            canvas.drawLine(busX, busTop, busX, busBottom, stroke)
            outX = busX
            outY = (busTop + busBottom) / 2
        } else {
            var busLeft = Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (op in 0 until ops) {
                if (carriers and (1 shl op) != 0) {
                    busLeft = min(busLeft, boxes[op].centerX())
                    bottom = max(bottom, boxes[op].bottom)
                }
            }
            outY = min(bottom + busGap, rect.bottom - stroke.strokeWidth)
            for (op in 0 until ops) {
                if (carriers and (1 shl op) != 0) {
                    val b = boxes[op]
                    canvas.drawLine(b.centerX(), b.bottom, b.centerX(), outY, stroke)
                }
            }
            outX = busLeft
        }
        if (carriers != 0) {
            canvas.drawLine(outX, outY, rect.right - arrow * 0.5f, outY, stroke)
            drawArrowHead(canvas, rect.right, outY, 1f, 0f, arrow, stroke)
        }
        // Feedback loop from output (right) of the first operator back to its input (left) over the top.
        // Connections from it start at right side or bottom below the loop, so they never cross it.
        if (feedback != 0) {
            val b = boxes[0]
            val top = b.top - topGap * 0.6f
            val left = b.left - loopGap * 0.6f
            val right = b.right + min(loopGap * 0.6f, (cellW - boxW) / 3)
            val y = b.centerY() - b.height() / 4
            path.reset()
            path.moveTo(b.right, y)
            path.lineTo(right, y)
            path.lineTo(right, top)
            path.lineTo(left, top)
            path.lineTo(left, y)
            path.lineTo(b.left - arrow * 0.5f, y)
            canvas.drawPath(path, dimStroke)
            drawArrowHead(canvas, b.left, y, 1f, 0f, arrow)
        }
        for (op in 0 until ops) {
            drawOperator(
                canvas,
                boxes[op],
                levels[op],
                carriers and (1 shl op) != 0,
                keys and (1 shl op) != 0,
                names[op],
                waves?.getOrNull(op)
            )
        }
    }

    // filled triangle with tip at (x, y) pointing to (dx, dy) direction (unit axis vector)
    private fun drawArrowHead(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, size: Float, paint: Paint = dimStroke) {
        path.reset()
        path.moveTo(x, y)
        path.lineTo(x - dx * size * 1.6f - dy * size, y - dy * size * 1.6f - dx * size)
        path.lineTo(x - dx * size * 1.6f + dy * size, y - dy * size * 1.6f + dx * size)
        path.close()
        fill.color = paint.color
        canvas.drawPath(path, fill)
    }

    /**
     * Algorithm and feedback are placed at top or (if there's no vertical space to spare) at left in two lines
     * @return area left for schema
     */
    private fun drawSchemaInfo(canvas: Canvas, rect: RectF, info: String): RectF {
        val textHeight = text.fontSpacing
        if (rect.width() < rect.height() * 3) {
            drawFit(canvas, info, rect.left, rect.top - text.ascent(), rect.width())
            area.set(rect.left, rect.top + textHeight, rect.right, rect.bottom)
            return area
        }
        val scale = min(1f, rect.height() / 2 / textHeight)
        text.textSize = baseTextSize * scale
        val infoWidth = text.measureText(INFO_TEMPLATE) + density * 4
        val split = info.indexOf(' ')
        val lineHeight = textHeight * scale
        val y = rect.centerY() - lineHeight - text.ascent()
        canvas.drawText(info, 0, split, rect.left, y, text)
        canvas.drawText(info, split + 1, info.length, rect.left, y + lineHeight, text)
        text.textSize = baseTextSize
        area.set(rect.left + infoWidth, rect.top, rect.right, rect.bottom)
        return area
    }

    private fun drawOperator(
        canvas: Canvas,
        b: RectF,
        level: Float,
        carrier: Boolean,
        key: Boolean,
        name: String,
        wave: Int?
    ) {
        // envelope level fill from bottom, carriers are brighter as they are heard directly
        fill.color = Color.argb(if (carrier) 150 else 80, 255, 255, 255)
        box.set(b.left, b.bottom - b.height() * level.coerceIn(0f, 1f), b.right, b.bottom)
        canvas.drawRect(box, fill)
        // pressed key makes operator outline bold
        val outline = if (carrier) stroke else dimStroke
        val width = outline.strokeWidth
        if (key) {
            outline.strokeWidth = width * 2
        }
        canvas.drawRect(b, outline)
        outline.strokeWidth = width
        val withWave = wave != null && b.height() > baseLabelSize * 2.2f
        // number only (as in datasheets and Furnace algorithm figures) for small boxes, as big as possible
        val small = b.height() < density * MIN_NAMED_BOX_DP
        label.textSize = min(baseLabelSize * 1.3f, b.height() * (if (withWave) {
            0.42f
        } else if (small) {
            0.75f
        } else {
            0.62f
        }))
        val shown = if (!small && label.measureText(name) <= b.width() - density * 4) name else name.substring(OP_PREFIX.length)
        if (wave != null && withWave) {
            drawOplWave(canvas, b, wave)
            canvas.drawText(shown, b.centerX(), b.bottom - density * 3, label)
        } else {
            canvas.drawText(shown, b.centerX(), b.centerY() - (label.ascent() + label.descent()) / 2, label)
        }
        label.textSize = baseLabelSize
    }

    // OPL waveforms: sine, half sine, abs sine, quarter sine, and OPL3 extensions
    private fun drawOplWave(canvas: Canvas, b: RectF, wave: Int) {
        val w = b.width() * 0.7f
        val h = b.height() * 0.22f
        val left = b.centerX() - w / 2
        val cy = b.top + b.height() * 0.32f
        path.reset()
        val steps = 24
        for (step in 0..steps) {
            val phase = step.toFloat() / steps
            val y = oplWaveform(wave and 7, phase)
            val x = left + w * phase
            if (step == 0) path.moveTo(x, cy - y * h) else path.lineTo(x, cy - y * h)
        }
        canvas.drawPath(path, stroke)
    }

    private fun drawDac(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray) {
        val value = (g.field(1) - 128) / 128f
        drawFit(canvas, if (g.field(0) != 0) "DAC on" else "DAC off", rect.left, rect.top - text.ascent(), rect.width())
        bar.set(rect.left, rect.top + text.fontSpacing, rect.right, rect.bottom)
        drawCenteredBar(canvas, bar, ease(smooth, 0, value))
    }

    // SN76489: 4 bit attenuation in 2dB steps, 10 bit period or noise mode
    private fun drawPsg(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray) {
        val att = g.field(0)
        val key = g.kind.toLong() or (att.toLong() shl 8) or (g.field(1).toLong() shl 16) or
            (g.field(2).toLong() shl 24) or (g.field(3).toLong() shl 32)
        val info = memo.get(0, key) {
            if (g.kind == VoiceGauges.Kind.PSG_TONE) {
                val period = g.field(1) or (g.field(2) shl 8)
                "N=%03X ATT %d%s".format(period, att, stereoText(g.field(3)))
            } else {
                "%s %s ATT %d%s".format(
                    if (g.field(1) != 0) "WHITE" else "PERIODIC",
                    NOISE_RATES.getOrElse(g.field(2)) { "?" },
                    att,
                    stereoText(g.field(3))
                )
            }
        }
        drawFit(canvas, info, rect.left, rect.top - text.ascent(), rect.right - (rect.left))
        bar.set(rect.left, rect.top + text.fontSpacing, rect.right, rect.bottom)
        drawSegments(canvas, bar, ease(smooth, 0, (15 - att) / 15f), 15)
    }

    private fun stereoText(bits: Int) = when (bits and 3) {
        3 -> ""
        2 -> " L"
        1 -> " R"
        else -> " -"
    }

    // S-DSP: ENVX with envelope phase, ADSR or GAIN registers, sample source and rate
    // S-DSP voice as cells of captions and values (as SID voice cells) over registers: sample, rate, envelope stage
    // and level, ADSR shape with current stage, stereo volumes balance and echo/pitch modulation/noise switches
    private fun drawSpc(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray) {
        val envx = ease(smooth, 0, g.field(0) / 127f)
        val mode = g.field(1)
        val adsr1 = g.field(2)
        val adsr2 = g.field(3)
        val pitch = g.field(8) or (g.field(9) shl 8)
        val flags = g.field(10)
        val barW = min(rect.width() * 0.08f, density * 12)
        bar.set(rect.left, rect.top, rect.left + barW, rect.bottom)
        drawVerticalBar(canvas, bar, envx)
        val left = bar.right + density * 6
        val width = rect.right - left
        val rowH = rect.height() * 0.48f
        val gap = density * 4
        val captionSize = min(baseCaptionSize, rowH * 0.3f)
        val valueSize = min(baseValueSize, rowH * 0.5f)
        caption.textSize = captionSize
        value.textSize = valueSize
        // first row: values
        var x = left
        val y = rect.top
        val sample = memo.get(0, g.field(5).toLong()) { "%02X".format(g.field(5)) }
        x = drawCell(canvas, "Sample", sample, x, y, width * 0.2f - gap, rowH) + gap
        val rate = memo.get(1, pitch.toLong()) { "%.1fk".format(pitch * 32f / 4096f) }
        x = drawCell(canvas, "Rate", rate, x, y, width * 0.24f - gap, rowH) + gap
        val stage = if (mode == 4) {
            memo.get(2, g.field(4).toLong()) { "Gain %02X".format(g.field(4)) }
        } else {
            ENV_STAGES.getOrElse(mode) { "?" }
        }
        x = drawCell(canvas, "Envelope", stage, x, y, width * 0.32f - gap, rowH) + gap
        val level = memo.get(3, g.field(0).toLong()) { "${g.field(0) * 100 / 127}%" }
        drawCell(canvas, "Level", level, x, y, rect.right - x, rowH)
        // second row: graphics
        val graphTop = rect.top + rowH + captionSize * 1.2f
        val graphBottom = rect.bottom - density * 2
        if (graphBottom - graphTop < density * 6) {
            caption.textSize = baseCaptionSize
            value.textSize = baseValueSize
            return
        }
        x = left
        val y2 = rect.top + rowH
        if (mode == 4) {
            drawCaption(canvas, "Gain", x, y2, width * 0.36f - gap)
            glyph.set(x, graphTop, x + width * 0.36f - gap, graphBottom)
            drawLevelBar(canvas, glyph, g.field(4) / 255f, 16)
        } else {
            drawCaption(canvas, "ADSR", x, y2, width * 0.36f - gap)
            glyph.set(x, graphTop, x + width * 0.36f - gap, graphBottom)
            drawAdsr(canvas, glyph, adsr1 and 15, (adsr1 shr 4) and 7, adsr2 shr 5, adsr2 and 31, mode)
        }
        x += width * 0.36f
        drawCaption(canvas, "Volume L/R", x, y2, width * 0.26f - gap)
        glyph.set(x, graphTop, x + width * 0.26f - gap, graphBottom)
        drawBalance(canvas, glyph, g.signedField(6), g.signedField(7))
        x += width * 0.26f
        glyph.set(x, graphTop, rect.right, graphBottom)
        drawSwitches(canvas, glyph, flags)
        caption.textSize = baseCaptionSize
        value.textSize = baseValueSize
    }

    private fun drawCaption(canvas: Canvas, title: String, x: Float, top: Float, w: Float) {
        val count = caption.breakText(title, true, w, null)
        canvas.drawText(title, 0, count, x, top - caption.ascent(), caption)
    }

    // @return right edge of cell
    private fun drawCell(canvas: Canvas, title: String, text: String, x: Float, top: Float, w: Float, h: Float): Float {
        drawCaption(canvas, title, x, top, w)
        val baseline = top - caption.ascent() + caption.descent() - value.ascent()
        if (baseline < top + h + density) {
            val count = value.breakText(text, true, w, null)
            canvas.drawText(text, 0, count, x, baseline, value)
        }
        return x + w
    }

    // Stylized envelope: segments lengths follow rates (faster is shorter), current stage is bright
    private fun drawAdsr(canvas: Canvas, r: RectF, attack: Int, decay: Int, sustain: Int, release: Int, mode: Int) {
        val a = (16 - attack) / 16f
        val d = (8 - decay) / 8f
        val rl = (32 - release) / 32f
        val total = a + d + 0.6f + rl
        val unit = r.width() / total
        val sustainY = r.bottom - r.height() * (sustain + 1) / 8f
        val x1 = r.left + a * unit
        val x2 = x1 + d * unit
        val x3 = x2 + 0.6f * unit
        val points = floatArrayOf(r.left, r.bottom, x1, r.top, x2, sustainY, x3, sustainY, r.right, r.bottom)
        // stages order of ENV_STAGES: release, attack, decay, sustain
        val current = when (mode) {
            1 -> 0
            2 -> 1
            3 -> 2
            0 -> 3
            else -> -1
        }
        for (seg in 0 until 4) {
            canvas.drawLine(points[seg * 2], points[seg * 2 + 1], points[seg * 2 + 2], points[seg * 2 + 3], if (seg == current) boldStroke else dimStroke)
        }
    }

    // Left and right volumes from center, inverted phase (negative volume, used for surround) is dim
    private fun drawBalance(canvas: Canvas, r: RectF, left: Int, right: Int) {
        val cx = r.centerX()
        val h = min(r.height(), density * 10)
        val top = r.centerY() - h / 2
        canvas.drawLine(cx, r.top, cx, r.bottom, dimStroke)
        fill.color = Color.argb(if (left < 0) 90 else 200, 255, 255, 255)
        box.set(cx - r.width() / 2 * min(abs(left), 127) / 127f, top, cx, top + h)
        canvas.drawRect(box, fill)
        fill.color = Color.argb(if (right < 0) 90 else 200, 255, 255, 255)
        box.set(cx, top, cx + r.width() / 2 * min(abs(right), 127) / 127f, top + h)
        canvas.drawRect(box, fill)
    }

    // switches as badges in a row, bright when on
    private fun drawSwitches(canvas: Canvas, r: RectF, flags: Int) {
        val cellW = r.width() / SPC_SWITCHES.size
        label.textSize = min(baseLabelSize, r.height() * 0.7f)
        while (label.textSize > 4 && label.measureText("NOISE") > cellW - density * 4) {
            label.textSize *= 0.9f
        }
        for ((idx, name) in SPC_SWITCHES.withIndex()) {
            val on = flags and (1 shl idx) != 0
            box.set(r.left + cellW * idx + density, r.top, r.left + cellW * (idx + 1) - density, r.bottom)
            if (on) {
                fill.color = Color.argb(200, 255, 255, 255)
                canvas.drawRect(box, fill)
            } else {
                canvas.drawRect(box, dimStroke)
            }
            label.color = if (on) Color.BLACK else Color.argb(110, 255, 255, 255)
            canvas.drawText(name, box.centerX(), box.centerY() - (label.ascent() + label.descent()) / 2, label)
        }
        label.color = Color.WHITE
        label.textSize = baseLabelSize
    }

    private fun drawMt32Part(canvas: Canvas, rect: RectF, g: VoiceGauges) {
        val notes = g.field(0)
        val partials = g.field(9)
        val keys = memo.get(0, notes.toLong() or (partials.toLong() shl 8)) { "KEYS %d PARTIALS %d".format(notes, partials) }
        drawFit(canvas, keys, rect.left, rect.top - text.ascent(), rect.right - (rect.left))
        val kb = bar
        kb.set(rect.left, rect.top + text.fontSpacing, rect.right, rect.bottom)
        if (kb.height() < density * 8) {
            return
        }
        pressed.fill(false)
        for (idx in 1..minOf(notes, 8)) {
            g.field(idx).takeIf { it != 0 }?.let { pressed[it and 127] = true }
        }
        drawKeyboard(canvas, kb, pressed)
    }

    private fun drawKeyboard(canvas: Canvas, kb: RectF, pressed: BooleanArray) {
        // C2..C7 covers most of melodic parts
        val firstKey = 36
        val lastKey = 96
        val whites = (firstKey..lastKey).count { !isBlack(it) }
        val keyW = kb.width() / whites
        var white = 0
        for (key in firstKey..lastKey) {
            if (isBlack(key)) continue
            box.set(kb.left + keyW * white, kb.top, kb.left + keyW * (white + 1), kb.bottom)
            if (pressed[key]) {
                fill.color = Color.WHITE
                canvas.drawRect(box, fill)
            }
            canvas.drawRect(box, dimStroke)
            ++white
        }
        white = 0
        for (key in firstKey..lastKey) {
            if (!isBlack(key)) {
                ++white
                continue
            }
            val x = kb.left + keyW * white
            box.set(x - keyW * 0.32f, kb.top, x + keyW * 0.32f, kb.top + kb.height() * 0.6f)
            fill.color = if (pressed[key]) Color.WHITE else Color.BLACK
            canvas.drawRect(box, fill)
            canvas.drawRect(box, dimStroke)
        }
    }

    private fun isBlack(key: Int) = BLACK_KEYS[key % 12]

    private fun drawMt32Partial(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray) {
        // brightness follows envelope phase
        val target = when (g.field(0)) {
            1 -> 1f
            2 -> 0.7f
            3 -> 0.3f
            else -> 0f
        }
        drawSegments(canvas, rect, ease(smooth, 0, target), 10)
    }

    // 2A03: duty cycle glyph, 4 bit volume/envelope, sweep and DMC state
    private fun drawNes(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray) {
        val glyphW = min(rect.width() * 0.3f, rect.height() * 1.6f)
        glyph.set(rect.left, rect.top, rect.left + glyphW, rect.bottom)
        val x = glyph.right + density * 6
        bar.set(x, rect.top + text.fontSpacing, rect.right, rect.bottom)
        when (g.kind) {
            VoiceGauges.Kind.NES_PULSE -> {
                val duty = g.field(0) and 3
                drawPulseGlyph(canvas, glyph, DUTIES[duty])
                val period = g.field(3) or (g.field(4) shl 8)
                val key = duty.toLong() or (g.field(2).toLong() shl 8) or (g.field(6).toLong() shl 16) or
                    (period.toLong() shl 24)
                val info = memo.get(0, key) {
                    "%s %s%s P=%03X".format(
                        DUTY_NAMES[duty],
                        if (g.field(2) != 0) "VOL" else "ENV",
                        if (g.field(6) != 0) " SWP" else "",
                        period
                    )
                }
                drawFit(canvas, info, x, rect.top - text.ascent(), rect.right - (x))
                drawSegments(canvas, bar, ease(smooth, 0, g.field(1) / 15f), 15)
            }

            VoiceGauges.Kind.NES_TRIANGLE -> {
                drawTriangleGlyph(canvas, glyph)
                val period = g.field(1) or (g.field(2) shl 8)
                val info = memo.get(1, g.field(0).toLong() or (period.toLong() shl 8)) {
                    "LIN %s P=%03X".format(if (g.field(0) != 0) "on" else "off", period)
                }
                drawFit(canvas, info, x, rect.top - text.ascent(), rect.right - (x))
                drawSegments(canvas, bar, ease(smooth, 0, if (g.isKeyOn) 1f else 0f), 15)
            }

            VoiceGauges.Kind.NES_NOISE -> {
                drawNoiseGlyph(canvas, glyph, g.field(1) != 0)
                val info = memo.get(2, g.field(1).toLong() or (g.field(2).toLong() shl 8)) {
                    "%s RATE %X".format(if (g.field(1) != 0) "SHORT" else "LONG", g.field(2))
                }
                drawFit(canvas, info, x, rect.top - text.ascent(), rect.right - (x))
                drawSegments(canvas, bar, ease(smooth, 0, g.field(0) / 15f), 15)
            }

            else -> {
                val key = g.field(1).toLong() or (g.field(2).toLong() shl 8) or (g.field(3).toLong() shl 16)
                val info = memo.get(3, key) {
                    "RATE %X%s%s".format(g.field(1), if (g.field(3) != 0) " LOOP" else "", if (g.field(2) != 0) " PLAY" else "")
                }
                drawFit(canvas, info, glyph.left, rect.top - text.ascent(), rect.right - (glyph.left))
                bar.set(glyph.left, bar.top, rect.right, rect.bottom)
                drawLevelBar(
                    canvas,
                    bar,
                    ease(smooth, 0, g.field(0) / 127f),
                    32
                )
            }
        }
    }

    private fun drawPulseGlyph(canvas: Canvas, r: RectF, duty: Float) {
        val top = r.top + r.height() * 0.25f
        val bottom = r.bottom - r.height() * 0.25f
        path.reset()
        path.moveTo(r.left, bottom)
        for (period in 0 until 2) {
            val start = r.left + r.width() / 2 * period
            val high = start + r.width() / 2 * duty
            path.lineTo(start, bottom)
            path.lineTo(start, top)
            path.lineTo(high, top)
            path.lineTo(high, bottom)
        }
        path.lineTo(r.right, bottom)
        canvas.drawPath(path, stroke)
    }

    private fun drawTriangleGlyph(canvas: Canvas, r: RectF) {
        val top = r.top + r.height() * 0.25f
        val bottom = r.bottom - r.height() * 0.25f
        path.reset()
        path.moveTo(r.left, bottom)
        path.lineTo(r.left + r.width() * 0.25f, top)
        path.lineTo(r.left + r.width() * 0.75f, bottom)
        path.lineTo(r.right, top)
        canvas.drawPath(path, stroke)
    }

    // short mode has 93 steps period, so drawn regular
    private fun drawNoiseGlyph(canvas: Canvas, r: RectF, short: Boolean) {
        val top = r.top + r.height() * 0.25f
        val bottom = r.bottom - r.height() * 0.25f
        val pattern = if (short) SHORT_NOISE else LONG_NOISE
        path.reset()
        pattern.forEachIndexed { idx, bit ->
            val x = r.left + r.width() * idx / pattern.size
            val y = if (bit) top else bottom
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            path.lineTo(r.left + r.width() * (idx + 1) / pattern.size, y)
        }
        canvas.drawPath(path, stroke)
    }

    private fun drawSegments(canvas: Canvas, r: RectF, value: Float, segments: Int) {
        if (r.height() < density * 3) return
        val gap = density
        val segW = (r.width() - gap * (segments - 1)) / segments
        val lit = value * segments
        for (seg in 0 until segments) {
            val left = r.left + (segW + gap) * seg
            box.set(left, r.top, left + segW, r.bottom)
            val part = (lit - seg).coerceIn(0f, 1f)
            fill.color = Color.argb((40 + 200 * part).toInt(), 255, 255, 255)
            canvas.drawRect(box, fill)
        }
    }

    private fun drawLevelBar(canvas: Canvas, r: RectF, value: Float, segments: Int) = drawSegments(canvas, r, value.coerceIn(0f, 1f), segments)

    private fun drawVerticalBar(canvas: Canvas, r: RectF, value: Float) {
        canvas.drawRect(r, dimStroke)
        box.set(r.left, r.bottom - r.height() * value.coerceIn(0f, 1f), r.right, r.bottom)
        fill.color = Color.argb(200, 255, 255, 255)
        canvas.drawRect(box, fill)
    }

    private fun drawCenteredBar(canvas: Canvas, r: RectF, value: Float) {
        canvas.drawRect(r, dimStroke)
        val cx = r.centerX()
        val x = cx + r.width() / 2 * value.coerceIn(-1f, 1f)
        box.set(min(cx, x), r.top, max(cx, x), r.bottom)
        fill.color = Color.argb(200, 255, 255, 255)
        canvas.drawRect(box, fill)
        canvas.drawLine(cx, r.top, cx, r.bottom, dimStroke)
    }

    // shrinks text (down to 70%) and then cuts it to fit width
    private fun drawFit(canvas: Canvas, str: String, x: Float, y: Float, maxWidth: Float) {
        val width = text.measureText(str)
        if (width <= maxWidth) {
            canvas.drawText(str, x, y, text)
            return
        }
        text.textSize = baseTextSize * max(maxWidth / width, 0.7f)
        val count = text.breakText(str, true, maxWidth, null)
        canvas.drawText(str, 0, count, x, y, text)
        text.textSize = baseTextSize
    }

    // exponential approach gives fluid motion at any refresh rate
    private fun ease(smooth: FloatArray, idx: Int, target: Float): Float {
        val value = smooth[idx] + (target - smooth[idx]) * EASING
        smooth[idx] = value
        return value
    }

    /**
     * Operators placement on grid and modulation connections (source shl 4 or destination)
     */
    class Schema(
        val col: FloatArray,
        val row: FloatArray,
        val edges: IntArray,
        val carriers: Int
    ) {
        val cols = (col.maxOrNull() ?: 0f).toInt() + 1
        val rows = ((row.maxOrNull() ?: 0f) + 1f).toInt().coerceAtLeast(1)

        /**
         * The biggest figure among chip's algorithms
         */
        class Grid(family: Array<Schema>) {
            val cols = family.maxOf { it.cols }
            val rows = family.maxOf { it.rows }
        }
    }

    companion object {
        const val SMOOTH_SIZE = 8

        // for ~30fps updates of dashboard
        private const val EASING = 0.8f

        /*
         * Operators are numbered as in Furnace tracker (OP1..OP4, src/gui/guiConst.cpp opOrder), Nuked-OPN2 and
         * YM2151 datasheet (M1 C1 M2 C2): by connection order (OP1 > OP2 > OP3 > OP4 in ALG 0), not registers order.
         * Registers order is OP1 OP3 OP2 OP4 (OPN, +0/+4/+8/+C) and M1 M2 C1 C2 (OPM), so emulation cores report
         * levels and keys already reordered (FM_SLOTS in fmopn.c, OPS in ym2151.c). Yamaha's S1..S4 names are
         * avoided, as documents disagree on S2/S3 order. OPL operators follow registers order (OPL3 datasheet).
         */
        private const val OP_PREFIX = "OP"
        private val OP_NAMES = Array(4) { "$OP_PREFIX${it + 1}" }
        private val OPL4_NAMES = arrayOf("FM-FM", "AM-FM", "FM-AM", "AM-AM")

        // operator box height to fit name, smaller ones are labeled by number
        private const val MIN_NAMED_BOX_DP = 20

        // the widest line of algorithm and feedback info
        private const val INFO_TEMPLATE = "FB7 -"

        /*
         * Hand placed figures after Yamaha datasheets (YM2612/YM2151 algorithm figures, OPL3 4 operators connections)
         * and Furnace drawAlgorithm (src/gui/insEdit/insTabFM.cpp), connections are as in MAME fmopn.c
         * setup_connection and Nuked-OPN2 fm_algorithm. Cards are wide and short, so at most two rows are used:
         * the bottom row is the main chain or parallel carriers, modulators of other branches are above their
         * destinations. Columns and rows are 0-based, operators are 0-based in edges.
         */
        private val OPN_ALGORITHMS = arrayOf(
            // 1 > 2 > 3 > 4
            Schema(floatArrayOf(0f, 1f, 2f, 3f), floatArrayOf(0f, 0f, 0f, 0f), intArrayOf(0x01, 0x12, 0x23), 8),
            // (1 + 2) > 3 > 4
            Schema(floatArrayOf(0f, 0f, 1f, 2f), floatArrayOf(0f, 1f, 1f, 1f), intArrayOf(0x02, 0x12, 0x23), 8),
            // 1 + (2 > 3) > 4
            Schema(floatArrayOf(1f, 0f, 1f, 2f), floatArrayOf(0f, 1f, 1f, 1f), intArrayOf(0x03, 0x12, 0x23), 8),
            // (1 > 2) + 3 > 4
            Schema(floatArrayOf(0f, 1f, 1f, 2f), floatArrayOf(0f, 0f, 1f, 1f), intArrayOf(0x01, 0x13, 0x23), 8),
            // (1 > 2) + (3 > 4)
            Schema(floatArrayOf(0f, 1f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 1f), intArrayOf(0x01, 0x23), 10),
            // 1 > (2 + 3 + 4)
            Schema(floatArrayOf(1f, 0f, 1f, 2f), floatArrayOf(0f, 1f, 1f, 1f), intArrayOf(0x01, 0x02, 0x03), 14),
            // (1 > 2) + 3 + 4
            Schema(floatArrayOf(0f, 0f, 1f, 2f), floatArrayOf(0f, 1f, 1f, 1f), intArrayOf(0x01), 14),
            // 1 + 2 + 3 + 4
            Schema(floatArrayOf(0f, 1f, 2f, 3f), floatArrayOf(0f, 0f, 0f, 0f), intArrayOf(), 15),
        )

        // OPL 2 operators: FM (modulator -> carrier) or AM (both heard)
        private val OPL2_ALGORITHMS = arrayOf(
            Schema(floatArrayOf(0f, 1f), floatArrayOf(0f, 0f), intArrayOf(0x01), 2),
            Schema(floatArrayOf(0f, 1f), floatArrayOf(0f, 0f), intArrayOf(), 3),
        )

        // OPL3 4 operators (CNT of the first and second pairs), see OPL3 datasheet and Furnace FM_ALGS_4OP_OPL
        private val OPL4_ALGORITHMS = arrayOf(
            // FM-FM: 1 > 2 > 3 > 4
            Schema(floatArrayOf(0f, 1f, 2f, 3f), floatArrayOf(0f, 0f, 0f, 0f), intArrayOf(0x01, 0x12, 0x23), 8),
            // AM-FM: 1 + (2 > 3 > 4)
            Schema(floatArrayOf(0f, 0f, 1f, 2f), floatArrayOf(0f, 1f, 1f, 1f), intArrayOf(0x12, 0x23), 9),
            // FM-AM: (1 > 2) + (3 > 4)
            Schema(floatArrayOf(0f, 1f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 1f), intArrayOf(0x01, 0x23), 10),
            // AM-AM: 1 + (2 > 3) + 4
            Schema(floatArrayOf(0f, 1f, 1f, 2f), floatArrayOf(1f, 0f, 1f, 1f), intArrayOf(0x12), 13),
        )

        private val OPN_GRID = Schema.Grid(OPN_ALGORITHMS)
        private val OPL2_GRID = Schema.Grid(OPL2_ALGORITHMS)
        private val OPL4_GRID = Schema.Grid(OPL4_ALGORITHMS)

        private val RHYTHM_NAMES = arrayOf("Bass drum", "Snare", "Tom", "Cymbal", "Hi-hat")
        private val PARTIAL_STATES = arrayOf("-", "ATK", "SUS", "REL")
        private val ENV_STAGES = arrayOf("Release", "Attack", "Decay", "Sustain")
        private val SPC_SWITCHES = arrayOf("ECHO", "PMOD", "NOISE")
        private val NOISE_RATES = arrayOf("N/512", "N/1024", "N/2048", "T3")
        private val DUTIES = floatArrayOf(0.125f, 0.25f, 0.5f, 0.75f)
        private val DUTY_NAMES = arrayOf("12%", "25%", "50%", "75%")
        private val LONG_NOISE = booleanArrayOf(
            true, false, false, true, true, false, true, false, false, false, true, true, false, true, true, false
        )
        private val SHORT_NOISE = booleanArrayOf(
            true, false, true, false, true, false, true, false, true, false, true, false, true, false, true, false
        )

        private val NOTES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        private val BLACK_KEYS = BooleanArray(12) { it == 1 || it == 3 || it == 6 || it == 8 || it == 10 }

        fun noteName(hz: Float): String {
            if (hz <= 0f) return ""
            return noteName(semitones(hz).roundToInt())
        }

        /**
         * @param note semitones from C0
         */
        fun noteName(note: Int) = if (note in 0..131) "${NOTES[note % 12]}${note / 12}" else ""

        fun semitones(hz: Float) = 12 * log2(hz / 440f) + 57

        fun summaryOf(g: VoiceGauges) = when (g.kind) {
            VoiceGauges.Kind.MT32_PART -> g.text.ifEmpty { "-" }
            VoiceGauges.Kind.MT32_PARTIAL -> partialSummary(g)
            VoiceGauges.Kind.OPL_RHYTHM -> RHYTHM_NAMES.getOrElse(g.field(0)) { "" }
            VoiceGauges.Kind.OPN_DAC -> if (g.field(0) != 0) "PCM" else "off"
            else -> noteSummary(g.summaryMode, g.frequency.roundToInt(), noteName(g.frequency), g.level.roundToInt())
        }

        private val VoiceGauges.summaryMode
            get() = when {
                isNoise && !hasFrequency -> SUMMARY_NOISE
                (!hasFrequency || frequency <= 0f) && hasLevel -> SUMMARY_LEVEL
                !hasFrequency || frequency <= 0f -> SUMMARY_NONE
                isNoise -> SUMMARY_NOISE_RATE
                else -> SUMMARY_NOTE
            }

        private const val SUMMARY_NOISE = 0
        private const val SUMMARY_LEVEL = 1
        private const val SUMMARY_NONE = 2
        private const val SUMMARY_NOISE_RATE = 3
        private const val SUMMARY_NOTE = 4

        private fun noteSummary(mode: Int, hz: Int, note: String, levelDb: Int) = when (mode) {
            SUMMARY_NOISE -> "noise"
            SUMMARY_LEVEL -> if (levelDb > -96) "${levelDb}dB" else "off"
            SUMMARY_NONE -> ""
            SUMMARY_NOISE_RATE -> "noise ${hz}Hz"
            else -> "$note ${hz}Hz"
        }

        private fun partialSummary(g: VoiceGauges): String {
            val state = PARTIAL_STATES.getOrElse(g.field(0)) { "" }
            val owner = g.field(1)
            return when {
                g.field(0) == 0 -> state
                owner == 9 -> "$state rhythm"
                g.hasFrequency -> "$state P$owner ${noteName(g.frequency)}"
                else -> "$state P$owner"
            }
        }

        // phase 0..1 of one period
        fun oplWaveform(wave: Int, phase: Float): Float {
            val s = sin(2 * PI * phase).toFloat()
            return when (wave) {
                0 -> s
                1 -> max(s, 0f)
                2 -> abs(s)
                3 -> if ((phase * 4).toInt() % 2 == 0) abs(s) else 0f
                4 -> if (phase < 0.5f) sin(4 * PI * phase).toFloat() else 0f
                5 -> if (phase < 0.5f) abs(sin(4 * PI * phase).toFloat()) else 0f
                6 -> if (phase < 0.5f) 1f else -1f
                else -> if (phase < 0.5f) 1f - phase * 2 else -(phase - 0.5f) * 2
            }
        }
    }

    /**
     * Title band summary of voice card, rebuilt only when its displayed values are changed to avoid allocations per
     * frame. Values are shown without delay, only the note is kept through vibrato (see [SidRowText.note]).
     */
    class LiveSummary {
        private var key = Long.MIN_VALUE
        private var note = SidRowText.NONE
        var text = ""
            private set

        fun update(g: VoiceGauges) {
            val kind = g.kind
            if (!kind.hasNoteSummary) {
                val current = summaryOf(g)
                if (current != text) {
                    text = current
                }
                key = Long.MIN_VALUE
                return
            }
            val mode = g.summaryMode
            val hasPitch = g.hasFrequency && g.frequency > 0f
            note = if (hasPitch) SidRowText.note(semitones(g.frequency), note) else SidRowText.NONE
            val hz = if (hasPitch) g.frequency.roundToInt() else 0
            val level = if (g.hasLevel) g.level.roundToInt() else 0
            val current = (kind.toLong() shl 56) or (mode.toLong() shl 48) or ((note.toLong() and 0xff) shl 40) or
                ((hz.toLong() and 0xffffff) shl 16) or (level.toLong() and 0xffff)
            if (current != key) {
                key = current
                text = noteSummary(mode, hz, if (note == SidRowText.NONE) "" else noteName(note), level)
            }
        }

        private val Int.hasNoteSummary
            get() = this != VoiceGauges.Kind.MT32_PART && this != VoiceGauges.Kind.MT32_PARTIAL &&
                this != VoiceGauges.Kind.OPL_RHYTHM && this != VoiceGauges.Kind.OPN_DAC
    }
}
