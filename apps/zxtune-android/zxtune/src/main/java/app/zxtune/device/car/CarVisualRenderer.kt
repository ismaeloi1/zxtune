package app.zxtune.device.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.GaugesData
import app.zxtune.playback.ScopeLayout
import app.zxtune.playback.Visualizer
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoicesLayout
import app.zxtune.ui.views.ChipWidgets
import kotlin.math.abs

/**
 * Snapshot of visualizer data for a single rendered frame
 */
class CarVisualFrame {
    val samples = ShortArray(MAX_CHANNELS * POINTS)
    var points = POINTS
    var channels = 0
    var layout: VoicesLayout = VoicesLayout.MASTER
    var layoutId = -1
    var gauges = ByteArray(0)
    var chips = 0
    var voiceGauges = ByteArray(0)
    var voices = 0

    /**
     * Fills frame from the same source as phone oscilloscope and dashboard views use
     */
    fun capture(src: Visualizer, windowMs: Int, gaugeWindowMs: Int, withGauges: Boolean) {
        val scope = ScopeLayout(src.getScope(samples, points, windowMs))
        channels = scope.channels.coerceAtMost(MAX_CHANNELS)
        // layout description is requested only on change
        if (scope.id != layoutId) {
            layout = runCatching { VoicesLayout.parse(src.getLayout()) }.getOrDefault(VoicesLayout.MASTER)
            layoutId = scope.id
        }
        chips = 0
        voices = 0
        if (!withGauges) {
            return
        }
        val current = layout
        if (current.hasRegisters) {
            if (gauges.size != MAX_CHIPS * ChipGauges.SIZE) {
                gauges = ByteArray(MAX_CHIPS * ChipGauges.SIZE)
            }
            chips = src.getGauges(gauges, gaugeWindowMs).coerceAtMost(MAX_CHIPS)
        } else if (current.voicesCount != 0) {
            // binder may transfer whole array, so keep it as small as possible
            val size = current.voicesCount * VoiceGauges.SIZE
            if (voiceGauges.size != size) {
                voiceGauges = ByteArray(size)
            }
            voices = src.getVoiceGauges(voiceGauges, gaugeWindowMs).coerceAtMost(current.voicesCount)
        }
    }

    companion object {
        // car screens show artwork downscaled, so less points than on phone are enough
        const val POINTS = 256
        const val MAX_CHANNELS = 32
        const val MAX_CHIPS = 3
    }
}

/**
 * Software (Canvas) renderer of oscilloscope and chips dashboard into small bitmaps suitable for
 * media session artwork. GLES views of the phone UI cannot be used: no window/surface in service and
 * artwork must be a plain bitmap anyway.
 */
class CarVisualRenderer(val size: Int = DEFAULT_SIZE) {

    private val unit = size / BASE_SIZE
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * unit
        strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = unit
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = LABEL_SIZE * unit
        typeface = Typeface.DEFAULT_BOLD
    }
    private val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = LABEL_SIZE * unit
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }
    private val columnPaint = Paint().apply {
        color = Color.argb(150, 255, 255, 255)
    }
    private val widgets = ChipWidgets(unit, LABEL_SIZE * unit)
    private val smooth = Array(MAX_VOICES) { FloatArray(ChipWidgets.SMOOTH_SIZE) }
    private val peaks = FloatArray(CarVisualFrame.MAX_CHANNELS)
    private val lines = FloatArray(CarVisualFrame.POINTS * 4)
    private val cell = RectF()
    private val body = RectF()

    fun createBitmap(): Bitmap = Bitmap.createBitmap(size, size, CONFIG)

    /**
     * @param target bitmap of [size] created by [createBitmap]
     */
    fun render(frame: CarVisualFrame, mode: CarVisualSettings.Mode, target: Bitmap): Bitmap {
        val canvas = Canvas(target)
        canvas.drawColor(Color.BLACK)
        val area = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val hasDashboard = frame.voices != 0 || frame.chips != 0
        when {
            mode == CarVisualSettings.Mode.OSCILLOSCOPE || !hasDashboard -> drawScope(canvas, area, frame)

            mode == CarVisualSettings.Mode.DASHBOARD -> drawDashboard(canvas, area, frame)

            else -> {
                val split = area.top + area.height() * SCOPE_SHARE
                drawScope(canvas, RectF(area.left, area.top, area.right, split), frame)
                canvas.drawLine(area.left, split, area.right, split, gridPaint)
                drawDashboard(canvas, RectF(area.left, split, area.right, area.bottom), frame)
            }
        }
        return target
    }

    // Column per chip (long chips are split), row per voice, as in phone oscilloscope
    private fun drawScope(canvas: Canvas, area: RectF, frame: CarVisualFrame) {
        val channels = frame.channels
        if (channels == 0) {
            return
        }
        val grid = Grid.of(frame.layout, channels)
        val cellW = area.width() / grid.cols
        val cellH = area.height() / grid.rows
        val withLabels = grid.labels.isNotEmpty() && cellH > labelPaint.fontSpacing * 3
        for (chan in 0 until channels) {
            val left = area.left + cellW * grid.columns[chan]
            val top = area.top + cellH * grid.row(chan)
            cell.set(left, top, left + cellW, top + cellH)
            if (withLabels) {
                canvas.drawText(grid.labels[chan], cell.left + unit * 3, cell.top - labelPaint.ascent(), labelPaint)
            }
            drawTrace(canvas, cell, frame, chan)
            if (grid.columns[chan] != 0) {
                canvas.drawLine(cell.left, cell.top, cell.left, cell.bottom, gridPaint)
            }
        }
    }

    private fun drawTrace(canvas: Canvas, rect: RectF, frame: CarVisualFrame, chan: Int) {
        val points = frame.points
        val offset = chan * points
        val gain = gainOf(frame, chan, offset, points)
        val centerY = rect.centerY()
        val ampl = rect.height() * 0.45f
        val margin = unit * 2
        val width = rect.width() - margin * 2
        canvas.drawLine(rect.left, centerY, rect.right, centerY, gridPaint)
        var count = 0
        var prevX = 0f
        var prevY = 0f
        for (idx in 0 until points) {
            val x = rect.left + margin + width * idx / (points - 1)
            val value = (frame.samples[offset + idx] * gain / 32768f).coerceIn(-1f, 1f)
            val y = centerY - ampl * value
            if (idx != 0) {
                lines[count++] = prevX
                lines[count++] = prevY
                lines[count++] = x
                lines[count++] = y
            }
            prevX = x
            prevY = y
        }
        canvas.drawLines(lines, 0, count, tracePaint)
    }

    // Automatic gain as on phone, but faster release because of low frame rate
    private fun gainOf(frame: CarVisualFrame, chan: Int, offset: Int, points: Int): Float {
        var peak = 0
        for (idx in 0 until points) {
            peak = maxOf(peak, abs(frame.samples[offset + idx].toInt()))
        }
        val level = peak / 32768f
        val prev = peaks[chan]
        val smoothed = if (level > prev) level else prev + (level - prev) * GAIN_RELEASE
        peaks[chan] = smoothed
        return (GAIN_TARGET / maxOf(smoothed, GAIN_TARGET / GAIN_MAX)).coerceAtLeast(1f)
    }

    private fun drawDashboard(canvas: Canvas, area: RectF, frame: CarVisualFrame) = if (frame.voices != 0) {
        drawVoices(canvas, area, frame)
    } else {
        drawChips(canvas, area, frame)
    }

    // Card per voice with title, summary and chip specific widget
    private fun drawVoices(canvas: Canvas, area: RectF, frame: CarVisualFrame) {
        val layout = frame.layout
        val totalCols = layout.groups.sumOf { columnsOf(it.voices.size) }.coerceAtLeast(1)
        val cellW = area.width() / totalCols
        var voice = 0
        var col = 0
        for (group in layout.groups) {
            val rows = rowsOf(group.voices.size)
            val cellH = area.height() / rows
            group.voices.forEachIndexed { idx, name ->
                if (voice >= frame.voices) {
                    return
                }
                val gauges = VoiceGauges(frame.voiceGauges, voice * VoiceGauges.SIZE)
                val left = area.left + cellW * (col + idx / rows)
                val top = area.top + cellH * (idx % rows)
                cell.set(left + unit, top + unit, left + cellW - unit, top + cellH - unit)
                canvas.drawRect(cell, gridPaint)
                val baseline = cell.top + unit * 2 - labelPaint.ascent()
                val key = if (gauges.isKeyOn) " ●" else ""
                canvas.drawText("$name$key", cell.left + unit * 3, baseline, labelPaint)
                canvas.drawText(widgets.summary(gauges), cell.right - unit * 3, baseline, summaryPaint)
                body.set(cell.left + unit * 3, baseline + labelPaint.descent() + unit * 2, cell.right - unit * 3, cell.bottom - unit * 2)
                if (body.height() > unit * 10 && !widgets.draw(canvas, body, gauges, smooth[voice % MAX_VOICES])) {
                    drawColumns(canvas, body, gauges, VoiceGauges.LEVEL, VoiceGauges.COLUMNS)
                }
                ++voice
            }
            col += columnsOf(group.voices.size)
        }
    }

    // SID-like chips: waveform and envelope history per voice, as JSIDPlay2 gauges
    private fun drawChips(canvas: Canvas, area: RectF, frame: CarVisualFrame) {
        val chips = frame.chips
        if (chips == 0) {
            return
        }
        val chipW = area.width() / chips
        val cellH = area.height() / 3
        for (chip in 0 until chips) {
            val gauges = ChipGauges(frame.gauges, chip * ChipGauges.SIZE)
            val state = gauges.state
            for (voice in 0 until 3) {
                val left = area.left + chipW * chip
                val top = area.top + cellH * voice
                cell.set(left + unit, top + unit, left + chipW - unit, top + cellH - unit)
                canvas.drawRect(cell, gridPaint)
                val baseline = cell.top + unit * 2 - labelPaint.ascent()
                val prefix = if (chips > 1) "S${chip + 1}" else ""
                canvas.drawText("${prefix}V${voice + 1}", cell.left + unit * 3, baseline, labelPaint)
                canvas.drawText(sidNote(state.frequency(voice)), cell.right - unit * 3, baseline, summaryPaint)
                val bodyTop = baseline + labelPaint.descent() + unit * 2
                val half = (cell.bottom - unit * 2 - bodyTop) / 2
                body.set(cell.left + unit * 3, bodyTop, cell.right - unit * 3, bodyTop + half - unit)
                drawColumns(canvas, body, gauges, ChipGauges.wave(voice), ChipGauges.COLUMNS)
                body.set(cell.left + unit * 3, bodyTop + half + unit, cell.right - unit * 3, cell.bottom - unit * 2)
                drawColumns(canvas, body, gauges, ChipGauges.envelope(voice), ChipGauges.COLUMNS)
            }
        }
    }

    // Each column is vertical line between min and max values
    private fun drawColumns(canvas: Canvas, rect: RectF, data: GaugesData, gauge: Int, columns: Int) {
        if (rect.height() <= 0 || rect.width() <= 0) {
            return
        }
        // less columns than source to keep lines visible on small bitmap
        val step = maxOf(1, (columns / rect.width()).toInt())
        val count = columns / step
        val colW = rect.width() / count
        var idx = 0
        for (col in 0 until count) {
            var lo = 255
            var hi = 0
            for (src in col * step until (col + 1) * step) {
                lo = minOf(lo, data.min(gauge, src))
                hi = maxOf(hi, data.max(gauge, src))
            }
            val x = rect.left + colW * (col + 0.5f)
            lines[idx++] = x
            lines[idx++] = rect.bottom - rect.height() * hi / 255f - unit / 2
            lines[idx++] = x
            lines[idx++] = rect.bottom - rect.height() * minOf(lo, hi) / 255f + unit / 2
        }
        columnPaint.strokeWidth = maxOf(colW, unit)
        canvas.drawLines(lines, 0, idx, columnPaint)
    }

    // Grid of cells: column per chip, row per voice. Same placement rules as in phone oscilloscope
    internal class Grid(
        val cols: Int,
        val rows: Int,
        val columns: IntArray,
        private val cellRows: IntArray,
        val labels: List<String>,
    ) {
        fun row(chan: Int) = cellRows[chan]

        companion object {
            fun of(layout: VoicesLayout, channels: Int): Grid {
                if (layout.groups.isEmpty()) {
                    return Grid(1, channels, IntArray(channels), IntArray(channels) { it }, emptyList())
                }
                val columns = ArrayList<Int>()
                val rows = ArrayList<Int>()
                val labels = ArrayList<String>()
                var cols = 0
                var maxRows = 1
                for (group in layout.groups) {
                    val groupCols = columnsOf(group.voices.size)
                    val groupRows = rowsOf(group.voices.size)
                    group.voices.forEachIndexed { idx, voice ->
                        columns.add(cols + idx / groupRows)
                        rows.add(idx % groupRows)
                        labels.add("${group.name} $voice")
                    }
                    cols += groupCols
                    maxRows = maxOf(maxRows, groupRows)
                }
                // source may provide less channels than described voices, e.g. master only
                if (channels > columns.size) {
                    return Grid(1, channels, IntArray(channels), IntArray(channels) { it }, emptyList())
                }
                return Grid(
                    cols.coerceAtLeast(1),
                    maxRows,
                    columns.take(channels).toIntArray(),
                    rows.take(channels).toIntArray(),
                    labels.take(channels)
                )
            }
        }
    }

    companion object {
        const val DEFAULT_SIZE = 400
        private const val BASE_SIZE = 400f

        // 2 bytes per pixel keeps metadata parcel well below binder transaction limit
        private val CONFIG = Bitmap.Config.RGB_565
        private const val LABEL_SIZE = 13f
        private const val SCOPE_SHARE = 0.5f
        private const val MAX_VOICES = 64
        private const val MAX_ROWS = 8
        private const val GAIN_TARGET = 0.9f
        private const val GAIN_MAX = 8f
        private const val GAIN_RELEASE = 0.3f

        private fun columnsOf(voices: Int) = maxOf((voices + MAX_ROWS - 1) / MAX_ROWS, 1)
        private fun rowsOf(voices: Int) = maxOf((voices + columnsOf(voices) - 1) / columnsOf(voices), 1)

        // SID frequency register is in 1/16777216 of PAL clock (985248Hz)
        private fun sidNote(reg: Int) = ChipWidgets.noteName(reg * 985248f / 16777216f)
    }
}
