package app.zxtune.ui.views

import org.junit.Assert.assertEquals
import org.junit.Test

class ScopeBackendTest {

    @Test
    fun `automatic selection prefers Vulkan`() {
        assertEquals(ScopeBackend.VULKAN, ScopeBackend.select(ScopeBackend.PREF_AUTO, true, false))
        assertEquals(ScopeBackend.VULKAN, ScopeBackend.select(null, true, false))
        assertEquals(ScopeBackend.VULKAN, ScopeBackend.select("unknown", true, false))
    }

    @Test
    fun `OpenGL ES is used without Vulkan support`() {
        for (pref in listOf(ScopeBackend.PREF_AUTO, ScopeBackend.PREF_VULKAN, ScopeBackend.PREF_GLES, null)) {
            assertEquals(ScopeBackend.GLES, ScopeBackend.select(pref, false, false))
        }
    }

    @Test
    fun `OpenGL ES is used after Vulkan failure`() {
        assertEquals(ScopeBackend.GLES, ScopeBackend.select(ScopeBackend.PREF_AUTO, true, true))
        assertEquals(ScopeBackend.GLES, ScopeBackend.select(ScopeBackend.PREF_VULKAN, true, true))
    }

    @Test
    fun `explicit selection`() {
        assertEquals(ScopeBackend.VULKAN, ScopeBackend.select(ScopeBackend.PREF_VULKAN, true, false))
        assertEquals(ScopeBackend.GLES, ScopeBackend.select(ScopeBackend.PREF_GLES, true, false))
    }
}
