/**
 * @file
 * @brief SID filter live state: big values, frequency response, history lanes and voices
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
import android.view.MotionEvent
import android.view.View
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import app.zxtune.R
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.ChipState
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Laid out like a hardware synth display, without sentences:
 * - caption with chip model and preset, switches of displayed chip and of main plot;
 * - mode, cutoff and resonance as big values;
 * - main plot: frequency response (dB by logarithmic frequency) with voices marked at their fundamental frequencies,
 *   or (by tap) cutoff frequency by cutoff register value as emulated with current settings (see [SidFilterModel])
 *   with reference curve of selected preset, to compare settings and presets;
 * - history lanes of cutoff and voices pitches (see [FilterLanesPainter]);
 * - voices chips with note and gain applied by filter.
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
     * Chip displayed, changed by tap on chip switch for 2SID/3SID tunes
     */
    var selectedChip = 0
        set(value) {
            if (field != value) {
                field = value
                resetTexts()
                invalidate()
            }
        }

    /** Recent states drawn as lanes, filled by the feed */
    var history: FilterHistory? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Main plot shows cutoff register curve instead of live response, switched by tap */
    var showDacCurve = false
        set(value) {
            if (field != value) {
                field = value
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

    // Text is rebuilt only when displayed value is changed
    private class CachedText {
        var key = Long.MIN_VALUE
        var text = ""

        inline fun get(newKey: Long, make: () -> String): String {
            if (newKey != key) {
                key = newKey
                text = make()
            }
            return text
        }
    }

    private val density = resources.displayMetrics.density
    private val textColor = themeColor(android.R.attr.textColorPrimary, 0xffe0e0e0.toInt())
    private val secondaryColor = themeColor(android.R.attr.textColorSecondary, 0xff909090.toInt())
    private val accentColor = themeColor(androidx.appcompat.R.attr.colorAccent, 0xff4fc3f7.toInt())

    // the only three text sizes
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryColor
        textSize = sp(CAPTION_SP)
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = sp(BODY_SP)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = sp(VALUE_SP)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bodyDimPaint = Paint(bodyPaint).apply {
        color = secondaryColor
    }
    private val voiceNamePaint = Paint(bodyPaint).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val chipValuePaint = Paint(valuePaint)
    private val valueSpacing = valuePaint.fontSpacing
    private val valueAscent = valuePaint.ascent()

    private val plotPaint = Paint().apply {
        color = textColor
        alpha = 12
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = secondaryColor
        alpha = 60
        strokeWidth = density
    }
    private val unityPaint = Paint(gridPaint).apply {
        alpha = 130
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        alpha = 24
        style = Paint.Style.FILL
    }
    private val otherCurvePaint = Paint(curvePaint).apply {
        color = secondaryColor
        alpha = 110
        strokeWidth = 1.2f * density
    }
    private val referencePaint = Paint(curvePaint).apply {
        alpha = 170
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CUTOFF_COLOR
        strokeWidth = 1.5f * density
    }
    private val voicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2 * density
    }
    private val voiceDashPaint = Paint(voicePaint).apply {
        pathEffect = DashPathEffect(floatArrayOf(4 * density, 4 * density), 0f)
    }
    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        alpha = 10
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val boxDashPaint = Paint(boxPaint).apply {
        pathEffect = DashPathEffect(floatArrayOf(5 * density, 4 * density), 0f)
    }
    private val pillPaint = Paint(boxPaint).apply {
        color = secondaryColor
        alpha = 120
        strokeWidth = density
    }
    private val pillActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        alpha = 70
    }

    private val responsePlot = RectF()
    private val cutoffPlot = RectF()
    private val lanesRect = RectF()
    private val chipPill = RectF()
    private val dacPill = RectF()
    private val box = RectF()
    private val path = Path()
    private val fillPath = Path()
    private val lanesPainter = FilterLanesPainter(context)

    private val locale = ConfigurationCompat.getLocales(resources.configuration)[0] ?: java.util.Locale.US
    private val modeNames = resources.getStringArray(R.array.pref_zxtune_sid_filter_response_modes)
    private val noteNames = resources.getStringArray(R.array.pref_zxtune_sid_filter_notes)
    private val noiseWord = context.getString(R.string.pref_zxtune_sid_filter_voice_noise)
    private val directWord = context.getString(R.string.pref_zxtune_sid_filter_voice_direct)
    private val dacWord = context.getString(R.string.pref_zxtune_sid_filter_dac_curve)
    private val captions = arrayOf(
        R.string.pref_zxtune_sid_filter_caption_mode,
        R.string.pref_zxtune_sid_filter_caption_cutoff,
        R.string.pref_zxtune_sid_filter_caption_resonance,
    ).map { context.getString(it).uppercase(locale) }.toTypedArray()
    private val legendTexts = arrayOf(
        context.getString(R.string.pref_zxtune_sid_filter_curve_legend_reference),
        context.getString(R.string.pref_zxtune_sid_filter_curve_legend_emulated),
    )

    private val topCaption = CachedText()
    private val chipText = CachedText()
    private val cutoffText = CachedText()
    private val cutoffNote = CachedText()
    private val resonanceText = CachedText()
    private val voiceNotes = Array(EXT) { CachedText() }
    private val voiceGains = Array(EXT) { CachedText() }
    private val hintLines = arrayOfNulls<String>(HINT_LINES)
    private var hintSource: String? = null
    private var hintWidth = 0f

    // values are shrunk on narrow screens, for the widest possible content so size doesn't jump while playing
    private var layoutWidth = -1
    private var headerValueSize = 0f
    private var chipValueSize = 0f

    private var touchX = 0f
    private var touchY = 0f

    init {
        setOnClickListener {
            if (chipPill.contains(touchX, touchY) && live.count > 1) {
                selectedChip = (selectedChip + 1) % live.count
            } else if (touchY < lanesRect.top) {
                showDacCurve = !showDacCurve
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchX = event.x
            touchY = event.y
        }
        return super.onTouchEvent(event)
    }

    private val gap
        get() = 14 * density

    private val pillHeight
        get() = captionPaint.fontSpacing + 8 * density

    private val headerHeight
        get() = captionPaint.fontSpacing + 2 * density + valueSpacing

    private val chipHeight
        get() = 20 * density + bodyPaint.fontSpacing + 2 * density + valueSpacing

    private val axisHeight
        get() = captionPaint.fontSpacing + 2 * density

    private fun plotHeight(width: Int) = (width * 0.28f).coerceIn(96 * density, 170 * density)

    private fun fixedHeight(width: Int) = pillHeight + gap + headerHeight + gap + plotHeight(width) + axisHeight + gap + gap + chipHeight

    // history is the main part
    private fun lanesHeight(width: Int) = fixedHeight(width) * LANES_SHARE / (1 - LANES_SHARE)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desired = paddingTop + paddingBottom + fixedHeight(width) + lanesHeight(width)
        setMeasuredDimension(width, resolveSize(desired.roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val curves = curves ?: makeCurves().also { curves = it }
        val emulated = if (state.is8580) curves.emulated8580 else curves.emulated6581
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        if (layoutWidth != width) {
            layoutWidth = width
            fitValues(right - left)
        }
        val chip = selectedChip.takeIf { it < live.count } ?: 0
        val ch = live.chips[chip].takeIf { live.count > 0 }
        var y = paddingTop.toFloat()

        drawTopRow(canvas, left, right, y)
        y += pillHeight + gap
        drawHeader(canvas, ch, emulated, left, right, y)
        y += headerHeight + gap

        val plotBottom = y + plotHeight(width)
        if (showDacCurve) {
            val labelWidth = captionPaint.measureText("10k") + 6 * density
            cutoffPlot.set(left + labelWidth, y, right, plotBottom)
            responsePlot.setEmpty()
            canvas.drawRect(cutoffPlot, plotPaint)
            drawCutoffPanel(canvas, curves, emulated)
        } else {
            responsePlot.set(left, y, right, plotBottom)
            cutoffPlot.setEmpty()
            canvas.drawRect(responsePlot, plotPaint)
            drawResponseGrid(canvas)
            if (ch != null) {
                for (other in 0 until live.count) {
                    if (other != chip) {
                        drawResponse(canvas, live.chips[other], emulated, otherCurvePaint, null)
                    }
                }
                drawResponse(canvas, ch, emulated, curvePaint, fillPaint)
            }
        }
        y = plotBottom + axisHeight + gap

        lanesRect.set(left, y, right, y + lanesHeight(width))
        val hist = history
        if (ch != null && hist != null) {
            lanesPainter.draw(canvas, lanesRect, hist)
        } else {
            canvas.drawRect(lanesRect, plotPaint)
            if (ch == null) {
                drawHint(canvas)
            }
        }
        y = lanesRect.bottom + gap

        drawVoices(canvas, ch, emulated, left, right, y)
    }

    private fun makeCurves() = with(SidFilterModel) {
        Curves(
            cutoff6581(state.settings),
            cutoff8580(state.settings),
            reference6581(state.settings),
            reference8580(state.settings)
        )
    }

    private fun resetTexts() {
        topCaption.key = Long.MIN_VALUE
        chipText.key = Long.MIN_VALUE
    }

    private fun fitValues(width: Float) {
        valuePaint.textSize = sp(VALUE_SP)
        val unitGap = 4 * density
        val modeWidth = modeNames.maxOf { valuePaint.measureText(it) }
        val cutoffWidth = valuePaint.measureText("8.8") + bodyPaint.measureText(" kHz ≈ ") +
            noteNames.maxOf { bodyPaint.measureText(it + "8") }
        val resonanceWidth = valuePaint.measureText("100") + unitGap + bodyPaint.measureText("%")
        var scale = 1f
        for (col in 0 until COLUMNS) {
            val needed = when (col) {
                0 -> modeWidth
                1 -> cutoffWidth
                else -> resonanceWidth
            }
            scale = minOf(scale, (columnWidth(col, width) - gap) / needed)
        }
        headerValueSize = sp(VALUE_SP) * scale.coerceAtLeast(MIN_SCALE)

        val inner = voiceChipWidth(width) - 2 * CHIP_PADDING * density
        val gainWidth = maxOf(valuePaint.measureText("−48") + unitGap + bodyPaint.measureText("dB"), valuePaint.measureText(directWord))
        chipValueSize = sp(VALUE_SP) * (inner / gainWidth).coerceIn(MIN_SCALE, 1f)
    }

    private fun columnWidth(col: Int, width: Float) = width * COLUMN_SHARES[col]

    private fun drawTopRow(canvas: Canvas, left: Float, right: Float, top: Float) {
        val h = pillHeight
        val baseline = top + (h - captionPaint.ascent() - captionPaint.descent()) / 2
        var pillsLeft = right
        pillsLeft = drawPill(canvas, dacPill, dacWord, pillsLeft, top, baseline, showDacCurve)
        if (live.count > 1) {
            val text = chipText.get(live.count * 4L + selectedChip) { "SID ${selectedChip + 1}/${live.count}" }
            pillsLeft = drawPill(canvas, chipPill, text, pillsLeft - 8 * density, top, baseline, false)
        } else {
            chipPill.setEmpty()
        }
        val text = topCaption.get(0L) {
            val s = state.settings
            buildString {
                append(if (state.is8580) "MOS8580" else "MOS6581")
                append(if (s.residFp) " · reSIDfp · " else " · reSID · ")
                append(state.presetName.ifEmpty { context.getString(R.string.pref_zxtune_sid_filter_curve_custom) })
            }
        }
        canvas.drawText(ellipsize(text, pillsLeft - left - gap, captionPaint), left, baseline, captionPaint)
    }

    private fun drawPill(canvas: Canvas, rect: RectF, text: String, right: Float, top: Float, baseline: Float, active: Boolean): Float {
        val pad = 10 * density
        val w = captionPaint.measureText(text) + 2 * pad
        rect.set(right - w, top, right, top + pillHeight)
        val radius = rect.height() / 2
        if (active) {
            canvas.drawRoundRect(rect, radius, radius, pillActivePaint)
        }
        rect.inset(pillPaint.strokeWidth / 2, pillPaint.strokeWidth / 2)
        canvas.drawRoundRect(rect, radius, radius, pillPaint)
        captionPaint.color = if (active) textColor else secondaryColor
        canvas.drawText(text, rect.left + pad, baseline, captionPaint)
        captionPaint.color = secondaryColor
        return rect.left
    }

    private fun cutoffHz(chip: ChipState, emulated: DoubleArray) = emulated[chip.cutoff.coerceIn(0, SidFilterModel.CUTOFF_VALUES - 1)]

    private fun drawHeader(canvas: Canvas, chip: ChipState?, emulated: DoubleArray, left: Float, right: Float, top: Float) {
        val width = right - left
        val captionBaseline = top - captionPaint.ascent()
        val baseline = top + captionPaint.fontSpacing + 2 * density - valueAscent
        valuePaint.textSize = headerValueSize
        val unitGap = 4 * density
        var x = left
        for (col in 0 until COLUMNS) {
            canvas.drawText(captions[col], x, captionBaseline, captionPaint)
            if (chip == null) {
                drawNone(canvas, x, baseline, valuePaint)
            } else {
                when (col) {
                    0 -> canvas.drawText(modeNames[chip.mode and 7], x, baseline, valuePaint)

                    1 -> {
                        val hz = SidDescription.roundCutoff(cutoffHz(chip, emulated).toFloat())
                        val number = cutoffText.get(hz.toLong()) {
                            if (hz >= 1000) String.format(locale, if (hz >= 10000) "%.0f" else "%.1f", hz / 1000f) else hz.toString()
                        }
                        var pos = x
                        canvas.drawText(number, pos, baseline, valuePaint)
                        pos += valuePaint.measureText(number) + unitGap
                        val unit = if (hz >= 1000) "kHz" else "Hz"
                        canvas.drawText(unit, pos, baseline, bodyDimPaint)
                        pos += bodyDimPaint.measureText(unit)
                        val note = cutoffNote.get(semitone(hz.toFloat()).toLong()) {
                            noteName(hz.toFloat()).let { if (it.isEmpty()) it else " ≈ $it" }
                        }
                        canvas.drawText(note, pos, baseline, bodyDimPaint)
                    }

                    else -> {
                        val number = resonanceText.get(chip.resonance.toLong()) { (chip.resonance * 100 / 15).toString() }
                        canvas.drawText(number, x, baseline, valuePaint)
                        canvas.drawText("%", x + valuePaint.measureText(number) + unitGap, baseline, bodyDimPaint)
                    }
                }
            }
            x += columnWidth(col, width)
        }
    }

    private fun semitone(hz: Float) = if (hz > 0f) ChipWidgets.semitones(hz).roundToInt() else -1

    private fun noteName(hz: Float): String {
        val note = semitone(hz)
        return if (note in 0..131) noteNames[note % 12] + (note / 12) else ""
    }

    private fun inverseQ(chip: ChipState) = SidFilterModel.inverseQ(chip.resonance, state.is8580, state.settings.residFp)

    private fun drawResponseGrid(canvas: Canvas) {
        val plot = responsePlot
        val unity = dbToY(0.0)
        canvas.drawLine(plot.left, unity, plot.right, unity, unityPaint)
        canvas.drawText(ZERO_DB, plot.left + 6 * density, unity - captionPaint.descent() - 2 * density, captionPaint)
        val baseline = plot.bottom + 2 * density - captionPaint.ascent()
        for (idx in FREQ_GRID.indices) {
            val x = hzToX(FREQ_GRID[idx])
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            val label = FREQ_LABELS[idx]
            val w = captionPaint.measureText(label)
            canvas.drawText(label, x - w / 2, baseline, captionPaint)
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
            drawVoiceMarkers(canvas, chip, emulated, false)
        }
        canvas.drawPath(path, paint)
        if (fill != null) {
            drawVoiceMarkers(canvas, chip, emulated, true)
        }
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

    // lines under the curve, dots of filtered voices gain over it
    private fun drawVoiceMarkers(canvas: Canvas, chip: ChipState, emulated: DoubleArray, dots: Boolean) {
        val plot = responsePlot
        for (voice in 0 until EXT) {
            val vm = voiceMode(chip, voice)
            if (vm == VoiceMode.OFF || vm == VoiceMode.MUTED || (dots && vm != VoiceMode.FILTERED)) {
                continue
            }
            val x = hzToX(voiceHz(chip, voice).coerceIn(MIN_RESPONSE_HZ, MAX_RESPONSE_HZ))
            val paint = if (vm == VoiceMode.FILTERED) voicePaint else voiceDashPaint
            paint.color = VOICE_COLORS[voice]
            // louder voices are more visible
            paint.alpha = 110 + 145 * chip.envelope(voice) / 255
            if (dots) {
                canvas.drawCircle(x, dbToY(voiceDb(chip, voice, emulated)), 4.5f * density, paint)
            } else {
                canvas.drawLine(x, plot.top, x, plot.bottom, paint)
            }
        }
    }

    private fun voiceChipWidth(width: Float) = (width - EXT_CHIP_WIDTH * density - CHIP_GAP * density * EXT) / EXT

    private fun drawVoices(canvas: Canvas, chip: ChipState?, emulated: DoubleArray, left: Float, right: Float, top: Float) {
        val chipWidth = voiceChipWidth(right - left)
        val pad = CHIP_PADDING * density
        val radius = 10 * density
        val nameBaseline = top + pad - bodyPaint.ascent()
        val valueBaseline = top + pad + bodyPaint.fontSpacing + 2 * density - valueAscent
        chipValuePaint.textSize = chipValueSize
        var x = left
        for (voice in 0 until EXT) {
            box.set(x, top, x + chipWidth, top + chipHeight)
            val vm = chip?.let { voiceMode(it, voice) } ?: VoiceMode.OFF
            val color = VOICE_COLORS[voice]
            val sounding = vm == VoiceMode.FILTERED || vm == VoiceMode.BYPASS
            drawBox(canvas, radius, if (sounding) color else secondaryColor, vm == VoiceMode.BYPASS, if (sounding) 255 else 70)
            voiceNamePaint.color = color
            voiceNamePaint.alpha = if (sounding) 255 else 130
            val name = VOICE_LABELS[voice]
            canvas.drawText(name, x + pad, nameBaseline, voiceNamePaint)
            val inner = chipWidth - 2 * pad
            if (chip != null && sounding) {
                val hz = voiceHz(chip, voice).toFloat()
                val noise = isNoise(chip, voice)
                val note = voiceNotes[voice].get(if (noise) -2L else semitone(hz).toLong()) {
                    if (noise) noiseWord else noteName(hz)
                }
                val noteWidth = bodyDimPaint.measureText(note)
                // note is dropped rather than overlapping name on very narrow screens
                if (voiceNamePaint.measureText(name) + 4 * density + noteWidth <= inner) {
                    canvas.drawText(note, x + chipWidth - pad - noteWidth, nameBaseline, bodyDimPaint)
                }
            }
            when {
                chip == null || !sounding -> drawNone(canvas, x + pad, valueBaseline, chipValuePaint)

                vm == VoiceMode.BYPASS -> {
                    chipValuePaint.alpha = 190
                    canvas.drawText(directWord, x + pad, valueBaseline, chipValuePaint)
                    chipValuePaint.alpha = 255
                }

                else -> {
                    val db = voiceDb(chip, voice, emulated).roundToInt()
                    val gain = voiceGains[voice].get(db.toLong()) {
                        when {
                            db <= MIN_SHOWN_DB -> "−∞"
                            db < 0 -> "−${-db}"
                            db > 0 -> "+$db"
                            else -> "0"
                        }
                    }
                    canvas.drawText(gain, x + pad, valueBaseline, chipValuePaint)
                    canvas.drawText("dB", x + pad + chipValuePaint.measureText(gain) + 4 * density, valueBaseline, bodyDimPaint)
                }
            }
            x += chipWidth + CHIP_GAP * density
        }
        // external input is rarely used, so only its routing is shown
        box.set(x, top, right, top + chipHeight)
        val extFiltered = chip != null && voiceMode(chip, EXT) == VoiceMode.FILTERED
        drawBox(canvas, radius, VOICE_COLORS[EXT], !extFiltered, if (extFiltered) 255 else 110)
        captionPaint.color = VOICE_COLORS[EXT]
        val w = captionPaint.measureText(VOICE_LABELS[EXT])
        canvas.drawText(VOICE_LABELS[EXT], box.centerX() - w / 2, box.centerY() - (captionPaint.ascent() + captionPaint.descent()) / 2, captionPaint)
        captionPaint.color = secondaryColor
    }

    private fun drawNone(canvas: Canvas, x: Float, baseline: Float, paint: Paint) {
        paint.color = secondaryColor
        paint.alpha = 120
        canvas.drawText(NONE, x, baseline, paint)
        paint.color = textColor
    }

    // solid border for filtered, dashed for direct
    private fun drawBox(canvas: Canvas, radius: Float, color: Int, dashed: Boolean, alpha: Int) {
        canvas.drawRoundRect(box, radius, radius, boxFillPaint)
        val paint = if (dashed) boxDashPaint else boxPaint
        paint.color = color
        paint.alpha = alpha
        val inset = paint.strokeWidth / 2
        box.inset(inset, inset)
        canvas.drawRoundRect(box, radius, radius, paint)
        box.inset(-inset, -inset)
    }

    private fun drawHint(canvas: Canvas) {
        val text = state.hint
        val maxWidth = lanesRect.width() - 4 * gap
        if (text !== hintSource || maxWidth != hintWidth) {
            hintSource = text
            hintWidth = maxWidth
            breakLines(text, maxWidth)
        }
        val count = hintLines.count { it != null }
        val lineHeight = bodyPaint.fontSpacing
        var baseline = lanesRect.centerY() - lineHeight * count / 2 - bodyPaint.ascent()
        for (line in hintLines) {
            line ?: break
            canvas.drawText(line, lanesRect.centerX() - bodyDimPaint.measureText(line) / 2, baseline, bodyDimPaint)
            baseline += lineHeight
        }
    }

    private fun breakLines(text: String, maxWidth: Float) {
        hintLines.fill(null)
        var rest = text.trim()
        for (idx in 0 until HINT_LINES) {
            if (rest.isEmpty()) break
            var count = bodyDimPaint.breakText(rest, true, maxWidth, null)
            if (count < rest.length) {
                count = rest.lastIndexOf(' ', count).takeIf { it > 0 } ?: count
            }
            hintLines[idx] = if (idx == HINT_LINES - 1) ellipsize(rest, maxWidth, bodyDimPaint) else rest.take(count)
            rest = rest.drop(count).trim()
        }
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
            canvas.drawText(label, paddingLeft.toFloat(), y + captionPaint.textSize / 3, captionPaint)
        }
        val bottom = plot.bottom + 2 * density - captionPaint.ascent()
        for (fc in CUTOFF_GRID) {
            val x = cutoffToX(fc)
            canvas.drawLine(x, plot.top, x, plot.bottom, gridPaint)
            val label = fc.toString()
            val w = captionPaint.measureText(label)
            canvas.drawText(label, (x - w / 2).coerceIn(plot.left, plot.right - w), bottom, captionPaint)
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
        var y = cutoffPlot.bottom - gap - captionPaint.descent()
        for (idx in 0 until 2) {
            val text = legendTexts[idx]
            val paint = if (idx == 0) referencePaint else curvePaint
            val textWidth = captionPaint.measureText(text)
            val x = cutoffPlot.right - gap - textWidth
            canvas.drawText(text, x, y, captionPaint)
            val lineY = y - captionPaint.textSize / 3
            canvas.drawLine(x - gap - sample, lineY, x - gap, lineY, paint)
            y -= captionPaint.fontSpacing
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

        val VOICE_COLORS = intArrayOf(0xffffb547.toInt(), 0xff5fd38d.toInt(), 0xffff6fa5.toInt(), 0xff90a4ae.toInt())
        private val VOICE_LABELS = arrayOf("V1", "V2", "V3", "Ext")

        private const val CAPTION_SP = 11f
        private const val BODY_SP = 14f
        private const val VALUE_SP = 24f
        private const val MIN_SCALE = 0.6f

        private const val COLUMNS = 3
        private val COLUMN_SHARES = floatArrayOf(0.40f, 0.37f, 0.23f)

        private const val LANES_SHARE = 0.45f
        private const val CHIP_PADDING = 10f
        private const val CHIP_GAP = 8f
        private const val EXT_CHIP_WIDTH = 40f

        private const val HINT_LINES = 3
        private const val NONE = "—"
        private const val ZERO_DB = "0 dB"
        private const val MIN_SHOWN_DB = -60

        private const val MIN_RESPONSE_HZ = 20.0
        private const val MAX_RESPONSE_HZ = 20000.0
        private val RESPONSE_SPAN = ln(MAX_RESPONSE_HZ / MIN_RESPONSE_HZ)

        // highest 6581 resonance peak is +24dB (see SidFilterModel.inverseQ), lower values are inaudible in mix
        private const val MAX_PLOT_DB = 24.0
        private const val MIN_PLOT_DB = -48.0
        private val FREQ_GRID = doubleArrayOf(100.0, 1000.0, 10000.0)
        private val FREQ_LABELS = arrayOf("100", "1k", "10k")

        private const val MIN_FREQ = 30.0
        private const val MAX_FREQ = 24000.0
        private val CUTOFF_FREQ_GRID = intArrayOf(100, 1000, 10000)
        private val CUTOFF_GRID = intArrayOf(0, 1024, 2047)
    }
}
