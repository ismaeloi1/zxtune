/**
 * @file
 * @brief FM channel state decoded from voice gauges, with registers values converted to physical units
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import app.zxtune.playback.VoiceGauges
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Single FM operator (Yamaha "slot"), raw register values as written by player
 */
internal class FmOperator {
    var mul = 0
    var tl = 0
    var ar = 0
    var dr = 0

    // OPN/OPM D2R ("sustain rate"), always 0 for OPL
    var sr = 0
    var rr = 0
    var sl = 0

    // OPN/OPM KS, OPL KSL (level scaling)
    var ks = 0
    var ksl = 0
    var dt = 0
    var dt2 = 0
    var ssg = 0
    var wave = 0

    /** see [FmChannel.STAGE_OFF] and others */
    var stage = 0
    var am = false
    var vib = false

    // OPL envelope type: true keeps sustain level while key is pressed, false fades out (percussive)
    var sustained = true
    var ksr = false
    var key = false

    /** 0..255 output level (envelope and TL) */
    var level = 0

    /** 0..255 envelope generator output without TL */
    var envelope = 0
}

/**
 * FM channel of OPN (YM2203, YM2608, YM2610, YM2612, TurboFM), OPM (YM2151) or OPL (YM3526, YM3812, YMF262) chip.
 * Operators are named OP1..OP4 in connection order as in Furnace (see [FmWidgets]).
 */
internal class FmChannel {
    var family = FAMILY_OPN
        private set
    var chip = CHIP_NONE
        private set
    var operatorsCount = 4
        private set

    /** OPN/OPM 0..7, OPL 2 operators: 0 FM, 1 AM, OPL 4 operators: 0..3 (FM-FM, AM-FM, FM-AM, AM-AM) */
    var algorithm = 0
        private set
    var feedback = 0
        private set
    var carriers = 0
        private set
    var keys = 0
        private set

    // L=2, R=1
    var pan = 3
        private set
    var ams = 0
        private set
    var pms = 0
        private set
    var block = 0
        private set
    var fnum = 0
        private set

    // OPM key code and fraction
    var keyCode = 0
        private set
    var keyFraction = 0
        private set
    var global = 0
        private set
    var global2 = 0
        private set
    var amd = 0
        private set
    var pmd = 0
        private set
    var noise = 0
        private set
    var envelopeRate = 0
        private set
    var timerA = 0
        private set
    var frequency = 0f
        private set
    var levelDb = -96f
        private set
    val operators = Array(4) { FmOperator() }

    /** Operators parameters are provided by emulation core, else only connection and levels are known */
    val isDetailed
        get() = chip != CHIP_NONE

    val isOpl
        get() = family == FAMILY_OPL

    fun update(g: VoiceGauges) {
        frequency = if (g.hasFrequency) g.frequency else 0f
        levelDb = if (g.hasLevel) g.level else -96f
        chip = g.field(CHIP)
        family = when (g.kind) {
            VoiceGauges.Kind.OPL_2OP, VoiceGauges.Kind.OPL_4OP -> FAMILY_OPL
            else -> if (chip == CHIP_OPM) FAMILY_OPM else FAMILY_OPN
        }
        when (g.kind) {
            VoiceGauges.Kind.OPL_2OP -> {
                operatorsCount = 2
                algorithm = g.field(0) and 1
                carriers = if (algorithm != 0) 3 else 2
                keys = if (g.field(6) != 0) 3 else 0
                pan = g.field(7) and 3
                block = g.field(8) and 7
                for (op in 0 until 2) {
                    operators[op].level = g.field(2 + op)
                    operators[op].wave = g.field(4 + op) and 7
                }
            }

            VoiceGauges.Kind.OPL_4OP -> {
                operatorsCount = 4
                algorithm = g.field(0) and 3
                carriers = OPL4_CARRIERS[algorithm]
                keys = if (g.field(10) != 0) 15 else 0
                pan = g.field(11) and 3
                for (op in 0 until 4) {
                    operators[op].level = g.field(2 + op)
                    operators[op].wave = g.field(6 + op) and 7
                }
            }

            else -> {
                operatorsCount = 4
                algorithm = g.field(0) and 7
                carriers = g.field(6) and 15
                pan = g.field(7) and 3
                ams = g.field(8) and 3
                pms = g.field(9) and 7
                keys = g.field(10) and 15
                block = g.field(11) and 7
                for (op in 0 until 4) {
                    operators[op].level = g.field(2 + op)
                }
            }
        }
        feedback = g.field(1) and 7
        for (op in 0 until operatorsCount) {
            operators[op].key = keys and (1 shl op) != 0
        }
        if (!isDetailed) {
            return
        }
        for (op in 0 until operatorsCount) {
            val base = OPS + op * OP_SIZE
            val dst = operators[op]
            dst.mul = g.field(base) and 15
            dst.tl = g.field(base + 1) and 127
            dst.ar = g.field(base + 2) and 31
            dst.dr = g.field(base + 3) and 31
            val slrr = g.field(base + 5)
            dst.sl = slrr and 15
            dst.rr = (slrr shr 4) and 15
            val flags = g.field(base + 6)
            dst.stage = min(flags and 7, STAGE_RELEASE)
            dst.am = flags and OPF_AM != 0
            dst.vib = flags and OPF_VIB != 0
            dst.sustained = !isOpl || flags and OPF_EGT != 0
            dst.ksr = flags and OPF_KSR != 0
            val extra = g.field(base + 7)
            if (isOpl) {
                dst.sr = 0
                dst.ks = 0
                dst.ksl = g.field(base + 4) and 3
                dst.dt = 0
                dst.dt2 = 0
                dst.ssg = 0
                dst.wave = extra and 7
            } else {
                val srks = g.field(base + 4)
                dst.sr = srks and 31
                dst.ks = (srks shr 5) and 3
                dst.ksl = 0
                dst.dt = extra and 7
                dst.dt2 = if (family == FAMILY_OPM) (extra shr 3) and 3 else 0
                dst.ssg = if (family == FAMILY_OPM) 0 else (extra shr 3) and 15
            }
            dst.envelope = g.field(EG + op)
        }
        val fn = g.field(FNUM) or (g.field(FNUM + 1) shl 8)
        if (family == FAMILY_OPM) {
            keyCode = g.field(FNUM) and 127
            keyFraction = g.field(FNUM + 1) and 63
            fnum = 0
            block = (keyCode shr 4) and 7
        } else if (isOpl) {
            // block is not in OPL_4OP fields
            fnum = fn and 0x3ff
            block = (fn shr 12) and 7
        } else {
            fnum = fn and 0x7ff
        }
        global = g.field(GLOBAL)
        global2 = g.field(GLOBAL2)
        amd = g.field(AMD)
        pmd = g.field(PMD)
        noise = g.field(NOISE)
        envelopeRate = g.field(EG_RATE) or (g.field(EG_RATE + 1) shl 8)
        timerA = g.field(TIMER_A) or (g.field(TIMER_A + 1) shl 8)
    }

    fun isCarrier(op: Int) = carriers and (1 shl op) != 0

    /**
     * Key code for rate scaling: 5 bits of octave and note group (OPN/OPM), or OPL rate offset base
     */
    private val keyScaleCode
        get() = when (family) {
            FAMILY_OPM -> keyCode shr 2

            // OPN: block and 2 MSB of F-number with "N3" detection, see YM2608 manual
            FAMILY_OPN -> {
                val f11 = (fnum shr 10) and 1
                val f10 = (fnum shr 9) and 1
                val f9 = (fnum shr 8) and 1
                val f8 = (fnum shr 7) and 1
                (block shl 2) or (f11 shl 1) or ((f11 and (f10 or f9 or f8)) or ((1 - f11) and f10 and f9 and f8))
            }

            // OPL: block and F-number bit selected by NTS
            else -> (block shl 1) or ((fnum shr (if (global and GLOBAL_OPL_NTS != 0) 8 else 9)) and 1)
        }

    /**
     * Effective envelope rate 0..63 (rate register scaled to 6 bits plus key scaling)
     */
    fun effectiveRate(op: FmOperator, rate: Int, isRelease: Boolean = false): Int {
        if (rate == 0) {
            return 0
        }
        val kc = keyScaleCode
        return if (isOpl) {
            min(63, rate * 4 + (if (op.ksr) kc else kc shr 2))
        } else {
            // release rate is 4 bits, as 5 bits rate it's 2*RR+1
            val r = if (isRelease) rate * 2 + 1 else rate
            min(63, r * 2 + (kc shr (3 - op.ks)))
        }
    }

    private val egClock
        get() = if (envelopeRate > 0) {
            envelopeRate.toFloat()
        } else if (isOpl) {
            OPL_EG_CLOCK
        } else {
            OPN_EG_CLOCK
        }

    /**
     * Time of full range (96dB) decay at effective rate, infinite as [Float.POSITIVE_INFINITY]
     */
    fun decayMs(effRate: Int): Float = when {
        effRate < 4 -> Float.POSITIVE_INFINITY

        // OPL datasheet: 39.28s at rate 1, halved by each rate step
        isOpl -> {
            val r = min(effRate, 60)
            39280f / 2f.pow(r / 4 - 1) / (1f + (r % 4) / 4f) * OPL_EG_CLOCK / egClock
        }

        // OPN/OPM: 1024 steps of attenuation, (4 + rate%4)/4 * 2^(rate/4 - 12) steps per envelope clock
        effRate >= 60 -> 1024f / 8f / egClock * 1000f

        else -> 1024f / ((1f + (effRate % 4) / 4f) * 2f.pow(effRate / 4 - 12)) / egClock * 1000f
    }

    /**
     * Attack is exponential, so it takes a fixed part of decay time at the same rate
     */
    fun attackMs(effRate: Int): Float = when {
        effRate < 4 -> Float.POSITIVE_INFINITY
        isOpl && effRate >= 60 -> 0f
        !isOpl && effRate >= 62 -> 0f
        else -> decayMs(effRate) * (if (isOpl) OPL_ATTACK_PART else OPN_ATTACK_PART)
    }

    fun multiplier(op: FmOperator) = if (isOpl) {
        OPL_MUL[op.mul]
    } else if (op.mul == 0) {
        0.5f
    } else {
        op.mul.toFloat()
    }

    /** Frequency of operator with multiplier (and OPM DT2), detune is small and ignored */
    fun operatorHz(op: FmOperator) = frequency * multiplier(op) * (if (family == FAMILY_OPM) DT2_RATIO[op.dt2] else 1f)

    /** @return LFO frequency in Hz or 0 if disabled */
    val lfoHz: Float
        get() = when {
            family == FAMILY_OPM -> 52.95f * 2f.pow((global2 shr 4) - 15) * (16 + (global2 and 15)) / 31f

            chip == CHIP_OPN2 || chip == CHIP_OPNA || chip == CHIP_OPNB ->
                if (global and 8 != 0) OPN_LFO_HZ[global and 7] else 0f

            else -> 0f
        }

    val hasLfo
        get() = family == FAMILY_OPM || chip == CHIP_OPN2 || chip == CHIP_OPNA || chip == CHIP_OPNB

    val hasPan
        get() = family == FAMILY_OPM || chip == CHIP_OPN2 || chip == CHIP_OPNA || chip == CHIP_OPNB || chip == CHIP_OPL3

    /** 0 normal, 1 special (per operator frequencies), 2 CSM (key on by timer A, used for speech) */
    val ch3Mode
        get() = if (family == FAMILY_OPN) (global shr 4) and 3 else 0

    val csmHz
        get() = if (timerA < 1024 && egClock > 0) egClock * 3 / (1024 - timerA) else 0f

    companion object {
        const val FAMILY_OPN = 0
        const val FAMILY_OPM = 1
        const val FAMILY_OPL = 2

        // mirrors Module::VoiceState::FmChip
        const val CHIP_NONE = 0
        const val CHIP_OPN = 1
        const val CHIP_OPN2 = 2
        const val CHIP_OPNA = 3
        const val CHIP_OPNB = 4
        const val CHIP_OPM = 5
        const val CHIP_OPL = 6
        const val CHIP_OPL2 = 7
        const val CHIP_OPL3 = 8

        const val STAGE_OFF = 0
        const val STAGE_ATTACK = 1
        const val STAGE_DECAY = 2
        const val STAGE_SUSTAIN = 3
        const val STAGE_RELEASE = 4

        // OPL global flags
        const val GLOBAL_OPL_RHYTHM = 0x01
        const val GLOBAL_OPL_AM_DEPTH = 0x02
        const val GLOBAL_OPL_VIB_DEPTH = 0x04
        const val GLOBAL_OPL3 = 0x08
        const val GLOBAL_OPL_CSM = 0x10
        const val GLOBAL_OPL_NTS = 0x20
        const val GLOBAL_OPL_WSE = 0x40

        // OPN/OPM global flags
        const val GLOBAL_OPN_DAC = 0x40
        const val GLOBAL_OPM_NOISE = 0x04

        // fields layout, see Module::VoiceState::FM_*
        private const val OPS = 16
        private const val OP_SIZE = 8
        private const val EG = 48
        private const val FNUM = 52
        private const val GLOBAL = 54
        private const val GLOBAL2 = 55
        private const val AMD = 56
        private const val PMD = 57
        private const val NOISE = 58
        private const val CHIP = 59
        private const val EG_RATE = 60
        private const val TIMER_A = 62
        private const val OPF_AM = 0x08
        private const val OPF_VIB = 0x10
        private const val OPF_EGT = 0x20
        private const val OPF_KSR = 0x40

        // carriers of OPL3 4 operators connections (FM-FM, AM-FM, FM-AM, AM-AM)
        private val OPL4_CARRIERS = intArrayOf(8, 9, 10, 13)
        private val OPL_MUL = floatArrayOf(0.5f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f, 10f, 10f, 12f, 12f, 15f, 15f)
        private val DT2_RATIO = floatArrayOf(1f, 1.41f, 1.57f, 1.73f)

        // YM2612 manual, at 7.67MHz (other OPN chips at their nominal clocks are the same)
        private val OPN_LFO_HZ = floatArrayOf(3.98f, 5.56f, 6.02f, 6.37f, 6.88f, 9.63f, 48.1f, 72.2f)

        // nominal envelope clocks if core does not report it: YM2612 at 7.67MHz, OPL at 3.58MHz
        private const val OPN_EG_CLOCK = 7670454f / 144 / 3
        private const val OPL_EG_CLOCK = 3579545f / 72

        // Attack curve approaches 0dB exponentially, ~66 of 1024 decay steps at the same rate (MAME fm.c), OPL
        // datasheet attack and decay tables give 2826ms vs 39280ms at rate 1
        private const val OPN_ATTACK_PART = 0.065f
        private const val OPL_ATTACK_PART = 0.072f

        /** Sustain level attenuation, 3dB per step and 93dB for 15 */
        fun sustainDb(sl: Int) = if (sl == 15) 93 else sl * 3

        /** TL attenuation in dB */
        fun totalLevelDb(tl: Int) = tl * 0.75f

        /** OPN/OPM DT1: 0..3 positive, 4..7 negative */
        fun detune(dt: Int) = if (dt and 4 != 0) -(dt and 3) else dt and 3

        fun levelDb(level: Int) = -(255 - level) * 96f / 255f
    }
}
