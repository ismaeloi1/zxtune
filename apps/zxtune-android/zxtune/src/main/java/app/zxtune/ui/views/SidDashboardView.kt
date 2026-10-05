/**
 * @file
 * @brief SID chip state gauges and diagnostics (inspired by JSIDPlay2's oscilloscope view)
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
import app.zxtune.playback.Visualizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = Logger("SidDashboard")

/**
 * Displays history of SID state for single chip:
 * - per voice: oscillator output, envelope (dB), frequency (log scale)
 * - global: master volume, resonance, filter cutoff
 * And multiline status text below. Multi-SID tunes chips are displayed side by side.
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
    private var readyBuffer = 0

    @Volatile
    private var readyChips = 0

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
    private val rect = RectF()
    private val lines = FloatArray(ChipGauges.COLUMNS * 4)

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
            invalidate()
        }
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
                val target = 1 - readyBuffer
                val chips = runCatching { src.getGauges(buffers[target], waveWindowMs) }.getOrDefault(0)
                readyBuffer = target
                readyChips = chips
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
        drawGauges(canvas, height - textHeight)
        var y = height - textHeight + padding() / 2 - textPaint.ascent()
        for (line in lines) {
            canvas.drawText(line, padding(), y, textPaint)
            y += lineHeight
        }
    }

    // Chips are placed side by side as in oscilloscope above: 3 gauges columns per chip
    private fun drawGauges(canvas: Canvas, areaHeight: Float) {
        val chips = readyChips
        if (chips == 0 || areaHeight < density * 40) {
            return
        }
        val groupW = width.toFloat() / chips
        val cellW = groupW / 3
        val cellH = areaHeight / 4
        for (chip in 0 until chips) {
            val gauges = ChipGauges(buffers[readyBuffer], chip * ChipGauges.SIZE)
            val last = gauges.state
            val left = groupW * chip
            val chipPrefix = if (chips > 1) "S${chip + 1}" else ""
            for (voice in 0 until 3) {
                val top = cellH * voice
                drawGauge(
                    canvas,
                    gauges,
                    ChipGauges.wave(voice),
                    left,
                    top,
                    cellW,
                    cellH,
                    "${chipPrefix}V${voice + 1} ${waveTitle(last, voice)}"
                )
                drawGauge(
                    canvas,
                    gauges,
                    ChipGauges.envelope(voice),
                    left + cellW,
                    top,
                    cellW,
                    cellH,
                    "Env ${last.envelopeRegs(voice)}"
                )
                drawGauge(
                    canvas,
                    gauges,
                    ChipGauges.frequency(voice),
                    left + cellW * 2,
                    top,
                    cellW,
                    cellH,
                    "Freq ${hex4(last.frequency(voice))}"
                )
            }
            val top = cellH * 3
            drawGauge(canvas, gauges, ChipGauges.VOLUME, left, top, cellW, cellH, "Vol ${last.volume}")
            drawGauge(canvas, gauges, ChipGauges.RESONANCE, left + cellW, top, cellW, cellH, "Res ${last.resonance}")
            drawGauge(canvas, gauges, ChipGauges.CUTOFF, left + cellW * 2, top, cellW, cellH, filterTitle(last))
            if (chip != 0) {
                canvas.drawLine(left, 0f, left, areaHeight, separatorPaint)
            }
        }
    }

    // Each column is vertical line between min and max values, as in JSIDPlay2
    private fun drawGauge(
        canvas: Canvas,
        gauges: ChipGauges,
        gauge: Int,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        title: String
    ) {
        val pad = density
        rect.set(left + pad, top + pad, left + w - pad, top + h - pad)
        canvas.drawRect(rect, framePaint)
        // title is drawn over the plot to give more space to traces
        val plotTop = rect.top + pad * 2
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
        // narrow cells of multi-SID tunes require smaller titles
        val available = rect.width() - pad * 6
        val titleWidth = titlePaint.measureText(title)
        val baseSize = sp(TITLE_SIZE_SP)
        titlePaint.textSize = if (titleWidth > available) maxOf(baseSize * available / titleWidth, sp(MIN_TITLE_SIZE_SP)) else baseSize
        canvas.drawText(title, rect.left + pad * 3, rect.top + pad - titlePaint.ascent(), titlePaint)
        titlePaint.textSize = baseSize
    }

    private fun padding() = density * 4

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        private const val TRACE_WIDTH = 1f
        private const val TITLE_SIZE_SP = 13f
        private const val MIN_TITLE_SIZE_SP = 9f
        private const val TEXT_SIZE_SP = 12f
        const val DEFAULT_WAVE_WINDOW_MS = 10

        private fun hex4(value: Int) = String.format(java.util.Locale.US, "%04X", value)
        private const val MAX_CHIPS = 3
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
