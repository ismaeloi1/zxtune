/**
 * @file
 * @brief Readable presentation of values changing at each frame (e.g. registers written by players every tick)
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.roundToInt

/*
 * Players write chip registers at each frame (50-60Hz), often with sweeps, vibrato and per-note jumps, while display
 * shows 60-144 frames per second. Drawn directly, graphics jitter and texts become an unreadable scribble. Established
 * practice of measuring instruments and audio meters is applied, with separate paths for graphics and texts:
 * - graphics show the exact state of the heard moment (smoothing would show values that never existed and lag behind
 *   the sound), readability comes from persistence: recent states are drawn fading as phosphor of oscilloscope;
 * - numbers are refreshed a few times per second as by digital instruments (2-5Hz), show robust statistic of recent
 *   window (median) with hysteresis, and a range instead of flickering number while value is modulated, as
 *   modulation displays of synthesizers (Vital, Serum) show the swept zone ([RealtimeReadout]);
 * - musical notes change only when pitch is clearly and persistently away from the shown note ([NoteReadout]);
 * - recent extent of swept graphics is drawn as decaying envelope, like peak hold of PPM meters
 *   (IEC 60268-10) ([ModulationRange]).
 * All of them are updated by frame time and don't allocate, so they're used at display rate on rendering threads.
 */

/**
 * Value for text: refreshed at most once per [periodSeconds] (at the same moments for all readouts), median of the last [windowSeconds] with hysteresis
 * (changes less than [deadband] are not shown), or range (10th..90th percentiles) while value is modulated, i.e.
 * spread of window is larger than [modulationSpread].
 * @param isLogarithmic deadband and spread are relative (ratio as log2, e.g. 1/12 is semitone), else absolute
 */
class RealtimeReadout(
    private val windowSeconds: Double = 0.5,
    private val periodSeconds: Double = 0.25,
    private val deadband: Double,
    private val modulationSpread: Double,
    private val isLogarithmic: Boolean = false,
) {
    private val times = LongArray(CAPACITY)
    private val values = DoubleArray(CAPACITY)
    private val sorted = DoubleArray(CAPACITY)
    private var head = 0
    private var size = 0
    private var lastRefresh = 0L

    /** Displayed center value, NaN if nothing pushed */
    var value = Double.NaN
        private set

    /** Displayed range, equal to [value] if not modulated */
    var low = Double.NaN
        private set
    var high = Double.NaN
        private set

    val isModulated
        get() = low != high

    /** Incremented by each change of displayed values, so texts are rebuilt only then */
    var version = 0
        private set

    /**
     * @param nanos monotonic time of frame
     * @return true if displayed values are changed
     */
    fun push(sample: Double, nanos: Long): Boolean {
        if (sample.isNaN()) {
            return false
        }
        times[head] = nanos
        values[head] = sample
        head = (head + 1) % CAPACITY
        size = minOf(size + 1, CAPACITY)
        if (value.isNaN()) {
            // the first value is shown immediately
            lastRefresh = nanos
            return show(sample, sample, sample)
        }
        // refreshes are aligned to absolute time, so all the texts of display change at once, as of instruments
        val period = (periodSeconds * 1e9).toLong()
        if (nanos / period == lastRefresh / period) {
            return false
        }
        lastRefresh = nanos
        return refresh(nanos)
    }

    /**
     * Show value immediately without history, e.g. single picture of paused playback
     */
    fun snap(sample: Double) {
        size = 0
        value = Double.NaN
        push(sample, lastRefresh)
    }

    fun reset() {
        size = 0
        value = Double.NaN
        low = Double.NaN
        high = Double.NaN
        ++version
    }

    private fun refresh(nanos: Long): Boolean {
        val oldest = nanos - (windowSeconds * 1e9).toLong()
        var count = 0
        for (idx in 0 until size) {
            val pos = (head - 1 - idx + CAPACITY) % CAPACITY
            if (times[pos] < oldest && count != 0) {
                break
            }
            sorted[count++] = values[pos]
        }
        java.util.Arrays.sort(sorted, 0, count)
        val median = percentile(count, 0.5)
        val lo = percentile(count, 0.1)
        val hi = percentile(count, 0.9)
        return if (distance(lo, hi) > modulationSpread) {
            // range is shown as is, its changes are visible movement of modulation
            show(median, lo, hi)
        } else if (isModulated || distance(value, median) > deadband) {
            show(median, median, median)
        } else {
            false
        }
    }

    private fun percentile(count: Int, part: Double): Double {
        val pos = part * (count - 1)
        val idx = pos.toInt()
        val next = minOf(idx + 1, count - 1)
        return sorted[idx] + (sorted[next] - sorted[idx]) * (pos - idx)
    }

    private fun distance(a: Double, b: Double) = if (isLogarithmic) {
        if (a > 0 && b > 0) abs(log2(b / a)) else Double.MAX_VALUE
    } else {
        abs(b - a)
    }

    private fun show(center: Double, lo: Double, hi: Double): Boolean {
        if (center == value && lo == low && hi == high) {
            return false
        }
        value = center
        low = lo
        high = hi
        ++version
        return true
    }

    private companion object {
        // half second at 144Hz with some margin
        const val CAPACITY = 128
    }
}

/**
 * Note of pitch for text: changed only if pitch is beyond the half semitone boundary of shown note by more than
 * [marginCents] (i.e. 70 cents away by default) for at least [holdSeconds], so vibrato and slides around note boundary
 * don't flicker, while real note changes (100 cents and more) are shown after hold time
 */
class NoteReadout(private val marginCents: Double = 20.0, private val holdSeconds: Double = 0.15) {
    private var candidateSince = 0L
    private var candidate = NONE

    /** Semitones from C0, [NONE] if nothing pushed or pitch is out of range */
    var note = NONE
        private set

    var version = 0
        private set

    /**
     * @param hz pitch, 0 or negative for no pitch
     * @return true if [note] is changed
     */
    fun push(hz: Double, nanos: Long): Boolean {
        if (hz <= 0 || hz.isNaN()) {
            return set(NONE)
        }
        val semitones = semitonesOf(hz)
        val nearest = semitones.roundToInt()
        if (note == NONE) {
            candidate = NONE
            return set(nearest)
        }
        if (abs(semitones - note) * 100 <= 50 + marginCents) {
            // within hysteresis band around shown note
            candidate = NONE
            return false
        }
        if (nearest != candidate) {
            candidate = nearest
            candidateSince = nanos
        }
        return if (nanos - candidateSince >= (holdSeconds * 1e9).toLong()) {
            candidate = NONE
            set(nearest)
        } else {
            false
        }
    }

    /** Show note immediately, e.g. single picture of paused playback */
    fun snap(hz: Double) {
        candidate = NONE
        set(if (hz > 0) semitonesOf(hz).roundToInt() else NONE)
    }

    private fun set(value: Int): Boolean {
        if (value == note) {
            return false
        }
        note = value
        ++version
        return true
    }

    companion object {
        const val NONE = Int.MIN_VALUE

        /** A4 is 440Hz, C0 is 0 */
        fun semitonesOf(hz: Double) = 12 * log2(hz / 440.0) + 57
    }
}

/**
 * Recent extent of value: follows it immediately outwards (as peak meter's attack), then returns to it with
 * [decaySeconds] time constant after [holdSeconds] (as peak hold), so swept value is seen as a calm zone.
 * Values are kept in arrays of [size] (e.g. points of curve), so all of them are updated by single call.
 */
class ModulationRange(size: Int, private val holdSeconds: Double = 0.3, private val decaySeconds: Double = 0.35) {
    val low = DoubleArray(size)
    val high = DoubleArray(size)
    private val lowSince = LongArray(size)
    private val highSince = LongArray(size)
    private var lastNanos = 0L
    private var count = 0

    /**
     * @param current values of this frame, the first [size] of them are used
     */
    fun update(current: DoubleArray, size: Int, nanos: Long) {
        if (size != count || lastNanos == 0L) {
            count = size
            reset(current, nanos)
            return
        }
        val dt = (nanos - lastNanos) / 1e9
        val k = if (dt <= 0) 0.0 else 1 - exp(-dt / decaySeconds)
        val hold = (holdSeconds * 1e9).toLong()
        lastNanos = nanos
        for (idx in 0 until size) {
            val value = current[idx]
            if (value >= high[idx]) {
                high[idx] = value
                highSince[idx] = nanos
            } else if (nanos - highSince[idx] >= hold) {
                high[idx] += (value - high[idx]) * k
            }
            if (value <= low[idx]) {
                low[idx] = value
                lowSince[idx] = nanos
            } else if (nanos - lowSince[idx] >= hold) {
                low[idx] += (value - low[idx]) * k
            }
        }
    }

    /** History is dropped by the next [update] */
    fun clear() {
        lastNanos = 0L
    }

    /** No history, e.g. after layout change or paused playback */
    fun reset(current: DoubleArray, nanos: Long) {
        for (idx in 0 until minOf(count, low.size)) {
            low[idx] = current[idx]
            high[idx] = current[idx]
            lowSince[idx] = nanos
            highSince[idx] = nanos
        }
        lastNanos = nanos
    }

    /** Range of point is wider than [threshold] */
    fun isVisible(idx: Int, threshold: Double) = high[idx] - low[idx] > threshold
}

/**
 * Placement of value tags attached to moving graphics (e.g. label by marker of voice on filter curve), as cursor tags
 * of oscilloscopes or value tags of equalizer bands: the position itself is the readout, so tag follows its anchor at
 * each frame, while side of anchor (above/below, right/left) is kept from previous frames while it fits, so tags don't
 * jump around. Tags don't overlap each other and reserved areas, and stay inside of bounds.
 * @param capacity maximal tags and reserved areas per frame
 * @param keys count of distinct tags (e.g. voices), their sides are remembered
 */
class TagLayout(capacity: Int, keys: Int) {
    private val rects = FloatArray(capacity * 4)
    private val sides = IntArray(keys) { -1 }
    private val forms = IntArray(keys)
    private val failingSince = LongArray(keys)
    private val betterSince = LongArray(keys)
    private var count = 0
    private var boundsLeft = 0f
    private var boundsTop = 0f
    private var boundsRight = 0f
    private var boundsBottom = 0f

    fun begin(left: Float, top: Float, right: Float, bottom: Float) {
        count = 0
        boundsLeft = left
        boundsTop = top
        boundsRight = right
        boundsBottom = bottom
    }

    /** Area not covered by tags placed after it */
    fun reserve(left: Float, top: Float, right: Float, bottom: Float): Int = add(left, top, right, bottom)

    /**
     * @param x,y anchor
     * @param gap distance from anchor to tag's edge
     * @param initialSide side of the first placement (see [ABOVE_RIGHT] etc), then the previous one is preferred
     * @return index of placed tag
     */
    fun place(key: Int, x: Float, y: Float, width: Float, height: Float, gap: Float, initialSide: Int = ABOVE_RIGHT) = place(key, x, y, width, height, gap, initialSide, false)

    /**
     * The same as [place], but tag is not placed if there's no free space for it
     * @return index of placed tag or -1
     */
    fun tryPlace(key: Int, x: Float, y: Float, width: Float, height: Float, gap: Float, initialSide: Int = ABOVE_RIGHT) = place(key, x, y, width, height, gap, initialSide, true)

    private fun place(
        key: Int,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        gap: Float,
        initialSide: Int,
        onlyFree: Boolean,
    ): Int {
        val preferred = sides[key].takeIf { it >= 0 } ?: initialSide
        var chosen = -1
        var fallback = -1
        for (attempt in 0 until SIDES) {
            val side = (preferred + attempt) % SIDES
            val l = sideLeft(side, x, width, gap)
            val t = sideTop(side, y, height, gap)
            if (l < boundsLeft || l + width > boundsRight || t < boundsTop || t + height > boundsBottom) {
                continue
            }
            if (!overlaps(l, t, l + width, t + height)) {
                chosen = side
                break
            }
            if (fallback < 0) {
                fallback = side
            }
        }
        if (chosen < 0 && onlyFree) {
            return -1
        }
        val side = if (chosen >= 0) {
            chosen
        } else if (fallback >= 0) {
            fallback
        } else {
            preferred
        }
        sides[key] = side
        val l = sideLeft(side, x, width, gap).coerceIn(boundsLeft, maxOf(boundsLeft, boundsRight - width))
        val t = sideTop(side, y, height, gap).coerceIn(boundsTop, maxOf(boundsTop, boundsBottom - height))
        return add(l, t, l + width, t + height)
    }

    /**
     * Tag with several forms from the most detailed to the shortest (e.g. "V1 E2 −12 dB", "V1"): the shown form is
     * changed only if it doesn't fit for [STABLE_SECONDS] (then it's drawn overlapping meanwhile) or a more detailed one
     * fits for that time, so tags neither blink nor change their texts as anchors move around
     * @param widths widths of forms, [count] of them
     * @param nanos monotonic time of frame, 0 for single picture (the most detailed fitting form is chosen at once)
     * @return index of placed tag or -1 if hidden, see [form]
     */
    fun placeStable(
        key: Int,
        x: Float,
        y: Float,
        widths: FloatArray,
        count: Int,
        height: Float,
        gap: Float,
        nanos: Long,
        initialSide: Int = ABOVE_RIGHT,
    ): Int {
        val stable = if (nanos == 0L) 0L else (STABLE_SECONDS * 1e9).toLong()
        var current = if (nanos == 0L) 0 else forms[key].coerceAtMost(count)
        // more detailed form fitting for a while
        if (current > 0 && fits(key, x, y, widths[current - 1], height, gap, initialSide)) {
            if (betterSince[key] == 0L) {
                betterSince[key] = nanos
            } else if (nanos - betterSince[key] >= stable) {
                --current
                betterSince[key] = 0L
            }
        } else {
            betterSince[key] = 0L
        }
        while (current < count) {
            val free = tryPlace(key, x, y, widths[current], height, gap, initialSide)
            if (free >= 0) {
                failingSince[key] = 0L
                forms[key] = current
                return free
            }
            if (failingSince[key] == 0L) {
                failingSince[key] = nanos
            }
            if (stable != 0L && nanos - failingSince[key] < stable) {
                forms[key] = current
                return place(key, x, y, widths[current], height, gap, initialSide)
            }
            // shorter form is tried at once
            ++current
            failingSince[key] = nanos
        }
        forms[key] = count
        return -1
    }

    /** Tag is not displayed anymore, so its side and form are not kept */
    fun forget(key: Int) {
        sides[key] = -1
        forms[key] = 0
        failingSince[key] = 0L
        betterSince[key] = 0L
    }

    /** Shown form of tag, see [placeStable] */
    fun form(key: Int) = forms[key]

    private fun fits(key: Int, x: Float, y: Float, width: Float, height: Float, gap: Float, initialSide: Int): Boolean {
        val saved = count
        val side = sides[key]
        val result = tryPlace(key, x, y, width, height, gap, initialSide) >= 0
        count = saved
        sides[key] = side
        return result
    }

    fun left(idx: Int) = rects[idx * 4]
    fun top(idx: Int) = rects[idx * 4 + 1]
    fun right(idx: Int) = rects[idx * 4 + 2]
    fun bottom(idx: Int) = rects[idx * 4 + 3]

    private fun add(l: Float, t: Float, r: Float, b: Float): Int {
        val idx = minOf(count, rects.size / 4 - 1)
        rects[idx * 4] = l
        rects[idx * 4 + 1] = t
        rects[idx * 4 + 2] = r
        rects[idx * 4 + 3] = b
        count = idx + 1
        return idx
    }

    private fun overlaps(l: Float, t: Float, r: Float, b: Float): Boolean {
        for (idx in 0 until count) {
            if (l < rects[idx * 4 + 2] && r > rects[idx * 4] && t < rects[idx * 4 + 3] && b > rects[idx * 4 + 1]) {
                return true
            }
        }
        return false
    }

    companion object {
        const val ABOVE_RIGHT = 0
        const val ABOVE_LEFT = 1
        const val BELOW_RIGHT = 2
        const val BELOW_LEFT = 3
        const val ABOVE = 4
        const val BELOW = 5
        private const val SIDES = 6
        private const val STABLE_SECONDS = 0.5

        private fun sideLeft(side: Int, x: Float, width: Float, gap: Float) = when (side) {
            0, 2 -> x + gap
            1, 3 -> x - gap - width
            else -> x - width / 2
        }

        private fun sideTop(side: Int, y: Float, height: Float, gap: Float) = when (side) {
            0, 1, 4 -> y - gap - height
            else -> y + gap
        }
    }
}
