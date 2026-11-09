/**
 * @file
 * @brief Switching of dashboard between all voices cards and inspector of single FM channel
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.graphics.Canvas
import android.graphics.RectF
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoicesLayout

/**
 * Cards of FM channels are too small for operators parameters, so tap on card opens [FmInspector] in the whole
 * dashboard area, with tabs of the same chip's channels and button back to all cards.
 */
internal class FmFocus(private val density: Float, private val scaledDensity: Float) {

    private val inspector by lazy { FmInspector(density, scaledDensity) }
    private val cards = Array(MAX_VOICES) { RectF() }
    private val kinds = IntArray(MAX_VOICES) { -1 }
    private var layout: VoicesLayout? = null
    private var tabs: FmInspector.Tabs? = null
    private var tab = 0
    private var operator = -1
    private val area = RectF()

    /** Focused voice index or -1 */
    val voice: Int
        get() = tabs?.voices?.getOrNull(tab) ?: -1

    /** Changes when static part of dashboard should be redrawn */
    val key: Long
        get() = (voice.toLong() shl 8) or (operator.toLong() and 0xff)

    /** Card of voice drawn at [rect] */
    fun card(voice: Int, rect: RectF, kind: Int) {
        if (voice in 0 until MAX_VOICES) {
            cards[voice].set(rect)
            kinds[voice] = kind
        }
    }

    /**
     * @param nanos monotonic time of frame for readouts of values changing at each tick, 0 for single picture
     * @return true if inspector is shown instead of cards
     */
    fun draw(
        canvas: Canvas,
        dynamic: Boolean,
        current: VoicesLayout,
        data: ByteArray,
        voices: Int,
        width: Float,
        height: Float,
        smooth: Array<FloatArray>,
        nanos: Long,
    ): Boolean {
        if (current !== layout) {
            layout = current
            close()
        }
        val tabs = tabs ?: return false
        val focused = voice
        if (focused !in 0 until voices || !isFm(VoiceGauges(data, focused * VoiceGauges.SIZE).kind)) {
            close()
            return false
        }
        if (dynamic) {
            area.set(0f, 0f, width, height)
            inspector.draw(canvas, area, data, tabs, tab, smooth[focused % smooth.size], operator, nanos)
        }
        return true
    }

    fun readoutTexts() = inspector.readoutTexts()

    /**
     * @return true if view should be redrawn
     */
    fun tap(x: Float, y: Float, data: ByteArray?, voices: Int): Boolean {
        if (tabs != null) {
            val hit = inspector.hit(x, y)
            val idx = (hit and 0xffffffffL).toInt()
            return when (hit ushr 32) {
                FmInspector.HIT_BACK -> {
                    close()
                    true
                }

                FmInspector.HIT_TAB -> {
                    tab = idx
                    operator = -1
                    true
                }

                FmInspector.HIT_OPERATOR -> {
                    operator = if (operator == idx) -1 else idx
                    true
                }

                else -> false
            }
        }
        val current = layout ?: return false
        if (data == null) {
            return false
        }
        for (voice in 0 until minOf(voices, MAX_VOICES)) {
            if (isFm(kinds[voice]) && cards[voice].contains(x, y)) {
                return open(current, data, voices, voice)
            }
        }
        return false
    }

    /**
     * Opens inspector of [voice] without tap, e.g. for tests
     */
    fun open(current: VoicesLayout, data: ByteArray, voices: Int, voice: Int, operator: Int): Boolean {
        layout = current
        val opened = open(current, data, voices, voice)
        this.operator = operator
        return opened
    }

    private fun open(current: VoicesLayout, data: ByteArray, voices: Int, voice: Int): Boolean {
        var first = 0
        for (group in current.groups) {
            val count = group.voices.size
            if (voice < first + count) {
                val indices = ArrayList<Int>()
                val names = ArrayList<String>()
                for (idx in 0 until count) {
                    val v = first + idx
                    if (v < voices && isFm(VoiceGauges(data, v * VoiceGauges.SIZE).kind)) {
                        indices.add(v)
                        names.add(group.voices[idx])
                    }
                }
                val pos = indices.indexOf(voice)
                if (pos < 0) {
                    return false
                }
                tabs = FmInspector.Tabs(group.name, names.toTypedArray(), indices.toIntArray())
                tab = pos
                operator = -1
                return true
            }
            first += count
        }
        return false
    }

    fun close() {
        tabs = null
        operator = -1
    }

    companion object {
        private const val MAX_VOICES = 64

        fun isFm(kind: Int) = kind == VoiceGauges.Kind.OPN_FM || kind == VoiceGauges.Kind.OPL_2OP ||
            kind == VoiceGauges.Kind.OPL_4OP
    }
}
