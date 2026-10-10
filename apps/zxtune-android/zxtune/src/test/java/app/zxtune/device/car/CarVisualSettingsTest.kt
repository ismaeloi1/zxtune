package app.zxtune.device.car

import android.content.Context
import androidx.core.content.edit
import app.zxtune.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class CarVisualSettingsTest {

    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val prefs = ctx.getSharedPreferences("test", Context.MODE_PRIVATE)

    @Test
    fun defaults() {
        with(CarVisualSettings.read(prefs)) {
            assertFalse(enabled)
            assertEquals(CarVisualSettings.DEFAULT_FPS, fps)
            assertEquals(CarVisualSettings.Mode.OSCILLOSCOPE, mode)
        }
    }

    @Test
    fun preferenceDefaultIsOff() {
        assertEquals("false", findDefaultValue(R.xml.preferences_car, CarVisualSettings.ENABLED_KEY))
        assertEquals(
            ctx.getString(R.string.pref_car_visualization_fps_default),
            CarVisualSettings.DEFAULT_FPS.toString()
        )
    }

    @Test
    fun stored() {
        prefs.edit {
            putBoolean(CarVisualSettings.ENABLED_KEY, true)
            putString(CarVisualSettings.FPS_KEY, "10")
            putString(CarVisualSettings.MODE_KEY, "both")
        }
        with(CarVisualSettings.read(prefs)) {
            assertTrue(enabled)
            assertEquals(10, fps)
            assertEquals(100L, periodMs)
            assertEquals(CarVisualSettings.Mode.BOTH, mode)
        }
    }

    @Test
    fun fpsLimits() {
        assertEquals(CarVisualSettings.DEFAULT_FPS, CarVisualSettings.parseFps(null))
        assertEquals(CarVisualSettings.DEFAULT_FPS, CarVisualSettings.parseFps("abc"))
        assertEquals(CarVisualSettings.MAX_FPS, CarVisualSettings.parseFps("60"))
        assertEquals(CarVisualSettings.MIN_FPS, CarVisualSettings.parseFps(0))
        assertEquals(2, CarVisualSettings.parseFps(2L))
        // every value offered in settings is supported
        ctx.resources.getStringArray(R.array.pref_car_visualization_fps_values).forEach {
            assertEquals(it.toInt(), CarVisualSettings.parseFps(it))
        }
    }

    @Test
    fun modes() {
        ctx.resources.getStringArray(R.array.pref_car_visualization_mode_values).forEach {
            assertEquals(it, CarVisualSettings.Mode.of(it).key)
        }
        assertEquals(CarVisualSettings.Mode.OSCILLOSCOPE, CarVisualSettings.Mode.of("unknown"))
    }

    private fun findDefaultValue(xml: Int, key: String): String? {
        val parser = ctx.resources.getXml(xml)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG &&
                parser.getAttributeValue(ANDROID_NS, "key") == key
            ) {
                return parser.getAttributeValue(ANDROID_NS, "defaultValue")
            }
        }
        return null
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
