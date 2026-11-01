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
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws live state of a single voice according to its chip:
 * - FM (OPN, OPM, OPL): operators connection schema (as in chips datasheets) with envelope levels, see [FmWidgets]
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

    // following are not allocated at each frame
    private val area = RectF()
    private val bar = RectF()
    private val glyph = RectF()
    private val glyph2 = RectF()
    private val box2 = RectF()
    private val fm = FmWidgets(density, textSizePx)
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
            VoiceGauges.Kind.OPN_FM, VoiceGauges.Kind.OPL_2OP, VoiceGauges.Kind.OPL_4OP -> drawFm(canvas, rect, g, smooth)

            VoiceGauges.Kind.OPN_DAC -> drawDac(canvas, rect, g, smooth)

            VoiceGauges.Kind.PSG_TONE, VoiceGauges.Kind.PSG_NOISE -> drawPsg(canvas, rect, g, smooth)

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

    // see FmWidgets
    private fun drawFm(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray) = fm.drawCard(canvas, rect, g, smooth, memo)

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

    // S-DSP voice as a mixer channel strip, readable at a glance and the same in every card height:
    // envelope level meter carrying note and sample, ADSR shape (or GAIN level) with current stage, stereo balance
    // and echo/pitch modulation/noise lamps. Exact register values are in the title summary.
    private fun drawSpc(canvas: Canvas, rect: RectF, g: VoiceGauges, smooth: FloatArray) {
        val envx = ease(smooth, 0, g.field(0) / 127f)
        val mode = g.field(1)
        val adsr1 = g.field(2)
        val adsr2 = g.field(3)
        val flags = g.field(10)
        val gap = density * 6
        val h = min(rect.height(), density * 34)
        val top = rect.centerY() - h / 2
        val bottom = top + h
        val meterRight = rect.left + rect.width() * 0.34f
        // level meter with note name and sample number inside
        bar.set(rect.left, top, meterRight, bottom)
        canvas.drawRect(bar, dimStroke)
        fill.color = Color.argb(if (g.isKeyOn) 210 else 120, 255, 255, 255)
        box.set(bar.left, bar.top, bar.left + bar.width() * envx.coerceIn(0f, 1f), bar.bottom)
        canvas.drawRect(box, fill)
        val hz = if (g.hasFrequency) g.frequency else 0f
        val note = memo.get(0, (g.field(5).toLong() shl 32) or hz.toBits().toLong()) {
            val name = noteName(hz)
            if (name.isEmpty()) "#%02X".format(g.field(5)) else "$name  #%02X".format(g.field(5))
        }
        value.textSize = min(baseValueSize, h * 0.6f)
        // text is drawn twice clipped by fill, so it's readable over both dark and filled parts
        val baseline = bar.centerY() - (value.ascent() + value.descent()) / 2
        val textX = bar.left + density * 6
        canvas.save()
        canvas.clipRect(box)
        value.color = Color.BLACK
        canvas.drawText(note, textX, baseline, value)
        canvas.restore()
        canvas.save()
        canvas.clipRect(box.right, bar.top, bar.right, bar.bottom)
        value.color = Color.WHITE
        canvas.drawText(note, textX, baseline, value)
        canvas.restore()
        value.textSize = baseValueSize
        // envelope shape
        var x = meterRight + gap
        val envRight = x + rect.width() * 0.38f
        glyph.set(x, rect.top, envRight, rect.bottom)
        if (mode == 4) {
            drawGain(canvas, glyph, g.field(4), envx)
        } else {
            drawAdsr(canvas, glyph, adsr1 and 15, (adsr1 shr 4) and 7, adsr2 shr 5, adsr2 and 31, mode, envx)
        }
        // stereo balance
        x = envRight + gap
        val balanceRight = x + rect.width() * 0.11f
        glyph.set(x, top, balanceRight, bottom)
        drawBalance(canvas, glyph, g.signedField(6), g.signedField(7))
        // lamps
        glyph.set(balanceRight + gap, top + h * 0.2f, rect.right, bottom - h * 0.2f)
        drawLamps(canvas, glyph, flags)
    }

    // switches as small lamps with letters, lit when on
    private fun drawLamps(canvas: Canvas, r: RectF, flags: Int) {
        val cellW = r.width() / SPC_LAMPS.size
        val radius = min(cellW, r.height()) * 0.42f
        label.textSize = radius * 1.1f
        for ((idx, name) in SPC_LAMPS.withIndex()) {
            val on = flags and (1 shl idx) != 0
            val cx = r.left + cellW * (idx + 0.5f)
            val cy = r.centerY()
            if (on) {
                fill.color = Color.argb(220, 255, 255, 255)
                canvas.drawCircle(cx, cy, radius, fill)
            } else {
                canvas.drawCircle(cx, cy, radius, dimStroke)
            }
            label.color = if (on) Color.BLACK else Color.argb(110, 255, 255, 255)
            canvas.drawText(name, cx, cy - (label.ascent() + label.descent()) / 2, label)
        }
        label.color = Color.WHITE
        label.textSize = baseLabelSize
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

    // Envelope as filled shape over time with the current stage bright and a dot at the current level, and the same in
    // words below (times from S-DSP rate tables), so it's readable without knowing how to read envelope graphs.
    // Segment widths follow log of times (2ms..38s span), so fast and slow envelopes are both visible.
    private fun drawAdsr(
        canvas: Canvas,
        r: RectF,
        attack: Int,
        decay: Int,
        sustain: Int,
        release: Int,
        mode: Int,
        level: Float
    ) {
        val attackMs = SPC_ATTACK_MS[attack]
        val decayMs = SPC_DECAY_MS[decay]
        val releaseMs = SPC_RELEASE_MS[release]
        val sustainLevel = (sustain + 1) / 8f
        caption.textSize = min(baseCaptionSize, r.height() * 0.28f)
        val textH = caption.fontSpacing
        val graph = box2
        graph.set(r.left, r.top + density * 2, r.right, r.bottom - textH - density * 2)
        if (graph.height() > density * 6) {
            fun span(ms: Int) = log10(1f + max(ms, 1) / 2f)
            val a = span(attackMs)
            val d = span(decayMs)
            val sus = 1.2f
            val rl = if (releaseMs == 0) 2f else span(releaseMs)
            val unit = graph.width() / (a + d + sus + rl)
            val sustainY = graph.bottom - graph.height() * sustainLevel
            val xs = floatArrayOf(graph.left, graph.left + a * unit, graph.left + (a + d) * unit, graph.left + (a + d + sus) * unit, graph.right)
            val ys = floatArrayOf(graph.bottom, graph.top, sustainY, sustainY, if (releaseMs == 0) sustainY else graph.bottom)
            // stage index in segments: attack, decay, sustain (gradual decrease by SR), release (key off)
            val current = when (mode) {
                1 -> 0
                2 -> 1
                3 -> 2
                0 -> 3
                else -> -1
            }
            for (seg in 0 until 4) {
                path.reset()
                path.moveTo(xs[seg], graph.bottom)
                path.lineTo(xs[seg], ys[seg])
                path.lineTo(xs[seg + 1], ys[seg + 1])
                path.lineTo(xs[seg + 1], graph.bottom)
                path.close()
                fill.color = Color.argb(if (seg == current) 150 else 45, 255, 255, 255)
                canvas.drawPath(path, fill)
                canvas.drawLine(xs[seg], ys[seg], xs[seg + 1], ys[seg + 1], if (seg == current) boldStroke else dimStroke)
            }
            if (current >= 0) {
                // current level on the active segment
                val y = graph.bottom - graph.height() * level.coerceIn(0f, 1f)
                val x0 = xs[current]
                val x1 = xs[current + 1]
                val y0 = ys[current]
                val y1 = ys[current + 1]
                val x = if (abs(y1 - y0) < 1f) (x0 + x1) / 2 else (x0 + (x1 - x0) * ((y - y0) / (y1 - y0)).coerceIn(0f, 1f))
                fill.color = Color.WHITE
                canvas.drawCircle(x, y, density * 3, fill)
            }
        }
        val words = memo.get(1, (attack.toLong() shl 16) or (decay.toLong() shl 12) or (sustain.toLong() shl 8) or release.toLong()) {
            "A ${formatMs(attackMs)}  D ${formatMs(decayMs)}  S ${(sustainLevel * 100).roundToInt()}%  R ${if (releaseMs == 0) "∞" else formatMs(releaseMs)}"
        }
        val count = caption.breakText(words, true, r.width(), null)
        canvas.drawText(words, 0, count, r.left, r.bottom - caption.descent(), caption)
        caption.textSize = baseCaptionSize
    }

    // GAIN mode: direct level or ramp, shown as level bar with the register meaning in words
    private fun drawGain(canvas: Canvas, r: RectF, gain: Int, level: Float) {
        caption.textSize = min(baseCaptionSize, r.height() * 0.28f)
        val textH = caption.fontSpacing
        glyph2.set(r.left, r.top + density * 4, r.right, r.bottom - textH - density * 4)
        drawLevelBar(canvas, glyph2, level, 16)
        val words = memo.get(1, gain.toLong() or 0x10000) {
            if (gain and 0x80 == 0) {
                "Gain: direct ${(gain and 0x7f) * 100 / 127}%"
            } else {
                val rate = SPC_RELEASE_MS[gain and 31]
                val kind = GAIN_MODES[(gain shr 5) and 3]
                "Gain: $kind ${if (rate == 0) "∞" else formatMs(rate)}"
            }
        }
        val count = caption.breakText(words, true, r.width(), null)
        canvas.drawText(words, 0, count, r.left, r.bottom - caption.descent(), caption)
        caption.textSize = baseCaptionSize
    }

    private fun formatMs(ms: Int) = when {
        ms >= 10000 -> "${ms / 1000}s"
        ms >= 1000 -> "%.1fs".format(ms / 1000f)
        else -> "${ms}ms"
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

    companion object {
        const val SMOOTH_SIZE = 8

        // for ~30fps updates of dashboard
        private const val EASING = 0.8f

        private val RHYTHM_NAMES = arrayOf("Bass drum", "Snare", "Tom", "Cymbal", "Hi-hat")
        private val PARTIAL_STATES = arrayOf("-", "ATK", "SUS", "REL")
        private val ENV_STAGES = arrayOf("Release", "Attack", "Decay", "Sustain")
        private val SPC_LAMPS = arrayOf("E", "P", "N")

        // S-DSP envelope times (full range) per rate register value, from fullsnes / Anomie's S-DSP doc
        private val SPC_ATTACK_MS = intArrayOf(4100, 2600, 1500, 1000, 640, 380, 260, 160, 96, 64, 40, 24, 16, 10, 6, 0)
        private val SPC_DECAY_MS = intArrayOf(1200, 740, 440, 290, 180, 110, 74, 37)

        // 0 is infinite
        private val SPC_RELEASE_MS = intArrayOf(
            0, 38000, 28000, 24000, 19000, 14000, 12000, 9400, 7100, 5900, 4700, 3500, 2900, 2400, 1800, 1500,
            1200, 880, 740, 590, 440, 370, 290, 220, 180, 150, 110, 92, 74, 55, 37, 18
        )
        private val GAIN_MODES = arrayOf("linear down", "exp down", "linear up", "bent up")
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

            // S-DSP pitch is sample playback rate, the note depends on unknown sample tuning
            VoiceGauges.Kind.SPC_DSP -> if (g.field(0) == 0 && !g.isKeyOn) "off" else spcRate(g)

            else -> noteSummary(g.summaryMode, g.frequency.roundToInt(), noteName(g.frequency), g.level.roundToInt())
        }

        private fun spcRate(g: VoiceGauges): String {
            val pitch = g.field(8) or (g.field(9) shl 8)
            return "%.1f kHz".format(pitch * 32f / 4096f)
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
