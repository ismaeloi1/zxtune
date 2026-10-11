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
import android.os.Build
import android.os.SystemClock
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
    private val summaryAlpha = summaryPaint.alpha

    // human readable values of registers are greyed to separate them from raw values
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 255, 255, 255)
        textSize = sp(TEXT_SIZE_SP)
        textAlign = Paint.Align.RIGHT
        tabularFigures()
    }
    private val valueAlpha = valuePaint.alpha

    // explanations are secondary information, so greyed and proportional to fit more words
    private val explainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        textSize = sp(EXPLAIN_SIZE_SP)
        tabularFigures()
    }
    private val explainAlpha = explainPaint.alpha
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

    /**
     * Time source for texts stabilization
     */
    @androidx.annotation.VisibleForTesting
    internal var clock: () -> Long = { SystemClock.uptimeMillis() }
    private val voiceTexts = Array(MAX_CHIPS) { Array(3) { SidVoiceText() } }
    private val globalTexts = Array(MAX_CHIPS) { SidGlobalText() }
    private val fitted = Array(MAX_CHIPS * 4) { Array(SidDescription.SLOTS) { FittedText() } }
    private val slots = SlotsLayout()
    private val voiceTemplates = TitleTemplates(VOICE_TEMPLATE)
    private val globalTemplates = TitleTemplates(GLOBAL_TEMPLATE)

    // geometry of the last drawn gauges rows
    @androidx.annotation.VisibleForTesting
    internal var gaugesRowHeight = 0f

    @androidx.annotation.VisibleForTesting
    internal var gaugesCellHeight = 0f
    private val summaries = Array(MAX_VOICES) { ChipWidgets.StableSummary() }

    // summary width is reserved by widest one to not shift title with each summary change
    private val summaryWidths = FloatArray(MAX_VOICES)
    private val summaryKinds = IntArray(MAX_VOICES) { -1 }

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
        val now = clock()
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
        gaugesRowHeight = rowH
        gaugesCellHeight = cellH
        slots.update(groupW - explanationPadding() * 2, explainLines)
        for (idx in 0 until chips) {
            val gauges = ChipGauges(buffers[readyBuffer], idx * ChipGauges.SIZE)
            val last = gauges.state
            val left = groupW * idx
            val chipLabel = if (chips > 1) idx + 1 else 0
            for (voice in 0 until 3) {
                val texts = voiceTexts[idx][voice]
                texts.update(last, voice, envelopeTrend(gauges, last, voice), chip, chipLabel, now)
                drawGaugesRow(canvas, gauges, VOICE_GAUGES[voice], texts.texts, voiceTemplates, left, rowH * voice, cellW, cellH, now)
                drawSlots(canvas, texts.texts, fitted[idx * 4 + voice], left, rowH * voice + cellH, now)
            }
            val texts = globalTexts[idx]
            texts.update(last, chip, now)
            drawGaugesRow(canvas, gauges, GLOBAL_GAUGES, texts.texts, globalTemplates, left, rowH * 3, cellW, cellH, now)
            drawSlots(canvas, texts.texts, fitted[idx * 4 + 3], left, rowH * 3 + cellH, now)
            if (idx != 0) {
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

    // Gauges in a row have the same title band height to keep plots aligned
    private fun drawGaugesRow(
        canvas: Canvas,
        gauges: GaugesData,
        ids: IntArray,
        texts: StableTexts,
        templates: TitleTemplates,
        left: Float,
        top: Float,
        cellW: Float,
        cellH: Float,
        now: Long
    ) {
        // see drawGauge and drawTitleBand paddings
        val inner = cellW - density * 11
        // widest values are used instead of current ones to keep layout stable
        val crowded = (0 until 3).any {
            templates.titleWidth[it] * SHRINKED_TITLE + templates.valueWidth(it) > inner
        }
        // second line is used only if plot is still visible enough
        val twoLines = crowded && cellH > (titlePaint.fontSpacing + valuePaint.fontSpacing) * 1.8f
        for (idx in 0 until 3) {
            drawGauge(canvas, gauges, ids[idx], left + cellW * idx, top, cellW, cellH, texts, templates, idx, twoLines, now)
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

    /**
     * Fixed positions of explanation slots (see [SidDescription.SLOTS]) for given width and lines count.
     * Sizes are taken from templates, so neither positions nor text size depend on current texts: changing part
     * of explanation doesn't move others (as columns in trackers).
     */
    private inner class SlotsLayout {
        private val templates = FloatArray(SidDescription.SLOTS) { idx ->
            SidDescription.SLOT_TEMPLATES[idx].maxOf { explainPaint.measureText(it) }
        }
        private var width = -1f
        var lines = -1
            private set
        var textSize = 0f
            private set
        val x = FloatArray(SidDescription.SLOTS)
        val maxWidth = FloatArray(SidDescription.SLOTS)
        val row = IntArray(SidDescription.SLOTS)

        fun update(w: Float, linesCount: Int) {
            if (w == width && linesCount == lines) {
                return
            }
            width = w
            lines = linesCount
            val base = explainPaint.textSize
            val gap = base
            val single = templates.sum() + gap * (SidDescription.SLOTS - 1)
            // two rows of two columns aligned across all rows: wave and filter, pitch and envelope
            val col0 = maxOf(templates[0], templates[2])
            val col1 = maxOf(templates[1], templates[3])
            val twoRows = linesCount >= 2 && single > w
            val required = if (twoRows) col0 + gap + col1 else single
            val scale = (w / required).coerceIn(MIN_EXPLANATION_SCALE, 1f)
            // what still doesn't fit is distributed proportionally and cut
            val squeeze = minOf(1f, w / (required * scale)) * scale
            textSize = base * scale
            if (twoRows) {
                for (idx in 0 until SidDescription.SLOTS) {
                    val second = idx % 2 == 1
                    row[idx] = idx / 2
                    x[idx] = if (second) (col0 + gap) * squeeze else 0f
                    maxWidth[idx] = if (second) w - x[idx] else col0 * squeeze
                }
            } else {
                var pos = 0f
                for (idx in 0 until SidDescription.SLOTS) {
                    row[idx] = 0
                    x[idx] = pos
                    maxWidth[idx] = if (idx == SidDescription.SLOTS - 1) w - pos else templates[idx] * squeeze
                    pos += (templates[idx] + gap) * squeeze
                }
            }
        }
    }

    /**
     * Ellipsized text cache, to not cut the same text every frame
     */
    private inner class FittedText {
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

    private fun drawSlots(canvas: Canvas, texts: StableTexts, cache: Array<FittedText>, left: Float, top: Float, now: Long) {
        if (slots.lines <= 0) {
            return
        }
        val baseSize = explainPaint.textSize
        explainPaint.textSize = slots.textSize
        val baseline = top + density - explainPaint.ascent()
        for (idx in 0 until SidDescription.SLOTS) {
            val text = texts[SidRowText.slot(idx)]
            if (text.isEmpty()) {
                continue
            }
            explainPaint.alpha = fadedAlpha(explainAlpha, texts.fade(SidRowText.slot(idx), now))
            val fit = cache[idx].get(text, slots.maxWidth[idx], explainPaint)
            canvas.drawText(
                fit,
                left + explanationPadding() + slots.x[idx],
                baseline + explainPaint.fontSpacing * slots.row[idx],
                explainPaint
            )
        }
        explainPaint.alpha = explainAlpha
        explainPaint.textSize = baseSize
    }

    // changed text appears from dimmed state to mark the change without movement
    private fun fadedAlpha(alpha: Int, progress: Float) = (alpha * (FADE_FROM + (1f - FADE_FROM) * progress)).toInt()

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
        val now = clock()
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
                drawVoiceCard(canvas, gauges, group.name, name, left, top, cellW, cellH, voice % MAX_VOICES, now)
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
        voice: Int,
        now: Long
    ) {
        val smoothValues = smooth[voice]
        val pad = density * 2
        rect.set(left + pad, top + pad, left + w - pad, top + h - pad)
        canvas.drawRect(rect, framePaint)
        // title band is separated from graphs to keep both readable
        val stable = summaries[voice]
        stable.update(gauges, now)
        val summary = stable.text
        if (summaryKinds[voice] != gauges.kind) {
            summaryKinds[voice] = gauges.kind
            summaryWidths[voice] = 0f
        }
        summaryWidths[voice] = maxOf(summaryWidths[voice], summaryPaint.measureText(summary))
        val reserved = summaryWidths[voice]
        val key = if (gauges.isKeyOn) " \u25CF" else ""
        // group name is omitted if there's no space for it
        val full = "$group $name$key"
        val available = rect.width() - density * 9 - reserved
        val title = if (titlePaint.measureText(full) <= available) full else "$name$key"
        summaryPaint.alpha = fadedAlpha(summaryAlpha, StableValue.fadeProgress(stable.changedAt, now))
        val band = drawTitleBand(canvas, rect, title, summary, reserved)
        summaryPaint.alpha = summaryAlpha
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

    // Title has priority over summary: summary is shrunk and cut if both don't fit
    // @param reserved width of summary to keep title and summary size independent of the current summary
    // @return bottom of title band
    private fun drawTitleBand(canvas: Canvas, card: RectF, title: String, summary: String, reserved: Float): Float {
        val pad = density * 3
        val baseline = card.top + pad - titlePaint.ascent()
        val baseSize = sp(TITLE_SIZE_SP)
        val minSize = sp(MIN_TITLE_SIZE_SP)
        val titleWidth = titlePaint.measureText(title)
        val inner = card.width() - pad * 3
        val valueBaseSize = summaryPaint.textSize
        val room = inner - minOf(titleWidth, titleWidth * minSize / baseSize)
        if (reserved > room) {
            summaryPaint.textSize *= maxOf(room / reserved, 0.8f)
        }
        val value = ellipsize(summary, room, summaryPaint)
        val available = inner - minOf(reserved * summaryPaint.textSize / valueBaseSize, room)
        drawTitle(canvas, title, card.left + pad, baseline, available, titleWidth)
        if (value.length >= 4 || value == summary) {
            canvas.drawText(value, card.right - pad, baseline, summaryPaint)
        }
        summaryPaint.textSize = valueBaseSize
        val bottom = baseline + titlePaint.descent() + pad
        canvas.drawLine(card.left, bottom, card.right, bottom, framePaint)
        return bottom
    }

    // Value is placed at the second line (if requested) or shrunk and cut if both don't fit. All sizes are taken
    // from templates, so only changed parts of texts are redrawn differently.
    // @return bottom of title band
    private fun drawGaugeTitleBand(
        canvas: Canvas,
        card: RectF,
        texts: StableTexts,
        templates: TitleTemplates,
        idx: Int,
        secondLine: Boolean,
        now: Long
    ): Float {
        val pad = density * 3
        val baseline = card.top + pad - titlePaint.ascent()
        val baseSize = sp(TITLE_SIZE_SP)
        val minSize = sp(MIN_TITLE_SIZE_SP)
        val titleWidth = templates.titleWidth[idx]
        val inner = card.width() - pad * 3
        val valueWidth = templates.valueWidth(idx)
        val title = texts[SidRowText.title(idx)]
        val bottom = if (!secondLine) {
            val room = inner - minOf(titleWidth, titleWidth * minSize / baseSize)
            val scale = if (valueWidth > room) maxOf(room / valueWidth, 0.8f) else 1f
            drawTitle(canvas, title, card.left + pad, baseline, inner - minOf(valueWidth * scale, room), titleWidth)
            drawGaugeValue(canvas, texts, templates, idx, card.right - pad, baseline, scale, room, now)
            baseline + titlePaint.descent() + pad
        } else {
            drawTitle(canvas, title, card.left + pad, baseline, inner, titleWidth)
            val valueBaseline = baseline + titlePaint.descent() - valuePaint.ascent()
            val room = card.width() - pad * 2
            val scale = if (valueWidth > room) maxOf(room / valueWidth, 0.8f) else 1f
            drawGaugeValue(canvas, texts, templates, idx, card.right - pad, valueBaseline, scale, room, now)
            valueBaseline + valuePaint.descent() + pad
        }
        canvas.drawLine(card.left, bottom, card.right, bottom, framePaint)
        return bottom
    }

    // Tail is aligned in its own fixed slot at the right, main part is right-aligned before it
    private fun drawGaugeValue(
        canvas: Canvas,
        texts: StableTexts,
        templates: TitleTemplates,
        idx: Int,
        right: Float,
        baseline: Float,
        scale: Float,
        room: Float,
        now: Long
    ) {
        val baseSize = valuePaint.textSize
        valuePaint.textSize = baseSize * scale
        val tailWidth = templates.tailWidth[idx] * scale
        val tail = texts[SidRowText.tail(idx)]
        if (tail.isNotEmpty()) {
            valuePaint.alpha = fadedAlpha(valueAlpha, texts.fade(SidRowText.tail(idx), now))
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
                valuePaint.alpha = fadedAlpha(valueAlpha, texts.fade(SidRowText.main(idx), now))
                canvas.drawText(ellipsize(main, available, valuePaint), mainRight, baseline, valuePaint)
            }
        }
        valuePaint.alpha = valueAlpha
        valuePaint.textSize = baseSize
    }

    // Shrinks title down to minimal size and then cuts it. Size is selected by widest title to not
    // change it with each value.
    private fun drawTitle(canvas: Canvas, fullTitle: String, x: Float, y: Float, available: Float, templateWidth: Float) {
        val baseSize = sp(TITLE_SIZE_SP)
        // raw register values are more important than gauge name in narrow cells
        val shorten = templateWidth * sp(MIN_TITLE_SIZE_SP) / baseSize > available
        val title = if (shorten) fullTitle.replace(" Wave ", " ") else fullTitle
        val actual = titlePaint.measureText(title)
        val template = if (shorten) templateWidth * actual / titlePaint.measureText(fullTitle) else templateWidth
        // template may be narrower for some values, cutting them is worse than rare size change
        val width = maxOf(template, actual)
        titlePaint.textSize =
            if (width > available) maxOf(baseSize * available / width, sp(MIN_TITLE_SIZE_SP)) else baseSize
        canvas.drawText(ellipsize(title, available, titlePaint), x, y, titlePaint)
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
        texts: StableTexts,
        templates: TitleTemplates,
        idx: Int,
        twoLines: Boolean,
        now: Long
    ) {
        val pad = density
        rect.set(left + pad, top + pad, left + w - pad, top + h - pad)
        canvas.drawRect(rect, framePaint)
        val plotTop = drawGaugeTitleBand(canvas, rect, texts, templates, idx, twoLines, now) + pad * 2
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
        private val VOICE_TEMPLATE = arrayOf(
            TitleTemplate("S1V1 Wave F SRTF", "Pulse 50% Sync", arrayOf(SidDescription.GATE_MARK)),
            TitleTemplate("Env FFFF", "100%", SidDescription.Phase.values().map { it.short }.toTypedArray(), Paint.Align.LEFT),
            TitleTemplate("Freq FFFF", "C#5", arrayOf("1047Hz", "3.8kHz"))
        )
        private val GLOBAL_TEMPLATE = arrayOf(
            TitleTemplate("Vol 15", "", arrayOf("100%")),
            TitleTemplate("Res 15", "", arrayOf("100%")),
            TitleTemplate("Filter LBH 2047", "Notch", arrayOf("~9.9kHz", "~18kHz", "~880Hz"))
        )
        private val VOICE_GAUGES = Array(3) {
            intArrayOf(ChipGauges.wave(it), ChipGauges.envelope(it), ChipGauges.frequency(it))
        }
        private val GLOBAL_GAUGES = intArrayOf(ChipGauges.VOLUME, ChipGauges.RESONANCE, ChipGauges.CUTOFF)
        private const val MIN_EXPLANATION_SCALE = 0.7f

        // dimmed enough to notice, but still readable
        private const val FADE_FROM = 0.4f
        private const val SHRINKED_TITLE = 0.85f
        private const val ELLIPSIS = "\u2026"
        const val DEFAULT_WAVE_WINDOW_MS = 10

        private const val MAX_CHIPS = 3
        private const val MAX_VOICES = 64

        // same as in OscilloscopeView
        private const val MAX_ROWS = 8
        private fun columnsOf(voices: Int) = (voices + MAX_ROWS - 1) / MAX_ROWS
        private fun rowsOf(voices: Int) = (voices + columnsOf(voices) - 1) / maxOf(columnsOf(voices), 1)

        private const val STATUS_PERIOD_NS = 500_000_000L

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
