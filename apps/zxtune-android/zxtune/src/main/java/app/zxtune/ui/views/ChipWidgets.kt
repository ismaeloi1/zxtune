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
    private val baseLabelSize = textSizePx * 0.85f
    private val path = Path()
    private val box = RectF()
    private val boxes = Array(4) { RectF() }

    /**
     * Short text describing current state, displayed in title band
     */
    fun summary(g: VoiceGauges) = when (g.kind) {
        VoiceGauges.Kind.MT32_PART -> g.text.ifEmpty { "-" }
        VoiceGauges.Kind.MT32_PARTIAL -> partialSummary(g)
        VoiceGauges.Kind.OPL_RHYTHM -> RHYTHM_NAMES.getOrElse(g.field(0)) { "" }
        VoiceGauges.Kind.OPN_DAC -> if (g.field(0) != 0) "PCM" else "off"
        else -> noteSummary(g)
    }

    private fun noteSummary(g: VoiceGauges) = when {
        g.isNoise && !g.hasFrequency -> "noise"
        (!g.hasFrequency || g.frequency <= 0f) && g.hasLevel -> if (g.level > -96f) "${g.level.roundToInt()}dB" else "off"
        !g.hasFrequency || g.frequency <= 0f -> ""
        g.isNoise -> "noise ${g.frequency.roundToInt()}Hz"
        else -> "${noteName(g.frequency)} ${g.frequency.roundToInt()}Hz"
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

    /**
     * @param smooth per voice storage of displayed values, at least SMOOTH_SIZE
     * @return true if widget is drawn
     */
    fun draw(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray): Boolean {
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

    // FM operators schema of YM2612 algorithms, S1..S4 order as in datasheet
    private fun drawOpnFm(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray) {
        val alg = g.field(0) and 7
        val levels = FloatArray(4) { ease(smooth, it, g.field(2 + it) / 255f) }
        val keys = g.field(10)
        val info = "ALG$alg FB${g.field(1)}${panText(g.field(7))}"
        val schema = OPN_ALGORITHMS[alg]
        drawSchema(canvas, rect, schema, g.field(6), levels, keys, g.field(1), info, OPN_NAMES, null)
    }

    // OPL2/OPL3 2 and 4 operators channels, FM or AM (additive) connections
    private fun drawOpl(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray) {
        val is4op = g.kind == VoiceGauges.Kind.OPL_4OP
        if (is4op) {
            val alg = g.field(0) and 3
            val levels = FloatArray(4) { ease(smooth, it, g.field(2 + it) / 255f) }
            val waves = IntArray(4) { g.field(6 + it) }
            val info = "${OPL4_NAMES[alg]} FB${g.field(1)}${panText(g.field(11))}"
            val schema = OPL4_ALGORITHMS[alg]
            drawSchema(canvas, rect, schema, schema.carriers, levels, if (g.field(10) != 0) 0xf else 0, g.field(1), info,
                OPL4_OPS, waves)
        } else {
            val additive = g.field(0) and 1
            val levels = FloatArray(2) { ease(smooth, it, g.field(2 + it) / 255f) }
            val waves = IntArray(2) { g.field(4 + it) }
            val info = "${if (additive != 0) "AM" else "FM"} FB${g.field(1)}${panText(g.field(7))}"
            val schema = OPL2_ALGORITHMS[additive]
            drawSchema(canvas, rect, schema, schema.carriers, levels, if (g.field(6) != 0) 3 else 0, g.field(1), info,
                OPL2_OPS, waves)
        }
    }

    private fun panText(pan: Int) = when (pan and 3) {
        2 -> " L"
        1 -> " R"
        0 -> " -"
        else -> ""
    }

    /**
     * Operators as boxes filled by envelope level, modulation connections as lines,
     * carriers connected to output bus, feedback loop on the first operator.
     */
    private fun drawSchema(
        canvas: Canvas,
        rect: RectF,
        schema: Schema,
        carriers: Int,
        levels: FloatArray,
        keys: Int,
        feedback: Int,
        info: String,
        names: Array<String>,
        waves: IntArray?
    ) {
        val textHeight = text.fontSpacing
        drawFit(canvas, info, rect.left, rect.top - text.ascent(), rect.right - (rect.left))
        val ops = levels.size
        // carrier followed by another operator in the same row cannot reach the bus horizontally,
        // so it goes down to a horizontal bus under the boxes
        var downCarriers = 0
        for (op in 0 until ops) {
            if (carriers and (1 shl op) != 0 &&
                (0 until ops).any { schema.row[it] == schema.row[op] && schema.col[it] > schema.col[op] }) {
                downCarriers = downCarriers or (1 shl op)
            }
        }
        val busGap = if (downCarriers != 0) density * 6 else 0f
        val area = RectF(rect.left, rect.top + textHeight, rect.right - density * 14, rect.bottom - busGap)
        if (area.height() < density * 12 || area.width() < density * 24) {
            return
        }
        val cellW = area.width() / schema.cols
        val cellH = area.height() / schema.rows
        // stacked layouts are short, so allow wider boxes to keep labels readable
        val boxH = min(cellW * 0.7f, cellH * 0.85f)
        val boxW = min(cellW * 0.7f, boxH * 1.6f)
        val size = min(boxW, boxH)
        for (op in 0 until ops) {
            val cx = area.left + cellW * (schema.col[op] + 0.5f)
            val cy = area.top + cellH * (schema.row[op] + 0.5f)
            boxes[op].set(cx - boxW / 2, cy - boxH / 2, cx + boxW / 2, cy + boxH / 2)
        }
        // modulation connections
        for (edge in schema.edges) {
            val src = boxes[edge shr 4]
            val dst = boxes[edge and 15]
            path.reset()
            if (abs(src.centerX() - dst.centerX()) < 1f) {
                path.moveTo(src.centerX(), src.bottom)
                path.lineTo(dst.centerX(), dst.top)
            } else {
                val mid = (src.right + dst.left) / 2
                path.moveTo(src.right, src.centerY())
                path.lineTo(mid, src.centerY())
                path.lineTo(mid, dst.centerY())
                path.lineTo(dst.left, dst.centerY())
            }
            canvas.drawPath(path, dimStroke)
        }
        // output bus of carriers
        val busX = area.right + density * 6
        var busTop = Float.MAX_VALUE
        var busBottom = -Float.MAX_VALUE
        val lowBusY = area.bottom + busGap * 0.6f
        for (op in 0 until ops) {
            val b = boxes[op]
            if (downCarriers and (1 shl op) != 0) {
                canvas.drawLine(b.centerX(), b.bottom, b.centerX(), lowBusY, stroke)
                canvas.drawLine(b.centerX(), lowBusY, busX, lowBusY, stroke)
                busTop = min(busTop, lowBusY)
                busBottom = max(busBottom, lowBusY)
            } else if (carriers and (1 shl op) != 0) {
                canvas.drawLine(b.right, b.centerY(), busX, b.centerY(), stroke)
                busTop = min(busTop, b.centerY())
                busBottom = max(busBottom, b.centerY())
            }
        }
        if (busTop <= busBottom) {
            canvas.drawLine(busX, busTop, busX, busBottom, stroke)
            val outY = (busTop + busBottom) / 2
            canvas.drawLine(busX, outY, rect.right, outY, stroke)
        }
        // feedback loop of the first operator
        if (feedback != 0) {
            val b = boxes[0]
            val loop = size * 0.25f
            path.reset()
            path.moveTo(b.right, b.centerY() - size * 0.2f)
            path.lineTo(b.right + loop, b.centerY() - size * 0.2f)
            path.lineTo(b.right + loop, b.top - loop * 0.6f)
            path.lineTo(b.left - loop, b.top - loop * 0.6f)
            path.lineTo(b.left - loop, b.centerY() - size * 0.2f)
            path.lineTo(b.left, b.centerY() - size * 0.2f)
            canvas.drawPath(path, dimStroke)
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
        label.textSize = min(baseLabelSize, b.height() * 0.42f)
        if (wave != null && b.height() > baseLabelSize * 2.2f) {
            drawOplWave(canvas, b, wave)
            canvas.drawText(name, b.centerX(), b.bottom - density * 3, label)
        } else {
            canvas.drawText(name, b.centerX(), b.centerY() - (label.ascent() + label.descent()) / 2, label)
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
        val bar = RectF(rect.left, rect.top + text.fontSpacing, rect.right, rect.bottom)
        drawCenteredBar(canvas, bar, ease(smooth, 0, value))
    }

    // SN76489: 4 bit attenuation in 2dB steps, 10 bit period or noise mode
    private fun drawPsg(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray) {
        val att = g.field(0)
        val info = if (g.kind == VoiceGauges.Kind.PSG_TONE) {
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
        drawFit(canvas, info, rect.left, rect.top - text.ascent(), rect.right - (rect.left))
        val bar = RectF(rect.left, rect.top + text.fontSpacing, rect.right, rect.bottom)
        drawSegments(canvas, bar, ease(smooth, 0, (15 - att) / 15f), 15)
    }

    private fun stereoText(bits: Int) = when (bits and 3) {
        3 -> ""
        2 -> " L"
        1 -> " R"
        else -> " -"
    }

    // S-DSP: ENVX with envelope phase, ADSR or GAIN registers, sample source and rate
    private fun drawSpc(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray) {
        val envx = ease(smooth, 0, g.field(0) / 127f)
        val mode = g.field(1)
        val adsr1 = g.field(2)
        val adsr2 = g.field(3)
        val pitch = g.field(8) or (g.field(9) shl 8)
        val flags = g.field(10)
        val barW = min(rect.width() * 0.18f, density * 18)
        val bar = RectF(rect.left, rect.top, rect.left + barW, rect.bottom)
        drawVerticalBar(canvas, bar, envx)
        val x = bar.right + density * 6
        var y = rect.top - text.ascent()
        val envelope = if (mode == 4) {
            "GAIN %02X".format(g.field(4))
        } else {
            "%s A%X D%X S%X R%02X".format(
                ENV_MODES.getOrElse(mode) { "?" },
                adsr1 and 15,
                (adsr1 shr 4) and 7,
                adsr2 shr 5,
                adsr2 and 31
            )
        }
        drawFit(canvas, envelope, x, y, rect.right - (x))
        y += text.fontSpacing
        drawFit(canvas, "SRC %02X %.1fkHz".format(g.field(5), pitch * 32f / 4096f), x, y, rect.right - (x))
        y += text.fontSpacing
        val badges = buildString {
            if (flags and 1 != 0) append("ECHO ")
            if (flags and 2 != 0) append("PMOD ")
            if (flags and 4 != 0) append("NOISE ")
        }
        if (y < rect.bottom) {
            drawFit(canvas, "L%+d R%+d %s".format(g.signedField(6), g.signedField(7), badges), x, y, rect.right - (x))
        }
    }

    private fun drawMt32Part(canvas: Canvas, rect: RectF, g: VoiceGauges) {
        val notes = g.field(0)
        val partials = g.field(9)
        drawFit(canvas, "KEYS %d PARTIALS %d".format(notes, partials), rect.left, rect.top - text.ascent(), rect.right - (rect.left))
        val kb = RectF(rect.left, rect.top + text.fontSpacing, rect.right, rect.bottom)
        if (kb.height() < density * 8) {
            return
        }
        val pressed = BooleanArray(128)
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

    private fun isBlack(key: Int) = (key % 12) in intArrayOf(1, 3, 6, 8, 10)

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
        val glyph = RectF(rect.left, rect.top, rect.left + glyphW, rect.bottom)
        val x = glyph.right + density * 6
        val bar = RectF(x, rect.top + text.fontSpacing, rect.right, rect.bottom)
        when (g.kind) {
            VoiceGauges.Kind.NES_PULSE -> {
                val duty = g.field(0) and 3
                drawPulseGlyph(canvas, glyph, DUTIES[duty])
                val period = g.field(3) or (g.field(4) shl 8)
                val info = "%s %s%s P=%03X".format(
                    DUTY_NAMES[duty],
                    if (g.field(2) != 0) "VOL" else "ENV",
                    if (g.field(6) != 0) " SWP" else "",
                    period
                )
                drawFit(canvas, info, x, rect.top - text.ascent(), rect.right - (x))
                drawSegments(canvas, bar, ease(smooth, 0, g.field(1) / 15f), 15)
            }

            VoiceGauges.Kind.NES_TRIANGLE -> {
                drawTriangleGlyph(canvas, glyph)
                val period = g.field(1) or (g.field(2) shl 8)
                drawFit(canvas, "LIN %s P=%03X".format(if (g.field(0) != 0) "on" else "off", period), x, rect.top - text.ascent(), rect.right - (x))
                drawSegments(canvas, bar, ease(smooth, 0, if (g.isKeyOn) 1f else 0f), 15)
            }

            VoiceGauges.Kind.NES_NOISE -> {
                drawNoiseGlyph(canvas, glyph, g.field(1) != 0)
                drawFit(canvas, "%s RATE %X".format(if (g.field(1) != 0) "SHORT" else "LONG", g.field(2)), x, rect.top - text.ascent(), rect.right - (x))
                drawSegments(canvas, bar, ease(smooth, 0, g.field(0) / 15f), 15)
            }

            else -> {
                drawFit(canvas, "RATE %X%s%s".format(g.field(1), if (g.field(3) != 0) " LOOP" else "", if (g.field(2) != 0) " PLAY" else ""), glyph.left, rect.top - text.ascent(), rect.right - (glyph.left))
                drawLevelBar(
                    canvas,
                    RectF(glyph.left, bar.top, rect.right, rect.bottom),
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
        val carriers: Int = 0
    ) {
        val cols = (col.maxOrNull() ?: 0f).toInt() + 1
        val rows = ((row.maxOrNull() ?: 0f) + 1f).toInt().coerceAtLeast(1)
    }

    companion object {
        const val SMOOTH_SIZE = 8
        private const val EASING = 0.35f

        private val OPN_NAMES = arrayOf("S1", "S2", "S3", "S4")
        private val OPL2_OPS = arrayOf("M", "C")
        private val OPL4_OPS = arrayOf("1", "2", "3", "4")
        private val OPL4_NAMES = arrayOf("FM-FM", "AM-FM", "FM-AM", "AM-AM")

        // see YM2612 datasheet, figure "Algorithm"
        private val OPN_ALGORITHMS = arrayOf(
            Schema(floatArrayOf(0f, 1f, 2f, 3f), floatArrayOf(0f, 0f, 0f, 0f), intArrayOf(0x01, 0x12, 0x23)),
            Schema(floatArrayOf(0f, 0f, 1f, 2f), floatArrayOf(0f, 1f, 0.5f, 0.5f), intArrayOf(0x02, 0x12, 0x23)),
            Schema(floatArrayOf(1f, 0f, 1f, 2f), floatArrayOf(0f, 1f, 1f, 0.5f), intArrayOf(0x03, 0x12, 0x23)),
            Schema(floatArrayOf(0f, 1f, 1f, 2f), floatArrayOf(0f, 0f, 1f, 0.5f), intArrayOf(0x01, 0x13, 0x23)),
            Schema(floatArrayOf(0f, 1f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 1f), intArrayOf(0x01, 0x23)),
            Schema(floatArrayOf(0f, 1f, 1f, 1f), floatArrayOf(1f, 0f, 1f, 2f), intArrayOf(0x01, 0x02, 0x03)),
            Schema(floatArrayOf(0f, 1f, 2f, 3f), floatArrayOf(0f, 0f, 0f, 0f), intArrayOf(0x01)),
            Schema(floatArrayOf(0f, 1f, 2f, 3f), floatArrayOf(0f, 0f, 0f, 0f), intArrayOf()),
        )

        // OPL 2 operators: FM (modulator -> carrier) or AM (both heard)
        private val OPL2_ALGORITHMS = arrayOf(
            Schema(floatArrayOf(0f, 1f), floatArrayOf(0f, 0f), intArrayOf(0x01), 2),
            Schema(floatArrayOf(0f, 1f), floatArrayOf(0f, 0f), intArrayOf(), 3),
        )

        // OPL3 4 operators: FM-FM 1*2*3*4, AM-FM 1+2*3*4, FM-AM 1*2+3*4, AM-AM 1+2*3+4
        private val OPL4_ALGORITHMS = arrayOf(
            Schema(floatArrayOf(0f, 1f, 2f, 3f), floatArrayOf(0f, 0f, 0f, 0f), intArrayOf(0x01, 0x12, 0x23), 8),
            Schema(floatArrayOf(0f, 0f, 1f, 2f), floatArrayOf(0f, 1f, 1f, 1f), intArrayOf(0x12, 0x23), 9),
            Schema(floatArrayOf(0f, 1f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 1f), intArrayOf(0x01, 0x23), 10),
            Schema(floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(0f, 1f, 1f, 2f), intArrayOf(0x12), 13),
        )

        private val RHYTHM_NAMES = arrayOf("Bass drum", "Snare", "Tom", "Cymbal", "Hi-hat")
        private val PARTIAL_STATES = arrayOf("-", "ATK", "SUS", "REL")
        private val ENV_MODES = arrayOf("REL", "ATK", "DEC", "SUS")
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

        fun noteName(hz: Float): String {
            if (hz <= 0f) return ""
            val note = (12 * log2(hz / 440f) + 57).roundToInt()
            return if (note in 0..131) "${NOTES[note % 12]}${note / 12}" else ""
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
}
