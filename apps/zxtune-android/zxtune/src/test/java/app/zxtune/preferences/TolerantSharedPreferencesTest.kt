package app.zxtune.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TolerantSharedPreferencesTest {
    private val raw = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("tolerant_test", Context.MODE_PRIVATE)

    @Test
    fun `incompatible values fall back to default and are removed`() {
        raw.edit().clear().putString("int", "12").putInt("string", 34).putLong("bool", 1L)
            .putBoolean("long", true).putInt("float", 1).putInt("set", 2).commit()
        with(TolerantSharedPreferences(raw)) {
            assertEquals(5, getInt("int", 5))
            assertEquals("def", getString("string", "def"))
            assertTrue(getBoolean("bool", true))
            assertEquals(7L, getLong("long", 7L))
            assertEquals(1.5f, getFloat("float", 1.5f))
            assertEquals(setOf("a"), getStringSet("set", mutableSetOf("a")))
        }
        for (key in listOf("int", "string", "bool", "long", "float", "set")) {
            assertFalse(key, raw.contains(key))
        }
    }

    @Test
    fun `compatible values are read as is`() {
        raw.edit().clear().putString("string", "val").putInt("int", 34).putBoolean("bool", true)
            .putLong("long", 56L).commit()
        with(TolerantSharedPreferences(raw)) {
            assertEquals("val", getString("string", null))
            assertEquals(34, getInt("int", 0))
            assertTrue(getBoolean("bool", false))
            assertEquals(56L, getLong("long", 0L))
            assertEquals(0, getInt("missing", 0))
        }
        assertEquals(4, raw.all.size)
    }

    @Test
    fun `unsupported types are skipped in provider listing`() {
        val result = android.os.Bundle()
        copyPref("float", 1.5f, result)
        copyPref("set", setOf("a"), result)
        copyPref("int", 1, result)
        assertEquals(setOf("int"), result.keySet())
    }
}
