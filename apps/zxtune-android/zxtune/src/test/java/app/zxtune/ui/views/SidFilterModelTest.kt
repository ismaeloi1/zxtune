package app.zxtune.ui.views

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reference values are printed by native module/players/external/sid/filters.cpp, so displayed curves match emulation
 */
class SidFilterModelTest {

    private val cutoffs = intArrayOf(0, 512, 1024, 2047)

    private fun check(expected: DoubleArray, curve: DoubleArray) = assertArrayEquals(expected, cutoffs.map { curve[it] }.toDoubleArray(), expected.maxOf { it } * 1e-4)

    private fun settings(
        residFp: Boolean = true,
        preset6581: Int = 0,
        preset8580: Int = 0,
        curve6581: Double = 0.5,
        range6581: Double = 0.5,
        curve8580: Double = 0.5,
        biasMv: Int = 0
    ) = SidFilterModel.Settings(residFp, preset6581, preset8580, curve6581, range6581, curve8580, biasMv)

    @Test
    fun `custom filters`() {
        check(doubleArrayOf(289.606, 2249.981, 8528.547, 15027.569), SidFilterModel.cutoff6581(settings()))
        check(
            doubleArrayOf(646.793, 7431.633, 13381.815, 21780.892),
            SidFilterModel.cutoff6581(settings(curve6581 = 0.2, range6581 = 0.7))
        )
        check(
            doubleArrayOf(275.589, 754.169, 6770.472, 19536.431),
            SidFilterModel.cutoff6581(settings(residFp = false, biasMv = 300))
        )
        check(doubleArrayOf(4.434, 4478.760, 8261.476, 13881.754), SidFilterModel.cutoff8580(settings(curve8580 = 0.3)))
    }

    @Test
    fun `presets`() {
        check(doubleArrayOf(277.353, 306.125, 3279.603, 15889.235), SidFilterModel.cutoff6581(settings(preset6581 = 4)))
        check(
            doubleArrayOf(275.307, 306.125, 3279.603, 17524.133),
            SidFilterModel.cutoff6581(settings(residFp = false, preset6581 = 4))
        )
        check(doubleArrayOf(224.068, 572.209, 4701.103, 13105.137), SidFilterModel.cutoff6581(settings(preset6581 = 22)))
        check(
            doubleArrayOf(275.364, 464.407, 5469.568, 18854.321),
            SidFilterModel.cutoff6581(settings(residFp = false, preset6581 = 22))
        )
        check(doubleArrayOf(224.068, 572.209, 4701.103, 18636.361), SidFilterModel.reference6581(settings(preset6581 = 22))!!)
        assertEquals(0.191402, SidFilterModel.fitResidBias(SidFilterModel.PRESETS_6581.getValue(22)), 1e-5)
        check(doubleArrayOf(3.514, 3585.075, 6820.065, 12009.143), SidFilterModel.cutoff8580(settings(preset8580 = 5)))
    }

    // 6581 cutoff DAC is not monotonic, so curves are compared with references instead
    @Test
    fun `reSIDfp follows presets`() {
        for (preset in 1..31) {
            val settings = settings(preset6581 = preset)
            val emulated = SidFilterModel.cutoff6581(settings)
            val reference = SidFilterModel.reference6581(settings)!!
            for (fc in 0 until SidFilterModel.CUTOFF_VALUES) {
                if (reference[fc] in 400.0..8000.0) {
                    assertEquals("preset=$preset fc=$fc", 1.0, emulated[fc] / reference[fc], 0.05)
                }
            }
        }
    }

    // 1/Q tables of reSID filter.cc set_Q and reSIDfp FilterModelConfig8580 resGain
    @Test
    fun `resonance to inverse Q`() {
        assertEquals(15 / 8.0, SidFilterModel.inverseQ(0, false, true), 1e-9)
        assertEquals(11 / 8.0, SidFilterModel.inverseQ(4, false, false), 1e-9)
        assertEquals(1 / 8.0, SidFilterModel.inverseQ(14, false, true), 1e-9)
        assertEquals(1 / 16.0, SidFilterModel.inverseQ(15, false, true), 1e-9)
        assertEquals(1448 / 1024.0, SidFilterModel.inverseQ(0, true, false), 1e-3)
        assertEquals(1.0, SidFilterModel.inverseQ(4, true, false), 1e-9)
        assertEquals(395 / 1024.0, SidFilterModel.inverseQ(15, true, false), 1e-3)
        assertEquals(1.4, SidFilterModel.inverseQ(0, true, true), 1e-9)
        assertEquals(0.770492, SidFilterModel.inverseQ(7, true, true), 1e-6)
        assertEquals(1.07869 / 2.8, SidFilterModel.inverseQ(15, true, true), 1e-5)
    }

    @Test
    fun `state variable filter response`() {
        val fc = 1000.0
        val invQ = 0.5
        fun db(mode: Int, hz: Double) = SidFilterModel.responseDb(mode, hz, fc, invQ)
        val lp = SidFilterModel.MODE_LOWPASS
        val bp = SidFilterModel.MODE_BANDPASS
        val hp = SidFilterModel.MODE_HIGHPASS
        // passband is unity, peak at cutoff is Q, two poles slope is 12dB per octave
        assertEquals(0.0, db(lp, 10.0), 0.01)
        assertEquals(6.02, db(lp, fc), 0.01)
        assertEquals(-12.0, db(lp, 16000.0) - db(lp, 8000.0), 0.2)
        assertEquals(0.0, db(hp, 100000.0), 0.01)
        assertEquals(6.02, db(hp, fc), 0.01)
        assertEquals(6.02, db(bp, fc), 0.01)
        assertEquals(-20.0, db(bp, 10.0) - db(bp, 100.0), 0.1)
        // LP and HP have the same sign
        assertEquals(SidFilterModel.MIN_DB, db(lp or hp, fc), 0.01)
        assertEquals(0.0, db(lp or hp, 10.0), 0.01)
        // all outputs with Q=1 is allpass
        for (hz in doubleArrayOf(20.0, 500.0, 1000.0, 3000.0, 20000.0)) {
            assertEquals(0.0, SidFilterModel.responseDb(lp or bp or hp, hz, fc, 1.0), 1e-9)
        }
        assertEquals(SidFilterModel.MIN_DB, db(0, fc), 0.01)
    }

    @Test
    fun `oscillator frequency`() {
        // A4 on PAL machine
        assertEquals(440.0, SidFilterModel.oscillatorHz(7493, 985248), 0.1)
    }
}
