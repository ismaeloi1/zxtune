package app.zxtune.playlist

import app.zxtune.TimeStamp
import app.zxtune.core.Identifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class M3uTest {
    @Test
    fun `round trip keeps locations and tags`() {
        val items = listOf(
            Item(Identifier.parse("file:///sdcard/Music/Commando.sid#2"), "Commando", "Rob Hubbard", TimeStamp.fromSeconds(192)),
            Item(Identifier.parse("modland:/Fasttracker 2/a b.xm"), "Title - with dash", "", TimeStamp.fromSeconds(0)),
        )
        val out = ByteArrayOutputStream()
        M3u.write(out, items)
        val text = out.toString("UTF-8")
        assertEquals(true, text.startsWith("#EXTM3U\n#EXTINF:192,Rob Hubbard - Commando\n"))
        val read = M3u.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(2, read.size)
        assertEquals(items[0].location, read[0].location)
        assertEquals("Commando", read[0].title)
        assertEquals("Rob Hubbard", read[0].author)
        assertEquals(192000L, read[0].duration.toMilliseconds())
        assertEquals(items[1].location, read[1].location)
    }

    @Test
    fun `plain paths of other players`() {
        val read = M3u.read(ByteArrayInputStream("/storage/emulated/0/a.vgz\r\n\r\n# comment\nhttp://x/y.mod\n".toByteArray()))
        assertEquals(listOf("file:///storage/emulated/0/a.vgz", "http://x/y.mod"), read.map { it.location.toString() })
    }
}
