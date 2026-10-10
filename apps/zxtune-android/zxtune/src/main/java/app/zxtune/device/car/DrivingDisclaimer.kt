package app.zxtune.device.car

import android.content.Context
import android.telephony.TelephonyManager
import androidx.annotation.StringRes
import app.zxtune.R
import java.util.Locale

/**
 * Selects legal notice about screens visible to the driver depending on the country the device is in.
 *
 * Network country is preferred because the rules depend on where the car is driven, not on the
 * language or SIM origin of the user (e.g. french speaking tourist in Germany).
 */
object DrivingDisclaimer {

    enum class Region(@StringRes val text: Int) {
        FRANCE(R.string.car_disclaimer_region_fr),
        GERMANY(R.string.car_disclaimer_region_de),
        UNITED_KINGDOM(R.string.car_disclaimer_region_gb),
        UNITED_STATES(R.string.car_disclaimer_region_us),
        EUROPEAN_UNION(R.string.car_disclaimer_region_eu),
        OTHER(R.string.car_disclaimer_region_other),
    }

    // Other EU/EEA members and Switzerland, all having national rules derived from the same
    // road safety principles (Vienna convention)
    private val EUROPE = setOf(
        "AT", "BE", "BG", "CY", "CZ", "DK", "EE", "ES", "FI", "GR", "HR", "HU", "IE", "IT",
        "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK", "IS", "LI", "NO", "CH",
    )

    // Overseas departments and collectivities use french road code
    private val FRANCE = setOf(
        "FR", "GP", "MQ", "GF", "RE", "YT", "PM", "BL", "MF", "MC",
    )

    fun regionOf(country: String?): Region = when (country?.trim()?.uppercase(Locale.US)) {
        null, "" -> Region.OTHER
        in FRANCE -> Region.FRANCE
        "DE" -> Region.GERMANY
        "GB", "UK", "GG", "JE", "IM" -> Region.UNITED_KINGDOM
        "US", "PR", "VI", "GU", "AS", "MP" -> Region.UNITED_STATES
        in EUROPE -> Region.EUROPEAN_UNION
        else -> Region.OTHER
    }

    /**
     * @return ISO 3166 alpha-2 code: network (where the device is now), else SIM, else locale
     */
    fun countryOf(ctx: Context, locale: Locale = Locale.getDefault()): String {
        val telephony = runCatching {
            ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }.getOrNull()
        // CDMA networks may report wrong network country, but it's still better than locale
        val candidates = sequence {
            yield(runCatching { telephony?.networkCountryIso }.getOrNull())
            yield(runCatching { telephony?.simCountryIso }.getOrNull())
            yield(locale.country)
        }
        return candidates.firstOrNull { !it.isNullOrBlank() }?.uppercase(Locale.US) ?: ""
    }

    fun getRegion(ctx: Context) = regionOf(countryOf(ctx))

    /**
     * Full text: generic warning, country specific part and responsibility notice
     */
    fun getText(ctx: Context, region: Region = getRegion(ctx)) = listOf(
        ctx.getString(R.string.car_disclaimer_intro),
        ctx.getString(region.text),
        ctx.getString(R.string.car_disclaimer_outro),
    ).joinToString("\n\n")

    /**
     * Mandatory reading time before any button of the notice is available
     */
    const val READING_TIME_SECONDS = 15
}
