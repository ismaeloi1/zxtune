package app.zxtune.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VulkanDriverTest {

    @Test
    fun `adrenotools package metadata`() {
        val meta = VulkanDriver.parseMeta(
            """{"schemaVersion":1,"name":"Turnip","description":"Mesa","author":"x","packageVersion":"1",
               "vendor":"Mesa","driverVersion":"Vulkan 1.4","minApi":28,"libraryName":"libvulkan_freedreno.so"}"""
        )!!
        assertEquals("libvulkan_freedreno.so", meta.libraryName)
        assertEquals("Turnip Vulkan 1.4", meta.name)
        assertEquals(28, meta.minApi)
    }

    @Test
    fun `invalid metadata`() {
        assertNull(VulkanDriver.parseMeta("{}"))
        assertNull(VulkanDriver.parseMeta("not json"))
        // library should be placed in driver's directory
        assertNull(VulkanDriver.parseMeta("""{"libraryName":"../libvulkan.so"}"""))
        assertNull(VulkanDriver.parseMeta("""{"libraryName":".."}"""))
        assertEquals("lib.so", VulkanDriver.parseMeta("""{"libraryName":"lib.so"}""")?.name)
    }

    private fun elfHeader(elfClass: Int, machine: Int) = ByteArray(20).apply {
        this[0] = 0x7f
        this[1] = 'E'.code.toByte()
        this[2] = 'L'.code.toByte()
        this[3] = 'F'.code.toByte()
        this[4] = elfClass.toByte()
        this[5] = 1
        this[18] = (machine and 0xff).toByte()
        this[19] = (machine shr 8).toByte()
    }

    @Test
    fun `only arm64 libraries are accepted`() {
        assertTrue(VulkanDriver.isArm64Elf(elfHeader(2, 183)))
        // 32-bit ARM
        assertFalse(VulkanDriver.isArm64Elf(elfHeader(1, 40)))
        // x86_64
        assertFalse(VulkanDriver.isArm64Elf(elfHeader(2, 62)))
        assertFalse(VulkanDriver.isArm64Elf(ByteArray(20)))
        assertFalse(VulkanDriver.isArm64Elf(ByteArray(4)))
    }
}
