/**
 * @file
 * @brief SID filter live frequency response with voices state and cutoff curve of current settings
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import app.zxtune.R
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.ChipState
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Two panels:
 * - frequency response (dB by logarithmic frequency) of the playing tune's filter with the voices placed at their
 *   fundamental frequencies, so it's visible how much each voice is attenuated or boosted right now;
 * - cutoff frequency by cutoff register value as emulated with current settings (see [SidFilterModel]) with reference
 *   curve of selected preset, to compare settings and presets.
 * Live state is updated at display rate (see [app.zxtune.ui.FilterCurveFeed]), so drawing doesn't allocate:
 * texts are cached by displayed (rounded) values.
 */
class FilterCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * Registers snapshots of chips at currently heard moment
     */
    class Live {
        val registers = ByteArray(MAX_CHIPS * ChipState.SIZE)
        val chips = Array(MAX_CHIPS) { ChipState(registers, it * ChipState.SIZE) }
        var count = 0

        /** Chip clock in Hz for oscillators frequencies */
        var clock = SidDescription.PAL_CLOCK

        /**
         * @param gauges data of [app.zxtune.playback.Visualizer.getGauges]
         * @return true if anything is changed
         */
        fun read(gauges: ByteArray, chipsCount: Int): Boolean {
            val newCount = chipsCount.coerceIn(0, MAX_CHIPS)
            var changed = newCount != count
            count = newCount
            for (chip in 0 until newCount) {
                val src = chip * ChipGauges.SIZE + ChipGauges.GAUGES * ChipGauges.COLUMNS * 2
                val dst = chip * ChipState.SIZE
                if (src + ChipState.SIZE > gauges.size) {
                    count = chip
                    return true
                }
                for (idx in 0 until ChipState.SIZE) {
                    val b = gauges[src + idx]
                    if (registers[dst + idx] != b) {
                        registers[dst + idx] = b
                        changed = true
                    }
                }
            }
            return changed
        }
    }

    data class State(
        val settings: SidFilterModel.Settings = SidFilterModel.Settings(),
        val is8580: Boolean = false,
        /** Name of selected preset, empty for custom filter */
        val presetName: String = "",
        /** Shown if no live state */
        val hint: String = "",
    )

    var state = State()
        set(value) {
            if (field != value) {
                val curvesChanged = field.settings != value.settings
                field = value
                if (curvesChanged) {
                    curves = null
                }
                resetTexts()
                invalidate()
            }
        }

    val live = Live()

    /**
     * Chip displayed on response panel, changed by tap for 2SID/3SID tunes
     */
    var selectedChip = 0
        set(value) {
            if (field != value) {
                field = value
                resetTexts()
                invalidate()
            }
        }

    private class Curves(
        val emulated6581: DoubleArray,
        val emulated8580: DoubleArray,
        val reference6581: DoubleArray?,
        val reference8580: DoubleArray?,
    )

    private var curves: Curves? = null

    private val density = resources.displayMetrics.density
    private val textColor = themeColor(android.R.attr.textColorPrimary, 0xffe0e0e0.toInt())
    private val secondaryColor = themeColor(android.R.attr.textColorSecondary, 0xff909090.toInt())
    private val accentColor = themeColor(androidx.appcompat.R.attr.colorAccent, 0xff4fc3f7.toInt())

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryColor
        alpha = 60
        strokeWidth = density
    }
    private val unityPaint = Paint(gridPaint).apply {
        alpha = 150
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryColor
        textSize = sp(10f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = sp(12f)
    }
    private val summaryPaint = Paint(textPaint).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val voiceNamePaint = Paint(textPaint).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        alpha = 40
        style = Paint.Style.FILL
    }
    private val otherCurvePaint = Paint(curvePaint).apply {
        color = secondaryColor
        alpha = 110
        strokeWidth = 1.2f * density
    }
    private val referencePaint = Paint(curvePaint).apply {
        color = textColor
        alpha = 170
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CUTOFF_COLOR
        strokeWidth = 1.5f * density
    }
    private val cutoffLinePaint = Paint(markerPaint).apply {
        alpha = 160
        pathEffect = DashPathEffect(floatArrayOf(4 * density, 3 * density), 0f)
    }
    private val voicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2 * density
    }
    private val voiceOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }
    private val responsePlot = RectF()
    private val cutoffPlot = RectF()
    private val path = Path()
    private val fillPath = Path()

    private val modeNames = resources.getStringArray(R.array.pref_zxtune_sid_filter_response_modes)
    private val extWord = context.getString(R.string.pref_zxtune_sid_filter_voice_ext)
    private val noiseWord = context.getString(R.string.pref_zxtune_sid_filter_voice_noise)
    private val panelTitle = context.getString(R.string.pref_zxtune_sid_filter_curve_panel)
    private val legendTexts = arrayOf(
        context.getString(R.string.pref_zxtune_sid_filter_curve_legend_reference),
        context.getString(R.string.pref_zxtune_sid_filter_curve_legend_emulated),
    )

    // Texts are rebuilt only when displayed values are changed, see [textKey]
    private val texts = arrayOfNulls<String>(TEXTS)
    private val textKeys = LongArray(TEXTS) { Long.MIN_VALUE }

    init {
        // tap switches displayed chip of multi-SID tunes
        setOnClickListener {
            if (live.count > 1) {
                selectedChip = (selectedChip + 1) % live.count
            }
        }
    }

    private val lineHeight
        get() = textPaint.fontSpacing

    private fun responseHeight(width: Int) = (width * 0.42f).coerceIn(130 * density, 260 * density)

    private fun cutoffHeight(width: Int) = (width * 0.22f).coerceIn(70 * density, 150 * density)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val labels = labelPaint.fontSpacing
        val desired = paddingTop + paddingBottom +
            // header, summary
            lineHeight * 2 + 4 * density +
            responseHeight(width) + labels + 4 * density +
            lineHeight * VOICES + 8 * density +
            // cutoff panel title
            labels + 2 * density +
            cutoffHeight(width) + labels
        setMeasuredDimension(width, resolveSize(desired.roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val curves = curves ?: makeCurves().also { curves = it }
        val emulated = if (state.is8580) curves.emulated8580 else curves.emulated6581
        val labelWidth = labelPaint.measureText("-48") + 4 * density
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        var y = paddingTop.toFloat()
        val chip = selectedChip.takeIf { it < live.count } ?: 0
        val isLive = live.count > 0

        drawText(canvas, TEXT_HEADER, headerKey(), right - left, left, y - textPaint.ascent(), textPaint) { header() }
        y += lineHeight
        if (isLive) {
            val ch = live.chips[chip]
            drawText(canvas, TEXT_SUMMARY, summaryKey(ch, emulated), right - left, left, y - textPaint.ascent(), summaryPaint) {
                summary(ch, emulated)
            }
        } else {
            canvas.drawText(ellipsize(state.hint, right - left, labelPaint), left, y - labelPaint.ascent(), labelPaint)
        }
        y += lineHeight + 4 * density

        responsePlot.set(left + labelWidth, y, right - 4 * density, y + responseHeight(width))
        drawResponseGrid(canvas)
        if (isLive) {
            for (other in 0 until live.count) {
                if (other != chip) {
                    drawResponse(canvas, live.chips[other], emulated, otherCurvePaint, null)
                }
            }
            drawResponse(canvas, live.chips[chip], emulated, curvePaint, fillPaint)
            drawVoiceMarkers(canvas, live.chips[chip], emulated)
        }
        y = responsePlot.bottom + labelPaint.fontSpacing + 4 * density

        if (isLive) {
            drawVoicesLegend(canvas, live.chips[chip], emulated, left, right, y)
        }
        y += lineHeight * VOICES + 8 * density

        canvas.drawText(
            ellipsize(panelTitle, right - left, labelPaint),
            left,
            y - labelPaint.ascent(),
            labelPaint
        )
        y += labelPaint.fontSpacing + 2 * density
        cutoffPlot.set(left + labelWidth, y, right - 4 * density, y + cutoffHeight(width))
        drawCutoffPanel(canvas, curves, emulated)
    }

    private fun makeCurves() = with(SidFilterModel) {
        Curves(
            cutoff6581(state.settings),
            cutoff8580(state.settings),
            reference6581(state.settings),
            reference8580(state.settings)
        )
    }

    private fun resetTexts() = textKeys.fill(Long.MIN_VALUE)

    private inline fun drawText(
        canvas: Canvas,
        slot: Int,
        key: Long,
        maxWidth: Float,
        x: Float,
        y: Float,
        paint: Paint,
        make: () -> String,
    ) {
        if (textKeys[slot] != key || texts[slot] == null) {
            textKeys[slot] = key
            val text = make()
            if (paint === summaryPaint) {
                fitTextSize(text, maxWidth)
            }
            texts[slot] = ellipsize(text, maxWidth, paint)
        }
        canvas.drawText(texts[slot]!!, x, y, paint)
    }

    private fun headerKey() = (if (live.count > 1) live.count * 4L + selectedChip else 0L)

    private fun header() = buildString {
        val s = state.settings
        append(if (state.is8580) "MOS8580" else "MOS6581")
        append(if (s.residFp) " · reSIDfp" else " · reSID")
        append(" · ")
        append(state.presetName.ifEmpty { context.getString(R.string.pref_zxtune_sid_filter_curve_custom) })
        if (live.count > 1) {
            append(" · ")
            append(context.getString(R.string.pref_zxtune_sid_filter_response_chip, selectedChip + 1, live.count))
        }
    }

    private fun cutoffHz(chip: ChipState, emulated: DoubleArray) = emulated[chip.cutoff.coerceIn(0, SidFilterModel.CUTOFF_VALUES - 1)]

    private fun summaryKey(chip: ChipState, emulated: DoubleArray): Long {
        val hz = SidDescription.roundCutoff(cutoffHz(chip, emulated).toFloat())
        val routed = if (chip.routing and 0x0f != 0) 1L else 0L
        return (chip.mode and 7).toLong() or (chip.resonance.toLong() shl 4) or (routed shl 8) or (hz.toLong() shl 9)
    }

    private fun summary(chip: ChipState, emulated: DoubleArray): String {
        val hz = SidDescription.roundCutoff(cutoffHz(chip, emulated).toFloat())
        val text = context.getString(
            R.string.pref_zxtune_sid_filter_response_summary,
            modeNames[chip.mode and 7],
            SidDescription.cutoffWord(hz),
            chip.resonance * 100 / 15
        )
        // the most common reason of "filter does nothing"
        return if (chip.routing and 0x0f == 0) {
            text + " · " + context.getString(R.string.pref_zxtune_sid_filter_response_no_voices)
        } else {
            text
        }
    }

    private fun inverseQ(chip: ChipState) = SidFilterModel.inverseQ(chip.resonance, state.is8580, state.settings.residFp)

    private fun drawResponseGrid(canvas: Canvas) {
        val plot = responsePlot
        for (db in DB_GRID) {
            val y = dbToY(db.toDouble())
            canvas.drawLine(plot.left, y, plot.right, y, if (db == 0) unityPaint else gridPaint)
            val label = if (db > 0) "+$db" else db.toString()
            canvas.drawText(label, paddingLeft.toFloat(), y + labelPaint.textSize / 3, labelPaint)
        }
        val bottom = plot.bottom + labelPaint.fontSpacing
        var lastRight = Float.NEGATIVE_INFINITY
        for (idx in FREQ_GRID.indices) {
            val freq = FREQ_GRID[idx]
            val x = hzToX(freq.toDouble())
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            val label = FREQ_LABELS[idx]
            val w = labelPaint.measureText(label)
            val pos = (x - w / 2).coerceIn(plot.left, plot.right - w)
            // labels are skipped on narrow screens instead of overlapping
            if (pos > lastRight + 2 * density) {
                canvas.drawText(label, pos, bottom, labelPaint)
                lastRight = pos + w
            }
        }
    }

    private fun drawResponse(canvas: Canvas, chip: ChipState, emulated: DoubleArray, paint: Paint, fill: Paint?) {
        val plot = responsePlot
        val mode = chip.mode and 7
        val fc = cutoffHz(chip, emulated)
        val invQ = inverseQ(chip)
        path.rewind()
        val step = 2 * density
        var x = plot.left
        var prevHz = 0.0
        while (true) {
            val hz = xToHz(x)
            // resonance peak and notch are narrow, so exact cutoff point is not skipped
            if (fc > prevHz && fc < hz && prevHz != 0.0) {
                path.lineTo(hzToX(fc), dbToY(response(mode, fc, fc, invQ)))
            }
            val y = dbToY(response(mode, hz, fc, invQ))
            if (prevHz == 0.0) path.moveTo(x, y) else path.lineTo(x, y)
            prevHz = hz
            if (x >= plot.right) {
                break
            }
            x = minOf(x + step, plot.right)
        }
        if (fill != null) {
            fillPath.set(path)
            fillPath.lineTo(plot.right, plot.bottom)
            fillPath.lineTo(plot.left, plot.bottom)
            fillPath.close()
            canvas.drawPath(fillPath, fill)
            if (fc in MIN_RESPONSE_HZ..MAX_RESPONSE_HZ) {
                val cx = hzToX(fc)
                canvas.drawLine(cx, plot.top, cx, plot.bottom, cutoffLinePaint)
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun response(mode: Int, hz: Double, fc: Double, invQ: Double) = if (mode == 0) {
        SidFilterModel.MIN_DB
    } else {
        SidFilterModel.responseDb(mode, hz, fc, invQ)
    }

    private enum class VoiceMode { OFF, BYPASS, FILTERED, MUTED }

    private fun voiceMode(chip: ChipState, voice: Int): VoiceMode {
        val mode = chip.mode
        val routing = chip.routing
        val filtered = routing and (1 shl voice) != 0
        if (voice == EXT) {
            return if (filtered) VoiceMode.FILTERED else VoiceMode.BYPASS
        }
        val control = chip.control(voice)
        return when {
            // reset or no waveform produce no tone, released envelope is silent
            control and 0x08 != 0 || control and 0xf0 == 0 || chip.envelope(voice) == 0 -> VoiceMode.OFF

            chip.frequency(voice) == 0 && control and 0x80 == 0 -> VoiceMode.OFF

            SidDescription.isMuted(voice, mode, routing) -> VoiceMode.MUTED

            filtered && mode and 7 == 0 -> VoiceMode.MUTED

            filtered -> VoiceMode.FILTERED

            else -> VoiceMode.BYPASS
        }
    }

    private fun voiceHz(chip: ChipState, voice: Int) = SidFilterModel.oscillatorHz(chip.frequency(voice), live.clock)

    private fun voiceDb(chip: ChipState, voice: Int, emulated: DoubleArray) = SidFilterModel.responseDb(
        chip.mode and 7,
        voiceHz(chip, voice),
        cutoffHz(chip, emulated),
        inverseQ(chip)
    )

    private fun isNoise(chip: ChipState, voice: Int) = chip.control(voice) and 0x80 != 0

    private fun drawVoiceMarkers(canvas: Canvas, chip: ChipState, emulated: DoubleArray) {
        val unity = dbToY(0.0)
        val radius = 5 * density
        for (voice in 0 until EXT) {
            val vm = voiceMode(chip, voice)
            if (vm == VoiceMode.OFF || vm == VoiceMode.MUTED) {
                continue
            }
            val hz = voiceHz(chip, voice).coerceIn(MIN_RESPONSE_HZ, MAX_RESPONSE_HZ)
            val x = hzToX(hz)
            val color = VOICE_COLORS[voice]
            // louder voices are more visible
            val alpha = 110 + 145 * chip.envelope(voice) / 255
            if (vm == VoiceMode.FILTERED) {
                val y = dbToY(voiceDb(chip, voice, emulated))
                voicePaint.color = color
                voicePaint.alpha = alpha
                // stem from unity gain shows attenuation or boost
                canvas.drawLine(x, unity, x, y, voicePaint)
                canvas.drawCircle(x, y, radius, voicePaint)
                drawVoiceLabel(canvas, voice, x, y, radius)
            } else {
                voiceOutlinePaint.color = color
                voiceOutlinePaint.alpha = alpha
                canvas.drawCircle(x, unity, radius, voiceOutlinePaint)
                drawVoiceLabel(canvas, voice, x, unity, radius)
            }
        }
    }

    // beside marker, so attenuation stem is not crossed
    private fun drawVoiceLabel(canvas: Canvas, voice: Int, x: Float, y: Float, radius: Float) {
        val label = VOICE_LABELS[voice]
        voiceNamePaint.color = VOICE_COLORS[voice]
        val w = voiceNamePaint.measureText(label)
        val gap = radius + 3 * density
        val left = if (x + gap + w <= responsePlot.right) x + gap else x - gap - w
        val baseline = (y + voiceNamePaint.textSize / 3).coerceIn(
            responsePlot.top - voiceNamePaint.ascent(),
            responsePlot.bottom - voiceNamePaint.descent()
        )
        canvas.drawText(label, left, baseline, voiceNamePaint)
    }

    private fun drawVoicesLegend(canvas: Canvas, chip: ChipState, emulated: DoubleArray, left: Float, right: Float, top: Float) {
        val swatch = 4 * density
        var y = top
        val nameWidth = voiceNamePaint.measureText("Ext") + 6 * density
        for (voice in 0 until VOICES) {
            val baseline = y - textPaint.ascent()
            val color = VOICE_COLORS[voice]
            voicePaint.color = color
            voicePaint.alpha = 255
            val cy = baseline - textPaint.textSize / 3
            canvas.drawCircle(left + swatch, cy, swatch, voicePaint)
            val nameX = left + swatch * 2 + 4 * density
            voiceNamePaint.color = color
            canvas.drawText(VOICE_LABELS[voice], nameX, baseline, voiceNamePaint)
            val textX = nameX + nameWidth
            drawText(canvas, TEXT_VOICES + voice, voiceKey(chip, voice, emulated), right - textX, textX, baseline, textPaint) {
                voiceText(chip, voice, emulated)
            }
            y += lineHeight
        }
    }

    private fun voiceKey(chip: ChipState, voice: Int, emulated: DoubleArray): Long {
        val vm = voiceMode(chip, voice)
        var key = vm.ordinal.toLong()
        if (voice == EXT || vm == VoiceMode.OFF || vm == VoiceMode.MUTED) {
            return key
        }
        val hz = voiceHz(chip, voice).roundToInt()
        key = key or (hz.toLong() shl 4) or ((if (isNoise(chip, voice)) 1L else 0L) shl 3)
        if (vm == VoiceMode.FILTERED) {
            key = key or ((voiceDb(chip, voice, emulated).roundToInt() + 1000).toLong() shl 40)
        }
        return key
    }

    private fun voiceText(chip: ChipState, voice: Int, emulated: DoubleArray): String {
        val vm = voiceMode(chip, voice)
        if (voice == EXT) {
            return extWord + " — " + context.getString(
                if (vm == VoiceMode.FILTERED) R.string.pref_zxtune_sid_filter_voice_ext_filtered else R.string.pref_zxtune_sid_filter_voice_bypass
            )
        }
        val state = when (vm) {
            VoiceMode.OFF -> return context.getString(R.string.pref_zxtune_sid_filter_voice_off)

            VoiceMode.MUTED -> return context.getString(R.string.pref_zxtune_sid_filter_voice_muted)

            VoiceMode.BYPASS -> context.getString(R.string.pref_zxtune_sid_filter_voice_bypass)

            VoiceMode.FILTERED -> {
                val db = voiceDb(chip, voice, emulated).roundToInt()
                when {
                    db <= SidFilterModel.MIN_DB.toInt() + 1 -> context.getString(R.string.pref_zxtune_sid_filter_voice_removed)
                    db < 0 -> context.getString(R.string.pref_zxtune_sid_filter_voice_cut, -db)
                    db > 0 -> context.getString(R.string.pref_zxtune_sid_filter_voice_boost, db)
                    else -> context.getString(R.string.pref_zxtune_sid_filter_voice_unchanged)
                }
            }
        }
        val hz = voiceHz(chip, voice).toFloat()
        val pitch = if (isNoise(chip, voice)) {
            noiseWord
        } else {
            val note = ChipWidgets.noteName(hz)
            if (note.isEmpty()) formatHz(hz.toDouble()) else "$note · ${formatHz(hz.toDouble())}"
        }
        return "$pitch — $state"
    }

    private fun drawCutoffPanel(canvas: Canvas, curves: Curves, active: DoubleArray) {
        val plot = cutoffPlot
        if (plot.width() <= 0 || plot.height() <= 0) {
            return
        }
        for (freq in CUTOFF_FREQ_GRID) {
            val y = freqToY(freq.toDouble())
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
            val label = if (freq >= 1000) "${freq / 1000}k" else freq.toString()
            canvas.drawText(label, paddingLeft.toFloat(), y + labelPaint.textSize / 3, labelPaint)
        }
        val bottom = plot.bottom + labelPaint.fontSpacing
        for (fc in CUTOFF_GRID) {
            val x = cutoffToX(fc)
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            val label = fc.toString()
            val w = labelPaint.measureText(label)
            canvas.drawText(label, (x - w / 2).coerceIn(plot.left, plot.right - w), bottom, labelPaint)
        }
        val other = if (state.is8580) curves.emulated6581 else curves.emulated8580
        drawCurve(canvas, other, otherCurvePaint)
        val reference = if (state.is8580) curves.reference8580 else curves.reference6581
        reference?.let {
            drawCurve(canvas, it, referencePaint)
        }
        drawCurve(canvas, active, curvePaint)
        if (reference != null) {
            drawLegend(canvas)
        }
        for (chip in 0 until live.count) {
            val fc = live.chips[chip].cutoff.coerceIn(0, SidFilterModel.CUTOFF_VALUES - 1)
            val x = cutoffToX(fc)
            val y = freqToY(active[fc])
            markerPaint.alpha = if (chip == selectedChip) 255 else 110
            canvas.drawLine(x, plot.top, x, plot.bottom, markerPaint)
            canvas.drawCircle(x, y, 4 * density, markerPaint)
        }
    }

    private fun drawCurve(canvas: Canvas, values: DoubleArray, paint: Paint) {
        path.rewind()
        val step = max(1, values.size / max(1, cutoffPlot.width().toInt()))
        var idx = 0
        while (idx < values.size) {
            val x = cutoffToX(idx)
            val y = freqToY(values[idx])
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            idx += step
        }
        path.lineTo(cutoffToX(values.size - 1), freqToY(values.last()))
        canvas.drawPath(path, paint)
    }

    // Bottom right corner of plot is free for any curve
    private fun drawLegend(canvas: Canvas) {
        val sample = 18 * density
        val gap = 6 * density
        var y = cutoffPlot.bottom - gap - labelPaint.descent()
        for (idx in 0 until 2) {
            val text = legendTexts[idx]
            val paint = if (idx == 0) referencePaint else curvePaint
            val textWidth = labelPaint.measureText(text)
            val x = cutoffPlot.right - gap - textWidth
            canvas.drawText(text, x, y, labelPaint)
            val lineY = y - labelPaint.textSize / 3
            canvas.drawLine(x - gap - sample, lineY, x - gap, lineY, paint)
            y -= labelPaint.fontSpacing
        }
    }

    // the most important line is shrunk on narrow screens instead of being cut
    private fun fitTextSize(text: String, maxWidth: Float) {
        val normal = textPaint.textSize
        summaryPaint.textSize = normal
        val width = summaryPaint.measureText(text)
        if (width > maxWidth) {
            summaryPaint.textSize = maxOf(normal * maxWidth / width, normal * 0.75f)
        }
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) {
            return text
        }
        val count = paint.breakText(text, true, maxWidth - paint.measureText("…"), null)
        return text.take(count) + "…"
    }

    private fun hzToX(hz: Double): Float {
        val pos = (ln(hz.coerceIn(MIN_RESPONSE_HZ, MAX_RESPONSE_HZ) / MIN_RESPONSE_HZ) / RESPONSE_SPAN).toFloat()
        return responsePlot.left + responsePlot.width() * pos
    }

    private fun xToHz(x: Float) = MIN_RESPONSE_HZ * kotlin.math.exp(RESPONSE_SPAN * (x - responsePlot.left) / responsePlot.width())

    private fun dbToY(db: Double): Float {
        val pos = ((db.coerceIn(MIN_PLOT_DB, MAX_PLOT_DB) - MIN_PLOT_DB) / (MAX_PLOT_DB - MIN_PLOT_DB)).toFloat()
        return responsePlot.bottom - responsePlot.height() * pos
    }

    private fun cutoffToX(fc: Int) = cutoffPlot.left + cutoffPlot.width() * fc / (SidFilterModel.CUTOFF_VALUES - 1)

    private fun freqToY(hz: Double): Float {
        val pos = (ln(hz.coerceIn(MIN_FREQ, MAX_FREQ) / MIN_FREQ) / ln(MAX_FREQ / MIN_FREQ)).toFloat()
        return cutoffPlot.bottom - cutoffPlot.height() * pos
    }

    private fun themeColor(@AttrRes attr: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) {
            return fallback
        }
        return if (value.resourceId != 0) {
            runCatching { ContextCompat.getColor(context, value.resourceId) }.getOrDefault(fallback)
        } else {
            value.data
        }
    }

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        const val MAX_CHIPS = 3

        /** Voices 1..3 and external input */
        const val VOICES = 4
        private const val EXT = 3

        private const val CUTOFF_COLOR = 0xffff7043.toInt()

        // distinct from accent (curve) and cutoff marker colors, readable on both dark and light backgrounds
        val VOICE_COLORS = intArrayOf(0xffffa726.toInt(), 0xff66bb6a.toInt(), 0xffec407a.toInt(), 0xff90a4ae.toInt())
        private val VOICE_LABELS = arrayOf("V1", "V2", "V3", "Ext")

        private const val TEXT_HEADER = 0
        private const val TEXT_SUMMARY = 1
        private const val TEXT_VOICES = 2
        private const val TEXTS = TEXT_VOICES + VOICES

        private const val MIN_RESPONSE_HZ = 20.0
        private const val MAX_RESPONSE_HZ = 20000.0
        private val RESPONSE_SPAN = ln(MAX_RESPONSE_HZ / MIN_RESPONSE_HZ)

        // highest 6581 resonance peak is +24dB (see SidFilterModel.inverseQ), lower values are inaudible in mix
        private const val MAX_PLOT_DB = 24.0
        private const val MIN_PLOT_DB = -48.0
        private val DB_GRID = intArrayOf(24, 12, 0, -12, -24, -36, -48)
        private val FREQ_GRID = intArrayOf(20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000)
        private val FREQ_LABELS = arrayOf("20", "50", "100", "200", "500", "1k", "2k", "5k", "10k", "20k")

        private const val MIN_FREQ = 30.0
        private const val MAX_FREQ = 24000.0
        private val CUTOFF_FREQ_GRID = intArrayOf(100, 1000, 10000)
        private val CUTOFF_GRID = intArrayOf(0, 512, 1024, 1536, 2047)

        fun formatHz(hz: Double) = if (hz >= 1000) {
            String.format(java.util.Locale.US, "%.1f kHz", hz / 1000)
        } else {
            "${hz.roundToInt()} Hz"
        }
    }
}
