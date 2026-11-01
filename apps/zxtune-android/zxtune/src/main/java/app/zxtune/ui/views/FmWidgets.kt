/**
 * @file
 * @brief FM channels algorithm figures (OPN, OPM, OPL) for dashboard cards and channel inspector
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import app.zxtune.playback.VoiceGauges
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Yamaha datasheet like figure of channel's operators: boxes filled by live output level, modulation as arrows (chains
 * run left to right, a modulator feeds operators of the row below from above), feedback loop on OP1.
 * Carriers (heard directly) are solid bright boxes connected to output, modulators (only change timbre of the operator
 * they point to) are dim dashed boxes, so the role is visible without knowing the algorithm.
 */
internal class FmWidgets(private val density: Float, textSizePx: Float) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = density * 1.2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dimStroke = Paint(stroke).apply {
        color = Color.argb(120, 255, 255, 255)
    }

    // modulators outline
    private val dashStroke = Paint(dimStroke).apply {
        color = Color.argb(150, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(density * 3, density * 2), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = textSizePx * 0.85f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val infoKey = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        textSize = textSizePx * 0.85f
    }
    private val infoValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = textSizePx * 1.05f
    }
    private val baseLabelSize = textSizePx * 0.85f
    private val baseKeySize = infoKey.textSize
    private val baseValueSize = infoValue.textSize
    private val path = Path()
    private val box = RectF()
    private val area = RectF()
    private val channel = FmChannel()
    private val levels = FloatArray(4)

    /** Operators boxes of the last drawn figure, in OP1..OP4 order */
    val boxes = Array(4) { RectF() }

    /** Operators count of the last drawn figure */
    var drawnOperators = 0
        private set

    private var texts = ChipWidgets.TextMemo()

    /**
     * Compact form for dashboard card: algorithm and feedback, figure with live levels
     */
    fun drawCard(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray, memo: ChipWidgets.TextMemo) {
        texts = memo
        channel.update(g)
        easeLevels(channel, smooth)
        val alg = channel.algorithm
        val fb = channel.feedback
        val pan = if (channel.hasPan) channel.pan else 3
        val key = alg.toLong() or (fb.toLong() shl 8) or (pan.toLong() shl 16) or (g.kind.toLong() shl 24)
        val first = texts.get(0, key) { algorithmName(channel) }
        val second = texts.get(1, key) { "$fb${PAN_MARKS[pan]}" }
        val figure = drawInfo(canvas, rect, first, second)
        // chevron as in list items tells that card opens channel inspector by tap
        val chevronW = infoValue.measureText(CHEVRON) + density * 4
        if (figure.width() > chevronW * 8) {
            canvas.drawText(CHEVRON, figure.right - chevronW + density * 4, figure.centerY() - (infoValue.ascent() + infoValue.descent()) / 2, infoKey.apply { textSize = baseValueSize * 1.3f })
            infoKey.textSize = baseKeySize
            figure.right -= chevronW + density * 4
        }
        drawDiagram(canvas, figure, channel, levels, -1, false)
    }

    // exponential approach gives fluid motion at any refresh rate
    fun easeLevels(ch: FmChannel, smooth: FloatArray) {
        for (op in 0 until ch.operatorsCount) {
            val target = ch.operators[op].level / 255f
            smooth[op] += (target - smooth[op]) * EASING
            levels[op] = smooth[op]
        }
    }

    /** Eased levels of the last [easeLevels] call */
    val easedLevels: FloatArray
        get() = levels

    /**
     * "Alg"/"FB" keys with values at top (tall cards) or at left in two lines
     * @return area left for figure
     */
    private fun drawInfo(canvas: Canvas, rect: RectF, first: String, second: String): RectF {
        val keyFirst = if (channel.isOpl) "" else ALG_KEY
        if (rect.width() < rect.height() * 3) {
            var x = rect.left
            val y = rect.top - infoValue.ascent()
            x = drawKeyValue(canvas, keyFirst, first, x, y) + density * 8
            drawKeyValue(canvas, FB_KEY, second, x, y)
            area.set(rect.left, rect.top + infoValue.fontSpacing, rect.right, rect.bottom)
            return area
        }
        val lineHeight = infoValue.fontSpacing
        val scale = min(1f, rect.height() / 2 / lineHeight)
        infoValue.textSize = baseValueSize * scale
        infoKey.textSize = baseKeySize * scale
        val width = infoKey.measureText(FB_KEY) + density * 3 + infoValue.measureText(INFO_TEMPLATE) + density * 6
        val y = rect.centerY() - lineHeight * scale - infoValue.ascent()
        drawKeyValue(canvas, keyFirst, first, rect.left, y)
        drawKeyValue(canvas, FB_KEY, second, rect.left, y + lineHeight * scale)
        infoValue.textSize = baseValueSize
        infoKey.textSize = baseKeySize
        area.set(rect.left + width, rect.top, rect.right, rect.bottom)
        return area
    }

    // @return right edge
    private fun drawKeyValue(canvas: Canvas, key: String, value: String, x: Float, y: Float): Float {
        var pos = x
        if (key.isNotEmpty()) {
            canvas.drawText(key, pos, y, infoKey)
            pos += infoKey.measureText(key) + density * 3
        }
        canvas.drawText(value, pos, y, infoValue)
        return pos + infoValue.measureText(value)
    }

    /**
     * @param levels operators output levels 0..1
     * @param selected highlighted operator or -1
     * @param big inspector figure with names and output mark
     */
    fun drawDiagram(canvas: Canvas, rect: RectF, ch: FmChannel, levels: FloatArray, selected: Int, big: Boolean) {
        val (schema, grid) = schemaOf(ch)
        val ops = ch.operatorsCount
        drawnOperators = 0
        val figure = box.apply { set(rect) }
        // gaps are small part of short cards, operators boxes are the main content
        val loopGap = min(density * (if (big) 10 else 7), figure.width() * 0.04f)
        val topGap = min(density * (if (big) 8 else 5), figure.height() * 0.07f)
        val busGap = min(density * (if (big) 9 else 6), figure.height() * 0.09f)
        val outLabel = if (big) label.measureText(OUT_LABEL) + density * 4 else 0f
        figure.left += loopGap
        figure.top += topGap
        figure.right -= density * 10 + outLabel
        figure.bottom -= busGap
        if (figure.height() < density * 12 || figure.width() < density * 24) {
            return
        }
        // Box size depends only on the biggest layout of chip's algorithms, so live algorithm changes do not
        // resize boxes. The rest of cell is for arrows, they should be visible at small cards too.
        val boxH = min(figure.height() / grid.rows * 0.72f, if (big) density * 56 else Float.MAX_VALUE)
        val boxW = min(figure.width() / grid.cols * 0.62f, boxH * 2.2f)
        val cellW = figure.width() / schema.cols
        // single row figures are centered vertically
        val cellH = figure.height() / grid.rows
        val rowsTop = figure.top + cellH * (grid.rows - schema.rows) / 2
        for (op in 0 until ops) {
            val cx = figure.left + cellW * (schema.col[op] + 0.5f)
            val cy = rowsTop + cellH * (schema.row[op] + 0.5f)
            boxes[op].set(cx - boxW / 2, cy - boxH / 2, cx + boxW / 2, cy + boxH / 2)
        }
        drawnOperators = ops
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
                drawArrowHead(canvas, dst.left, dst.centerY(), 1f, 0f, arrow, dimStroke)
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
                drawArrowHead(canvas, x, dst.top, 0f, 1f, arrow, dimStroke)
            }
        }
        val carriers = schema.carriers
        // Carriers which are the last in their rows go to the bus at right, else all carriers go down to the bus
        // under the figure (layouts keep such carriers at the bottom row, so lines never cross operators)
        val rightBus = (0 until ops).all { op ->
            carriers and (1 shl op) == 0 || (0 until ops).none {
                boxes[it].centerY() == boxes[op].centerY() && boxes[it].left > boxes[op].left
            }
        }
        var outY: Float
        var outX: Float
        if (rightBus) {
            val busX = figure.right + density * 5
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
        val outEnd = rect.right - outLabel
        if (carriers != 0) {
            canvas.drawLine(outX, outY, outEnd - arrow * 0.5f, outY, stroke)
            drawArrowHead(canvas, outEnd, outY, 1f, 0f, arrow, stroke)
            if (big) {
                label.textAlign = Paint.Align.LEFT
                label.textSize = baseLabelSize
                canvas.drawText(OUT_LABEL, outEnd + density * 3, outY - (label.ascent() + label.descent()) / 2, label)
                label.textAlign = Paint.Align.CENTER
            }
        }
        // Feedback loop from output (right) of the first operator back to its input (left) over the top.
        // Connections from it start at right side or bottom below the loop, so they never cross it.
        if (ch.feedback != 0) {
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
            drawArrowHead(canvas, b.left, y, 1f, 0f, arrow, dimStroke)
        }
        for (op in 0 until ops) {
            drawOperator(
                canvas,
                boxes[op],
                levels[op],
                carriers and (1 shl op) != 0,
                ch.operators[op].key,
                op,
                if (ch.isOpl) ch.operators[op].wave else -1,
                op == selected
            )
        }
    }

    // filled triangle with tip at (x, y) pointing to (dx, dy) direction (unit axis vector)
    private fun drawArrowHead(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float, size: Float, paint: Paint) {
        path.reset()
        path.moveTo(x, y)
        path.lineTo(x - dx * size * 1.6f - dy * size, y - dy * size * 1.6f - dx * size)
        path.lineTo(x - dx * size * 1.6f + dy * size, y - dy * size * 1.6f + dx * size)
        path.close()
        fill.color = paint.color
        canvas.drawPath(path, fill)
    }

    private fun drawOperator(
        canvas: Canvas,
        b: RectF,
        level: Float,
        carrier: Boolean,
        key: Boolean,
        op: Int,
        wave: Int,
        selected: Boolean
    ) {
        if (selected) {
            fill.color = Color.argb(60, 255, 255, 255)
            val grow = density * 3
            canvas.drawRect(b.left - grow, b.top - grow, b.right + grow, b.bottom + grow, fill)
        }
        // envelope level fill from bottom, carriers are brighter as they are heard directly
        fill.color = Color.argb(if (carrier) 170 else 80, 255, 255, 255)
        val filled = b.height() * level.coerceIn(0f, 1f)
        canvas.drawRect(b.left, b.bottom - filled, b.right, b.bottom, fill)
        // pressed key makes operator outline bold
        val outline = if (carrier) stroke else dashStroke
        val width = outline.strokeWidth
        if (key) {
            outline.strokeWidth = width * 2
        }
        canvas.drawRect(b, outline)
        outline.strokeWidth = width
        val withWave = wave >= 0 && b.height() > baseLabelSize * 2.2f
        // number only (as in datasheets and Furnace algorithm figures) for small boxes, as big as possible
        val small = b.height() < density * MIN_NAMED_BOX_DP
        label.textSize = min(
            baseLabelSize * 1.3f,
            b.height() * (if (withWave) {
                0.42f
            } else if (small) {
                0.75f
            } else {
                0.62f
            })
        )
        val name = OP_NAMES[op]
        val shown = if (!small && label.measureText(name) <= b.width() - density * 4) name else OP_NUMBERS[op]
        // text over bright fill is drawn dark to stay readable
        val textY = if (withWave) b.bottom - density * 3 else b.centerY() - (label.ascent() + label.descent()) / 2
        if (withWave) {
            drawOplWave(canvas, b, wave, Color.WHITE)
        }
        // outlined text stays readable over any fill level
        label.style = Paint.Style.STROKE
        label.strokeWidth = density * 2.5f
        label.color = Color.BLACK
        canvas.drawText(shown, b.centerX(), textY, label)
        label.style = Paint.Style.FILL
        label.color = Color.WHITE
        canvas.drawText(shown, b.centerX(), textY, label)
        label.textSize = baseLabelSize
    }

    // OPL waveforms: sine, half sine, abs sine, quarter sine, and OPL3 extensions
    private fun drawOplWave(canvas: Canvas, b: RectF, wave: Int, color: Int) {
        val w = b.width() * 0.7f
        val h = b.height() * 0.22f
        val left = b.centerX() - w / 2
        val cy = b.top + b.height() * 0.32f
        drawWaveform(canvas, wave, left, cy, w, h, color)
    }

    /**
     * Single period of OPL waveform centered at [cy] with [h] amplitude
     */
    fun drawWaveform(canvas: Canvas, wave: Int, left: Float, cy: Float, w: Float, h: Float, color: Int = Color.WHITE) {
        path.reset()
        val steps = 32
        for (step in 0..steps) {
            val phase = step.toFloat() / steps
            val y = oplWaveform(wave and 7, phase)
            val x = left + w * phase
            if (step == 0) path.moveTo(x, cy - y * h) else path.lineTo(x, cy - y * h)
        }
        val saved = stroke.color
        val width = stroke.strokeWidth
        // dark outline keeps glyph visible over bright fill of carriers
        stroke.color = Color.argb(160, 0, 0, 0)
        stroke.strokeWidth = width * 2.5f
        canvas.drawPath(path, stroke)
        stroke.strokeWidth = width
        stroke.color = color
        canvas.drawPath(path, stroke)
        stroke.color = saved
    }

    /**
     * Small sample of operator box style for legend
     */
    fun drawLegendBox(canvas: Canvas, b: RectF, carrier: Boolean) {
        fill.color = Color.argb(if (carrier) 170 else 80, 255, 255, 255)
        canvas.drawRect(b.left, b.centerY(), b.right, b.bottom, fill)
        canvas.drawRect(b, if (carrier) stroke else dashStroke)
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
        // for ~30fps updates of dashboard
        private const val EASING = 0.8f

        /*
         * Operators are numbered as in Furnace tracker (OP1..OP4, src/gui/guiConst.cpp opOrder), Nuked-OPN2 and
         * YM2151 datasheet (M1 C1 M2 C2): by connection order (OP1 > OP2 > OP3 > OP4 in ALG 0), not registers order.
         * Registers order is OP1 OP3 OP2 OP4 (OPN, +0/+4/+8/+C) and M1 M2 C1 C2 (OPM), so emulation cores report
         * levels and keys already reordered (FM_SLOTS in fmopn.c, OPS in ym2151.c). Yamaha's S1..S4 names are
         * avoided, as documents disagree on S2/S3 order. OPL operators follow registers order (OPL3 datasheet).
         */
        val OP_NAMES = Array(4) { "OP${it + 1}" }
        private val OP_NUMBERS = Array(4) { "${it + 1}" }
        private val OPL4_NAMES = arrayOf("FM-FM", "AM-FM", "FM-AM", "AM-AM")
        private val PAN_MARKS = arrayOf(" -", " R", " L", "")
        private const val ALG_KEY = "Alg"
        private const val FB_KEY = "FB"
        private const val OUT_LABEL = "out"
        private const val CHEVRON = "\u203a"

        // operator box height to fit name, smaller ones are labeled by number
        private const val MIN_NAMED_BOX_DP = 20

        // the widest value of algorithm and feedback info
        private const val INFO_TEMPLATE = "FM-AM"

        fun algorithmName(ch: FmChannel) = when {
            ch.isOpl && ch.operatorsCount == 2 -> if (ch.algorithm != 0) "AM" else "FM"
            ch.isOpl -> OPL4_NAMES[ch.algorithm and 3]
            else -> "${ch.algorithm}"
        }

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

        private fun schemaOf(ch: FmChannel) = when {
            ch.isOpl && ch.operatorsCount == 2 -> OPL2_ALGORITHMS[ch.algorithm and 1] to OPL2_GRID
            ch.isOpl -> OPL4_ALGORITHMS[ch.algorithm and 3] to OPL4_GRID
            else -> OPN_ALGORITHMS[ch.algorithm and 7] to OPN_GRID
        }

        /** Carriers mask of algorithm figure, as drawn */
        fun carriersOf(ch: FmChannel) = schemaOf(ch).first.carriers

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
}
