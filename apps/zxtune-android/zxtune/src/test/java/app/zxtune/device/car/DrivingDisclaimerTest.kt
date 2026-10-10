package app.zxtune.device.car

import android.content.Context
import android.telephony.TelephonyManager
import app.zxtune.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DrivingDisclaimerTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()

    @Test
    fun regions() {
        assertEquals(DrivingDisclaimer.Region.FRANCE, DrivingDisclaimer.regionOf("fr"))
        assertEquals(DrivingDisclaimer.Region.FRANCE, DrivingDisclaimer.regionOf(" FR "))
        // overseas departments
        assertEquals(DrivingDisclaimer.Region.FRANCE, DrivingDisclaimer.regionOf("RE"))
        assertEquals(DrivingDisclaimer.Region.GERMANY, DrivingDisclaimer.regionOf("de"))
        assertEquals(DrivingDisclaimer.Region.UNITED_KINGDOM, DrivingDisclaimer.regionOf("gb"))
        assertEquals(DrivingDisclaimer.Region.UNITED_KINGDOM, DrivingDisclaimer.regionOf("uk"))
        assertEquals(DrivingDisclaimer.Region.UNITED_STATES, DrivingDisclaimer.regionOf("us"))
        assertEquals(DrivingDisclaimer.Region.EUROPEAN_UNION, DrivingDisclaimer.regionOf("it"))
        assertEquals(DrivingDisclaimer.Region.EUROPEAN_UNION, DrivingDisclaimer.regionOf("CH"))
        assertEquals(DrivingDisclaimer.Region.OTHER, DrivingDisclaimer.regionOf("jp"))
        assertEquals(DrivingDisclaimer.Region.OTHER, DrivingDisclaimer.regionOf(""))
        assertEquals(DrivingDisclaimer.Region.OTHER, DrivingDisclaimer.regionOf(null))
    }

    @Test
    fun countryFromNetworkFirst() {
        telephony().run {
            setNetworkCountryIso("de")
            setSimCountryIso("fr")
        }
        assertEquals("DE", DrivingDisclaimer.countryOf(ctx, Locale.US))
        assertEquals(DrivingDisclaimer.Region.GERMANY, DrivingDisclaimer.getRegion(ctx))
    }

    @Test
    fun countryFromSim() {
        telephony().run {
            setNetworkCountryIso("")
            setSimCountryIso("fr")
        }
        assertEquals("FR", DrivingDisclaimer.countryOf(ctx, Locale.US))
    }

    @Test
    fun countryFromLocale() {
        telephony().run {
            setNetworkCountryIso("")
            setSimCountryIso("")
        }
        assertEquals("GB", DrivingDisclaimer.countryOf(ctx, Locale.UK))
        assertEquals("", DrivingDisclaimer.countryOf(ctx, Locale.ENGLISH))
    }

    @Test
    fun text() {
        val text = DrivingDisclaimer.getText(ctx, DrivingDisclaimer.Region.FRANCE)
        assertTrue(text.startsWith(ctx.getString(R.string.car_disclaimer_intro)))
        assertTrue(text.contains("R412-6-2"))
        assertTrue(text.endsWith(ctx.getString(R.string.car_disclaimer_outro)))
        // every region has own text
        assertEquals(
            DrivingDisclaimer.Region.entries.size,
            DrivingDisclaimer.Region.entries.map { ctx.getString(it.text) }.toSet().size
        )
    }

    @Test
    fun readingTime() {
        assertEquals(15, DrivingDisclaimer.READING_TIME_SECONDS)
    }

    private fun telephony() = shadowOf(ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
}
