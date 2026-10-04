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
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import app.zxtune.Logger
import app.zxtune.playback.ChipState
import app.zxtune.playback.Visualizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ln
import kotlin.math.log10

private val LOG = Logger("SidDashboard")

/**
 * Displays history of SID state for single chip:
 * - per voice: oscillator output, envelope (dB), frequency (log scale)
 * - global: master volume, resonance, filter cutoff
 * And multiline status text below. Tap switches chip for multi-SID tunes.
 */
class SidDashboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile
    private var source: Visualizer? = null
    private var statsProvider: (() -> String)? = null

    // Data is requested on background thread to keep UI responsive, binder calls may take time
    private val executor = Executors.newSingleThreadExecutor()
    private val requestPending = AtomicBoolean(false)
    private val buffers = Array(2) { ByteArray(MAX_CHIPS * RECORDS * ChipState.SIZE) }
    private var readyBuffer = 0

    @Volatile
    private var readyChips = 0

    @Volatile
    private var status = ""
    private var lastStatusTime = 0L
    private var selectedChip = 0

    private val density = resources.displayMetrics.density
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = sp(10f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        textSize = sp(10f)
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
        strokeWidth = 1.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    private val values = FloatArray(RECORDS)

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
        setOnClickListener {
            ++selectedChip
            invalidate()
        }
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
                val chips = runCatching { src.getChipStates(buffers[target], RECORDS) }.getOrDefault(0)
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

    private fun drawGauges(canvas: Canvas, areaHeight: Float) {
        val chips = readyChips
        if (chips == 0 || areaHeight < density * 40) {
            return
        }
        val chip = selectedChip % chips
        val data = buffers[readyBuffer]
        val base = chip * RECORDS
        val last = ChipState(data, (base + RECORDS - 1) * ChipState.SIZE)
        val cellW = width / 3f
        val cellH = areaHeight / 4
        for (voice in 0 until 3) {
            val top = cellH * voice
            val voicePrefix = if (chips > 1) "SID ${chip + 1} V${voice + 1}" else "V${voice + 1}"
            fillValues(data, base) { it.oscillator(voice) / 255f }
            drawGauge(canvas, 0f, top, cellW, cellH, "$voicePrefix ${waveTitle(last, voice)}")
            fillValues(data, base) { envelopeLevel(it.envelope(voice)) }
            drawGauge(canvas, cellW, top, cellW, cellH, "Envelope")
            fillValues(data, base) { frequencyLevel(it.frequency(voice)) }
            drawGauge(canvas, cellW * 2, top, cellW, cellH, "Frequency")
        }
        val top = cellH * 3
        fillValues(data, base) { it.volume / 15f }
        drawGauge(canvas, 0f, top, cellW, cellH, "Master volume")
        fillValues(data, base) { it.resonance / 15f }
        drawGauge(canvas, cellW, top, cellW, cellH, "Resonance")
        fillValues(data, base) { it.cutoff / 2047f }
        drawGauge(canvas, cellW * 2, top, cellW, cellH, filterTitle(last))
    }

    private inline fun fillValues(data: ByteArray, base: Int, get: (ChipState) -> Float) {
        for (idx in 0 until RECORDS) {
            values[idx] = get(ChipState(data, (base + idx) * ChipState.SIZE)).coerceIn(0f, 1f)
        }
    }

    private fun drawGauge(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, title: String) {
        val pad = density * 2
        rect.set(left + pad, top + pad, left + w - pad, top + h - pad)
        canvas.drawRect(rect, framePaint)
        canvas.drawText(title, rect.left + pad * 2, rect.top + pad - titlePaint.ascent(), titlePaint)
        val plotTop = rect.top + titlePaint.fontSpacing + pad
        val plotHeight = rect.bottom - pad - plotTop
        if (plotHeight <= 0) {
            return
        }
        path.rewind()
        val plotWidth = rect.width() - pad * 2
        for (idx in 0 until RECORDS) {
            val x = rect.left + pad + plotWidth * idx / (RECORDS - 1)
            val y = plotTop + plotHeight * (1 - values[idx])
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, tracePaint)
    }

    private fun padding() = density * 4

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    companion object {
        // ~1s of history
        const val RECORDS = 256
        private const val MAX_CHIPS = 3
        private const val STATUS_PERIOD_NS = 500_000_000L

        // dB scale of 8-bit envelope down to -48dB, as in JSIDPlay2
        internal fun envelopeLevel(env: Int) = if (env == 0) 0f else 1f + 20f * log10(env / 255f) / 48f

        // logarithmic scale of 7 octaves, as in JSIDPlay2
        internal fun frequencyLevel(freq: Int) = if (freq == 0) 0f else 1f + (ln(freq / 65535f) / ln(2f) * 12f) / (12 * 7)

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
        }
    }
}
