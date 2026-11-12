package app.zxtune.ui.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoicesLayout
import java.io.File
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FM dashboard cards and channel inspector with full operators state as reported by emulation cores.
 * Images are stored to directory from ZXTUNE_SCREENSHOTS environment variable for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// 1080x2400 phone
@Config(qualifiers = "w411dp-h914dp-420dpi")
class FmInspectorRenderTest {

    private class Voice(val kind: Int, val fields: IntArray, val freq: Float = 220f, val level: Float = -6f, val flags: Int = 7)

    // Operator: mul, tl, ar, dr, sr|ks<<5 (OPL: ksl), sl|rr<<4, stage|flags, extra (OPN dt|ssg<<3, OPL wave), envelope
    private class Op(
        val mul: Int,
        val tl: Int,
        val ar: Int,
        val dr: Int,
        val sr: Int,
        val sl: Int,
        val rr: Int,
        val stage: Int,
        val flags: Int = 0,
        val extra: Int = 0,
        val env: Int = 200,
        val level: Int = 180
    )

    private fun opn(
        alg: Int,
        fb: Int,
        ops: List<Op>,
        block: Int = 4,
        fnum: Int = 1038,
        chip: Int = FmChannel.CHIP_OPN2,
        global: Int = 0x0b,
        pan: Int = 3,
        ams: Int = 1,
        pms: Int = 2,
        global2: Int = 0
    ): IntArray {
        val f = IntArray(VoiceGauges.FIELDS)
        f[0] = alg
        f[1] = fb
        f[6] = OPN_CARRIERS[alg]
        f[7] = pan
        f[8] = ams
        f[9] = pms
        f[11] = block
        ops.forEachIndexed { idx, op ->
            f[2 + idx] = op.level
            if (op.stage in 1..3) f[10] = f[10] or (1 shl idx)
            putOp(f, idx, op)
        }
        f[52] = fnum and 0xff
        f[53] = fnum shr 8
        f[54] = global
        f[55] = global2
        f[59] = chip
        putEgRate(f, if (chip == FmChannel.CHIP_OPM) 18643 else 17756)
        return f
    }

    private fun opl(additive: Int, fb: Int, ops: List<Op>, block: Int = 4, fnum: Int = 0x241, chip: Int = FmChannel.CHIP_OPL2, global: Int = 0x40): IntArray {
        val f = IntArray(VoiceGauges.FIELDS)
        f[0] = additive
        f[1] = fb
        ops.forEachIndexed { idx, op ->
            f[2 + idx] = op.level
            f[4 + idx] = op.extra
            putOp(f, idx, op)
        }
        f[6] = 1
        f[7] = 3
        f[8] = block
        f[52] = fnum and 0xff
        f[53] = (fnum shr 8) or (block shl 4)
        f[54] = global
        f[59] = chip
        putEgRate(f, 49716)
        return f
    }

    private fun opl4(alg: Int, fb: Int, ops: List<Op>, global2: Int = 0x03): IntArray {
        val f = IntArray(VoiceGauges.FIELDS)
        f[0] = alg
        f[1] = fb
        ops.forEachIndexed { idx, op ->
            f[2 + idx] = op.level
            f[6 + idx] = op.extra
            putOp(f, idx, op)
        }
        f[10] = 1
        f[11] = 3
        f[52] = 0x41
        f[53] = 2 or (4 shl 4)
        f[54] = 0x08 or 0x02
        f[55] = global2
        f[59] = FmChannel.CHIP_OPL3
        putEgRate(f, 49716)
        return f
    }

    private fun putOp(f: IntArray, idx: Int, op: Op) {
        val base = 16 + idx * 8
        f[base] = op.mul
        f[base + 1] = op.tl
        f[base + 2] = op.ar
        f[base + 3] = op.dr
        f[base + 4] = op.sr
        f[base + 5] = op.sl or (op.rr shl 4)
        f[base + 6] = op.stage or op.flags
        f[base + 7] = op.extra
        f[48 + idx] = op.env
    }

    private fun putEgRate(f: IntArray, rate: Int) {
        f[60] = rate and 0xff
        f[61] = rate shr 8
    }

    private fun makeData(voices: List<Voice>): ByteArray {
        val data = ByteArray(voices.size * VoiceGauges.SIZE)
        voices.forEachIndexed { idx, voice ->
            val offset = idx * VoiceGauges.SIZE
            for (col in 0 until VoiceGauges.COLUMNS) {
                fun put(gauge: Int, min: Int, max: Int) {
                    data[offset + (gauge * VoiceGauges.COLUMNS + col) * 2] = min.toByte()
                    data[offset + (gauge * VoiceGauges.COLUMNS + col) * 2 + 1] = max.toByte()
                }
                val level = (180 + 60 * sin(col / 9.0 + idx)).toInt().coerceIn(0, 255)
                put(VoiceGauges.WAVE, 100, 160)
                put(VoiceGauges.LEVEL, level - 10, level)
                put(VoiceGauges.FREQUENCY, 120, 120)
            }
            val state = offset + VoiceGauges.GAUGES * VoiceGauges.COLUMNS * 2
            putFloat(data, state, voice.freq)
            putFloat(data, state + 4, voice.level)
            data[state + 8] = voice.flags.toByte()
            data[state + 9] = voice.kind.toByte()
            voice.fields.forEachIndexed { f, value -> data[state + 10 + f] = value.toByte() }
        }
        return data
    }

    private fun putFloat(data: ByteArray, at: Int, value: Float) {
        val bits = value.toRawBits()
        for (idx in 0 until 4) {
            data[at + idx] = (bits shr (idx * 8)).toByte()
        }
    }

    private fun render(name: String, layout: String, voices: List<Voice>, width: Int, height: Int, focus: Int = -1, op: Int = -1): SidDashboardView {
        val view = SidDashboardView(ApplicationProvider.getApplicationContext())
        view.setVoicesData(VoicesLayout.parse(layout), makeData(voices), voices.size, "Emulation description")
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        if (focus >= 0) {
            assertTrue(name, view.openFmInspector(focus, op))
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // values are eased between frames
        repeat(12) {
            bitmap.eraseColor(0)
            view.draw(Canvas(bitmap))
        }
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return view
    }

    private val megaDriveVoices = listOf(
        Voice(VoiceGauges.Kind.PSG_TONE, intArrayOf(0, 0xfe, 0, 3), 440.4f, 0f),
        Voice(VoiceGauges.Kind.PSG_TONE, intArrayOf(4, 0x7f, 0, 3), 880.8f, -8f),
        Voice(VoiceGauges.Kind.PSG_TONE, intArrayOf(15, 0, 0, 3), 109f, -96f, 2 or 4),
        Voice(VoiceGauges.Kind.PSG_NOISE, intArrayOf(2, 1, 0, 3), 6991f, -4f, 1 or 2 or 4 or 8),
    ) + listOf(
        // slap bass: ALG 4 with feedback
        Voice(
            VoiceGauges.Kind.OPN_FM,
            opn(
                4,
                5,
                listOf(
                    Op(1, 24, 31, 12, 5 or (1 shl 5), 3, 7, 2, 8, 1, 230, 190),
                    Op(1, 10, 31, 8, 4, 2, 9, 2, 0, 0, 240, 220),
                    Op(2, 32, 28, 14, 6, 5, 7, 3, 0, 3 or (10 shl 3), 160, 120),
                    Op(1, 6, 31, 10, 3, 4, 8, 3, 0, 5, 200, 205)
                ),
                block = 2,
                fnum = 1100
            ),
            82.4f,
            -4f
        ),
        Voice(
            VoiceGauges.Kind.OPN_FM,
            opn(
                7,
                0,
                listOf(
                    Op(1, 20, 25, 5, 2, 1, 5, 1, 0, 0, 150, 130),
                    Op(2, 22, 25, 5, 2, 1, 5, 1, 0, 1, 150, 128),
                    Op(4, 24, 25, 5, 2, 1, 5, 1, 0, 2, 150, 126),
                    Op(8, 26, 25, 5, 2, 1, 5, 1, 0, 3, 150, 124)
                ),
                block = 4,
                fnum = 653,
                pan = 2
            ),
            261.6f,
            -14f
        ),
        Voice(
            VoiceGauges.Kind.OPN_FM,
            opn(
                0,
                7,
                listOf(
                    Op(3, 30, 20, 3, 0, 0, 2, 4, 0, 0, 100, 60),
                    Op(1, 40, 20, 3, 0, 0, 2, 4, 0, 0, 90, 50),
                    Op(5, 35, 20, 3, 0, 0, 2, 4, 0, 0, 80, 40),
                    Op(1, 0, 20, 3, 0, 0, 2, 4, 0, 0, 70, 70)
                ),
                block = 5,
                fnum = 653
            ),
            523.2f,
            -30f,
            2 or 4
        ),
        Voice(VoiceGauges.Kind.OPN_FM, opn(2, 3, List(4) { Op(it + 1, 20 + it * 4, 31, 10, 4, 6, 6, 2) }), 330f, -8f),
        Voice(VoiceGauges.Kind.OPN_FM, opn(5, 2, List(4) { Op(it * 2 + 1, 18, 31, 8, 3, 4, 7, 3) }, block = 3), 196f, -12f),
        Voice(VoiceGauges.Kind.OPN_FM, opn(6, 0, List(4) { Op(1, 30, 31, 31, 0, 15, 15, 0, 0, 0, 0, 0) }), 0f, -96f, 2 or 4),
        Voice(VoiceGauges.Kind.OPN_DAC, intArrayOf(1, 200), 0f, 0f, 1),
    )

    @Test
    fun megaDriveCards() = render("fm-megadrive-cards", MEGADRIVE, megaDriveVoices, 1080, 1210).let { }

    @Test
    fun megaDriveInspector() = render("fm-megadrive-inspector", MEGADRIVE, megaDriveVoices, 1080, 1210, 4, 2).let { }

    @Test
    fun megaDriveInspectorLandscape() = render("fm-megadrive-inspector-land", MEGADRIVE, megaDriveVoices, 2400, 760, 4).let { }

    @Test
    fun megaDriveInspectorTall() = render("fm-megadrive-inspector-tall", MEGADRIVE, megaDriveVoices, 1080, 1700, 5).let { }

    private val adlibVoices = (0 until 9).map {
        Voice(
            VoiceGauges.Kind.OPL_2OP,
            opl(
                if (it == 3) 1 else 0,
                it % 8,
                listOf(
                    Op(1, 16 + it, 15, 3, 0, 3, 5, 2, 0x20 or 0x08, it % 4, 200, 160),
                    Op(2, 5, 13, 4, 1, 2, 6, 3, if (it % 2 == 0) 0x20 else 0x10, (it + 1) % 4, 230, 210)
                )
            ),
            220f * (1 + it % 3),
            -6f - it
        )
    } + (0 until 5).map {
        Voice(VoiceGauges.Kind.OPL_RHYTHM, intArrayOf(it, 0, 0, 0), 0f, -96f, 0)
    }

    @Test
    fun adlibCards() = render("fm-adlib-cards", ADLIB, adlibVoices, 1080, 1210).let { }

    @Test
    fun adlibInspector() = render("fm-adlib-inspector", ADLIB, adlibVoices, 1080, 1210, 0).let { }

    private val opl3Voices = (0 until 3).map {
        Voice(
            VoiceGauges.Kind.OPL_4OP,
            opl4(
                it + 1,
                4,
                List(4) { op -> Op(op + 1, 10 + op * 6, 14, 4, op % 4, 3, 6, 1 + op % 3, 0x20, (it + op) % 8, 220 - op * 20, 200 - op * 30) }
            ),
            330f,
            -6f
        )
    } + (3 until 6).map { Voice(VoiceGauges.Kind.GENERIC, intArrayOf(), 0f, -96f, 0) } + (6 until 9).map {
        Voice(VoiceGauges.Kind.OPL_2OP, opl(0, 2, List(2) { op -> Op(1, 20, 12, 4, 0, 4, 5, 2, 0x20, 0) }, chip = FmChannel.CHIP_OPL3, global = 0x08), 196f, -12f)
    }

    @Test
    fun opl3Inspector() = render("fm-opl3-inspector", OPL3, opl3Voices, 1080, 1210, 1).let { }

    @Test
    fun ym2151Inspector() = render(
        "fm-ym2151-inspector",
        "audio\nYM2151\t1\t2\t3\t4\t5\t6\t7\t8",
        (0 until 8).map {
            Voice(
                VoiceGauges.Kind.OPN_FM,
                opn(
                    it,
                    it % 8,
                    List(4) { op -> Op(op + 1, 20 + op * 5, 31, 9, 4 or (op % 4 shl 5), 5, 7, 2, 8, op % 4 or ((op % 4) shl 3)) },
                    fnum = 0x4a or (12 shl 8),
                    chip = FmChannel.CHIP_OPM,
                    global = 2,
                    global2 = 200
                ).also { f ->
                    f[56] = 64
                    f[57] = 32
                },
                440f,
                -6f
            )
        },
        1080,
        1210,
        3
    ).let { }

    @Test
    fun turboFmInspector() = render(
        "fm-tfm-inspector",
        "audio\nTurboFM\t1\t2\t3\t4\t5\t6",
        (0 until 6).map {
            Voice(
                VoiceGauges.Kind.OPN_FM,
                opn(it, 3, List(4) { op -> Op(op + 1, 25, 31, 10, 4, 5, 7, 2) }, chip = FmChannel.CHIP_OPN, global = 0x20, pan = 3, ams = 0, pms = 0)
                    .also { f ->
                        f[62] = 0x10
                        f[63] = 3
                    },
                220f,
                -6f
            )
        },
        1080,
        1210,
        2
    ).let { }

    // emulation cores without operators parameters (e.g. Nuked) give only connection and levels
    @Test
    fun undetailedInspector() = render(
        "fm-undetailed-inspector",
        "audio\nYM2612\tFM 1\tFM 2",
        listOf(
            Voice(VoiceGauges.Kind.OPN_FM, intArrayOf(4, 5, 200, 120, 255, 230, 10, 3, 0, 0, 15, 4)),
            Voice(VoiceGauges.Kind.OPN_FM, intArrayOf(7, 0, 200, 120, 255, 230, 15, 3, 0, 0, 15, 4)),
        ),
        1080,
        1210,
        0
    ).let { }

    /**
     * Channel with vibrato (±70 cents at 6Hz) and tremolo written by player at each 50Hz tick, drawn at 60fps:
     * texts are refreshed at most 4 times per second, note is kept, pitch is shown as swept range.
     */
    @Test
    fun readoutsSequence() {
        RealtimeReadout.isAveraged = true
        try {
            val view = SidDashboardView(ApplicationProvider.getApplicationContext())
            // the same layout for all the frames, else inspector is closed as for other tune
            val layout = VoicesLayout.parse(MEGADRIVE)
            view.setVoicesData(layout, makeData(megaDriveVoices), megaDriveVoices.size, "Emulation description")
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1210, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, 1210)
            assertTrue(view.openFmInspector(4))
            val bitmap = Bitmap.createBitmap(1080, 1210, Bitmap.Config.ARGB_8888)
            val histories = ArrayList<ArrayList<String>>()
            for (frame in 0 until 180) {
                val t = Math.floor(frame / 60.0 * 50) / 50
                val voices = megaDriveVoices.mapIndexed { idx, voice ->
                    if (idx != 4) {
                        voice
                    } else {
                        val hz = 82.4f * Math.pow(2.0, 0.7 * sin(2 * Math.PI * 6 * t) / 12).toFloat()
                        Voice(voice.kind, voice.fields, hz, (-8 + 5 * sin(2 * Math.PI * 3 * t)).toFloat(), voice.flags)
                    }
                }
                view.setVoicesData(layout, makeData(voices), voices.size, "Emulation description")
                view.testFrameNanos = 1_000_000_000L + frame * 1_000_000_000L / 60
                bitmap.eraseColor(0)
                view.draw(Canvas(bitmap))
                view.fmInspectorTexts().forEachIndexed { idx, text ->
                    if (histories.size <= idx) histories.add(ArrayList())
                    if (histories[idx].lastOrNull() != text) histories[idx].add(text)
                }
            }
            System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
                File(dir, "fm-megadrive-inspector-vibrato.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            for (history in histories) {
                assertTrue(history.toString(), history.size <= 1 + 4 * 3 + 1)
            }
            assertEquals(listOf("E2"), histories[0])
            assertTrue(histories[1].toString(), Regex("8\\d\\.\\d\\d…8\\d\\.\\d\\d Hz|7\\d\\.\\d\\d…8\\d\\.\\d\\d Hz").matches(histories[1].last()))
            assertTrue(histories[2].toString(), histories[2].last().contains('…'))
        } finally {
            RealtimeReadout.isAveraged = false
        }
    }

    @Test
    fun envelopeTimes() {
        val ch = FmChannel()
        val data = makeData(listOf(Voice(VoiceGauges.Kind.OPN_FM, opn(0, 0, List(4) { Op(1, 0, 31, 0, 0, 0, 0, 1) }, block = 0, fnum = 0))))
        ch.update(VoiceGauges(data, 0))
        val op = ch.operators[0]
        // YM2612 at 7.67MHz: rate 63 decays 96dB in ~7.2ms, rate 4 in ~118s (MAME fm.c eg_inc tables)
        assertEquals(7.2f, ch.decayMs(63), 0.1f)
        assertEquals(118100f, ch.decayMs(4), 500f)
        assertEquals(0f, ch.attackMs(62), 0f)
        assertTrue(ch.decayMs(1).isInfinite())
        // AR 31 without key scaling is rate 62
        assertEquals(62, ch.effectiveRate(op, 31))
        // RR 15 is 5 bits rate 31
        assertEquals(62, ch.effectiveRate(op, 15, true))
        assertEquals(93, FmChannel.sustainDb(15))
        assertEquals(-3, FmChannel.detune(7))
        val opl = FmChannel()
        val oplData = makeData(listOf(Voice(VoiceGauges.Kind.OPL_2OP, opl(0, 0, List(2) { Op(1, 0, 1, 1, 0, 0, 1, 1) }, block = 0, fnum = 0))))
        opl.update(VoiceGauges(oplData, 0))
        // OPL datasheet: decay 39.28s and attack 2.83s at rate 1
        assertEquals(39280f, opl.decayMs(opl.effectiveRate(opl.operators[0], 1)), 1f)
        assertEquals(2828f, opl.attackMs(opl.effectiveRate(opl.operators[0], 1)), 5f)
    }

    private companion object {
        const val MEGADRIVE = "audio\nSEGA PSG\t1\t2\t3\tNoise\nYM2612\tFM 1\tFM 2\tFM 3\tFM 4\tFM 5\tFM 6\tDAC"
        const val ADLIB = "audio\nYM3812\t1\t2\t3\t4\t5\t6\t7\t8\t9\tBass Drum\tSnare Drum\tTom Tom\tCymbal\tHi-Hat"
        const val OPL3 = "audio\nYMF262\t1\t2\t3\t4\t5\t6\t7\t8\t9"

        // as reported by emulation cores
        val OPN_CARRIERS = intArrayOf(8, 8, 8, 8, 10, 14, 14, 15)
    }
}
