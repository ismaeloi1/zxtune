/**
 * @file
 * @brief SID filter response drawn by oscilloscope rendering backends
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import app.zxtune.R
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.ChipState
import app.zxtune.playback.VoicesLayout
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Emulation settings affecting filter response and its layout
 * @param is8580 chip model if not reported by emulation status
 */
data class FilterSettings(
    val model: SidFilterModel.Settings = SidFilterModel.Settings(),
    val is8580: Boolean = false,
    /** Oscilloscope lanes are displayed under filter response */
    val withWaves: Boolean = true,
)

/**
 * Localized words and colors of filter view, loaded once per view
 */
internal class FilterResources(
    /** See SID mode register, 8 combinations of outputs */
    val modes: Array<String>,
    val noise: String,
    val direct: String,
    val filtered: String,
    val resonance: String,
    /** Lane title format by chip name */
    val title: String,
    val decimalSeparator: Char,
    /** The same as of dashboard */
    val voiceColors: IntArray,
) {
    companion object {
        fun of(ctx: Context) = with(ctx.resources) {
            FilterResources(
                getStringArray(R.array.pref_zxtune_sid_filter_response_modes),
                getString(R.string.pref_zxtune_sid_filter_voice_noise),
                getString(R.string.pref_zxtune_sid_filter_voice_direct),
                getString(R.string.scope_filter_voice_filtered),
                getString(R.string.scope_filter_resonance),
                getString(R.string.scope_filter_title),
                DecimalFormatSymbols.getInstance(ConfigurationCompat.getLocales(configuration)[0] ?: Locale.US).decimalSeparator,
                intArrayOf(R.color.lhuna_voice_1, R.color.lhuna_voice_2, R.color.lhuna_voice_3).map {
                    ContextCompat.getColor(ctx, it)
                }.toIntArray(),
            )
        }
    }
}

/**
 * Geometry of filter view, immutable, created on layout change (view size, chips) with static background and glyphs
 * atlas. Plot per chip, in the visual language of oscilloscope lanes: title band with chip's name and filter mode,
 * then frequency response (dB by logarithmic frequency). With waveforms, plots are placed in chips columns above
 * oscilloscope lanes (see [ScopeGrid]), whose title bands get routing tags of voices; else plots are stacked.
 */
internal class FilterLayout private constructor(
    val width: Int,
    val height: Int,
    private val grid: ScopeGrid,
    private val titles: Array<String>,
    private val labelTextSize: Float,
    val density: Float,
    res: FilterResources,
    /** Waveforms are drawn under plots */
    val withWaves: Boolean,
    // oscilloscope channels of chips' voices
    private val groupFirst: IntArray,
    private val groupVoices: IntArray,
) {
    val lanes = titles.size

    /** Oscilloscope lanes area */
    val wavesTop = if (withWaves) (height * FILTER_SHARE).roundToInt().toFloat() else height.toFloat()
    val wavesHeight = (height - wavesTop).toInt()

    private val laneLeft = FloatArray(lanes)
    private val laneRight = FloatArray(lanes)
    private val laneTop = FloatArray(lanes)
    private val laneBottom = FloatArray(lanes)

    init {
        for (lane in 0 until lanes) {
            if (withWaves) {
                val column = grid.columns.column(lane, 0)
                laneLeft[lane] = width * grid.columns.left(column)
                laneRight[lane] = width * grid.columns.right(column)
                laneTop[lane] = 0f
                laneBottom[lane] = wavesTop
            } else {
                laneLeft[lane] = 0f
                laneRight[lane] = width.toFloat()
                laneTop[lane] = height.toFloat() * lane / lanes
                laneBottom[lane] = height.toFloat() * (lane + 1) / lanes
            }
        }
    }

    private val laneHeight = laneBottom[0] - laneTop[0]
    val labelSize = ScopeGrid.labelSize(laneHeight, labelTextSize)
    private val band = labelSize * ScopeGrid.LABEL_BAND_TO_TEXT

    /** Text start in title band, see [ScopeGrid.drawBackground] */
    private val textPadding = labelSize * (ScopeGrid.LABEL_BAND_TO_TEXT - 1)
    private val smallSize = labelTextSize * SMALL_TO_LABEL

    // labels of oscilloscope lanes, see ScopeGrid
    private val wavesLabelSize = if (withWaves) ScopeGrid.labelSize(wavesHeight.toFloat() / grid.rows, labelTextSize) else 0f

    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = labelSize }
    val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = smallSize }
    private val wavesLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = wavesLabelSize }

    val atlas = GlyphAtlas(arrayOf(labelPaint, smallPaint, wavesLabelPaint), charsOf(res, titles))

    fun left(lane: Int) = laneLeft[lane] + (laneRight[lane] - laneLeft[lane]) * ScopeGrid.CELL_MARGIN

    fun right(lane: Int) = laneRight[lane] - (laneRight[lane] - laneLeft[lane]) * ScopeGrid.CELL_MARGIN

    // space for peak marker above plot
    fun plotTop(lane: Int) = laneTop[lane] + band + PEAK_PADDING_DP * density

    // space for notes ruler and cutoff tag at frequency axis
    fun plotBottom(lane: Int) = laneBottom[lane] - (smallSize * TAG_HEIGHT_TO_SIZE + 2 * density)

    fun hzToX(lane: Int, hz: Double): Float {
        val pos = (ln(hz.coerceIn(MIN_HZ, MAX_HZ) / MIN_HZ) / HZ_SPAN).toFloat()
        return left(lane) + (right(lane) - left(lane)) * pos
    }

    fun xToHz(lane: Int, x: Float) = MIN_HZ * exp(HZ_SPAN * (x - left(lane)) / (right(lane) - left(lane)))

    fun dbToY(lane: Int, db: Double): Float {
        val top = plotTop(lane)
        val pos = ((MAX_DB - db.coerceIn(MIN_DB, MAX_DB)) / (MAX_DB - MIN_DB)).toFloat()
        return top + (plotBottom(lane) - top) * pos
    }

    fun titleEnd(lane: Int) = left(lane) + textPadding + if (labelSize > 0) labelPaint.measureText(titles[lane]) else 0f

    fun titleBaseline(lane: Int) = laneTop[lane] + textPadding / 2 - labelPaint.ascent()

    /**
     * Static labels are not covered by tags
     */
    fun reserveLabels(lane: Int, tags: TagLayout) {
        val bottom = plotBottom(lane)
        val height = smallPaint.descent() - smallPaint.ascent()
        for (idx in DECADES.indices) {
            val x = hzToX(lane, DECADES[idx]) + 3 * density
            tags.reserve(x, bottom - height - density, x + smallPaint.measureText(DECADE_LABELS[idx]), bottom)
        }
        val y = dbToY(lane, 0.0)
        val r = right(lane)
        tags.reserve(r - smallPaint.measureText(ZERO_DB) - 2 * density, y - height - density, r, y)
    }

    /** Height of cutoff tag at frequency axis */
    val tagHeight = smallSize * TAG_HEIGHT_TO_SIZE

    /**
     * Oscilloscope channel of chip's voice, -1 if not displayed
     */
    fun channelOf(lane: Int, voice: Int): Int {
        if (!withWaves || lane >= groupFirst.size || voice >= groupVoices[lane]) {
            return -1
        }
        return (groupFirst[lane] + voice).takeIf { it < grid.channels } ?: -1
    }

    /** Baseline of oscilloscope lane's title */
    fun wavesBaseline(chan: Int): Float {
        val cellH = wavesHeight.toFloat() / grid.rows
        val padding = wavesLabelSize * (ScopeGrid.LABEL_BAND_TO_TEXT - 1)
        return wavesTop + cellH * grid.row(chan) + padding / 2 - wavesLabelPaint.ascent()
    }

    private val wavesTitleEnds = FloatArray(grid.channels) { chan ->
        val padding = wavesLabelSize * (ScopeGrid.LABEL_BAND_TO_TEXT - 1)
        width * grid.left(chan) + padding + (grid.labels.getOrNull(chan)?.let { wavesLabelPaint.measureText(it) } ?: 0f) + wavesLabelSize
    }

    /** End of oscilloscope lane's title with space after it */
    fun wavesTitleEnd(chan: Int) = wavesTitleEnds[chan]

    /** Right edge of oscilloscope lane's title area */
    fun wavesRight(chan: Int): Float {
        val padding = wavesLabelSize * (ScopeGrid.LABEL_BAND_TO_TEXT - 1)
        return width * (grid.left(chan) + grid.width(chan)) - padding
    }

    val hasWavesLabels
        get() = wavesLabelSize > 0

    /** Oscilloscope traces under plots, see [ScopeGrid.traces] */
    fun traces(target: FloatArray) = if (withWaves) {
        grid.traces(width, wavesHeight, labelTextSize, target, wavesTop)
        grid.channels
    } else {
        0
    }

    /**
     * White with alpha: title bands, grid of decades, notes ruler and unity gain, axis labels, oscilloscope lanes
     */
    fun drawBackground(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
        val gridPaint = Paint().apply {
            color = Color.argb(GRID_ALPHA, 255, 255, 255)
            strokeWidth = 1f
        }
        val unity = Paint(gridPaint).apply { color = Color.argb(UNITY_ALPHA, 255, 255, 255) }
        val ruler = Paint(gridPaint).apply { color = Color.argb(RULER_ALPHA, 255, 255, 255) }
        val title = Paint(labelPaint).apply { color = Color.argb(LABEL_ALPHA, 255, 255, 255) }
        val axis = Paint(smallPaint).apply { color = Color.argb(AXIS_ALPHA, 255, 255, 255) }
        val notes = Paint(smallPaint).apply {
            color = Color.argb(RULER_LABEL_ALPHA, 255, 255, 255)
            textSize = smallSize * RULER_TO_SMALL
        }
        for (lane in 0 until lanes) {
            val top = plotTop(lane)
            val bottom = plotBottom(lane)
            val l = left(lane)
            val r = right(lane)
            if (band > 0) {
                val y = laneTop[lane] + band
                canvas.drawLine(laneLeft[lane], y, laneRight[lane], y, gridPaint)
                canvas.drawText(titles[lane], l + textPadding, titleBaseline(lane), title)
            }
            for (idx in DECADES.indices) {
                val x = hzToX(lane, DECADES[idx])
                canvas.drawLine(x, top, x, bottom, gridPaint)
                canvas.drawText(DECADE_LABELS[idx], x + 3 * density, bottom - axis.descent() - density, axis)
            }
            canvas.drawLine(l, bottom, r, bottom, gridPaint)
            // notes ruler under frequency axis: C of each octave, as keyboard
            val tick = RULER_TICK_DP * density
            for (octave in RULER_OCTAVES) {
                val x = hzToX(lane, 440.0 * Math.pow(2.0, (octave * 12 - 57) / 12.0))
                canvas.drawLine(x, bottom, x, bottom + tick, ruler)
                val label = "C$octave"
                val w = notes.measureText(label)
                if (x - w / 2 > l && x + w / 2 < r) {
                    canvas.drawText(label, x - w / 2, bottom + tick - notes.ascent(), notes)
                }
            }
            val y = dbToY(lane, 0.0)
            canvas.drawLine(l, y, r, y, unity)
            val w = axis.measureText(ZERO_DB)
            canvas.drawText(ZERO_DB, r - w - 2 * density, y - axis.descent() - density, axis)
        }
        if (withWaves && wavesHeight > 0) {
            canvas.save()
            canvas.translate(0f, wavesTop)
            canvas.clipRect(0, 0, width, wavesHeight)
            grid.drawBackground(canvas, width, wavesHeight, labelTextSize)
            canvas.restore()
        }
    }

    companion object {
        const val MAX_LANES = 3

        /** Text styles of [atlas] */
        const val LABEL = 0
        const val SMALL = 1
        const val WAVES_LABEL = 2

        // plots part of height above waveforms
        private const val FILTER_SHARE = 0.55f
        private const val SMALL_TO_LABEL = 0.85f
        private const val TAG_HEIGHT_TO_SIZE = 1.5f
        private const val PEAK_PADDING_DP = 8f
        private const val RULER_TICK_DP = 3f
        private const val RULER_TO_SMALL = 0.8f
        private const val LABEL_ALPHA = 200
        private const val GRID_ALPHA = 38
        private const val UNITY_ALPHA = 64
        private const val AXIS_ALPHA = 110
        private const val RULER_ALPHA = 70
        private const val RULER_LABEL_ALPHA = 80

        const val MIN_HZ = 20.0
        const val MAX_HZ = 20000.0
        private val HZ_SPAN = ln(MAX_HZ / MIN_HZ)

        // the highest 6581 resonance peak is +24dB (see SidFilterModel.inverseQ)
        const val MAX_DB = 24.0
        const val MIN_DB = -36.0

        private val DECADES = doubleArrayOf(100.0, 1000.0, 10000.0)
        private val DECADE_LABELS = arrayOf("100", "1k", "10k")
        private val RULER_OCTAVES = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        private const val ZERO_DB = "0 dB"

        /**
         * @param withWaves oscilloscope lanes under plots
         */
        fun of(
            ctx: Context,
            width: Int,
            height: Int,
            layout: VoicesLayout,
            grid: ScopeGrid,
            labelTextSize: Float,
            res: FilterResources,
            withWaves: Boolean,
        ): FilterLayout {
            val groups = layout.groups.take(MAX_LANES)
            val names = groups.map { it.name }.ifEmpty { listOf("SID") }
            val first = IntArray(groups.size)
            for (idx in 1 until groups.size) {
                first[idx] = first[idx - 1] + groups[idx - 1].voices.size
            }
            return FilterLayout(
                width,
                height,
                grid,
                names.map { String.format(res.title, it) }.toTypedArray(),
                labelTextSize,
                ctx.resources.displayMetrics.density,
                res,
                withWaves && layout.groups.isNotEmpty() && grid.channels != 0,
                first,
                groups.map { it.voices.size }.toIntArray(),
            )
        }

        private fun charsOf(res: FilterResources, titles: Array<String>) = buildString {
            append("0123456789 .,%()#·–+…")
            append(TextLine.MINUS)
            append("∞HzkdBV")
            res.modes.forEach { append(it) }
            append(res.noise)
            append(res.direct)
            append(res.filtered)
            append(res.resonance)
            append(res.decimalSeparator)
            titles.forEach { append(it) }
            ChipWidgets.NOTE_NAMES.forEach { append(it) }
        }
    }
}

/**
 * Per frame shapes of filter view by chips registers at currently heard moment (the same gauges as of dashboard and
 * oscilloscope). Graphics show the exact state, recent states are drawn fading as phosphor persistence, and the swept
 * zone of the last moments is drawn as faint band (see [ModulationRange]), so sweeps and per-note jumps are readable
 * without lag or values that never existed. Values are tags attached to what they describe: cutoff at frequency axis
 * under its marker, resonance at the peak, voices by their markers; their digits and notes are guarded by
 * [RealtimeReadout] and [NoteReadout]. Used on rendering thread only, doesn't allocate per frame.
 */
internal class FilterScene(private val res: FilterResources) {

    @Volatile
    var settings = FilterSettings()

    private var tablesSettings: SidFilterModel.Settings? = null
    private var cutoff6581 = DoubleArray(0)
    private var cutoff8580 = DoubleArray(0)

    private val registers = ByteArray(FilterLayout.MAX_LANES * ChipState.SIZE)
    private val chips = Array(FilterLayout.MAX_LANES) { ChipState(registers, it * ChipState.SIZE) }

    private var statusSource: String? = null
    private var statusChip = SidDescription.Chip.DEFAULT
    private var hasModel = false
    private var clock = SidDescription.PAL_CLOCK
    private var is8580 = false

    private val pointsX = FloatArray(MAX_POINTS)
    private val pointsY = FloatArray(MAX_POINTS)
    private val pointsDb = DoubleArray(MAX_POINTS)
    private val bandDb = DoubleArray(MAX_POINTS)
    private val line = TextLine()

    private class Lane {
        // states of the last moments, the last one is current
        val time = LongArray(HISTORY)
        val mode = IntArray(HISTORY)
        val cutoff = DoubleArray(HISTORY)
        val inverseQ = DoubleArray(HISTORY)
        val voiceHz = DoubleArray(HISTORY * VOICES)
        val voiceDb = DoubleArray(HISTORY * VOICES)
        val voiceOn = BooleanArray(HISTORY * VOICES)
        var head = 0
        var size = 0

        val band = ModulationRange(MAX_POINTS)

        val cutoffText = RealtimeReadout(deadband = 1.0 / 24, modulationSpread = 1.0 / 4, isLogarithmic = true)
        val resonanceText = RealtimeReadout(deadband = 0.5, modulationSpread = 2.5)
        val notes = Array(VOICES) { NoteReadout() }
        val gains = Array(VOICES) { RealtimeReadout(deadband = 1.0, modulationSpread = 6.0) }

        fun last() = (head - 1 + HISTORY) % HISTORY

        fun reset() {
            size = 0
            band.clear()
            cutoffText.reset()
            resonanceText.reset()
            gains.forEach { it.reset() }
        }
    }

    private val lanes = Array(FilterLayout.MAX_LANES) { Lane() }
    private val tags = TagLayout(TAGS, FilterLayout.MAX_LANES * TAG_KEYS)
    private val widths = FloatArray(2)
    private var lastLayout: FilterLayout? = null

    /**
     * @param gauges chips gauges, see [app.zxtune.playback.Visualizer.getGauges]
     * @param status emulation status, see [app.zxtune.playback.Visualizer.getStatus]
     * @param isLive false to show single state without persistence (e.g. paused)
     */
    fun build(
        layout: FilterLayout,
        gauges: ByteArray,
        count: Int,
        status: String,
        nanos: Long,
        isLive: Boolean,
        shapes: ScopeShapes,
    ) {
        shapes.clear()
        if (layout !== lastLayout || !isLive) {
            lastLayout = layout
            lanes.forEach { it.reset() }
        }
        updateModel(status)
        val chipsCount = read(gauges, count)
        val emulated = tables()
        for (lane in 0 until minOf(layout.lanes, chipsCount)) {
            val chip = chips[lane]
            val state = lanes[lane]
            record(state, chip, emulated, nanos)
            drawLane(layout, lane, state, chip, nanos, isLive, shapes)
        }
    }

    private fun updateModel(status: String) {
        if (status !== statusSource) {
            statusSource = status
            statusChip = SidDescription.Chip.fromStatus(status)
            hasModel = status.contains("MOS")
        }
        clock = statusChip.clock
        is8580 = if (hasModel) statusChip.is8580 else settings.is8580
    }

    private fun tables(): DoubleArray {
        val current = settings.model
        if (current != tablesSettings) {
            tablesSettings = current
            cutoff6581 = SidFilterModel.cutoff6581(current)
            cutoff8580 = SidFilterModel.cutoff8580(current)
        }
        return if (is8580) cutoff8580 else cutoff6581
    }

    private fun read(gauges: ByteArray, count: Int): Int {
        var result = 0
        for (chip in 0 until minOf(count, FilterLayout.MAX_LANES)) {
            val src = chip * ChipGauges.SIZE + ChipGauges.GAUGES * ChipGauges.COLUMNS * 2
            if (src + ChipState.SIZE > gauges.size) {
                break
            }
            gauges.copyInto(registers, chip * ChipState.SIZE, src, src + ChipState.SIZE)
            result = chip + 1
        }
        return result
    }

    // New entry of history only if the state is changed (registers are held between player's ticks)
    private fun record(lane: Lane, chip: ChipState, emulated: DoubleArray, nanos: Long) {
        val mode = chip.mode and 7
        val fc = emulated[chip.cutoff.coerceIn(0, SidFilterModel.CUTOFF_VALUES - 1)]
        val invQ = SidFilterModel.inverseQ(chip.resonance, is8580, settings.model.residFp)
        val last = lane.last()
        var changed = lane.size == 0 || lane.mode[last] != mode || lane.cutoff[last] != fc || lane.inverseQ[last] != invQ
        for (voice in 0 until VOICES) {
            val routing = routing(chip, voice, mode)
            val hz = SidFilterModel.oscillatorHz(chip.frequency(voice), clock)
            val on = routing != Routing.OFF
            val db = if (routing == Routing.FILTERED) SidFilterModel.responseDb(mode, hz, fc, invQ) else 0.0
            val pos = last * VOICES + voice
            changed = changed || lane.voiceOn[pos] != on || lane.voiceHz[pos] != hz || lane.voiceDb[pos] != db
            voiceHz[voice] = hz
            voiceDb[voice] = db
            voiceOn[voice] = on
        }
        if (!changed) {
            return
        }
        val idx = lane.head
        lane.time[idx] = nanos
        lane.mode[idx] = mode
        lane.cutoff[idx] = fc
        lane.inverseQ[idx] = invQ
        for (voice in 0 until VOICES) {
            lane.voiceHz[idx * VOICES + voice] = voiceHz[voice]
            lane.voiceDb[idx * VOICES + voice] = voiceDb[voice]
            lane.voiceOn[idx * VOICES + voice] = voiceOn[voice]
        }
        lane.head = (idx + 1) % HISTORY
        lane.size = minOf(lane.size + 1, HISTORY)
    }

    private val voiceHz = DoubleArray(VOICES)
    private val voiceDb = DoubleArray(VOICES)
    private val voiceOn = BooleanArray(VOICES)

    private fun drawLane(layout: FilterLayout, lane: Int, state: Lane, chip: ChipState, nanos: Long, isLive: Boolean, shapes: ScopeShapes) {
        val current = state.last()
        val mode = state.mode[current]
        val fc = state.cutoff[current]
        val invQ = state.inverseQ[current]
        drawCaption(layout, lane, mode, shapes)
        if (mode != 0) {
            drawBand(layout, lane, state, mode, fc, invQ, nanos, shapes)
            drawPersistence(layout, lane, state, nanos, shapes)
            drawCurve(layout, lane, mode, fc, invQ, shapes)
        }
        tags.begin(layout.left(lane), layout.plotTop(lane), layout.right(lane), layout.plotBottom(lane))
        layout.reserveLabels(lane, tags)
        drawCutoff(layout, lane, state, chip, mode, fc, invQ, nanos, isLive, shapes)
        drawVoices(layout, lane, state, chip, nanos, isLive, shapes)
    }

    // "SID 1 - Filter · Low-pass": mode is not a moving value, so it's in the title
    private fun drawCaption(layout: FilterLayout, lane: Int, mode: Int, shapes: ScopeShapes) {
        if (layout.labelSize <= 0) {
            return
        }
        line.clear().append(SEPARATOR).append(res.modes[mode])
        val x = layout.titleEnd(lane)
        if (x + layout.atlas.width(FilterLayout.LABEL, line.chars, line.length) > layout.right(lane)) {
            return
        }
        layout.atlas.draw(shapes, FilterLayout.LABEL, line.chars, line.length, layout.titleEnd(lane), layout.titleBaseline(lane), Color.WHITE, CAPTION_ALPHA)
    }

    private fun response(mode: Int, hz: Double, fc: Double, invQ: Double) = SidFilterModel.responseDb(mode, hz, fc, invQ)

    private fun fade(db: Double) = ((db - FilterLayout.MIN_DB) / FADE_DB).toFloat().coerceIn(0f, 1f)

    // Swept zone of the last moments on fixed points (cutoff's point is not inserted)
    private fun drawBand(layout: FilterLayout, lane: Int, state: Lane, mode: Int, fc: Double, invQ: Double, nanos: Long, shapes: ScopeShapes) {
        val step = BAND_STEP_DP * layout.density
        val left = layout.left(lane)
        val right = layout.right(lane)
        val count = minOf(((right - left) / step).toInt() + 1, MAX_POINTS)
        for (idx in 0 until count) {
            bandDb[idx] = response(mode, layout.xToHz(lane, left + (right - left) * idx / (count - 1)), fc, invQ)
                .coerceIn(FilterLayout.MIN_DB, FilterLayout.MAX_DB)
        }
        state.band.update(bandDb, count, nanos)
        val low = state.band.low
        val high = state.band.high
        for (idx in 0 until count - 1) {
            if (!state.band.isVisible(idx, BAND_MIN_DB) && !state.band.isVisible(idx + 1, BAND_MIN_DB)) {
                continue
            }
            val x0 = left + (right - left) * idx / (count - 1)
            val x1 = left + (right - left) * (idx + 1) / (count - 1)
            shapes.fill(
                x0, layout.dbToY(lane, high[idx]), x1, layout.dbToY(lane, high[idx + 1]),
                layout.dbToY(lane, low[idx]), layout.dbToY(lane, low[idx + 1]),
                1f, Color.WHITE, BAND_ALPHA * fade(high[idx])
            )
        }
    }

    // Previous states fade out as phosphor, the current one is drawn by drawCurve
    private fun drawPersistence(layout: FilterLayout, lane: Int, state: Lane, nanos: Long, shapes: ScopeShapes) {
        for (age in 1 until state.size) {
            val idx = (state.head - 1 - age + HISTORY) % HISTORY
            val next = (idx + 1) % HISTORY
            // replaced at the start of the next state
            val seconds = (nanos - state.time[next]) / 1e9
            val alpha = (PERSISTENCE_ALPHA * exp(-seconds / PERSISTENCE_SECONDS)).toFloat()
            if (alpha < MIN_ALPHA || state.mode[idx] == 0) {
                continue
            }
            val count = curvePoints(layout, lane, state.mode[idx], state.cutoff[idx], state.inverseQ[idx], GHOST_STEP_DP)
            strokeCurve(count, Color.WHITE, alpha, shapes)
            for (voice in 0 until VOICES) {
                val pos = idx * VOICES + voice
                if (state.voiceOn[pos]) {
                    val x = layout.hzToX(lane, state.voiceHz[pos])
                    val y = layout.dbToY(lane, state.voiceDb[pos])
                    shapes.circle(x, y, GHOST_RADIUS_DP * layout.density, 0f, res.voiceColors[voice], alpha)
                }
            }
        }
    }

    private fun drawCurve(layout: FilterLayout, lane: Int, mode: Int, fc: Double, invQ: Double, shapes: ScopeShapes) {
        val count = curvePoints(layout, lane, mode, fc, invQ, CURVE_STEP_DP)
        val bottom = layout.plotBottom(lane)
        for (idx in 0 until count - 1) {
            val fade = fade((pointsDb[idx] + pointsDb[idx + 1]) / 2)
            if (fade > 0f) {
                shapes.fill(pointsX[idx], pointsY[idx], pointsX[idx + 1], pointsY[idx + 1], bottom, bottom, 0f, Color.WHITE, FILL_ALPHA * fade)
            }
        }
        strokeCurve(count, Color.WHITE, 1f, shapes)
    }

    // Thin line as traces, fading out near the floor instead of being clamped to flat line there
    private fun strokeCurve(count: Int, color: Int, alpha: Float, shapes: ScopeShapes) {
        for (idx in 0 until count - 1) {
            val fade = fade((pointsDb[idx] + pointsDb[idx + 1]) / 2)
            if (fade <= 0f) {
                continue
            }
            val prev = maxOf(idx - 1, 0)
            val next = minOf(idx + 2, count - 1)
            shapes.segment(
                pointsX[idx], pointsY[idx], pointsX[idx + 1], pointsY[idx + 1],
                pointsX[prev], pointsY[prev], pointsX[next], pointsY[next],
                LINE_WIDTH_PX, color, alpha * fade
            )
        }
    }

    private fun curvePoints(layout: FilterLayout, lane: Int, mode: Int, fc: Double, invQ: Double, stepDp: Float): Int {
        val step = stepDp * layout.density
        val right = layout.right(lane)
        var count = 0
        var x = layout.left(lane)
        var prevHz = 0.0
        while (count < MAX_POINTS - 1) {
            val hz = layout.xToHz(lane, x)
            // narrow resonance peak or notch is not skipped
            if (prevHz != 0.0 && fc > prevHz && fc < hz) {
                count = addPoint(layout, lane, count, layout.hzToX(lane, fc), response(mode, fc, fc, invQ))
            }
            count = addPoint(layout, lane, count, x, response(mode, hz, fc, invQ))
            prevHz = hz
            if (x >= right) {
                break
            }
            x = minOf(x + step, right)
        }
        return count
    }

    private fun addPoint(layout: FilterLayout, lane: Int, idx: Int, x: Float, db: Double): Int {
        pointsX[idx] = x
        pointsDb[idx] = db
        pointsY[idx] = layout.dbToY(lane, db)
        return idx + 1
    }

    // Marker from axis to curve with tag at axis (the position itself is the readout), resonance tag at the peak
    private fun drawCutoff(
        layout: FilterLayout,
        lane: Int,
        state: Lane,
        chip: ChipState,
        mode: Int,
        fc: Double,
        invQ: Double,
        nanos: Long,
        isLive: Boolean,
        shapes: ScopeShapes,
    ) {
        val density = layout.density
        val x = layout.hzToX(lane, fc)
        val bottom = layout.plotBottom(lane)
        val peakDb = if (mode == 0) FilterLayout.MIN_DB else response(mode, fc, fc, invQ)
        val peakY = layout.dbToY(lane, peakDb)
        shapes.line(x, bottom, x, peakY, MARKER_LINE_DP * density, Color.WHITE, MARKER_ALPHA)
        if (isLive) state.cutoffText.push(fc, nanos) else state.cutoffText.snap(fc)
        if (isLive) state.resonanceText.push(chip.resonance.toDouble(), nanos) else state.resonanceText.snap(chip.resonance.toDouble())
        val atlas = layout.atlas
        // cutoff tag in pill at axis, over decades labels
        line.clear()
        val text = state.cutoffText
        if (text.isModulated) {
            // the same unit for both
            val kHz = text.high >= 1000
            appendFrequency(text.low, false, kHz)
            line.append(RANGE)
            appendFrequency(text.high, true, kHz)
        } else {
            appendFrequency(text.value, true, text.value >= 1000)
            val note = NoteReadout.semitonesOf(text.value).roundToInt()
            if (note in 0..131) {
                line.append(' ').append(ChipWidgets.NOTE_NAMES[note % 12]).append(note / 12)
            }
        }
        val w = atlas.width(FilterLayout.SMALL, line.chars, line.length)
        val h = layout.tagHeight
        val pad = TAG_PADDING_DP * density
        val tagLeft = (x - w / 2).coerceIn(layout.left(lane) + pad, layout.right(lane) - w - pad)
        val tagTop = bottom + 1 * density
        shapes.line(tagLeft, tagTop + h / 2, tagLeft + w, tagTop + h / 2, h, Color.BLACK, PILL_ALPHA)
        val baseline = tagTop + (h - atlas.ascent(FilterLayout.SMALL) - atlas.descent(FilterLayout.SMALL)) / 2
        atlas.draw(shapes, FilterLayout.SMALL, line.chars, line.length, tagLeft, baseline, Color.WHITE, TAG_ALPHA)
        if (mode == 0) {
            return
        }
        // resonance tag by the peak
        line.clear().append(res.resonance).append(' ')
        val resonance = state.resonanceText
        line.append(percent(resonance.low))
        if (resonance.isModulated) {
            line.append(RANGE).append(percent(resonance.high))
        }
        line.append('%')
        val rw = atlas.width(FilterLayout.SMALL, line.chars, line.length)
        val rh = atlas.descent(FilterLayout.SMALL) - atlas.ascent(FilterLayout.SMALL)
        widths[0] = rw
        val idx = tags.placeStable(lane * TAG_KEYS + RESONANCE_KEY, x, peakY, widths, 1, rh, TAG_GAP_DP * density, if (isLive) nanos else 0L, TagLayout.ABOVE)
        if (idx < 0) {
            return
        }
        pill(idx, shapes)
        atlas.draw(shapes, FilterLayout.SMALL, line.chars, line.length, tags.left(idx), tags.bottom(idx) - atlas.descent(FilterLayout.SMALL), Color.WHITE, CAPTION_ALPHA)
    }

    // tags stay readable over curves, as value tags of equalizers
    private fun pill(idx: Int, shapes: ScopeShapes) {
        val h = tags.bottom(idx) - tags.top(idx)
        val y = (tags.top(idx) + tags.bottom(idx)) / 2
        shapes.line(tags.left(idx), y, tags.right(idx), y, h, Color.BLACK, PILL_ALPHA)
    }

    private fun percent(reg: Double) = (reg * 100 / 15).roundToInt()

    private fun appendFrequency(hz: Double, withUnit: Boolean, kHz: Boolean) {
        val rounded = SidDescription.roundCutoff(hz.toFloat())
        if (!kHz) {
            line.append(rounded)
            if (withUnit) line.append(" Hz")
        } else {
            // 0.33, 1.7, 12
            val hundredths = (rounded + 5) / 10
            if (hundredths < 100) {
                line.append(0).append(res.decimalSeparator).append(hundredths / 10).append(hundredths % 10)
            } else {
                val tenths = (rounded + 50) / 100
                line.append(tenths / 10)
                if (tenths < 100) {
                    line.append(res.decimalSeparator).append(tenths % 10)
                }
            }
            if (withUnit) line.append(" kHz")
        }
    }

    private enum class Routing { OFF, DIRECT, FILTERED }

    private fun routing(chip: ChipState, voice: Int, mode: Int): Routing {
        val control = chip.control(voice)
        val filtered = chip.routing and (1 shl voice) != 0
        return when {
            // reset or no waveform produce no tone, released envelope is silent
            control and 0x08 != 0 || control and 0xf0 == 0 || chip.envelope(voice) == 0 -> Routing.OFF

            chip.frequency(voice) == 0 && control and 0x80 == 0 -> Routing.OFF

            SidDescription.isMuted(voice, chip.mode, chip.routing) -> Routing.OFF

            // routed to filter without any output
            filtered && mode == 0 -> Routing.OFF

            filtered -> Routing.FILTERED

            else -> Routing.DIRECT
        }
    }

    // Small markers on the curve: disc of filtered voice at its gain, ring of direct one at unity gain, with tick at
    // frequency axis and tag "V1 E2 −12 dB" in voice's color
    private fun drawVoices(layout: FilterLayout, lane: Int, state: Lane, chip: ChipState, nanos: Long, isLive: Boolean, shapes: ScopeShapes) {
        val density = layout.density
        val bottom = layout.plotBottom(lane)
        val current = state.last()
        val mode = state.mode[current]
        val atlas = layout.atlas
        for (voice in 0 until VOICES) {
            val pos = current * VOICES + voice
            val routing = routing(chip, voice, mode)
            if (!state.voiceOn[pos] || routing == Routing.OFF) {
                state.gains[voice].reset()
                tags.forget(lane * TAG_KEYS + voice)
                continue
            }
            val hz = state.voiceHz[pos]
            val db = state.voiceDb[pos]
            val x = layout.hzToX(lane, hz)
            val y = layout.dbToY(lane, db)
            val color = res.voiceColors[voice]
            // louder voices are more visible
            val alpha = 0.5f + 0.5f * chip.envelope(voice) / 255f
            shapes.line(x, bottom - TICK_DP * density, x, bottom, 1.5f * density, color, alpha)
            val filtered = routing == Routing.FILTERED
            shapes.circle(x, y, MARKER_RADIUS_DP * density, if (filtered) 0f else 1.5f * density, color, alpha)
            val isNoise = chip.control(voice) and 0x80 != 0
            val note = state.notes[voice]
            if (isLive) note.push(hz, nanos) else note.snap(hz)
            val gain = state.gains[voice]
            if (filtered) {
                if (isLive) gain.push(db, nanos) else gain.snap(db)
            } else {
                gain.reset()
            }
            line.clear().append('V').append(voice + 1)
            widths[1] = atlas.width(FilterLayout.SMALL, line.chars, line.length)
            line.append(' ')
            if (isNoise) {
                line.append(res.noise)
            } else if (note.note in 0..131) {
                line.append(ChipWidgets.NOTE_NAMES[note.note % 12]).append(note.note / 12)
            }
            if (filtered) {
                line.append(' ')
                appendGain(gain)
            }
            // tag by marker, its name only in narrow space, else nothing
            val h = atlas.descent(FilterLayout.SMALL) - atlas.ascent(FilterLayout.SMALL)
            val gap = (MARKER_RADIUS_DP + 3) * density
            val key = lane * TAG_KEYS + voice
            widths[0] = atlas.width(FilterLayout.SMALL, line.chars, line.length)
            val idx = tags.placeStable(key, x, y, widths, 2, h, gap, if (isLive) nanos else 0L)
            if (tags.form(key) == 1) {
                line.clear().append('V').append(voice + 1)
            }
            if (idx >= 0) {
                pill(idx, shapes)
                atlas.draw(shapes, FilterLayout.SMALL, line.chars, line.length, tags.left(idx), tags.bottom(idx) - atlas.descent(FilterLayout.SMALL), color, alpha)
            }
            drawRouting(layout, lane, voice, filtered, gain, color, shapes)
        }
    }

    private fun appendGain(gain: RealtimeReadout) {
        appendDb(if (gain.isModulated) gain.low else gain.value)
        if (gain.isModulated) {
            line.append(GAIN_RANGE)
            appendDb(gain.high)
        }
        line.append(" dB")
    }

    private fun appendDb(db: Double) {
        val rounded = db.roundToInt()
        when {
            db <= SidFilterModel.MIN_DB + 1 -> line.append(TextLine.MINUS).append('∞')
            rounded > 0 -> line.append('+').append(rounded)
            else -> line.append(rounded)
        }
    }

    // routing tag at the right of oscilloscope lane's title: "filtered −3 dB" or "direct"
    private fun drawRouting(layout: FilterLayout, lane: Int, voice: Int, filtered: Boolean, gain: RealtimeReadout, color: Int, shapes: ScopeShapes) {
        val chan = layout.channelOf(lane, voice)
        if (chan < 0 || !layout.hasWavesLabels) {
            return
        }
        val atlas = layout.atlas
        val space = layout.wavesRight(chan) - layout.wavesTitleEnd(chan)
        // full form, then gain only if lane is narrow
        for (pass in 0..1) {
            val full = pass == 0
            line.clear()
            if (filtered) {
                if (full) line.append(res.filtered).append(' ')
                appendGain(gain)
            } else {
                line.append(res.direct)
            }
            val w = atlas.width(FilterLayout.WAVES_LABEL, line.chars, line.length)
            if (w <= space) {
                atlas.draw(shapes, FilterLayout.WAVES_LABEL, line.chars, line.length, layout.wavesRight(chan) - w, layout.wavesBaseline(chan), color, 1f)
                return
            }
        }
    }

    companion object {
        private const val VOICES = 3
        private const val HISTORY = 12
        private const val TAGS = 16
        private const val TAG_KEYS = 4
        private const val RESONANCE_KEY = 3
        private const val SEPARATOR = " · "
        private const val GAIN_RANGE = '…'
        private const val RANGE = '–'
        private const val MAX_POINTS = 2048

        private const val CURVE_STEP_DP = 1.5f
        private const val GHOST_STEP_DP = 4f
        private const val BAND_STEP_DP = 3f

        // as traces, see OscilloscopeView
        private const val LINE_WIDTH_PX = 1.5f

        // curve fades out over the lowest part of the plot
        private const val FADE_DB = 12.0
        private const val FILL_ALPHA = 0.10f
        private const val BAND_ALPHA = 0.08f
        private const val BAND_MIN_DB = 1.0
        private const val PERSISTENCE_ALPHA = 0.25
        private const val PERSISTENCE_SECONDS = 0.2
        private const val MIN_ALPHA = 0.02f
        private const val GHOST_RADIUS_DP = 2.5f
        private const val CAPTION_ALPHA = 0.6f
        private const val TAG_ALPHA = 0.85f
        private const val PILL_ALPHA = 0.85f
        private const val MARKER_ALPHA = 0.3f
        private const val MARKER_LINE_DP = 1f
        private const val MARKER_RADIUS_DP = 3.5f
        private const val TICK_DP = 5f
        private const val TAG_GAP_DP = 4f
        private const val TAG_PADDING_DP = 2f
    }
}
