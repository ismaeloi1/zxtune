/**
 * @file
 * @brief Chips state gauges and diagnostics (inspired by JSIDPlay2's oscilloscope view)
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import app.zxtune.Logger
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.ChipState
import app.zxtune.playback.FrameResult
import app.zxtune.playback.GaugesData
import app.zxtune.playback.Visualizer
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoicesLayout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

private val LOG = Logger("SidDashboard")

/**
 * Displays history of SID state for single chip:
 * - per voice: oscillator output, envelope (dB), frequency (log scale)
 * - global: master volume, resonance, filter cutoff
 * S-DSP (SNES) is displayed as rows of voices like SID ones: sample, envelope program and level/pitch history panels
 * with cells of values, and global row of volumes, echo and FIR (see [drawSpc]).
 * Other chips are displayed per voice as cards: title band with live summary, level and pitch history and
 * hardware specific state (see [ChipWidgets]).
 * And multiline status text below. Chips are displayed side by side.
 * Gauges are delivered by oscilloscope (see [attachTo]) with waveforms of the same moment, view is redrawn with the
 * latest of them at most at DASHBOARD_FPS. Static parts (frames, captions, separators) are cached in bitmap and only
 * live values are drawn at each frame.
 */
class SidDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile
    private var source: Visualizer? = null

    /**
     * Gauges data, filled by oscilloscope's data thread
     */
    val exchange = GaugesExchange().apply {
        listener = { requestRedraw() }
    }

    // Oscilloscope publishes gauges at display rate (up to 120-144Hz), while redrawing of the whole dashboard at such
    // rate takes UI and render threads and GPU time from oscilloscope (it was 47fps instead of 120). So requests are
    // collapsed and limited to DASHBOARD_FPS, each redraw takes the latest data (of the moment displayed by scope).
    private val redrawPending = AtomicBoolean(false)

    @Volatile
    private var lastDrawNanos = 0L

    private fun requestRedraw() {
        if (!redrawPending.compareAndSet(false, true)) {
            return
        }
        val wait = lastDrawNanos + MIN_REDRAW_INTERVAL_NS - System.nanoTime()
        if (wait > 0) {
            // not dropped, else the last data is not displayed if no more data is published (e.g. paused)
            postInvalidateDelayed((wait + 999_999) / 1_000_000)
        } else {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Displayed duration of oscillators and volume gauges
     */
    var waveWindowMs by exchange::waveWindowMs
    private var statsProvider: (() -> String)? = null

    // Status is requested on background thread to keep UI responsive, binder calls may take time
    private val executor = Executors.newSingleThreadExecutor()
    private val requestPending = AtomicBoolean(false)

    // not created at each frame
    private var columns = ChipColumns(VoicesLayout.MASTER.groups)
    private var columnsLayout = VoicesLayout.MASTER

    @Volatile
    private var status = ""
    private var lastStatusTime = 0L

    // status and statistics lines, split only on change
    private var statusSource = ""
    private var statsSource = ""
    private val textLines = ArrayList<String>()

    // static part of picture, see [Pass]
    private var pass = Pass.ALL
    private var staticLayer: Bitmap? = null
    private var staticKey = 0L

    @androidx.annotation.VisibleForTesting
    internal var staticRedraws = 0
    private val layerPaint = Paint().apply {
        color = Color.WHITE
    }

    private val density = resources.displayMetrics.density
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = sp(TITLE_SIZE_SP)
        // No shadow: titles are in own bands, while blurred shadows of texts changed at each frame are rasterized
        // again on each frame by RenderThread
        tabularFigures()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = sp(TEXT_SIZE_SP)
        typeface = Typeface.MONOSPACE
    }
    private val framePaint = Paint().apply {
        color = Color.argb(70, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = TRACE_WIDTH * density
        strokeCap = Paint.Cap.BUTT
    }
    private val separatorPaint = Paint().apply {
        color = Color.argb(160, 255, 255, 255)
        strokeWidth = density * 2
    }
    private val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = sp(TEXT_SIZE_SP)
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }

    // human readable values of registers are greyed to separate them from raw values
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        textSize = sp(TEXT_SIZE_SP)
        textAlign = Paint.Align.RIGHT
        tabularFigures()
    }

    // cells values are the main information for non technical user, captions are just hints
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 255, 255, 255)
        textSize = sp(CELL_SIZE_SP)
        tabularFigures()
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(130, 255, 255, 255)
        textSize = sp(CAPTION_SIZE_SP)
        tabularFigures()
    }
    private val cellFramePaint = Paint().apply {
        color = Color.argb(50, 255, 255, 255)
        strokeWidth = density
    }
    private val dimTracePaint = Paint(tracePaint).apply {
        color = Color.argb(110, 255, 255, 255)
    }
    private val rect = RectF()
    private val body = RectF()
    private val history = RectF()
    private val widget = RectF()
    private val lines = FloatArray(ChipGauges.COLUMNS * 4)
    private val widgets = ChipWidgets(density, sp(TEXT_SIZE_SP))
    private val smooth = Array(MAX_VOICES) { FloatArray(ChipWidgets.SMOOTH_SIZE) }
    private val widgetTexts = Array(MAX_VOICES) { ChipWidgets.TextMemo() }

    // card titles with and without group name and key mark, see [cardTitle]
    private val cardTitles = Array(MAX_VOICES) { arrayOfNulls<String>(4) }

    @androidx.annotation.VisibleForTesting
    internal val voiceTexts = Array(MAX_CHIPS) { Array(3) { SidVoiceText() } }

    @androidx.annotation.VisibleForTesting
    internal val globalTexts = Array(MAX_CHIPS) { SidGlobalText() }
    private val voiceFitted = Array(MAX_CHIPS * 3) { Array(SidCells.VOICE.size) { FittedText() } }
    private val globalFitted = Array(MAX_CHIPS) { Array(SidCells.GLOBAL.size) { FittedText() } }
    private val titleFitted = Array(MAX_CHIPS * 4 * 3) { FittedTitle() }

    @androidx.annotation.VisibleForTesting
    internal val voiceGrid = CellGrid(SidCells.VOICE)

    @androidx.annotation.VisibleForTesting
    internal val globalGrid = CellGrid(SidCells.GLOBAL)
    private val voiceTemplates = TitleTemplates(VOICE_TEMPLATE)
    private val globalTemplates = TitleTemplates(GLOBAL_TEMPLATE)

    // S-DSP rows, see [drawSpc]
    private val spcVoiceTexts = Array(SpcDescription.VOICES) { SpcVoiceText() }
    private val spcGlobalText = SpcGlobalText()
    private val spcGlobals = SpcGlobals()
    private val spcEnvelopes = Array(SpcDescription.VOICES) { SpcEnvelope() }
    private val spcVoiceFitted = Array(SpcDescription.VOICES) { Array(SpcCells.VOICE.size) { FittedText() } }
    private val spcCaptionFitted = Array(SpcDescription.VOICES) { Array(SpcCells.VOICE.size) { FittedText() } }
    private val spcGlobalFitted = Array(SpcCells.GLOBAL.size) { FittedText() }
    private val spcTitleFitted = Array((SpcDescription.VOICES + 1) * 3) { FittedTitle() }

    @androidx.annotation.VisibleForTesting
    internal val spcVoiceGrid = CellGrid(SpcCells.VOICE)

    @androidx.annotation.VisibleForTesting
    internal val spcGlobalGrid = CellGrid(SpcCells.GLOBAL)
    private val spcVoiceTemplates = TitleTemplates(SPC_VOICE_TEMPLATE)
    private val spcGlobalTemplates = TitleTemplates(SPC_GLOBAL_TEMPLATE)

    // geometry of the last drawn S-DSP rows
    @androidx.annotation.VisibleForTesting
    internal var spcRowHeight = 0f

    @androidx.annotation.VisibleForTesting
    internal var spcPanelHeight = 0f

    @androidx.annotation.VisibleForTesting
    internal var spcColumns = 0

    @androidx.annotation.VisibleForTesting
    internal var spcCellsAside = false
    private val spcPath = Path()
    private val spcPlot = RectF()
    private val spcFir = FloatArray(SPC_FIR_POINTS)
    private var spcFirKey = Long.MIN_VALUE
    private var spcValueScale = 0f
    private var spcSecondLine = false
    private var spcGridWidth = -1f
    private var spcGridSpace = -1f
    private val spcFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val spcLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textSize = sp(SPC_LABEL_SIZE_SP)
        tabularFigures()
    }

    // geometry of the last drawn gauges rows
    @androidx.annotation.VisibleForTesting
    internal var gaugesRowHeight = 0f

    @androidx.annotation.VisibleForTesting
    internal var gaugesCellHeight = 0f
    private val summaries = Array(MAX_VOICES) { ChipWidgets.LiveSummary() }

    // summary width is reserved by widest one to not shift title with each summary change
    private val summaryWidths = FloatArray(MAX_VOICES)

    // card titles are the same at most frames, so they are not measured and cut again
    private val cardFits = Array(MAX_VOICES) { CardFit() }
    private val summaryKinds = IntArray(MAX_VOICES) { -1 }

    init {
        setBackgroundColor(Color.BLACK)
        // consume taps to not toggle parent's visualizer or close fullscreen
        isClickable = true
    }

    /**
     * Gauges are requested by oscilloscope together with waveforms of the same moment
     */
    fun attachTo(display: ScopeDisplay) {
        display.gauges = exchange
    }

    /**
     * @param src data source or null to stop updating
     * @param stats provider of additional (UI side) statistics lines
     * @param isLive false for single picture (e.g. paused playback), see [ScopeDisplay.setSource]
     */
    @Suppress("UNUSED_PARAMETER")
    fun setSource(src: Visualizer?, stats: (() -> String)? = null, isLive: Boolean = true) {
        source = src
        statsProvider = stats
        exchange.isEnabled = src != null
        if (src != null) {
            lastStatusTime = System.nanoTime()
            requestStatus()
        } else {
            exchange.publish(FrameResult.GAUGES_NONE, 0, VoicesLayout.MASTER)
            invalidate()
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun setVoicesData(voicesLayout: VoicesLayout, data: ByteArray, voices: Int, statusText: String) {
        val kind = FrameResult.GAUGES_VOICES
        val target = exchange.buffer(kind, voicesLayout)
        data.copyInto(target, 0, 0, minOf(data.size, target.size))
        exchange.publish(kind, voices, voicesLayout)
        status = statusText
    }

    @androidx.annotation.VisibleForTesting
    internal fun setGaugesData(data: ByteArray, chips: Int, statusText: String) {
        val layout = VoicesLayout.parse("registers\nSID\tVoice 1\tVoice 2\tVoice 3")
        val kind = FrameResult.GAUGES_CHIPS
        val target = exchange.buffer(kind, layout)
        data.copyInto(target, 0, 0, minOf(data.size, target.size))
        exchange.publish(kind, chips, layout)
        status = statusText
    }

    // requests made while detached are not delivered
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        redrawPending.set(false)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        staticLayer?.recycle()
        staticLayer = null
        super.onDetachedFromWindow()
    }

    private fun requestStatus() {
        val src = source ?: return
        if (!requestPending.compareAndSet(false, true)) {
            return
        }
        executor.execute {
            try {
                val newStatus = runCatching { src.getStatus() }.getOrElse {
                    LOG.w(it) { "Failed to get status" }
                    ""
                }
                if (newStatus != status) {
                    status = newStatus
                    postInvalidate()
                }
            } finally {
                requestPending.set(false)
            }
        }
    }

    // Static part depends only on geometry and layout, but not on values
    private fun staticKeyOf(data: GaugesExchange.Data, lines: Int): Long {
        var key = width.toLong()
        key = key * 31 + height
        key = key * 31 + data.kind
        key = key * 31 + data.count
        key = key * 31 + System.identityHashCode(data.layout)
        key = key * 31 + lines
        if (data.kind == FrameResult.GAUGES_VOICES) {
            // cards layout depends on voice kind
            for (voice in 0 until minOf(data.count, data.bytes.size / VoiceGauges.SIZE)) {
                key = key * 31 + VoiceGauges(data.bytes, voice * VoiceGauges.SIZE).kind
            }
        }
        return key
    }

    private fun updateTextLines() {
        val stats = statsProvider?.invoke() ?: ""
        val currentStatus = status
        if (currentStatus === statusSource && stats === statsSource) {
            return
        }
        statusSource = currentStatus
        statsSource = stats
        textLines.clear()
        currentStatus.lines().filterTo(textLines) { it.isNotBlank() }
        stats.lines().filterTo(textLines) { it.isNotBlank() }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        lastDrawNanos = now
        redrawPending.set(false)
        val data = exchange.acquire()
        if (source != null && now - lastStatusTime > STATUS_PERIOD_NS) {
            lastStatusTime = now
            requestStatus()
        }
        updateTextLines()
        val textHeight = textPaint.fontSpacing * textLines.size + padding()
        val areaHeight = height - textHeight
        if (width > 0 && height > 0) {
            val key = staticKeyOf(data, textLines.size)
            val layer = staticLayer?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also {
                    staticLayer?.recycle()
                    staticLayer = it
                    staticKey = key.inv()
                }
            if (key != staticKey) {
                staticKey = key
                ++staticRedraws
                val layerCanvas = Canvas(layer)
                layerCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawContent(layerCanvas, data, areaHeight, Pass.STATIC)
            }
            // alpha only bitmap is drawn by paint's color
            canvas.drawBitmap(layer, 0f, 0f, layerPaint)
        }
        drawContent(canvas, data, areaHeight, Pass.DYNAMIC)
        val lineHeight = textPaint.fontSpacing
        var y = areaHeight + padding() / 2 - textPaint.ascent()
        for (line in textLines) {
            canvas.drawText(line, padding(), y, textPaint)
            y += lineHeight
        }
    }

    private fun drawContent(canvas: Canvas, data: GaugesExchange.Data, areaHeight: Float, current: Pass) {
        pass = current
        when (data.kind) {
            FrameResult.GAUGES_VOICES -> drawVoices(canvas, data, areaHeight)
            FrameResult.GAUGES_CHIPS -> drawGauges(canvas, data, areaHeight)
        }
        pass = Pass.ALL
    }

    /**
     * Static elements are drawn once into layer (white with alpha), live ones at each frame over it
     */
    private enum class Pass {
        ALL,
        STATIC,
        DYNAMIC;

        val isStatic
            get() = this != DYNAMIC
        val isDynamic
            get() = this != STATIC
    }

    // Chips are placed side by side as in oscilloscope above: 3 gauges columns per chip.
    // Each row (voice or global filter/volume state) ends with grid of value cells, if fits.
    private fun drawGauges(canvas: Canvas, data: GaugesExchange.Data, areaHeight: Float) {
        val chips = minOf(data.count, data.bytes.size / ChipGauges.SIZE)
        if (chips == 0 || areaHeight < density * 40) {
            return
        }
        val chip = SidDescription.Chip.fromStatus(status)
        val groupW = width.toFloat() / chips
        val cellW = groupW / 3
        val rowH = areaHeight / 4
        // cells are less important than gauges themselves, so take only space above gauges minimal height;
        // space is reserved regardless of current values to avoid layout jumping
        updateGrids(groupW - cellsPadding() * 2, rowH - density * MIN_GAUGE_DP)
        val cellH = rowH - gridHeight()
        gaugesRowHeight = rowH
        gaugesCellHeight = cellH
        for (idx in 0 until chips) {
            val gauges = ChipGauges(data.bytes, idx * ChipGauges.SIZE)
            val last = gauges.state
            val left = groupW * idx
            val chipLabel = if (chips > 1) idx + 1 else 0
            for (voice in 0 until 3) {
                val texts = voiceTexts[idx][voice]
                val row = idx * 4 + voice
                if (pass.isDynamic) {
                    texts.update(last, voice, envelopeTrend(gauges, last, voice), chip, chipLabel)
                }
                drawGaugesRow(canvas, gauges, VOICE_GAUGES[voice], texts.titles, voiceTemplates, left, rowH * voice, cellW, cellH, row)
                drawCells(canvas, voiceGrid, texts.cells, voiceFitted[idx * 3 + voice], left, rowH * voice + cellH)
            }
            val texts = globalTexts[idx]
            if (pass.isDynamic) {
                texts.update(last, chip)
            }
            drawGaugesRow(canvas, gauges, GLOBAL_GAUGES, texts.titles, globalTemplates, left, rowH * 3, cellW, cellH, idx * 4 + 3)
            drawCells(canvas, globalGrid, texts.cells, globalFitted[idx], left, rowH * 3 + cellH)
            if (idx != 0 && pass.isStatic) {
                canvas.drawLine(left, 0f, left, areaHeight, separatorPaint)
            }
        }
    }

    /**
     * Widest title and value parts of gauges in a row (value is split to main part and tail slot, e.g. "60%" and
     * "Sus"), measured once to keep layout stable regardless of current texts
     */
    private class TitleTemplate(
        val title: String,
        val main: String,
        val tails: Array<String>,
        val tailAlign: Paint.Align = Paint.Align.RIGHT
    )

    private inner class TitleTemplates(templates: Array<TitleTemplate>) {
        val items = templates

        // hex digits are not of the same width even with tabular figures
        val titleWidth = FloatArray(templates.size) { idx ->
            maxOf(titlePaint.measureText(templates[idx].title), titlePaint.measureText(templates[idx].title.replace('F', '0')))
        }
        val mainWidth = FloatArray(templates.size) { valuePaint.measureText(templates[it].main) }
        val tailWidth = FloatArray(templates.size) { idx -> templates[idx].tails.maxOf { valuePaint.measureText(it) } }
        val spaceWidth = valuePaint.measureText(" ")

        fun valueWidth(idx: Int) = mainWidth[idx] + (if (mainWidth[idx] > 0f) spaceWidth else 0f) + tailWidth[idx]
    }

    // Gauges in a row have the same title band layout to keep plots aligned. Layout is selected by templates only,
    // so it's not changed with values. Raw title and value have separate regions: value is placed at the same line
    // if both fit, at the second line if there's enough height, or is not displayed at all.
    private fun drawGaugesRow(
        canvas: Canvas,
        gauges: GaugesData,
        ids: IntArray,
        texts: LiveTexts,
        templates: TitleTemplates,
        left: Float,
        top: Float,
        cellW: Float,
        cellH: Float,
        row: Int
    ) {
        // see drawGauge and drawGaugeTitleBand paddings
        val inner = cellW - density * 11 - titleGap()
        val minTitle = sp(MIN_TITLE_SIZE_SP) / sp(TITLE_SIZE_SP)
        val fits = { titleScale: Float, valueScale: Float ->
            (0 until 3).all { templates.titleWidth[it] * titleScale + templates.valueWidth(it) * valueScale <= inner }
        }
        var secondLine = false
        val valueScale = when {
            fits(SHRINKED_TITLE, 1f) -> 1f

            // second line is used only if plot is still visible enough
            cellH > (titlePaint.fontSpacing + valuePaint.fontSpacing) * 1.8f -> {
                secondLine = true
                // the same size for all gauges in a row, see drawGaugeTitleBand
                val room = cellW - density * 8
                (0 until 3).minOf { room / templates.valueWidth(it) }.coerceIn(MIN_VALUE_SCALE, 1f)
            }

            fits(minTitle, MIN_VALUE_SCALE) -> MIN_VALUE_SCALE

            else -> 0f
        }
        for (idx in 0 until 3) {
            drawGauge(
                canvas, gauges, ids[idx], left + cellW * idx, top, cellW, cellH, texts, templates, idx, secondLine,
                valueScale, titleFitted[row * 3 + idx]
            )
        }
    }

    // Registers snapshot is not enough to distinguish attack from decay, so last history column is used
    private fun envelopeTrend(gauges: ChipGauges, state: ChipState, voice: Int): Int {
        val gauge = ChipGauges.envelope(voice)
        val col = ChipGauges.COLUMNS - 1
        val min = gauges.min(gauge, col)
        val max = gauges.max(gauge, col)
        val current = SidDescription.envelopeGaugeLevel(state.envelope(voice))
        return when {
            min == max -> 0
            current >= max -> 1
            current <= min -> -1
            else -> 0
        }
    }

    private fun cellsPadding() = density * 4

    private fun titleGap() = density * 6

    private fun cellLineHeight(scale: Float) = (captionPaint.descent() - captionPaint.ascent() + cellPaint.fontSpacing) * scale + density * 3

    private var gridWidth = -1f
    private var gridSpace = -1f

    private fun updateGrids(w: Float, space: Float) {
        if (w == gridWidth && space == gridSpace) {
            return
        }
        gridWidth = w
        gridSpace = space
        voiceGrid.update(w, space, CELL_SCALES)
        // the same text size for all rows
        globalGrid.update(w, space, floatArrayOf(voiceGrid.scale))
    }

    // all rows have the same height to keep gauges aligned
    private fun gridHeight(): Float {
        val lines = maxOf(voiceGrid.lines, globalGrid.lines)
        return if (lines == 0) 0f else cellLineHeight(voiceGrid.scale) * lines + density * 2
    }

    /**
     * Fixed positions of cells (see [CellSpec]) for given width and available height. Sizes are taken from templates,
     * so neither positions nor text size depend on current values: change of one value doesn't move others, and the
     * same cells of all voices are aligned in columns (as in trackers). Cells are wrapped to several lines and the
     * least important ones are dropped if there's not enough space, but never overlap.
     */
    internal inner class CellGrid(val specs: Array<CellSpec>) {
        private val widths = FloatArray(specs.size)

        // the most important first
        private val importance = specs.indices.sortedBy { specs[it].priority }.toIntArray()
        val visible = BooleanArray(specs.size)
        val left = FloatArray(specs.size)
        val right = FloatArray(specs.size)
        val line = IntArray(specs.size)
        val captions = Array(specs.size) { FittedText() }
        var lines = 0
            private set
        var scale = 1f
            private set
        var gap = 0f
            private set

        fun update(w: Float, space: Float, scales: FloatArray) {
            // the biggest text which allows to show the most cells
            var best = scales[0]
            var bestShown = -1
            for (candidate in scales) {
                val shown = fit(w, space, candidate)
                if (shown > bestShown) {
                    best = candidate
                    bestShown = shown
                }
            }
            fit(w, space, best)
        }

        // @return visible cells count
        private fun fit(w: Float, space: Float, candidate: Float): Int {
            scale = candidate
            gap = cellPaint.textSize * CELL_GAP * scale
            measure()
            val maxLines = if (w <= 0f) 0 else (space / cellLineHeight(scale)).toInt().coerceIn(0, MAX_CELL_LINES)
            var shown = if (maxLines > 0) specs.size else 0
            visible.fill(shown != 0)
            while (pack(w) > maxLines) {
                visible[importance[--shown]] = false
            }
            return shown
        }

        // Text width is not exactly proportional to size (hinting), so templates are measured at the actual size
        private fun measure() {
            val valueSize = cellPaint.textSize
            val captionSize = captionPaint.textSize
            cellPaint.textSize = valueSize * scale
            captionPaint.textSize = captionSize * scale
            for (idx in specs.indices) {
                val spec = specs[idx]
                widths[idx] = maxOf(captionPaint.measureText(spec.caption), spec.templates.maxOf { cellPaint.measureText(it) }) + density
            }
            cellPaint.textSize = valueSize
            captionPaint.textSize = captionSize
        }

        // @return lines count
        private fun pack(w: Float): Int {
            var x = 0f
            var current = -1
            for (idx in specs.indices) {
                if (!visible[idx]) {
                    continue
                }
                val width = minOf(widths[idx], w)
                if (current < 0 || x + width > w) {
                    ++current
                    x = 0f
                }
                line[idx] = current
                left[idx] = x
                right[idx] = x + width
                x += width + gap
            }
            lines = current + 1
            for (ln in 0 until lines) {
                justify(ln, w)
            }
            return lines
        }

        // spare space is distributed between cells to use the whole width, but not too much to keep them grouped
        private fun justify(ln: Int, w: Float) {
            var count = 0
            var end = 0f
            for (idx in specs.indices) {
                if (visible[idx] && line[idx] == ln) {
                    ++count
                    end = right[idx]
                }
            }
            if (count < 2) {
                return
            }
            val add = minOf((w - end) / (count - 1), gap * MAX_JUSTIFY)
            var pos = 0
            for (idx in specs.indices) {
                if (visible[idx] && line[idx] == ln) {
                    left[idx] += add * pos
                    right[idx] += add * pos
                    ++pos
                }
            }
        }
    }

    /**
     * Ellipsized text cache, to not cut the same text every frame
     */
    internal inner class FittedText {
        private var source = ""
        private var width = 0f
        private var size = 0f
        private var result = ""

        fun get(text: String, maxWidth: Float, paint: Paint): String {
            if (text !== source || maxWidth != width || paint.textSize != size) {
                source = text
                width = maxWidth
                size = paint.textSize
                result = ellipsize(text, maxWidth, paint)
            }
            return result
        }
    }

    private class MeasuredText {
        private var source = ""
        private var size = 0f
        private var width = 0f

        fun of(text: String, paint: Paint): Float {
            if (text !== source || paint.textSize != size) {
                source = text
                size = paint.textSize
                width = paint.measureText(text)
            }
            return width
        }
    }

    private inner class CardFit {
        val fullWidth = MeasuredText()
        val titleWidth = MeasuredText()
        val summaryWidth = MeasuredText()
        val summary = FittedText()
        val title = FittedText()
    }

    // Caption and value of each cell are cut to cell's own width to never overlap neighbours, numbers are
    // right-aligned to keep digits in place
    // @param captions texts of cells with dynamic captions (see [CellSpec.dynamicCaption]) and their fit caches
    private fun drawCells(
        canvas: Canvas,
        grid: CellGrid,
        texts: LiveTexts,
        fitted: Array<FittedText>,
        left: Float,
        top: Float,
        captions: LiveTexts? = null,
        captionsFitted: Array<FittedText>? = null
    ) {
        if (grid.lines == 0) {
            return
        }
        val valueSize = cellPaint.textSize
        val captionSize = captionPaint.textSize
        cellPaint.textSize = valueSize * grid.scale
        captionPaint.textSize = captionSize * grid.scale
        val lineH = cellLineHeight(grid.scale)
        val x0 = left + cellsPadding()
        val y0 = top + density * 2
        for (idx in grid.specs.indices) {
            if (!grid.visible[idx]) {
                continue
            }
            val cellLeft = x0 + grid.left[idx]
            val cellRight = x0 + grid.right[idx]
            val cellTop = y0 + lineH * grid.line[idx]
            val captionBaseline = cellTop - captionPaint.ascent()
            val valueBaseline = captionBaseline + captionPaint.descent() - cellPaint.ascent()
            if (grid.left[idx] > 0f && pass.isStatic) {
                // thin separator in the middle of gap makes cells look as separate boxes
                val x = cellLeft - grid.gap / 2
                canvas.drawLine(x, cellTop + density, x, cellTop + lineH - density * 3, cellFramePaint)
            }
            val numeric = grid.specs[idx].numeric
            val align = if (numeric) Paint.Align.RIGHT else Paint.Align.LEFT
            val x = if (numeric) cellRight else cellLeft
            val w = cellRight - cellLeft
            captionPaint.textAlign = align
            cellPaint.textAlign = align
            if (captions != null && captionsFitted != null && grid.specs[idx].dynamicCaption) {
                val caption = captions[idx]
                if (caption.isNotEmpty() && pass.isDynamic) {
                    canvas.drawText(captionsFitted[idx].get(caption, w, captionPaint), x, captionBaseline, captionPaint)
                }
            } else if (pass.isStatic) {
                canvas.drawText(grid.captions[idx].get(grid.specs[idx].caption, w, captionPaint), x, captionBaseline, captionPaint)
            }
            val value = texts[idx]
            if (value.isNotEmpty() && pass.isDynamic) {
                canvas.drawText(fitted[idx].get(value, w, cellPaint), x, valueBaseline, cellPaint)
            }
        }
        captionPaint.textAlign = Paint.Align.LEFT
        cellPaint.textAlign = Paint.Align.LEFT
        cellPaint.textSize = valueSize
        captionPaint.textSize = captionSize
    }

    // Chips are placed side by side as in oscilloscope above, see [ChipColumns], card per voice
    private fun drawVoices(canvas: Canvas, gaugesData: GaugesExchange.Data, areaHeight: Float) {
        val current = gaugesData.layout
        val data = gaugesData.bytes
        val voices = minOf(gaugesData.count, data.size / VoiceGauges.SIZE)
        if (voices == 0 || areaHeight < density * 40) {
            return
        }
        // S-DSP has much more state than a generic card shows
        if (voices >= SpcDescription.VOICES && VoiceGauges(data, 0).kind == VoiceGauges.Kind.SPC_DSP) {
            drawSpc(canvas, data, voices, areaHeight)
            return
        }
        if (columnsLayout !== current) {
            columns = ChipColumns(current.groups)
            columnsLayout = current
            cardTitles.forEach { it.fill(null) }
        }
        val columns = columns
        val fullW = width.toFloat()
        var voice = 0
        current.groups.forEachIndexed { groupIdx, group ->
            val rows = columns.rows(groupIdx)
            // chips with less voices get taller cards
            val cellH = areaHeight / rows
            val first = columns.column(groupIdx, 0)
            if (first != 0 && pass.isStatic) {
                val x = fullW * columns.left(first)
                canvas.drawLine(x, 0f, x, areaHeight, separatorPaint)
            }
            group.voices.forEachIndexed { idx, name ->
                if (voice >= voices) {
                    return
                }
                val gauges = VoiceGauges(data, voice * VoiceGauges.SIZE)
                val col = columns.column(groupIdx, idx)
                val left = fullW * columns.left(col)
                val top = cellH * columns.row(groupIdx, idx)
                drawVoiceCard(canvas, gauges, group.name, name, left, top, fullW * columns.width(col), cellH, voice % MAX_VOICES)
                ++voice
            }
        }
    }

    private fun drawVoiceCard(
        canvas: Canvas,
        gauges: VoiceGauges,
        group: String,
        name: String,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        voice: Int
    ) {
        val smoothValues = smooth[voice]
        val pad = density * 2
        rect.set(left + pad, top + pad, left + w - pad, top + h - pad)
        if (pass.isStatic) {
            canvas.drawRect(rect, framePaint)
        }
        // title band is separated from graphs to keep both readable
        val stable = summaries[voice]
        if (pass.isDynamic) {
            stable.update(gauges)
        }
        val summary = stable.text
        if (summaryKinds[voice] != gauges.kind) {
            summaryKinds[voice] = gauges.kind
            summaryWidths[voice] = 0f
        }
        val fit = cardFits[voice]
        summaryWidths[voice] = maxOf(summaryWidths[voice], fit.summaryWidth.of(summary, summaryPaint))
        val reserved = summaryWidths[voice]
        val keyOn = gauges.isKeyOn
        // group name is omitted if there's no space for it
        val full = cardTitle(voice, group, name, keyOn, true)
        val available = rect.width() - density * 9 - reserved
        val title = if (fit.fullWidth.of(full, titlePaint) <= available) full else cardTitle(voice, group, name, keyOn, false)
        val band = drawTitleBand(canvas, rect, title, summary, reserved, fit)
        body.set(rect.left + pad * 2, band + pad, rect.right - pad * 2, rect.bottom - pad * 2)
        if (body.height() < density * 18) {
            // no space for details, only current level
            history.set(body)
            if (pass.isDynamic) {
                drawHistory(canvas, history, gauges, false)
            }
            return
        }
        val wide = body.width() > body.height() * 2.2f
        if (wide) {
            // operators schemas need more space
            val schema = gauges.kind == VoiceGauges.Kind.OPN_FM || gauges.kind == VoiceGauges.Kind.OPL_2OP ||
                gauges.kind == VoiceGauges.Kind.OPL_4OP
            val split = body.left + body.width() * (if (schema) 0.28f else 0.45f)
            history.set(body.left, body.top, split - pad * 2, body.bottom)
            widget.set(split + pad * 2, body.top, body.right, body.bottom)
        } else {
            val split = body.top + body.height() * 0.4f
            history.set(body.left, body.top, body.right, split - pad)
            widget.set(body.left, split + pad * 2, body.right, body.bottom)
        }
        if (pass.isDynamic && widgets.draw(canvas, widget, gauges, smoothValues, widgetTexts[voice])) {
            drawHistory(canvas, history, gauges, true)
        } else if (pass.isDynamic) {
            drawHistory(canvas, body, gauges, true)
        }
    }

    // S-DSP: row per voice (sample with output wave, envelope program on time axis, level and pitch history) with
    // cells of values below, and global row (volumes, echo repeats, FIR response). Voices are in one column on phones
    // and in two columns on wide screens, global row takes the whole width.
    private fun drawSpc(canvas: Canvas, data: ByteArray, voices: Int, areaHeight: Float) {
        val fullW = width.toFloat()
        val columns = if (fullW >= density * SPC_TWO_COLUMNS_DP) 2 else 1
        val voiceRows = SpcDescription.VOICES / columns
        val rowH = areaHeight / (voiceRows + 1)
        val colW = fullW / columns
        updateSpcGrids(colW, fullW, rowH)
        val aside = spcCellsAside
        val gridH = spcGridHeight()
        val panelH = if (aside) rowH else rowH - gridH
        val panelsW = if (aside) colW * (1 - SPC_ASIDE_CELLS) else colW
        // cells are vertically centered aside of panels
        val cellsDx = if (aside) panelsW else 0f
        val cellsDy = if (aside) (rowH - gridH) / 2 else panelH
        spcRowHeight = rowH
        spcPanelHeight = panelH
        spcColumns = columns
        if (pass.isDynamic) {
            spcGlobals.update(data, voices)
        }
        for (voice in 0 until SpcDescription.VOICES) {
            val g = VoiceGauges(data, voice * VoiceGauges.SIZE)
            val left = colW * (voice / voiceRows)
            val top = rowH * (voice % voiceRows)
            val texts = spcVoiceTexts[voice]
            if (pass.isDynamic) {
                spcEnvelopes[voice].update(g.field(2), g.field(3), g.field(4), g.field(0) * 16)
                texts.update(g, voice, spcEnvelopes[voice])
            }
            drawSpcPanels(canvas, texts.titles, spcVoiceTemplates, left, top, panelsW, panelH, voice, g)
            drawCells(canvas, spcVoiceGrid, texts.cells, spcVoiceFitted[voice], left + cellsDx, top + cellsDy, texts.captions, spcCaptionFitted[voice])
        }
        val globalTop = rowH * voiceRows
        if (pass.isDynamic) {
            spcGlobalText.update(spcGlobals)
        }
        val globalPanelsW = if (aside) fullW * (1 - SPC_ASIDE_CELLS) else fullW
        drawSpcPanels(canvas, spcGlobalText.titles, spcGlobalTemplates, 0f, globalTop, globalPanelsW, panelH, SpcDescription.VOICES, null)
        drawCells(canvas, spcGlobalGrid, spcGlobalText.cells, spcGlobalFitted, if (aside) globalPanelsW else 0f, globalTop + cellsDy)
        if (pass.isStatic) {
            if (columns > 1) {
                canvas.drawLine(colW, 0f, colW, globalTop, separatorPaint)
            }
            canvas.drawLine(0f, globalTop, fullW, globalTop, separatorPaint)
        }
    }

    // Cells are below panels if they fit there, else (short rows of landscape or small screens) aside of panels:
    // values in words are more important than taller plots
    private fun updateSpcGrids(colW: Float, fullW: Float, rowH: Float) {
        if (colW == spcGridWidth && rowH == spcGridSpace) {
            return
        }
        spcGridWidth = colW
        spcGridSpace = rowH
        val pad = cellsPadding() * 2
        val asideSpace = rowH - density * 4
        // plots are not shrunk below readable height for cells: envelope curve is the main information there
        val belowSpace = rowH - density * MIN_SPC_PANEL_DP
        spcVoiceGrid.update(colW * SPC_ASIDE_CELLS - pad, asideSpace, CELL_SCALES)
        val asideShown = spcVoiceGrid.visible.count { it }
        spcVoiceGrid.update(colW - pad, belowSpace, CELL_SCALES)
        val belowShown = spcVoiceGrid.visible.count { it }
        // narrower panels would cut raw registers in titles
        spcCellsAside = asideShown > belowShown && colW >= density * SPC_ASIDE_MIN_DP
        if (spcCellsAside) {
            spcVoiceGrid.update(colW * SPC_ASIDE_CELLS - pad, asideSpace, CELL_SCALES)
        }
        // the same text size for all rows
        val globalW = if (spcCellsAside) fullW * SPC_ASIDE_CELLS else fullW
        spcGlobalGrid.update(globalW - pad, if (spcCellsAside) asideSpace else belowSpace, floatArrayOf(spcVoiceGrid.scale))
    }

    // all rows have the same height to keep panels aligned
    private fun spcGridHeight(): Float {
        val lines = maxOf(spcVoiceGrid.lines, spcGlobalGrid.lines)
        return if (lines == 0) 0f else cellLineHeight(spcVoiceGrid.scale) * lines + density * 2
    }

    // Panels of a row have title bands of the same layout (see drawGaugesRow), selected by templates only
    private fun drawSpcPanels(
        canvas: Canvas,
        texts: LiveTexts,
        templates: TitleTemplates,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        row: Int,
        g: VoiceGauges?
    ) {
        selectSpcTitleLayout(templates, w, h)
        val pad = density
        var x = left
        for (idx in 0 until 3) {
            val panelW = w * SPC_PANELS[idx]
            rect.set(x + pad, top + pad, x + panelW - pad, top + h - pad)
            x += panelW
            if (pass.isStatic) {
                canvas.drawRect(rect, framePaint)
            }
            val plotTop = drawGaugeTitleBand(canvas, rect, texts, templates, idx, spcSecondLine, spcValueScale, spcTitleFitted[row * 3 + idx]) + pad * 2
            spcPlot.set(rect.left + pad * 2, plotTop, rect.right - pad * 2, rect.bottom - pad * 2)
            if (!pass.isDynamic || spcPlot.height() < density * 4) {
                continue
            }
            if (g != null) {
                when (idx) {
                    0 -> drawSpcSample(canvas, spcPlot, g)
                    1 -> drawSpcEnvelope(canvas, spcPlot, g, row, spcEnvelopes[row])
                    else -> drawHistory(canvas, spcPlot, g, true)
                }
            } else {
                when (idx) {
                    0 -> drawSpcVolumes(canvas, spcPlot)
                    1 -> drawSpcEcho(canvas, spcPlot)
                    else -> drawSpcFir(canvas, spcPlot)
                }
            }
        }
    }

    // Rows are low, so values are kept in title line by shrinking first, second line is the last resort
    private fun selectSpcTitleLayout(templates: TitleTemplates, w: Float, h: Float) {
        val minTitle = sp(MIN_TITLE_SIZE_SP) / sp(TITLE_SIZE_SP)
        spcSecondLine = false
        spcValueScale = when {
            spcTitleFits(templates, w, SHRINKED_TITLE, 1f) -> 1f

            spcTitleFits(templates, w, minTitle, MIN_VALUE_SCALE) -> MIN_VALUE_SCALE

            h > (titlePaint.fontSpacing + valuePaint.fontSpacing) * 2.4f -> {
                spcSecondLine = true
                var scale = 1f
                for (idx in 0 until 3) {
                    scale = minOf(scale, (w * SPC_PANELS[idx] - density * 8) / templates.valueWidth(idx))
                }
                scale.coerceIn(MIN_VALUE_SCALE, 1f)
            }

            else -> 0f
        }
    }

    private fun spcTitleFits(templates: TitleTemplates, w: Float, titleScale: Float, valueScale: Float): Boolean {
        for (idx in 0 until 3) {
            val inner = w * SPC_PANELS[idx] - density * 11 - titleGap()
            if (templates.titleWidth[idx] * titleScale + templates.valueWidth(idx) * valueScale > inner) {
                return false
            }
        }
        return true
    }

    // Output wave of voice and position in sample: the whole sample as a bar with looped part and current block
    private fun drawSpcSample(canvas: Canvas, area: RectF, g: VoiceGauges) {
        val hasBar = SpcSample.hasInfo(g) && area.height() > density * 16
        val barH = if (hasBar) minOf(density * 5, area.height() * 0.2f) else 0f
        val waveBottom = area.bottom - (if (hasBar) barH + density * 3 else 0f)
        drawSpcColumns(canvas, area.left, area.top, area.right, waveBottom, g, VoiceGauges.WAVE)
        if (!hasBar) {
            return
        }
        val blocks = SpcSample.blocks(g).coerceAtLeast(1)
        val top = area.bottom - barH
        val w = area.width()
        spcFillPaint.color = Color.argb(60, 255, 255, 255)
        canvas.drawRect(area.left, top, area.right, area.bottom, spcFillPaint)
        val loop = SpcSample.loopBlock(g)
        if (SpcSample.loops(g) && loop >= 0) {
            spcFillPaint.color = Color.argb(130, 255, 255, 255)
            canvas.drawRect(area.left + w * loop / blocks, top, area.right, area.bottom, spcFillPaint)
        }
        if (SpcSample.hasPosition(g) && g.field(0) != 0) {
            val x = area.left + w * (SpcSample.position(g) + 0.5f) / blocks
            spcFillPaint.color = Color.WHITE
            canvas.drawRect(x - density * 1.5f, top - density * 2, x + density * 1.5f, area.bottom, spcFillPaint)
        }
    }

    // min..max of columns, merged to line width (see drawGauge)
    private fun drawSpcColumns(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, gauges: GaugesData, gauge: Int) {
        val h = bottom - top
        if (h <= 0f) {
            return
        }
        val columnWidth = (right - left) / VoiceGauges.COLUMNS
        val lineWidth = maxOf(columnWidth, density)
        val step = mergedColumns(columnWidth, lineWidth)
        var count = 0
        for (group in 0 until groupsOf(step)) {
            val last = VoiceGauges.COLUMNS - 1 - group * step
            val first = maxOf(last - step + 1, 0)
            var max = 0
            var min = 255
            for (col in first..last) {
                max = maxOf(max, gauges.max(gauge, col))
                min = minOf(min, gauges.min(gauge, col))
            }
            val x = left + columnWidth * ((first + last) / 2f + 0.5f)
            val yMax = top + h * (1 - max / 255f)
            val yMin = top + h * (1 - min / 255f)
            val center = (yMin + yMax) / 2
            val half = maxOf((yMin - yMax) / 2, tracePaint.strokeWidth / 2)
            lines[count++] = x
            lines[count++] = center - half
            lines[count++] = x
            lines[count++] = center + half
        }
        tracePaint.strokeWidth = lineWidth
        canvas.drawLines(lines, 0, count, tracePaint)
        tracePaint.strokeWidth = TRACE_WIDTH * density
    }

    // Envelope program from key on on linear time axis (duration at the right), current stage is bright, dot is the
    // current level. Release is fixed 8ms linear fall from any level, so it's drawn separately at the right.
    private fun drawSpcEnvelope(canvas: Canvas, area: RectF, g: VoiceGauges, voice: Int, env: SpcEnvelope) {
        val mode = g.field(1)
        val level = minOf(g.field(0) * 16, SpcDescription.MAX_ENV)
        val withLabels = area.height() > density * 26
        val labelH = if (withLabels) spcLabelPaint.descent() - spcLabelPaint.ascent() else 0f
        val top = area.top + density
        val bottom = area.bottom - labelH
        val h = bottom - top
        if (h < density * 4) {
            return
        }
        val relW = minOf(area.width() * 0.16f, density * 30)
        val relLeft = area.right - relW
        val mainRight = relLeft - density * 5
        val mainW = mainRight - area.left
        val duration = env.duration.toFloat()
        fun yOf(level: Float) = bottom - h * level / SpcDescription.MAX_ENV
        fun xOf(time: Float) = area.left + mainW * time / duration
        val current = when {
            mode == SpcDescription.MODE_RELEASE -> -1
            mode == SpcDescription.MODE_GAIN || !env.isAdsr -> 0
            else -> mode - 1
        }
        // stages fills, the current one is bright
        val stages = if (env.isAdsr) 3 else 1
        val columns = maxOf(8, (mainW / (density * 1.5f)).toInt())
        for (stage in 0 until stages) {
            val from = env.times[env.stageStart[stage]].toFloat()
            val to = env.times[env.stageStart[if (env.isAdsr) stage + 1 else 3]].toFloat()
            if (to <= from && stage != 0) {
                continue
            }
            spcPath.reset()
            spcPath.moveTo(xOf(from), bottom)
            val first = (from / duration * columns).toInt()
            val last = (to / duration * columns).toInt().coerceAtMost(columns)
            spcPath.lineTo(xOf(from), yOf(env.levelAt(from)))
            for (col in first + 1..last) {
                val t = duration * col / columns
                spcPath.lineTo(xOf(t), yOf(env.levelAt(t)))
            }
            spcPath.lineTo(xOf(to), yOf(env.levelAt(to)))
            spcPath.lineTo(xOf(to), bottom)
            spcPath.close()
            spcFillPaint.color = Color.argb(if (stage == current) 150 else 45, 255, 255, 255)
            canvas.drawPath(spcPath, spcFillPaint)
            if (stage > 0) {
                canvas.drawLine(xOf(from), top, xOf(from), bottom, cellFramePaint)
            }
        }
        // the curve itself
        spcPath.reset()
        spcPath.moveTo(area.left, yOf(env.levelAt(0f)))
        for (col in 1..columns) {
            val t = duration * col / columns
            spcPath.lineTo(xOf(t), yOf(env.levelAt(t)))
        }
        tracePaint.style = Paint.Style.STROKE
        tracePaint.strokeWidth = density * 1.5f
        canvas.drawPath(spcPath, tracePaint)
        // release
        val released = mode == SpcDescription.MODE_RELEASE
        if (released) {
            spcPath.reset()
            spcPath.moveTo(relLeft, top)
            spcPath.lineTo(area.right, bottom)
            spcPath.lineTo(relLeft, bottom)
            spcPath.close()
            spcFillPaint.color = Color.argb(150, 255, 255, 255)
            canvas.drawPath(spcPath, spcFillPaint)
        }
        canvas.drawLine(relLeft, top, area.right, bottom, if (released) tracePaint else dimTracePaint)
        tracePaint.strokeWidth = TRACE_WIDTH * density
        // current level
        if (level > 0 || mode != SpcDescription.MODE_RELEASE) {
            val y = yOf(level.toFloat())
            val x = when {
                released -> relLeft + relW * (1f - level.toFloat() / SpcDescription.MAX_ENV)

                // time is unknown on endless level, so dot is in the middle of it
                env.isEndless && !env.isAdsr -> (area.left + mainRight) / 2

                env.isEndless && current == 2 -> (xOf(env.times[env.stageStart[2]].toFloat()) + mainRight) / 2

                else -> xOf(env.timeOf(current, level).coerceAtLeast(0f))
            }
            canvas.drawLine(x, y, x, bottom, tracePaint)
            spcFillPaint.color = Color.WHITE
            canvas.drawCircle(x, y, density * 3, spcFillPaint)
        }
        if (withLabels) {
            val baseline = area.bottom - spcLabelPaint.descent()
            // labels are dropped rather than overlapped in narrow panels
            if (env.isEndless) {
                // only the end of attack and decay has time, the level is kept forever after it
                val infiniteW = spcLabelPaint.measureText(SpcDescription.Words.INFINITE)
                var room = mainRight - area.left
                if (env.isAdsr) {
                    val span = spcEnvelopeSpan(voice, env)
                    val x = xOf(env.times[env.stageStart[2]].toFloat()) + density * 2
                    canvas.drawText(span, x, baseline, spcLabelPaint)
                    room = mainRight - (x + spcLabelPaint.measureText(span) + density * 4)
                }
                if (infiniteW <= room) {
                    spcLabelPaint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(SpcDescription.Words.INFINITE, mainRight, baseline, spcLabelPaint)
                }
            } else {
                spcLabelPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(spcEnvelopeSpan(voice, env), mainRight, baseline, spcLabelPaint)
            }
            // release duration is the same for any envelope, so it's the first to drop
            if (spcLabelPaint.measureText(SPC_RELEASE_LABEL) <= relW + density * 4) {
                spcLabelPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(SPC_RELEASE_LABEL, (relLeft + area.right) / 2, baseline, spcLabelPaint)
            }
            spcLabelPaint.textAlign = Paint.Align.LEFT
        }
    }

    private val spcSpanSamples = IntArray(SpcDescription.VOICES) { -1 }
    private val spcSpanTexts = Array(SpcDescription.VOICES) { "" }

    // Time at the end of program, or at the start of endless sustain. Not formatted each frame.
    private fun spcEnvelopeSpan(voice: Int, env: SpcEnvelope): String {
        val samples = if (env.isEndless) env.attackSamples + env.decaySamples else env.duration
        if (spcSpanSamples[voice] != samples) {
            spcSpanSamples[voice] = samples
            spcSpanTexts[voice] = SpcDescription.durationWord(samples)
        }
        return spcSpanTexts[voice]
    }

    // Signed bars from center: main and echo volumes, negative (inverted phase) are to the left
    private fun drawSpcVolumes(canvas: Canvas, area: RectF) {
        val rowH = area.height() / 4
        val labelSize = spcLabelPaint.textSize
        spcLabelPaint.textSize = minOf(labelSize, rowH * 0.95f)
        val withLabels = spcLabelPaint.textSize >= labelSize * 0.6f
        val labelW = if (withLabels) spcLabelPaint.measureText(SPC_VOLUME_LABELS[3]) + density * 4 else 0f
        val left = area.left + labelW
        val cx = (left + area.right) / 2
        val half = (area.right - left) / 2
        canvas.drawLine(cx, area.top, cx, area.bottom, cellFramePaint)
        for (idx in 0 until 4) {
            val value = when (idx) {
                0 -> spcGlobals.mainLeft
                1 -> spcGlobals.mainRight
                2 -> spcGlobals.echoLeft
                else -> spcGlobals.echoRight
            }
            val top = area.top + rowH * idx
            if (withLabels) {
                canvas.drawText(SPC_VOLUME_LABELS[idx], area.left, top + (rowH - spcLabelPaint.ascent() - spcLabelPaint.descent()) / 2, spcLabelPaint)
            }
            val barTop = top + rowH * 0.2f
            val barBottom = top + rowH * 0.8f
            val end = cx + half * value / 128f
            spcFillPaint.color = Color.argb(if (idx < 2) 220 else 150, 255, 255, 255)
            canvas.drawRect(minOf(cx, end), barTop, maxOf(cx, end), barBottom, spcFillPaint)
        }
        spcLabelPaint.textSize = labelSize
    }

    // Echo repeats on time axis: the first is delayed by EDL*16ms and scaled by echo volume and FIR gain, each next
    // one is scaled by feedback and FIR gain again (at low frequencies, see FIR panel for others)
    private fun drawSpcEcho(canvas: Canvas, area: RectF) {
        val gl = spcGlobals
        val delay = gl.delay
        val dc = minOf(gl.firDcGain, 1f)
        val first = maxOf(abs(gl.echoLeft), abs(gl.echoRight)) / 128f * dc
        val feedback = minOf(abs(gl.feedback) / 128f * dc, 1f)
        val withLabels = area.height() > density * 26
        val labelH = if (withLabels) spcLabelPaint.descent() - spcLabelPaint.ascent() else 0f
        val bottom = area.bottom - labelH
        val h = bottom - area.top
        canvas.drawLine(area.left, bottom, area.right, bottom, cellFramePaint)
        // dry signal at 0 for scale
        val dry = maxOf(abs(gl.mainLeft), abs(gl.mainRight)) / 128f
        val barW = maxOf(density * 2, area.width() / 60)
        spcFillPaint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRect(area.left, bottom - h * dry, area.left + barW, bottom, spcFillPaint)
        val audible = delay > 0 && first > 0.001f
        if (audible) {
            spcFillPaint.color = Color.argb(if (gl.isEchoWriteEnabled) 220 else 90, 255, 255, 255)
            val step = (area.width() - barW) / SPC_ECHO_REPEATS
            var amp = first
            for (n in 1..SPC_ECHO_REPEATS) {
                val x = area.left + step * n
                canvas.drawRect(x, bottom - h * amp, x + barW, bottom, spcFillPaint)
                amp *= feedback
                if (amp < 0.004f) {
                    break
                }
            }
        }
        if (withLabels) {
            val baseline = area.bottom - spcLabelPaint.descent()
            spcLabelPaint.textAlign = Paint.Align.RIGHT
            val span = if (audible) spcEchoSpan(delay) else SPC_NO_ECHO
            canvas.drawText(span, area.right, baseline, spcLabelPaint)
            spcLabelPaint.textAlign = Paint.Align.LEFT
        }
    }

    private val spcEchoSpans = Array(16) { "${it * 16 * SPC_ECHO_REPEATS} ms" }

    private fun spcEchoSpan(delay: Int) = spcEchoSpans[delay and 15]

    // FIR magnitude response, 0..16kHz on linear axis, -24..+12dB with 0dB line
    private fun drawSpcFir(canvas: Canvas, area: RectF) {
        val fir = spcGlobals.fir
        var key = 0L
        for (c in fir) {
            key = (key shl 8) or (c.toLong() and 0xff)
        }
        if (key != spcFirKey) {
            spcFirKey = key
            for (idx in spcFir.indices) {
                val hz = SpcDescription.SAMPLE_RATE / 2f * idx / (spcFir.size - 1)
                spcFir[idx] = SpcDescription.db(SpcDescription.firGain(fir, hz))
            }
        }
        val withLabels = area.height() > density * 26
        val labelH = if (withLabels) spcLabelPaint.descent() - spcLabelPaint.ascent() else 0f
        val bottom = area.bottom - labelH
        val h = bottom - area.top
        fun yOf(db: Float) = bottom - h * (db.coerceIn(SPC_FIR_MIN_DB, SPC_FIR_MAX_DB) - SPC_FIR_MIN_DB) / (SPC_FIR_MAX_DB - SPC_FIR_MIN_DB)
        val zero = yOf(0f)
        canvas.drawLine(area.left, zero, area.right, zero, cellFramePaint)
        spcPath.reset()
        for (idx in spcFir.indices) {
            val x = area.left + area.width() * idx / (spcFir.size - 1)
            val y = yOf(spcFir[idx])
            if (idx == 0) spcPath.moveTo(x, y) else spcPath.lineTo(x, y)
        }
        tracePaint.style = Paint.Style.STROKE
        tracePaint.strokeWidth = density * 1.5f
        canvas.drawPath(spcPath, tracePaint)
        tracePaint.strokeWidth = TRACE_WIDTH * density
        if (withLabels) {
            val baseline = area.bottom - spcLabelPaint.descent()
            canvas.drawText(SPC_FIR_LOW, area.left, baseline, spcLabelPaint)
            spcLabelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(SPC_FIR_HIGH, area.right, baseline, spcLabelPaint)
            spcLabelPaint.textAlign = Paint.Align.LEFT
            // most of echo filters are low pass, so the right end is the least occupied
            spcLabelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(SPC_FIR_ZERO, area.right - density * 2, zero - spcLabelPaint.descent() - density, spcLabelPaint)
            spcLabelPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun cardTitle(voice: Int, group: String, name: String, keyOn: Boolean, withGroup: Boolean): String {
        val slot = (if (keyOn) 1 else 0) + (if (withGroup) 2 else 0)
        return cardTitles[voice][slot] ?: buildString {
            if (withGroup) {
                append(group)
                append(' ')
            }
            append(name)
            if (keyOn) {
                append(" \u25CF")
            }
        }.also { cardTitles[voice][slot] = it }
    }

    // Title has priority over summary: summary is shrunk and cut if both don't fit
    // @param reserved width of summary to keep title and summary size independent of the current summary
    // @return bottom of title band
    private fun drawTitleBand(canvas: Canvas, card: RectF, title: String, summary: String, reserved: Float, fit: CardFit): Float {
        val pad = density * 3
        val baseline = card.top + pad - titlePaint.ascent()
        val baseSize = sp(TITLE_SIZE_SP)
        val minSize = sp(MIN_TITLE_SIZE_SP)
        val titleWidth = fit.titleWidth.of(title, titlePaint)
        val inner = card.width() - pad * 3
        val valueBaseSize = summaryPaint.textSize
        val room = inner - minOf(titleWidth, titleWidth * minSize / baseSize) - titleGap()
        if (reserved > room) {
            summaryPaint.textSize *= maxOf(room / reserved, 0.8f)
        }
        val value = fit.summary.get(summary, room, summaryPaint)
        // gap keeps title and summary regions apart
        val available = inner - minOf(reserved * summaryPaint.textSize / valueBaseSize, room) - titleGap()
        if (pass.isDynamic) {
            drawCardTitle(canvas, title, card.left + pad, baseline, available, titleWidth, fit.title)
            if (value.length >= 4 || value == summary) {
                canvas.drawText(value, card.right - pad, baseline, summaryPaint)
            }
        }
        summaryPaint.textSize = valueBaseSize
        val bottom = baseline + titlePaint.descent() + pad
        if (pass.isStatic) {
            canvas.drawLine(card.left, bottom, card.right, bottom, framePaint)
        }
        return bottom
    }

    // Raw title is at the left, value at the right in its own region of template width (or at the second line).
    // All sizes are taken from templates, so only changed parts of texts are redrawn differently.
    // @param valueScale value text scale or 0 if not displayed
    // @return bottom of title band
    private fun drawGaugeTitleBand(
        canvas: Canvas,
        card: RectF,
        texts: LiveTexts,
        templates: TitleTemplates,
        idx: Int,
        secondLine: Boolean,
        valueScale: Float,
        fit: FittedTitle
    ): Float {
        val pad = density * 3
        val baseline = card.top + pad - titlePaint.ascent()
        val inner = card.width() - pad * 3
        val title = texts[SidRowText.title(idx)]
        val dynamic = pass.isDynamic
        val bottom = if (!secondLine) {
            val valueWidth = if (valueScale > 0f) templates.valueWidth(idx) * valueScale + titleGap() else 0f
            if (dynamic) {
                drawTitle(canvas, fit, title, card.left + pad, baseline, inner - valueWidth, templates.titleWidth[idx])
                if (valueScale > 0f) {
                    drawGaugeValue(canvas, texts, templates, idx, card.right - pad, baseline, valueScale, fit.main)
                }
            }
            baseline + titlePaint.descent() + pad
        } else {
            val valueBaseline = baseline + titlePaint.descent() - valuePaint.ascent()
            if (dynamic) {
                drawTitle(canvas, fit, title, card.left + pad, baseline, inner, templates.titleWidth[idx])
                val room = card.width() - pad * 2
                drawGaugeValue(canvas, texts, templates, idx, card.right - pad, valueBaseline, valueScale, fit.main, room)
            }
            valueBaseline + valuePaint.descent() + pad
        }
        if (pass.isStatic) {
            canvas.drawLine(card.left, bottom, card.right, bottom, framePaint)
        }
        return bottom
    }

    // Tail is aligned in its own fixed slot at the right, main part is right-aligned before it and cut to the rest
    // of value region
    private fun drawGaugeValue(
        canvas: Canvas,
        texts: LiveTexts,
        templates: TitleTemplates,
        idx: Int,
        right: Float,
        baseline: Float,
        scale: Float,
        mainFit: FittedText,
        room: Float = templates.valueWidth(idx) * scale
    ) {
        val baseSize = valuePaint.textSize
        valuePaint.textSize = baseSize * scale
        val tailWidth = templates.tailWidth[idx] * scale
        val tail = texts[SidRowText.tail(idx)]
        if (tail.isNotEmpty()) {
            if (templates.items[idx].tailAlign == Paint.Align.LEFT) {
                valuePaint.textAlign = Paint.Align.LEFT
                canvas.drawText(tail, right - tailWidth, baseline, valuePaint)
                valuePaint.textAlign = Paint.Align.RIGHT
            } else {
                canvas.drawText(tail, right, baseline, valuePaint)
            }
        }
        val main = texts[SidRowText.main(idx)]
        if (main.isNotEmpty()) {
            val mainRight = right - tailWidth - (if (tailWidth > 0f) templates.spaceWidth * scale else 0f)
            val available = room - (right - mainRight)
            if (available > 0f) {
                canvas.drawText(mainFit.get(main, available, valuePaint), mainRight, baseline, valuePaint)
            }
        }
        valuePaint.textSize = baseSize
    }

    /**
     * Fitted raw title of gauge (see [drawTitle]) and its value cache, to not measure the same texts every frame
     */
    private inner class FittedTitle {
        private var source = ""
        private var available = -1f
        var text = ""
            private set
        var size = 0f
            private set
        val main = FittedText()

        // Shrinks title down to minimal size and then cuts it. Size is selected by widest title to not
        // change it with each value.
        fun update(fullTitle: String, maxWidth: Float, templateWidth: Float) {
            if (fullTitle === source && maxWidth == available) {
                return
            }
            source = fullTitle
            available = maxWidth
            val baseSize = sp(TITLE_SIZE_SP)
            // raw register values are more important than gauge name in narrow cells
            val shorten = templateWidth * sp(MIN_TITLE_SIZE_SP) / baseSize > maxWidth
            val title = if (shorten) fullTitle.replace(" Wave ", " ") else fullTitle
            val actual = titlePaint.measureText(title)
            val template = if (shorten) templateWidth * actual / titlePaint.measureText(fullTitle) else templateWidth
            // template may be narrower for some values, cutting them is worse than rare size change
            val width = maxOf(template, actual)
            size = if (width > maxWidth) maxOf(baseSize * maxWidth / width, sp(MIN_TITLE_SIZE_SP)) else baseSize
            titlePaint.textSize = size
            text = ellipsize(title, maxWidth, titlePaint)
            titlePaint.textSize = baseSize
        }
    }

    private fun drawTitle(
        canvas: Canvas,
        fit: FittedTitle,
        fullTitle: String,
        x: Float,
        y: Float,
        available: Float,
        templateWidth: Float
    ) {
        fit.update(fullTitle, available, templateWidth)
        val baseSize = titlePaint.textSize
        titlePaint.textSize = fit.size
        canvas.drawText(fit.text, x, y, titlePaint)
        titlePaint.textSize = baseSize
    }

    // Shrinks title down to minimal size and then cuts it
    private fun drawCardTitle(canvas: Canvas, title: String, x: Float, y: Float, available: Float, titleWidth: Float, fit: FittedText) {
        val baseSize = sp(TITLE_SIZE_SP)
        titlePaint.textSize =
            if (titleWidth > available) maxOf(baseSize * available / titleWidth, sp(MIN_TITLE_SIZE_SP)) else baseSize
        canvas.drawText(fit.get(title, available, titlePaint), x, y, titlePaint)
        titlePaint.textSize = baseSize
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) {
            return text
        }
        val count = paint.breakText(text, true, maxOf(maxWidth - paint.measureText(ELLIPSIS), 0f), null)
        return if (count == 0) "" else text.substring(0, count).trimEnd(' ', ',') + ELLIPSIS
    }

    // level (dB) as filled columns, pitch (log scale) as line over it
    private fun drawHistory(canvas: Canvas, area: RectF, gauges: VoiceGauges, withPitch: Boolean) {
        if (area.height() <= 0 || area.width() <= 0) {
            return
        }
        val columnWidth = area.width() / VoiceGauges.COLUMNS
        // the last column is in progress, so history is scrolled smoothly by its part
        val shift = columnWidth * (1 - gauges.phase)
        canvas.save()
        canvas.clipRect(area)
        for (col in 0 until VoiceGauges.COLUMNS) {
            val x = area.left + columnWidth * (col + 0.5f) + shift
            val idx = col * 4
            lines[idx] = x
            lines[idx + 1] = area.bottom - area.height() * gauges.max(VoiceGauges.LEVEL, col) / 255f
            lines[idx + 2] = x
            lines[idx + 3] = area.bottom
        }
        dimTracePaint.strokeWidth = maxOf(columnWidth, density)
        canvas.drawLines(lines, dimTracePaint)
        if (!withPitch) {
            canvas.restore()
            return
        }
        var count = 0
        for (col in 0 until VoiceGauges.COLUMNS) {
            val value = gauges.max(VoiceGauges.FREQUENCY, col)
            if (value == 0) {
                continue
            }
            val x = area.left + columnWidth * (col + 0.5f) + shift
            val yMax = area.bottom - area.height() * value / 255f
            val yMin = area.bottom - area.height() * gauges.min(VoiceGauges.FREQUENCY, col) / 255f
            lines[count++] = x
            lines[count++] = yMax - density
            lines[count++] = x
            lines[count++] = maxOf(yMin, yMax) + density
        }
        tracePaint.strokeWidth = maxOf(columnWidth, density * 1.5f)
        canvas.drawLines(lines, 0, count, tracePaint)
        tracePaint.strokeWidth = TRACE_WIDTH * density
        canvas.restore()
    }

    // Each column is vertical line between min and max values, as in JSIDPlay2
    private fun drawGauge(
        canvas: Canvas,
        gauges: GaugesData,
        gauge: Int,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        texts: LiveTexts,
        templates: TitleTemplates,
        idx: Int,
        secondLine: Boolean,
        valueScale: Float,
        fit: FittedTitle
    ) {
        val pad = density
        rect.set(left + pad, top + pad, left + w - pad, top + h - pad)
        if (pass.isStatic) {
            canvas.drawRect(rect, framePaint)
        }
        val plotTop = drawGaugeTitleBand(canvas, rect, texts, templates, idx, secondLine, valueScale, fit) + pad * 2
        val plotHeight = rect.bottom - pad * 2 - plotTop
        if (plotHeight <= 0 || !pass.isDynamic) {
            return
        }
        val plotLeft = rect.left + pad
        val columnWidth = (rect.width() - pad * 2) / ChipGauges.COLUMNS
        // slow gauges' last column is in progress, so history is scrolled smoothly by its part
        val slow = ChipGauges.isSlow(gauge) && gauges is ChipGauges
        val shift = if (slow) columnWidth * (1 - (gauges as ChipGauges).phase) else 0f
        if (slow) {
            canvas.save()
            canvas.clipRect(plotLeft, plotTop - pad, rect.right - pad, rect.bottom - pad)
        }
        // minimal visible segment for flat parts
        val minHeight = tracePaint.strokeWidth
        val lineWidth = maxOf(columnWidth, density)
        // scrolled history is not merged, else content of groups is changed at each new column
        val step = if (slow) 1 else mergedColumns(columnWidth, lineWidth)
        var count = 0
        for (group in 0 until groupsOf(step)) {
            val last = ChipGauges.COLUMNS - 1 - group * step
            val first = maxOf(last - step + 1, 0)
            var max = 0
            var min = 255
            for (col in first..last) {
                max = maxOf(max, gauges.max(gauge, col))
                min = minOf(min, gauges.min(gauge, col))
            }
            val x = plotLeft + columnWidth * ((first + last) / 2f + 0.5f) + shift
            val yMax = plotTop + plotHeight * (1 - max / 255f)
            val yMin = plotTop + plotHeight * (1 - min / 255f)
            val center = (yMin + yMax) / 2
            val half = maxOf((yMin - yMax) / 2, minHeight / 2)
            lines[count++] = x
            lines[count++] = center - half
            lines[count++] = x
            lines[count++] = center + half
        }
        tracePaint.strokeWidth = lineWidth
        canvas.drawLines(lines, 0, count, tracePaint)
        tracePaint.strokeWidth = TRACE_WIDTH * density
        if (slow) {
            canvas.restore()
        }
    }

    // Columns narrower than line are merged (minimum of minimums, maximum of maximums), so picture is the same while
    // much less lines are drawn (e.g. 3 chips on phone: 256 columns in 120px wide gauge by 2.6px wide lines)
    private fun mergedColumns(columnWidth: Float, lineWidth: Float) = maxOf(1, (lineWidth / columnWidth).toInt())

    // groups are aligned by the latest column
    private fun groupsOf(step: Int) = (ChipGauges.COLUMNS + step - 1) / step

    private fun padding() = density * 4

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private const val TRACE_WIDTH = 1f
        private const val TITLE_SIZE_SP = 13f
        private const val MIN_TITLE_SIZE_SP = 9f
        private const val TEXT_SIZE_SP = 12f
        private const val CELL_SIZE_SP = 13f
        private const val CAPTION_SIZE_SP = 10f

        // 13sp -> 11sp, still readable on phones
        private val CELL_SCALES = floatArrayOf(1f, 0.92f, 0.85f)

        // more lines make gauges too small, it's better to drop less important cells
        private const val MAX_CELL_LINES = 2
        private const val MIN_GAUGE_DP = 72

        // relative to text size
        private const val CELL_GAP = 0.8f
        private const val MAX_JUSTIFY = 2f
        private const val MIN_VALUE_SCALE = 0.8f

        // widest titles and values of gauges
        private val VOICE_TEMPLATE = arrayOf(
            TitleTemplate("S1V1 Wave F SRTF", "Pulse+Tri Sync Ring", arrayOf(SidDescription.GATE_MARK)),
            TitleTemplate("Env FFFF", "100%", SidDescription.Phase.values().map { it.short }.toTypedArray(), Paint.Align.LEFT),
            TitleTemplate("Freq FFFF", "G#8", arrayOf("1047Hz", "3.8kHz"))
        )
        private val GLOBAL_TEMPLATE = arrayOf(
            TitleTemplate("Vol 15", "", arrayOf("100%")),
            TitleTemplate("Res 15", "", arrayOf("100%")),
            TitleTemplate("Filter LBH 2047", "BP+HP", arrayOf("~9.9kHz", "~18kHz", "~880Hz"))
        )

        // S-DSP panels: sample with output wave, envelope, level and pitch history
        private val SPC_PANELS = floatArrayOf(0.27f, 0.41f, 0.32f)
        private val SPC_VOICE_TEMPLATE = arrayOf(
            TitleTemplate("V8 SRCN FF", "once", arrayOf(SidDescription.GATE_MARK)),
            TitleTemplate("ADSR FF FF", "100%", SpcDescription.Stage.values().map { it.short }.toTypedArray(), Paint.Align.LEFT),
            TitleTemplate("Pitch 3FFF", "\u2248C#5", arrayOf("128kHz", "32.0kHz"))
        )
        private val SPC_GLOBAL_TEMPLATE = arrayOf(
            TitleTemplate("MVOL 7F 81", "", arrayOf("EVOL 3C 3C")),
            TitleTemplate("EDL 15 EFB 7F", "240 ms", arrayOf("\u2212100%")),
            TitleTemplate("FIR", "", arrayOf("DC \u221212.0dB"))
        )

        // rows are taller with voices in two columns
        private const val SPC_TWO_COLUMNS_DP = 760

        // part of row width for cells aside of panels
        private const val SPC_ASIDE_CELLS = 0.42f
        private const val SPC_ASIDE_MIN_DP = 390
        private const val MIN_SPC_PANEL_DP = 42
        private const val SPC_LABEL_SIZE_SP = 9f
        private const val SPC_FIR_POINTS = 65
        private const val SPC_FIR_MIN_DB = -24f
        private const val SPC_FIR_MAX_DB = 12f
        private const val SPC_ECHO_REPEATS = 8
        private const val SPC_RELEASE_LABEL = "8 ms"
        private const val SPC_NO_ECHO = "no echo"
        private const val SPC_FIR_LOW = "0"
        private const val SPC_FIR_HIGH = "16 kHz"
        private const val SPC_FIR_ZERO = "0 dB"
        private val SPC_VOLUME_LABELS = arrayOf("Main L", "Main R", "Echo L", "Echo R")
        private val VOICE_GAUGES = Array(3) {
            intArrayOf(ChipGauges.wave(it), ChipGauges.envelope(it), ChipGauges.frequency(it))
        }
        private val GLOBAL_GAUGES = intArrayOf(ChipGauges.VOLUME, ChipGauges.RESONANCE, ChipGauges.CUTOFF)
        private const val SHRINKED_TITLE = 0.85f
        private const val ELLIPSIS = "\u2026"
        const val DEFAULT_WAVE_WINDOW_MS = 10

        private const val MAX_CHIPS = 3
        private const val MAX_VOICES = 64

        private const val STATUS_PERIOD_NS = 500_000_000L

        // smooth enough for gauges, vsync timestamps jitter is allowed
        private const val DASHBOARD_FPS = 60
        private const val MIN_REDRAW_INTERVAL_NS = 1_000_000_000L / DASHBOARD_FPS - 2_000_000L

        // Waveform bits and flags: Sync, Ring modulation, Test, Filtered
        internal fun waveTitle(control: Int, filtered: Boolean) = buildString {
            append("Wave ")
            append(Integer.toHexString(control shr 4))
            append(' ')
            if (control and 2 != 0) append('S')
            if (control and 4 != 0) append('R')
            if (control and 8 != 0) append('T')
            if (filtered) append('F')
        }

        // Filter modes: Lowpass, Bandpass, Highpass
        internal fun filterTitle(mode: Int, cutoff: Int) = buildString {
            append("Filter ")
            if (mode and 1 != 0) append('L')
            if (mode and 2 != 0) append('B')
            if (mode and 4 != 0) append('H')
            append(' ')
            append(cutoff)
        }

        // Digits of the same width keep numbers in place while they change. Roboto has them by default,
        // but not every font does.
        private fun Paint.tabularFigures() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                fontFeatureSettings = "tnum"
            }
        }
    }
}
