/**
 * @file
 * @brief Oscilloscope cells geometry and static background shared by rendering backends
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.zxtune.playback.VoicesLayout

/**
 * Grid of cells: column per chip, row per voice. Columns are shared with dashboard, see [ChipColumns]
 */
internal class ScopeGrid private constructor(
    val columns: ChipColumns,
    val rows: Int,
    private val cellColumns: IntArray,
    private val cellRows: IntArray,
    val labels: List<String>,
) {
    val channels
        get() = cellColumns.size

    fun row(chan: Int) = cellRows[chan]

    // relative (0..1) horizontal placement of channel's cell
    fun left(chan: Int) = columns.left(cellColumns[chan])
    fun width(chan: Int) = columns.width(cellColumns[chan])

    /**
     * Two points per pixel column of the narrowest cell: samples range of each column is displayed by its minimum and
     * maximum (see [app.zxtune.playback.Visualizer.getScope]), so nothing is lost by decimation. Short windows have
     * less samples than points, then all of them are displayed.
     */
    fun points(viewWidth: Int) = (viewWidth * columns.narrowest * 2).toInt().coerceIn(MIN_POINTS, MAX_POINTS)

    /**
     * Height of labels band in pixels, see [labelSize]
     */
    fun labelBand(viewHeight: Int, textSize: Float) = if (labels.isEmpty()) {
        0f
    } else {
        labelSize(viewHeight.toFloat() / rows, textSize) * LABEL_BAND_TO_TEXT
    }

    /**
     * Trace areas in pixels (y is from top), 4 values per channel: left, width, vertical center, amplitude
     */
    fun traces(viewWidth: Int, viewHeight: Int, textSize: Float, target: FloatArray) {
        val band = labelBand(viewHeight, textSize)
        val cellH = viewHeight.toFloat() / rows
        for (chan in 0 until channels) {
            val cellW = viewWidth * width(chan)
            val top = cellH * row(chan) + band
            val traceH = cellH - band
            target[chan * 4] = viewWidth * left(chan) + cellW * CELL_MARGIN
            target[chan * 4 + 1] = cellW * (1 - 2 * CELL_MARGIN)
            target[chan * 4 + 2] = top + traceH / 2
            target[chan * 4 + 3] = traceH * 0.5f * AMPLIFICATION
        }
    }

    /**
     * Static part of picture (center lines, titles bands borders and titles) as white with alpha.
     * Drawn once per layout or size change, so per frame only traces are drawn over it.
     */
    fun drawBackground(bitmap: Bitmap, textSize: Float) {
        val width = bitmap.width
        val height = bitmap.height
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.SRC)
        val cellH = height.toFloat() / rows
        val band = labelBand(height, textSize)
        val linePaint = Paint().apply {
            color = Color.argb(GRID_ALPHA, 255, 255, 255)
            strokeWidth = 1f
        }
        for (chan in 0 until channels) {
            val left = width * left(chan)
            val right = left + width * width(chan)
            val top = cellH * row(chan)
            val centerY = (top + band + top + cellH) / 2
            canvas.drawLine(left, centerY, right, centerY, linePaint)
            if (band != 0f) {
                canvas.drawLine(left, top + band, right, top + band, linePaint)
            }
        }
        val size = labelSize(cellH, textSize)
        if (labels.isEmpty() || size <= 0f) {
            return
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(LABEL_ALPHA, 255, 255, 255)
            this.textSize = size
        }
        val padding = size * (LABEL_BAND_TO_TEXT - 1)
        for (chan in 0 until channels) {
            val x = width * left(chan) + padding
            // centered in the band
            val y = cellH * row(chan) + padding / 2 - textPaint.ascent()
            canvas.drawText(labels[chan], x, y, textPaint)
        }
    }

    companion object {
        const val MAX_POINTS = 2048
        const val MIN_POINTS = 128

        // labels are never drawn over traces, so they are shrunk for short cells to keep traces high enough
        private const val MAX_BAND_PART = 0.4f
        private const val LABEL_BAND_TO_TEXT = 1.4f
        private const val MIN_LABEL_SCALE = 0.6f
        private const val AMPLIFICATION = 0.95f
        private const val CELL_MARGIN = 0.01f
        private const val LABEL_ALPHA = 200

        // 0.15 of white
        private const val GRID_ALPHA = 38

        /**
         * @return text size of labels in cell of specified height, 0 if there's no space for labels
         */
        fun labelSize(cellHeight: Float, textSize: Float): Float {
            val size = minOf(textSize, cellHeight * MAX_BAND_PART / LABEL_BAND_TO_TEXT)
            return if (size >= textSize * MIN_LABEL_SCALE) size else 0f
        }

        fun of(layout: VoicesLayout, channels: Int): ScopeGrid {
            val columns = ChipColumns(layout.groups)
            if (layout.groups.isEmpty()) {
                return ScopeGrid(columns, channels, IntArray(channels), IntArray(channels) { it }, emptyList())
            }
            val cellColumns = ArrayList<Int>()
            val rows = ArrayList<Int>()
            val labels = ArrayList<String>()
            layout.groups.forEachIndexed { group, desc ->
                desc.voices.forEachIndexed { idx, voice ->
                    cellColumns.add(columns.column(group, idx))
                    rows.add(columns.row(group, idx))
                    labels.add("${desc.name} - $voice")
                }
            }
            val count = minOf(channels, cellColumns.size)
            return ScopeGrid(
                columns,
                columns.maxRows,
                cellColumns.take(count).toIntArray(),
                rows.take(count).toIntArray(),
                labels.take(count)
            )
        }
    }
}
