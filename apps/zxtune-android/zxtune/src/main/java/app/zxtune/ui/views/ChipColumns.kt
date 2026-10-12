/**
 * @file
 * @brief Horizontal placement of chips shared by oscilloscope and dashboard
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import app.zxtune.playback.VoicesLayout

/**
 * Chips (voices groups) are placed side by side, long chips are split to several columns of at most [MAX_ROWS]
 * voices. Both oscilloscope and dashboard use it, so voices of the same chip are aligned vertically.
 */
internal class ChipColumns(groups: List<VoicesLayout.Group>) {
    private val firstColumn = IntArray(groups.size)
    private val groupRows = IntArray(groups.size)

    /** Relative (0..1) left edges of columns, and the right edge of the last one */
    private val edges: FloatArray

    val columns: Int
    val maxRows: Int

    init {
        var cols = 0
        var rows = 1
        val weights = ArrayList<Int>()
        groups.forEachIndexed { idx, group ->
            val voices = group.voices.size
            // split long chips (e.g. OPL3 with 23 voices) to equal columns
            val groupCols = maxOf((voices + MAX_ROWS - 1) / MAX_ROWS, 1)
            firstColumn[idx] = cols
            groupRows[idx] = maxOf((voices + groupCols - 1) / groupCols, 1)
            repeat(groupCols) { weights.add(weightOf(group)) }
            cols += groupCols
            rows = maxOf(rows, groupRows[idx])
        }
        columns = maxOf(cols, 1)
        maxRows = rows
        val total = weights.sum().coerceAtLeast(1).toFloat()
        edges = FloatArray(columns + 1)
        var sum = 0
        weights.forEachIndexed { col, weight ->
            edges[col] = sum / total
            sum += weight
        }
        edges[columns] = 1f
    }

    fun rows(group: Int) = groupRows[group]
    fun column(group: Int, voice: Int) = firstColumn[group] + voice / groupRows[group]
    fun row(group: Int, voice: Int) = voice % groupRows[group]

    fun left(column: Int) = edges[column]
    fun right(column: Int) = edges[column + 1]
    fun width(column: Int) = edges[column + 1] - edges[column]

    val narrowest: Float
        get() = (0 until columns).minOf { width(it) }

    companion object {
        const val MAX_ROWS = 8

        private val SEGA_FM = setOf("YM2612", "YM3438")

        /*
         * Mega Drive's FM chip has much more state to show (operators schemas) than its PSG, so it gets 2/3 of width.
         * Other combinations (e.g. OPL with rhythm, YM2203 FM and SSG parts) keep equal columns.
         */
        private fun weightOf(group: VoicesLayout.Group) = if (group.chipName in SEGA_FM) 2 else 1

        // multiple instances of the same chip are suffixed by '/N'
        private val VoicesLayout.Group.chipName
            get() = name.substringBefore('/')
    }
}
