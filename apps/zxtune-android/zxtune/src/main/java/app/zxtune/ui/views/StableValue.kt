/**
 * @file
 * @brief Calm presentation of rapidly changing values for live text readouts
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Displayed integer value (register, quantized level, note index, enum ordinal) that follows input slowly enough
 * to be read. Text is redrawn at display refresh rate (60..120fps) while the eye needs ~250ms to read a number,
 * so (as in digital meters) the value:
 * - is switched only after the new input is confirmed (stays the same for [confirmMs] for discrete values, or
 *   stays out of [deadband] for continuous ones), so short glitches (vibrato, arpeggio steps, hard restart) are not
 *   displayed at all;
 * - is held at least [holdMs] after the change (limits update rate per field);
 * - takes the input which stopped changing for [settleMs] even inside dead band, so final values are not lost;
 * - takes any input that differs from the displayed one for [staleMs], so the value is never too outdated.
 * Time is passed explicitly to keep logic deterministic.
 */
internal class StableValue(
    private val confirmMs: Long = CONFIRM_MS,
    private val holdMs: Long = HOLD_MS,
    private val deadband: Int = 0,
    private val settleMs: Long = SETTLE_MS,
    private val staleMs: Long = STALE_MS,
) {
    var value = 0
        private set
    var isSet = false
        private set

    /** Time of the last displayed value change, see [fade] */
    var changedAt = 0L
        private set

    private var lastInput = 0
    private var lastInputSince = 0L
    private var outsideSince = NONE
    private var differentSince = NONE

    /**
     * @return true if displayed value is changed
     */
    fun update(input: Int, nowMs: Long): Boolean {
        if (!isSet) {
            isSet = true
            // the first value is not a change for the viewer, so it is neither faded nor held
            accept(input, nowMs - maxOf(holdMs, FADE_MS))
            return true
        }
        if (input != lastInput) {
            lastInput = input
            lastInputSince = nowMs
        }
        if (input == value) {
            outsideSince = NONE
            differentSince = NONE
            return false
        }
        if (differentSince == NONE) {
            differentSince = nowMs
        }
        if (deadband == 0) {
            // discrete values have no order, so only exactly the same candidate confirms itself
            outsideSince = lastInputSince
        } else if (abs(input - value) > deadband) {
            if (outsideSince == NONE) {
                outsideSince = nowMs
            }
        } else {
            outsideSince = NONE
        }
        if (nowMs - changedAt < holdMs) {
            return false
        }
        val confirmed = outsideSince != NONE && nowMs - outsideSince >= confirmMs
        val settled = nowMs - lastInputSince >= settleMs
        val stale = nowMs - differentSince >= staleMs
        if (confirmed || settled || stale) {
            accept(input, nowMs)
            return true
        }
        return false
    }

    /**
     * Quantizes continuous input (e.g. semitones) to integer steps with hysteresis: the displayed step is kept
     * while input is within [margin] beyond its half-step bounds
     */
    fun update(input: Float, margin: Float, nowMs: Long): Boolean {
        val step = if (isSet && abs(input - value) <= 0.5f + margin) value else input.roundToInt()
        return update(step, nowMs)
    }

    /**
     * @return 0..1 progress of appearance since the last change, to fade in changed field only
     */
    fun fade(nowMs: Long) = fadeProgress(changedAt, nowMs)

    fun reset() {
        isSet = false
        outsideSince = NONE
        differentSince = NONE
    }

    private fun accept(input: Int, at: Long) {
        value = input
        changedAt = at
        lastInput = input
        lastInputSince = at
        outsideSince = NONE
        differentSince = NONE
    }

    companion object {
        // Readability limits: ~4 changes per second is the commonly cited maximum for numeric readouts,
        // longer confirmation would make text lag behind the music noticeably
        const val CONFIRM_MS = 80L
        const val HOLD_MS = 250L
        const val SETTLE_MS = 400L
        const val STALE_MS = 1000L

        // short enough to not blur the next change
        const val FADE_MS = 150L
        private const val NONE = Long.MIN_VALUE

        fun fadeProgress(changedAt: Long, nowMs: Long) = ((nowMs - changedAt).toFloat() / FADE_MS).coerceIn(0f, 1f)
    }
}

/**
 * Fixed set of texts produced from stabilized values with time of the last change of each one.
 * Texts are rebuilt only on values change to avoid allocations per frame.
 */
internal class StableTexts(size: Int) {
    private val texts = Array(size) { "" }
    private val changed = LongArray(size) { Long.MIN_VALUE / 2 }
    private val initialized = BooleanArray(size)

    operator fun get(idx: Int) = texts[idx]

    fun set(idx: Int, text: String, nowMs: Long) {
        if (!initialized[idx]) {
            // the first text is not a change for the viewer
            initialized[idx] = true
            texts[idx] = text
        } else if (texts[idx] != text) {
            changed[idx] = nowMs
            texts[idx] = text
        }
    }

    fun fade(idx: Int, nowMs: Long) = StableValue.fadeProgress(changed[idx], nowMs)
}
