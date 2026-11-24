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

    // SID master volume samples lanes (one per chip) are placed under voices of their chip, see native SID player
    private val samplesGroup = groups.indexOfLast { it.name == SAMPLES_GROUP }
        .takeIf { it > 0 && groups[it].voices.size == it }
        ?: -1

    // position of each voice among displayed ones of its group, -1 if hidden
    private val positions = Array(groups.size) { idx ->
        val group = groups[idx]
        val hide = !showOplRhythm && group.chipName in OPL_RHYTHM_CHIPS
        var pos = 0
        IntArray(group.voices.size) { voice -> if (hide && group.voices[voice] in RHYTHM_VOICES) -1 else pos++ }
    }

    init {
        var cols = 0
        var rows = 1
        val weights = ArrayList<Int>()
        groups.forEachIndexed { idx, group ->
            if (idx == samplesGroup) {
                // one more row of each chip column
                for (chip in 0 until idx) {
                    rows = maxOf(rows, groupRows[chip] + 1)
                }
                groupRows[idx] = 1
                return@forEachIndexed
            }
            val voices = positions[idx].count { it >= 0 }
            val melodicOnly = voices != group.voices.size
            // split long chips (e.g. OPL3 with 23 voices) to equal columns; 9 melodic AdLib voices are in 3x3
            val groupCols = if (melodicOnly) SQUARE_SIDE else maxOf((voices + MAX_ROWS - 1) / MAX_ROWS, 1)
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

    fun rows(group: Int) = if (group == samplesGroup) maxRows else groupRows[group]

    /** Voice is not displayed (e.g. AdLib rhythm instruments, unused by most of tunes), see [showOplRhythm] */
    fun isHidden(group: Int, voice: Int) = positions[group].getOrElse(voice) { 0 } < 0

    fun column(group: Int, voice: Int) = if (group == samplesGroup) {
        firstColumn[voice]
    } else {
        firstColumn[group] + position(group, voice) / groupRows[group]
    }

    fun row(group: Int, voice: Int) = if (group == samplesGroup) groupRows[voice] else position(group, voice) % groupRows[group]

    private fun position(group: Int, voice: Int) = maxOf(positions[group].getOrElse(voice) { voice }, 0)

    fun left(column: Int) = edges[column]
    fun right(column: Int) = edges[column + 1]
    fun width(column: Int) = edges[column + 1] - edges[column]

    val narrowest: Float
        get() = (0 until columns).minOf { width(it) }

    companion object {
        const val MAX_ROWS = 8
        const val SAMPLES_GROUP = FilterLayout.SAMPLES_GROUP

        /** Rhythm mode instruments lanes of OPL/OPL2 are shown, user setting (applied to the next layout) */
        @Volatile
        var showOplRhythm = false

        private const val SQUARE_SIDE = 3
        private val OPL_RHYTHM_CHIPS = setOf("YM3526", "YM3812", "Y8950")

        // see libvgm DeviceChannelNames of OPL
        private val RHYTHM_VOICES = setOf("Bass Drum", "Snare Drum", "Tom Tom", "Cymbal", "Hi-Hat")

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
