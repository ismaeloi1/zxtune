package app.zxtune.preferences

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import app.zxtune.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/**
 * Engine settings are passed to native code as is, so defaults should match the ones in core_parameters.h
 */
@RunWith(RobolectricTestRunner::class)
class EnginePreferencesTest {

    private class Entry(val defaultValue: String?, val values: List<String>?)

    private val expectedDefaults = mapOf(
        "zxtune.core.aym.clockrate" to "1750000",
        "zxtune.core.aym.type" to "0",
        "zxtune.core.aym.interpolation" to "2",
        "zxtune.core.aym.layout" to "0",
        "zxtune.core.aym.duty_cycle" to "50",
        "zxtune.core.aym.duty_cycle_mask" to "0",
        "zxtune.core.z80.clockrate" to "3500000",
        "zxtune.core.z80.int_ticks" to "24",
        "zxtune.core.fm.clockrate" to "3500000",
        "zxtune.core.dac.interpolation" to "0",
        "zxtune.core.saa.clockrate" to "8000000",
        "zxtune.core.saa.interpolation" to "1",
        "zxtune.core.sid.engine" to "1",
        "zxtune.core.sid.model" to "0",
        "zxtune.core.sid.model_force" to "0",
        "zxtune.core.sid.clock" to "0",
        "zxtune.core.sid.clock_force" to "0",
        "zxtune.core.sid.interpolation" to "0",
        "zxtune.core.sid.filter" to "1",
        "zxtune.core.sid.digiboost" to "0",
        "zxtune.core.sid.filter_bias" to "0",
        "zxtune.core.sid.filter_6581_curve" to "50",
        "zxtune.core.sid.filter_6581_range" to "50",
        "zxtune.core.sid.filter_8580_curve" to "50",
        "zxtune.core.sid.combined_waveforms" to "0",
        "zxtune.core.mt32.model" to "0",
        "zxtune.core.mt32.voices" to "0",
    )

    @Test
    fun `engine parameters are exposed with native defaults`() {
        val entries = loadPreferences()
        for ((key, default) in expectedDefaults) {
            val entry = entries[key]
            assertTrue("$key is not exposed", entry != null)
            assertEquals(key, default, entry!!.defaultValue)
            entry.values?.let {
                assertTrue("$key default is not in values", it.contains(default))
            }
        }
    }

    private fun loadPreferences(): Map<String, Entry> {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val result = HashMap<String, Entry>()
        resources.getXml(R.xml.preferences).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) {
                    continue
                }
                val key = parser.getAttributeValue(ANDROID_NS, "key") ?: continue
                val default = parser.getAttributeResourceValue(ANDROID_NS, "defaultValue", 0)
                    .takeIf { it != 0 }?.let { resolve(resources, it) }
                val values = parser.getAttributeResourceValue(ANDROID_NS, "entryValues", 0)
                    .takeIf { it != 0 }?.let { resources.getStringArray(it).toList() }
                result[key] = Entry(default, values)
            }
        }
        return result
    }

    private fun resolve(resources: Resources, id: Int) = TypedValue().also { resources.getValue(id, it, true) }.coerceToString()?.toString()
}
