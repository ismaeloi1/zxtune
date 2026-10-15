package app.zxtune.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StartupGuardTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("startup_guard_test", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `safe mode after consecutive unhealthy starts`() {
        // each instance emulates new process
        assertFalse(StartupGuard(prefs).begin())
        assertFalse(StartupGuard(prefs).begin())
        assertTrue(StartupGuard(prefs).begin())
        assertTrue(StartupGuard(prefs).begin())
    }

    @Test
    fun `healthy start resets counter`() {
        assertFalse(StartupGuard(prefs).begin())
        assertFalse(StartupGuard(prefs).begin())
        StartupGuard(prefs).healthy()
        assertFalse(StartupGuard(prefs).begin())
        StartupGuard(prefs).run {
            assertFalse(begin())
            healthy()
        }
        assertFalse(StartupGuard(prefs).begin())
    }

    @Test
    fun `corrupted counter does not break start`() {
        prefs.edit().putString("unhealthy_starts", "x").commit()
        assertFalse(StartupGuard(prefs).begin())
    }
}
