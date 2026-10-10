/**
 * @file
 * @brief Chips state gauges and diagnostics (inspired by JSIDPlay2's oscilloscope view)
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import app.zxtune.Logger
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.ChipState
import app.zxtune.playback.GaugesData
import app.zxtune.playback.Visualizer
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoicesLayout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = Logger("SidDashboard")

/**
 * Displays history of SID state for single chip:
 * - per voice: oscillator output, envelope (dB), frequency (log scale)
 * - global: master volume, resonance, filter cutoff
 * Other chips are displayed per voice as cards: title band with live summary, level and pitch history and
 * hardware specific state (see [ChipWidgets]).
 * And multiline status text below. Chips are displayed side by side.
 */
class SidDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile
    private var source: Visualizer? = null

    /**
     * Displayed duration of oscillators and volume gauges
     */
    @Volatile
    var waveWindowMs = DEFAULT_WAVE_WINDOW_MS
    private var statsProvider: (() -> String)? = null

    // Data is requested on background thread to keep UI responsive, binder calls may take time
    private val executor = Executors.newSingleThreadExecutor()
    private val requestPending = AtomicBoolean(false)
    private val buffers = Array(2) { ByteArray(MAX_CHIPS * ChipGauges.SIZE) }
    private var voiceBuffers = Array(2) { ByteArray(0) }
    private var readyBuffer = 0

    @Volatile
    private var readyChips = 0

    @Volatile
    private var readyVoices = 0

    @Volatile
    private var layout = VoicesLayout.MASTER

    @Volatile
    private var status = ""
    private var lastStatusTime = 0L

    private val density = resources.displayMetrics.density
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = sp(TITLE_SIZE_SP)
        // keeps titles readable over traces
        setShadowLayer(density * 2, 0f, 0f, Color.BLACK)
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
    }

    // explanations are secondary information, so greyed and proportional to fit more words
    private val explainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textSize = sp(EXPLAIN_SIZE_SP)
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

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (source == null || !isAttachedToWindow) {
                return
            }
            requestData(frameTimeNanos)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        // consume taps to not toggle parent's visualizer or close fullscreen
        isClickable = true
    }

    /**
     * @param src data source or null to stop updating
     * @param stats provider of additional (UI side) statistics lines
     */
    fun setSource(src: Visualizer?, stats: (() -> String)? = null) {
        val wasActive = source != null
        source = src
        statsProvider = stats
        if (src != null && !wasActive) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else if (src == null) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            readyChips = 0
            readyVoices = 0
            layout = VoicesLayout.MASTER
            invalidate()
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun setVoicesData(voicesLayout: VoicesLayout, data: ByteArray, voices: Int, statusText: String) {
        layout = voicesLayout
        voiceBuffers[readyBuffer] = data
        readyVoices = voices
        status = statusText
    }

    @androidx.annotation.VisibleForTesting
    internal fun setGaugesData(data: ByteArray, chips: Int, statusText: String) {
        layout = VoicesLayout.parse("registers")
        data.copyInto(buffers[readyBuffer], 0, 0, minOf(data.size, buffers[readyBuffer].size))
        readyVoices = 0
        readyChips = chips
        status = statusText
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    private fun requestData(nowNanos: Long) {
        val src = source ?: return
        if (!requestPending.compareAndSet(false, true)) {
            return
        }
        val needStatus = nowNanos - lastStatusTime > STATUS_PERIOD_NS
        if (needStatus) {
            lastStatusTime = nowNanos
        }
        executor.execute {
            try {
                if (needStatus) {
                    // cheap enough to not track layout changes precisely
                    layout = runCatching { VoicesLayout.parse(src.getLayout()) }.getOrDefault(VoicesLayout.MASTER)
                }
                val target = 1 - readyBuffer
                val current = layout
                if (current.hasRegisters) {
                    val chips = runCatching { src.getGauges(buffers[target], waveWindowMs) }.getOrDefault(0)
                    readyBuffer = target
                    readyVoices = 0
                    readyChips = chips
                } else if (current.voicesCount != 0) {
                    // binder transfers whole array, so keep it as small as possible
                    val size = current.voicesCount * VoiceGauges.SIZE
                    if (voiceBuffers[target].size != size) {
                        voiceBuffers[target] = ByteArray(size)
                    }
                    val voices = runCatching { src.getVoiceGauges(voiceBuffers[target], waveWindowMs) }.getOrDefault(0)
                    readyBuffer = target
                    readyChips = 0
                    readyVoices = voices
                } else {
                    readyChips = 0
                    readyVoices = 0
                }
                if (needStatus) {
                    status = runCatching { src.getStatus() }.getOrElse {
                        LOG.w(it) { "Failed to get status" }
                        ""
                    }
                }
            } finally {
                requestPending.set(false)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val lines = buildList {
            addAll(status.lines().filter { it.isNotBlank() })
            statsProvider?.invoke()?.lines()?.filter { it.isNotBlank() }?.let { addAll(it) }
        }
        val lineHeight = textPaint.fontSpacing
        val textHeight = lineHeight * lines.size + padding()
        if (readyVoices != 0) {
            drawVoices(canvas, height - textHeight)
        } else {
            drawGauges(canvas, height - textHeight)
        }
        var y = height - textHeight + padding() / 2 - textPaint.ascent()
        for (line in lines) {
            canvas.drawText(line, padding(), y, textPaint)
            y += lineHeight
        }
    }

    // Chips are placed side by side as in oscilloscope above: 3 gauges columns per chip.
    // Each row (voice or global filter/volume state) ends with plain language explanation strip, if fits.
    private fun drawGauges(canvas: Canvas, areaHeight: Float) {
        val chips = readyChips
        if (chips == 0 || areaHeight < density * 40) {
            return
        }
        val chip = SidDescription.Chip.fromStatus(status)
        val groupW = width.toFloat() / chips
        val cellW = groupW / 3
        val rowH = areaHeight / 4
        // explanation is less important than gauges themselves, so takes space only if it's enough;
        // space is reserved regardless of current text to avoid layout jumping
        val explainLines = (MAX_EXPLANATION_LINES downTo 1).firstOrNull {
            rowH - explanationHeight(it) >= density * MIN_EXPLAINED_CELL_DP
        } ?: 0
        val cellH = rowH - explanationHeight(explainLines)
        for (idx in 0 until chips) {
            val gauges = ChipGauges(buffers[readyBuffer], idx * ChipGauges.SIZE)
            val last = gauges.state
            val left = groupW * idx
            val chipPrefix = if (chips > 1) "S${idx + 1}" else ""
            for (voice in 0 until 3) {
                val trend = envelopeTrend(gauges, last, voice)
                val cells = listOf(
                    Cell(
                        ChipGauges.wave(voice),
                        "${chipPrefix}V${voice + 1} ${waveTitle(last, voice)}",
                        SidDescription.waveSummary(last, voice)
                    ),
                    Cell(
                        ChipGauges.envelope(voice),
                        "Env ${last.envelopeRegs(voice)}",
                        SidDescription.envelopeSummary(last, voice, trend)
                    ),
                    Cell(
                        ChipGauges.frequency(voice),
                        "Freq ${hex4(last.frequency(voice))}",
                        SidDescription.frequencySummary(last, voice, chip)
                    )
                )
                drawGaugesRow(canvas, gauges, cells, VOICE_TEMPLATE, left, rowH * voice, cellW, cellH)
                val explanation = SidDescription.voiceExplanation(last, voice, trend, chip)
                drawExplanation(canvas, explanation, left, rowH * voice + cellH, groupW, explainLines)
            }
            val cells = listOf(
                Cell(ChipGauges.VOLUME, "Vol ${last.volume}", SidDescription.volumeSummary(last)),
                Cell(ChipGauges.RESONANCE, "Res ${last.resonance}", SidDescription.resonanceSummary(last)),
                Cell(ChipGauges.CUTOFF, filterTitle(last), SidDescription.filterSummary(last, chip))
            )
            drawGaugesRow(canvas, gauges, cells, GLOBAL_TEMPLATE, left, rowH * 3, cellW, cellH)
            val explanation = SidDescription.filterExplanation(last, chip)
            drawExplanation(canvas, explanation, left, rowH * 3 + cellH, groupW, explainLines)
            if (idx != 0) {
                canvas.drawLine(left, 0f, left, areaHeight, separatorPaint)
            }
        }
    }

    private class Cell(val gauge: Int, val title: String, val summary: String)

    // Gauges in a row have the same title band height to keep plots aligned
    private fun drawGaugesRow(
        canvas: Canvas,
        gauges: GaugesData,
        cells: List<Cell>,
        template: List<Cell>,
        left: Float,
        top: Float,
        cellW: Float,
        cellH: Float
    ) {
        // see drawGauge and drawTitleBand paddings
        val inner = cellW - density * 11
        // widest values are used instead of current ones to keep layout stable
        val crowded = template.any {
            titlePaint.measureText(it.title) * SHRINKED_TITLE + valuePaint.measureText(it.summary) > inner
        }
        // second line is used only if plot is still visible enough
        val twoLines = crowded && cellH > (titlePaint.fontSpacing + valuePaint.fontSpacing) * 1.8f
        cells.forEachIndexed { idx, cell ->
            drawGauge(
                canvas,
                gauges,
                cell.gauge,
                left + cellW * idx,
                top,
                cellW,
                cellH,
                cell.title,
                cell.summary,
                twoLines
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

    private fun explanationHeight(lines: Int) = if (lines == 0) 0f else explainPaint.fontSpacing * lines + density * 2

    private fun explanationPadding() = density * 4

    // Wraps text by words to the given lines count, the last line is shrunk (down to 70%) and cut to fit width
    private fun drawExplanation(canvas: Canvas, text: String, left: Float, top: Float, w: Float, lines: Int) {
        val pad = explanationPadding()
        val maxWidth = w - pad * 2
        if (maxWidth <= 0 || lines == 0) {
            return
        }
        val baseSize = explainPaint.textSize
        // whole text is scaled uniformly to look consistent, words wrapping wastes some space
        val width = explainPaint.measureText(text)
        if (width > maxWidth * lines) {
            explainPaint.textSize = baseSize * maxOf(maxWidth * lines * 0.92f / width, 0.7f)
        }
        var rest = text
        var y = top + density - explainPaint.ascent()
        for (line in 1 until lines) {
            if (explainPaint.measureText(rest) <= maxWidth) {
                break
            }
            val count = explainPaint.breakText(rest, true, maxWidth, null)
            // break at words boundary if possible
            val space = rest.lastIndexOf(' ', count)
            val end = if (space > 0) space else count
            canvas.drawText(rest, 0, end, left + pad, y, explainPaint)
            rest = rest.substring(end).trimStart()
            y += explainPaint.fontSpacing
        }
        canvas.drawText(ellipsize(rest, maxWidth, explainPaint), left + pad, y, explainPaint)
        explainPaint.textSize = baseSize
    }

    // Chips are placed side by side as in oscilloscope above (long chips are split to several columns),
    // card per voice
    private fun drawVoices(canvas: Canvas, areaHeight: Float) {
        val voices = readyVoices
        val current = layout
        if (voices == 0 || areaHeight < density * 40) {
            return
        }
        val data = voiceBuffers[readyBuffer]
        if (data.size < voices * VoiceGauges.SIZE) {
            return
        }
        var totalCols = 0
        for (group in current.groups) {
            totalCols += columnsOf(group.voices.size)
        }
        val cellW = width.toFloat() / maxOf(totalCols, 1)
        var voice = 0
        var col = 0
        for (group in current.groups) {
            val rows = rowsOf(group.voices.size)
            // chips with less voices get taller cards
            val cellH = areaHeight / rows
            if (col != 0) {
                canvas.drawLine(cellW * col, 0f, cellW * col, areaHeight, separatorPaint)
            }
            group.voices.forEachIndexed { idx, name ->
                if (voice >= voices) {
                    return
                }
                val gauges = VoiceGauges(data, voice * VoiceGauges.SIZE)
                val left = cellW * (col + idx / rows)
                val top = cellH * (idx % rows)
                drawVoiceCard(canvas, gauges, group.name, name, left, top, cellW, cellH, smooth[voice % MAX_VOICES])
                ++voice
            }
            col += columnsOf(group.voices.size)
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
        smoothValues: FloatArray
    ) {
        val pad = density * 2
        rect.set(left + pad, top + pad, left + w - pad, top + h - pad)
        canvas.drawRect(rect, framePaint)
        // title band is separated from graphs to keep both readable
        val summary = widgets.summary(gauges)
        val key = if (gauges.isKeyOn) " \u25CF" else ""
        // group name is omitted if there's no space for it
        val full = "$group $name$key"
        val available = rect.width() - density * 9 - summaryPaint.measureText(summary)
        val title = if (titlePaint.measureText(full) <= available) full else "$name$key"
        val band = drawTitleBand(canvas, rect, title, summary)
        body.set(rect.left + pad * 2, band + pad, rect.right - pad * 2, rect.bottom - pad * 2)
        if (body.height() < density * 18) {
            // no space for details, only current level
            history.set(body)
            drawHistory(canvas, history, gauges, false)
            return
        }
        val wide = body.width() > body.height() * 2.2f
        if (wide) {
            // operators schemas need more space
            val schema = gauges.kind == VoiceGauges.Kind.OPN_FM || gauges.kind == VoiceGauges.Kind.OPL_2OP ||
                gauges.kind == VoiceGauges.Kind.OPL_4OP
            val split = body.left + body.width() * (if (schema) 0.35f else 0.45f)
            history.set(body.left, body.top, split - pad * 2, body.bottom)
            widget.set(split + pad * 2, body.top, body.right, body.bottom)
        } else {
            val split = body.top + body.height() * 0.4f
            history.set(body.left, body.top, body.right, split - pad)
            widget.set(body.left, split + pad * 2, body.right, body.bottom)
        }
        if (widgets.draw(canvas, widget, gauges, smoothValues)) {
            drawHistory(canvas, history, gauges, true)
        } else {
            drawHistory(canvas, body, gauges, true)
        }
    }

    // Title has priority over summary: summary is placed at the second line (if requested) or shrunk and cut
    // if both don't fit
    // @return bottom of title band
    private fun drawTitleBand(
        canvas: Canvas,
        card: RectF,
        title: String,
        summary: String,
        valuePaint: Paint = summaryPaint,
        secondLine: Boolean = false
    ): Float {
        val pad = density * 3
        val baseline = card.top + pad - titlePaint.ascent()
        val baseSize = sp(TITLE_SIZE_SP)
        val minSize = sp(MIN_TITLE_SIZE_SP)
        val titleWidth = titlePaint.measureText(title)
        val inner = card.width() - pad * 3
        val valueBaseSize = valuePaint.textSize
        val bottom = if (!secondLine || summary.isEmpty()) {
            val room = inner - minOf(titleWidth, titleWidth * minSize / baseSize)
            val value = fitText(summary, room, valuePaint, 0.8f)
            val available = inner - valuePaint.measureText(value)
            drawTitle(canvas, title, card.left + pad, baseline, available)
            if (value.isNotEmpty()) {
                canvas.drawText(value, card.right - pad, baseline, valuePaint)
            }
            baseline + titlePaint.descent() + pad
        } else {
            drawTitle(canvas, title, card.left + pad, baseline, inner)
            val valueBaseline = baseline + titlePaint.descent() - valuePaint.ascent()
            val value = fitText(summary, card.width() - pad * 2, valuePaint, 0.8f)
            canvas.drawText(value, card.right - pad, valueBaseline, valuePaint)
            valueBaseline + valuePaint.descent() + pad
        }
        valuePaint.textSize = valueBaseSize
        canvas.drawLine(card.left, bottom, card.right, bottom, framePaint)
        return bottom
    }

    // shrinks title down to minimal size and then cuts it
    private fun drawTitle(canvas: Canvas, fullTitle: String, x: Float, y: Float, available: Float) {
        val baseSize = sp(TITLE_SIZE_SP)
        // raw register values are more important than gauge name in narrow cells
        val title = if (titlePaint.measureText(fullTitle) * sp(MIN_TITLE_SIZE_SP) / baseSize > available) {
            fullTitle.replace(" Wave ", " ")
        } else {
            fullTitle
        }
        val titleWidth = titlePaint.measureText(title)
        titlePaint.textSize =
            if (titleWidth > available) maxOf(baseSize * available / titleWidth, sp(MIN_TITLE_SIZE_SP)) else baseSize
        canvas.drawText(ellipsize(title, available, titlePaint), x, y, titlePaint)
        titlePaint.textSize = baseSize
    }

    // Changes paint size (restoring is caller's responsibility), empty result if too little is left
    private fun fitText(text: String, maxWidth: Float, paint: Paint, minScale: Float): String {
        val width = paint.measureText(text)
        if (width <= maxWidth) {
            return text
        }
        paint.textSize *= maxOf(maxWidth / width, minScale)
        val result = ellipsize(text, maxWidth, paint)
        // too short beginning is useless
        return if (result.length < 4) "" else result
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
        for (col in 0 until VoiceGauges.COLUMNS) {
            val x = area.left + columnWidth * (col + 0.5f)
            val idx = col * 4
            lines[idx] = x
            lines[idx + 1] = area.bottom - area.height() * gauges.max(VoiceGauges.LEVEL, col) / 255f
            lines[idx + 2] = x
            lines[idx + 3] = area.bottom
        }
        dimTracePaint.strokeWidth = maxOf(columnWidth, density)
        canvas.drawLines(lines, dimTracePaint)
        if (!withPitch) {
            return
        }
        var count = 0
        for (col in 0 until VoiceGauges.COLUMNS) {
            val value = gauges.max(VoiceGauges.FREQUENCY, col)
            if (value == 0) {
                continue
            }
            val x = area.left + columnWidth * (col + 0.5f)
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
        title: String,
        summary: String,
        twoLines: Boolean
    ) {
        val pad = density
        rect.set(left + pad, top + pad, left + w - pad, top + h - pad)
        canvas.drawRect(rect, framePaint)
        val plotTop = drawTitleBand(canvas, rect, title, summary, valuePaint, twoLines) + pad * 2
        val plotHeight = rect.bottom - pad * 2 - plotTop
        if (plotHeight <= 0) {
            return
        }
        val plotLeft = rect.left + pad
        val columnWidth = (rect.width() - pad * 2) / ChipGauges.COLUMNS
        // minimal visible segment for flat parts
        val minHeight = tracePaint.strokeWidth
        for (col in 0 until ChipGauges.COLUMNS) {
            val x = plotLeft + columnWidth * (col + 0.5f)
            val yMax = plotTop + plotHeight * (1 - gauges.max(gauge, col) / 255f)
            val yMin = plotTop + plotHeight * (1 - gauges.min(gauge, col) / 255f)
            val center = (yMin + yMax) / 2
            val half = maxOf((yMin - yMax) / 2, minHeight / 2)
            val idx = col * 4
            lines[idx] = x
            lines[idx + 1] = center - half
            lines[idx + 2] = x
            lines[idx + 3] = center + half
        }
        tracePaint.strokeWidth = maxOf(columnWidth, density)
        canvas.drawLines(lines, tracePaint)
        tracePaint.strokeWidth = TRACE_WIDTH * density
    }

    private fun padding() = density * 4

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private const val TRACE_WIDTH = 1f
        private const val TITLE_SIZE_SP = 13f
        private const val MIN_TITLE_SIZE_SP = 9f
        private const val TEXT_SIZE_SP = 12f
        private const val EXPLAIN_SIZE_SP = 11f
        private const val MIN_EXPLAINED_CELL_DP = 56
        private const val MAX_EXPLANATION_LINES = 2

        // widest titles and values of gauges
        private val VOICE_TEMPLATE = listOf(
            Cell(0, "S1V1 Wave F SRTF", "Pulse 50% Sync \u25CF"),
            Cell(0, "Env FFFF", "100% Atk"),
            Cell(0, "Freq FFFF", "C#5 1047Hz")
        )
        private val GLOBAL_TEMPLATE = listOf(
            Cell(0, "Vol 15", "100%"),
            Cell(0, "Res 15", "100%"),
            Cell(0, "Filter LBH 2047", "Notch ~18kHz")
        )
        private const val SHRINKED_TITLE = 0.85f
        private const val ELLIPSIS = "\u2026"
        const val DEFAULT_WAVE_WINDOW_MS = 10

        private fun hex4(value: Int) = String.format(java.util.Locale.US, "%04X", value)
        private const val MAX_CHIPS = 3
        private const val MAX_VOICES = 64

        // same as in OscilloscopeView
        private const val MAX_ROWS = 8
        private fun columnsOf(voices: Int) = (voices + MAX_ROWS - 1) / MAX_ROWS
        private fun rowsOf(voices: Int) = (voices + columnsOf(voices) - 1) / maxOf(columnsOf(voices), 1)

        private const val STATUS_PERIOD_NS = 500_000_000L

        // Waveform bits and flags: Sync, Ring modulation, Test, Filtered
        internal fun waveTitle(state: ChipState, voice: Int) = buildString {
            val ctrl = state.control(voice)
            append("Wave ")
            append(Integer.toHexString(ctrl shr 4))
            append(' ')
            if (ctrl and 2 != 0) append('S')
            if (ctrl and 4 != 0) append('R')
            if (ctrl and 8 != 0) append('T')
            if (state.routing and (1 shl voice) != 0) append('F')
        }

        // Filter modes: Lowpass, Bandpass, Highpass
        internal fun filterTitle(state: ChipState) = buildString {
            append("Filter ")
            val mode = state.mode
            if (mode and 1 != 0) append('L')
            if (mode and 2 != 0) append('B')
            if (mode and 4 != 0) append('H')
            append(' ')
            append(state.cutoff)
        }
    }
}
