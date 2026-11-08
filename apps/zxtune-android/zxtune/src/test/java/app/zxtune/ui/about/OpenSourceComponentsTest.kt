package app.zxtune.ui.about

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceComponentsTest {
    // every shipped 3rdparty component is credited
    @Test
    fun thirdpartyCoverage() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "3rdparty") }
            .firstOrNull { File(it, "sidid").isDirectory }
            ?: return
        val dirs = root.listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet() -
            OpenSourceComponents.NOT_SHIPPED
        val credited = OpenSourceComponents.ALL.mapNotNull { it.dir }.toSet()
        assertEquals(emptySet<String>(), dirs - credited)
        assertEquals(emptySet<String>(), credited - dirs)
    }

    @Test
    fun format() {
        val sidid = OpenSourceComponents.ALL.first { it.dir == "sidid" }
        assertEquals("github.com/cadaver/sidid", OpenSourceComponentsFormat.link(sidid))
        assertEquals("SIDId", OpenSourceComponentsFormat.shortName(sidid))
        val sidplay = OpenSourceComponents.ALL.first { it.dir == "sidplayfp" }
        assertEquals("libsidplayfp", OpenSourceComponentsFormat.shortName(sidplay))
        OpenSourceComponents.ALL.forEach {
            assertTrue(it.name, it.url.startsWith("https://"))
            assertTrue(it.name, OpenSourceComponentsFormat.title(it.category).isNotEmpty())
        }
    }
}
