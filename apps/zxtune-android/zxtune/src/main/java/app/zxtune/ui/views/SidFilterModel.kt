/**
 * @file
 * @brief SID filters cutoff curves as applied by native emulation
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Mirrors module/players/external/sid/filters.cpp (presets and small signal cutoff models of reSID and reSIDfp)
 * and parameters translation of module/players/external/sid.cpp, so displayed curve is the one really emulated.
 * Presets are from JSIDPlay2 default configuration (jsidplay2.ini) and VICE defaults.
 */
object SidFilterModel {
    const val CUTOFF_VALUES = 2048
    private const val CUTOFF_BITS = 11

    sealed interface Curve {
        fun frequency(fc: Int): Double
    }

    /** reSID 1.0 6581 model with DAC zero level shift in volts */
    class ResidCurve(val bias: Double) : Curve {
        override fun frequency(fc: Int) = cutoff6581(false, residDacVoltage(fc, bias), RESID_UCOX)
    }

    /** resid-fp (Antti Lankila) model: fixed resistance parallel to FET one charging 470pF capacitor */
    class Type3Curve(
        val base: Double,
        val offset: Double,
        val steepness: Double,
        val minFet: Double,
        val nonlinearity: Double = 0.96,
        val attenuation: Double = 0.5,
    ) : Curve {
        override fun frequency(fc: Int): Double {
            val dynamic = minFet + offset / steepness.pow(kinkedDac(fc, nonlinearity))
            val resistance = base * dynamic / (base + dynamic)
            // highpass output attenuation lowers cutoff of 2-pole loop
            return sqrt(attenuation) / (2 * PI * 470e-12 * resistance)
        }
    }

    class LinearCurve(val perUnit: Double, val atZero: Double) : Curve {
        override fun frequency(fc: Int) = perUnit * fc + atZero
    }

    // JSIDPlay2 reSID 1.0 curve position P means DAC zero of 6.65V - (P - 0.5) * 2V
    private fun residPosition(position: Double) = ResidCurve((0.5 - position) * 2)

    /** Keys are values of zxtune.core.sid.filter_6581_preset, 0 is custom */
    val PRESETS_6581: Map<Int, Curve> = mapOf(
        1 to residPosition(0.1),
        2 to residPosition(0.3),
        3 to residPosition(0.4),
        4 to residPosition(0.5),
        5 to residPosition(0.6),
        6 to residPosition(0.7),
        7 to residPosition(0.9),
        8 to ResidCurve(0.5),
        9 to Type3Curve(1537690.1305811733, 76322577.13241087, 1.0072175060266852, 19114.018733380854),
        10 to Type3Curve(
            1147036.4394268463,
            274228796.97550374,
            1.0066634233403395,
            16125.154840564108,
            0.9613160610660189
        ),
        11 to Type3Curve(1522171.922983084, 21729926.667291082, 1.004994802537475, 14299.149638099827),
        12 to Type3Curve(1613349.4942964897, 67122945.35403329, 1.005727808339829, 15462.006399118263),
        13 to Type3Curve(1.55e6, 2.5e8, 1.006, 1.9e4),
        14 to Type3Curve(1399768.3253307983, 553018906.8926692, 1.0051493199361266, 11961.908870403166),
        15 to Type3Curve(1250736.2235895505, 1521187976.8735676, 1.005543646897986, 8581.78418415723),
        16 to Type3Curve(1.9e6, 8.5e7, 1.0058, 2e4),
        17 to Type3Curve(1.83e6, 2.6e9, 1.0064, 2.5e4),
        18 to Type3Curve(1.65e6, 1.2e10, 1.006, 1e4),
        19 to Type3Curve(1.5e6, 1.8e8, 1.0065, 1.8e4),
        20 to Type3Curve(1.3e6, 1.9e8, 1.0066, 1.8e4),
        21 to Type3Curve(1.3e6, 3.7e8, 1.0066, 1.8e4),
        22 to Type3Curve(1164920.4999651583, 12915042.165290257, 1.0058853753357735, 12914.5661141159),
        23 to Type3Curve(1.16e6, 9.9e6, 1.0058, 1.48e4),
        24 to Type3Curve(840577.4520801408, 1909158.8633669745, 1.0068865662510837, 14858.140079688419),
        25 to Type3Curve(1.08e6, 1.8e6, 1.006, 1.0e4),
        26 to Type3Curve(1.1e6, 8e6, 1.0052, 1.7e4),
        27 to Type3Curve(1590881.4720854638, 32349044.218055427, 1.005583147929036, 16357.252396224501),
        28 to Type3Curve(1.45e6, 1.75e8, 1.0055, 1e4),
        29 to Type3Curve(1585454.0607309118, 97702791.79673694, 1.0053036856077033, 14497.859765307807),
        30 to Type3Curve(1090649.9265298771, 264401456.3944572, 1.0145069244334666, 19890.786352277173),
        31 to Type3Curve(1119380.8539989102, 257709531.3343812, 1.0073881870085593, 20269.79137269368),
    )

    /** Keys are values of zxtune.core.sid.filter_8580_preset, 0 is custom */
    val PRESETS_8580: Map<Int, Curve> = mapOf(
        1 to LinearCurve(13400.0 / (CUTOFF_VALUES - 1), 0.0),
        2 to LinearCurve(12500.0 / (CUTOFF_VALUES - 1), 0.0),
        3 to LinearCurve(11700.0 / (CUTOFF_VALUES - 1), 0.0),
        4 to LinearCurve(5.7, 20.0),
        5 to LinearCurve(6.55, 20.0),
    )

    /**
     * Emulation settings affecting cutoff, see zxtune.core.sid.* preferences
     */
    data class Settings(
        val residFp: Boolean = true,
        val preset6581: Int = 0,
        val preset8580: Int = 0,
        /** reSIDfp, 0..1 */
        val curve6581: Double = 0.5,
        val range6581: Double = 0.5,
        val curve8580: Double = 0.5,
        /** reSID, millivolts */
        val biasMv: Int = 0,
    )

    /** Emulated cutoff frequencies in Hz for every register value */
    fun cutoff6581(settings: Settings): DoubleArray {
        val preset = PRESETS_6581[settings.preset6581]
        if (!settings.residFp) {
            val bias = preset?.let { fitResidBias(it) } ?: (settings.biasMv.coerceIn(-1000, 1000) / 1000.0)
            return DoubleArray(CUTOFF_VALUES) { cutoff6581(false, residDacVoltage(it, bias), RESID_UCOX) }
        }
        if (preset == null) {
            val uCox = residFpUcox(settings.range6581)
            return DoubleArray(CUTOFF_VALUES) { cutoff6581(true, residFpDacVoltage(it, settings.curve6581), uCox) }
        }
        val base = (preset as? Type3Curve)?.let { ucoxForBase(it.base) } ?: RESID_UCOX
        val uCox = residFpUcox(residFpRange(base))
        return DoubleArray(CUTOFF_VALUES) { cutoff6581(true, dacVoltage6581(true, preset.frequency(it), uCox), uCox) }
    }

    fun cutoff8580(settings: Settings): DoubleArray {
        val preset = PRESETS_8580[settings.preset8580]
        return if (settings.residFp) {
            val curve = preset?.let { fit(it, 0.0, 1.0, ::residFp8580Cutoff) } ?: settings.curve8580
            DoubleArray(CUTOFF_VALUES) { residFp8580Cutoff(it, curve) }
        } else {
            val max = preset?.frequency(CUTOFF_VALUES - 1) ?: RESID_8580_MAX_CUTOFF
            DoubleArray(CUTOFF_VALUES) { max * (it + 1) / CUTOFF_VALUES }
        }
    }

    /** Curve defined by preset (e.g. measured on real chip), null for custom filter */
    fun reference6581(settings: Settings) = PRESETS_6581[settings.preset6581]?.let { curve ->
        DoubleArray(CUTOFF_VALUES) { curve.frequency(it) }
    }

    fun reference8580(settings: Settings) = PRESETS_8580[settings.preset8580]?.let { curve ->
        DoubleArray(CUTOFF_VALUES) { curve.frequency(it) }
    }

    // JSIDPlay2 builder.resid.residfp.SID.kinkedDac
    private fun kinkedDac(input: Int, nonlinearity: Double): Double {
        var value = 0.0
        var weight = 1.0
        val dir = 2 * nonlinearity
        for (bit in 0 until CUTOFF_BITS) {
            if (input and (1 shl bit) != 0) {
                value += weight
            }
            weight *= dir
        }
        return value / (weight / nonlinearity / nonlinearity) * (1 shl CUTOFF_BITS)
    }

    // 6581 constants are the same in reSID 1.0 (filter.cc) and reSIDfp (FilterModelConfig6581.cpp)
    private const val VDDT = 12.18 - 1.31
    private const val VTH = 1.31
    private const val WORKING_POINT = 4.54
    private const val UT = 26.0e-3
    private const val CAPACITOR = 470e-12
    private const val WL_VCR = 9.0
    private const val WL_SNAKE = 1.0 / 115
    private const val DAC_ZERO = 6.65
    private const val DAC_SCALE = 2.63
    private const val MIN_VW = 6.0
    private const val MAX_VW = 10.3
    const val RESID_UCOX = 20e-6

    // R-2R ladder of 6581 cutoff DAC with 2R/R=2.2 and missing termination
    private val DAC_BITS = DoubleArray(CUTOFF_BITS) { setBit ->
        val r = 1.0
        val r2 = 2.2 * r
        var vn = 1.0
        var rn = Double.POSITIVE_INFINITY
        var bit = 0
        while (bit < setBit) {
            rn = if (rn.isInfinite()) r + r2 else r + r2 * rn / (r2 + rn)
            ++bit
        }
        if (rn.isInfinite()) {
            rn = r2
        } else {
            rn = r2 * rn / (r2 + rn)
            vn = vn * rn / r2
        }
        ++bit
        while (bit < CUTOFF_BITS) {
            rn += r
            val i = vn / rn
            rn = r2 * rn / (r2 + rn)
            vn = rn * i
            ++bit
        }
        vn
    }
    private val DAC_SUM = DAC_BITS.sum()

    private fun dacOutput(fc: Int, leakage: Double) = DAC_BITS.indices.sumOf { DAC_BITS[it] * if (fc and (1 shl it) != 0) 1.0 else leakage }

    fun residFpUcox(range: Double) = (1 + 39 * range.coerceIn(0.0, 1.0)) * 1e-6

    fun residFpRange(uCox: Double) = ((uCox * 1e6 - 1) / 39).coerceIn(0.0, 1.0)

    fun residFpDacVoltage(fc: Int, curve: Double) = DAC_ZERO + (1 - curve) + dacOutput(fc, 0.0075) / DAC_SUM * DAC_SCALE

    fun residDacVoltage(fc: Int, bias: Double): Double {
        val dac = floor(dacOutput(fc, 0.01) * ((1 shl CUTOFF_BITS) - 1) + 0.5)
        return DAC_ZERO + bias + dac * DAC_SCALE / (1 shl CUTOFF_BITS)
    }

    /** Small signal integrator frequency at op-amp working point */
    fun integratorFrequency6581(vw: Double, uCox: Double): Double {
        val vgdt = VDDT - WORKING_POINT
        val snake = uCox * WL_SNAKE * vgdt
        val vg = VDDT - sqrt(((VDDT - vw) * (VDDT - vw) + vgdt * vgdt) / 2)
        val u = (vg - VTH - WORKING_POINT) / (2 * UT)
        val vcr = 2 * UT * WL_VCR * uCox * ln1p(exp(u)) / (1 + exp(-u))
        return (snake + vcr) / (2 * PI * CAPACITOR)
    }

    // Measured cutoff to integrator frequency ratios (see filters.cpp)
    private val RESID_6581_CORRECTION = arrayOf(
        300.0 to 0.73, 500.0 to 0.75, 700.0 to 0.66, 1000.0 to 0.6, 2000.0 to 0.61, 8000.0 to 0.625,
        11000.0 to 0.64, 16000.0 to 0.67, 22000.0 to 0.7
    )
    private val RESIDFP_6581_CORRECTION = arrayOf(
        250.0 to 0.71, 400.0 to 0.75, 700.0 to 0.79, 1400.0 to 0.81, 2000.0 to 0.75, 2800.0 to 0.68,
        4000.0 to 0.62, 8000.0 to 0.625, 11000.0 to 0.62, 16000.0 to 0.59, 22000.0 to 0.535, 32000.0 to 0.49
    )
    private val RESIDFP_8580_CORRECTION = arrayOf(
        150.0 to 0.95, 1000.0 to 0.94, 2000.0 to 0.965, 4000.0 to 0.945, 5600.0 to 0.93, 8000.0 to 0.895,
        11000.0 to 0.84, 16000.0 to 0.785, 22000.0 to 0.68, 32000.0 to 0.66
    )

    private fun correct(table: Array<Pair<Double, Double>>, freq: Double): Double {
        if (freq <= table[0].first) {
            return freq * table[0].second
        }
        for (idx in 1 until table.size) {
            val (loFreq, loFactor) = table[idx - 1]
            val (hiFreq, hiFactor) = table[idx]
            if (freq < hiFreq) {
                val pos = ln(freq / loFreq) / ln(hiFreq / loFreq)
                return freq * (loFactor + (hiFactor - loFactor) * pos)
            }
        }
        return freq * table.last().second
    }

    /** Effective cutoff of reSIDfp (or reSID) 6581 */
    fun cutoff6581(residFp: Boolean, vw: Double, uCox: Double) = correct(if (residFp) RESIDFP_6581_CORRECTION else RESID_6581_CORRECTION, integratorFrequency6581(vw, uCox))

    fun dacVoltage6581(residFp: Boolean, cutoff: Double, uCox: Double): Double {
        var lo = MIN_VW
        var hi = MAX_VW
        if (cutoff <= cutoff6581(residFp, lo, uCox)) {
            return lo
        }
        if (cutoff >= cutoff6581(residFp, hi, uCox)) {
            return hi
        }
        repeat(40) {
            val mid = (lo + hi) / 2
            if (cutoff6581(residFp, mid, uCox) < cutoff) {
                lo = mid
            } else {
                hi = mid
            }
        }
        return (lo + hi) / 2
    }

    fun ucoxForBase(baseResistance: Double) = 1 / (baseResistance * WL_SNAKE * (VDDT - WORKING_POINT))

    // 8580 constants are from reSIDfp FilterModelConfig8580.cpp, Filter8580.cpp and Integrator8580.h
    private const val UCOX_8580 = 100e-6
    private const val VTH_8580 = 0.80
    private const val CAPACITOR_8580 = 22e-9
    private const val DAC_WL0 = 0.00615
    private const val WORKING_POINT_8580 = 4.813
    const val RESID_8580_MAX_CUTOFF = 12500.0

    private fun dacWL8580(fc: Int) = if (fc != 0) DAC_WL0 * (fc and (CUTOFF_VALUES - 1)) else DAC_WL0 / 2

    fun residFp8580Cutoff(fc: Int, curve: Double): Double {
        val cp = 1.8 - curve.coerceIn(0.0, 1.0) * 3 / 5
        val vgst = 4.76 * cp - VTH_8580 - WORKING_POINT_8580
        return correct(RESIDFP_8580_CORRECTION, UCOX_8580 * dacWL8580(fc) * vgst / (2 * PI * CAPACITOR_8580))
    }

    // golden section search minimizing logarithmic error
    private fun fit(curve: Curve, low: Double, high: Double, cutoff: (Int, Double) -> Double): Double {
        fun error(param: Double): Double {
            var result = 0.0
            for (fc in 32 until CUTOFF_VALUES step 32) {
                val diff = ln(cutoff(fc, param)) - ln(curve.frequency(fc))
                result += diff * diff
            }
            return result
        }
        val ratio = (sqrt(5.0) - 1) / 2
        var lo = low
        var hi = high
        repeat(50) {
            val x1 = hi - ratio * (hi - lo)
            val x2 = lo + ratio * (hi - lo)
            if (error(x1) < error(x2)) {
                hi = x2
            } else {
                lo = x1
            }
        }
        return (lo + hi) / 2
    }

    fun fitResidBias(curve: Curve) = if (curve is ResidCurve) {
        curve.bias
    } else {
        fit(curve, -2.0, 2.0) { fc, bias -> cutoff6581(false, residDacVoltage(fc, bias), RESID_UCOX) }
    }
}
