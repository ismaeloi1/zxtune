package app.zxtune.playback

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.zxtune.core.Identifier
import app.zxtune.core.Module
import app.zxtune.core.Scanner
import app.zxtune.fs.VfsDir
import app.zxtune.fs.VfsExtensions
import app.zxtune.fs.VfsFile
import app.zxtune.fs.VfsObject
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

// Covers folder navigation started from the browser, where playback usually starts in the middle
// of the folder and 'previous' must reach files that were never played
@RunWith(RobolectricTestRunner::class)
class FileIteratorNavigationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `previous reaches files before the started one`() {
        // enumeration order differs from the sorted one to verify the same ordering both ways
        val dir = TestDir(listOf(7, 3, 1, 9, 5, 2, 8, 4, 6, 10).map { "file$it" })
        val analyzer = TestAnalyzer()
        val iter = FileIterator(context, FileIterator.listFolder(dir.file("file5")), analyzer)

        assertEquals("file5", iter.current())
        assertEquals(listOf("file4", "file3", "file2", "file1"), iter.moveAll { prev() })
        // stays at the first one
        assertFalse(iter.prev())
        assertEquals((2..10).map { "file$it" }, iter.moveAll { next() })
        assertFalse(iter.next())
    }

    @Test
    fun `preceding files are analyzed only on demand`() {
        val dir = TestDir((1..20).map { "file$it" })
        val analyzer = TestAnalyzer()
        val iter = FileIterator(context, FileIterator.listFolder(dir.file("file10")), analyzer)

        assertEquals("file10", iter.current())
        assertTrue(iter.prev())
        assertEquals("file9", iter.current())
        assertEquals(
            emptyList<String>(),
            analyzer.analyzed.filter { it in (1..8).map { idx -> "file$idx" } }
        )
    }

    @Test
    fun `previous multitrack file starts from its last track`() {
        val dir = TestDir(listOf("a", "b", "c"))
        val analyzer = TestAnalyzer(tracks = mapOf("b" to 3))
        val iter = FileIterator(context, FileIterator.listFolder(dir.file("c")), analyzer)

        assertEquals("c", iter.current())
        assertEquals(listOf("b#3", "b#2", "b#1", "a"), iter.moveAll { prev() })
        assertEquals(listOf("b#1", "b#2", "b#3", "c"), iter.moveAll { next() })
    }

    @Test
    fun `previous skips unplayable files`() {
        val dir = TestDir(listOf("a", "b", "c", "d"))
        val analyzer = TestAnalyzer(tracks = mapOf("b" to 0), failed = setOf("c"))
        val iter = FileIterator(context, FileIterator.listFolder(dir.file("d")), analyzer)

        assertEquals(listOf("a"), iter.moveAll { prev() })
        assertEquals(listOf("d"), iter.moveAll { next() })
    }

    @Test
    fun `history is preserved when going back and forth`() {
        val dir = TestDir((1..5).map { "file$it" })
        val analyzer = TestAnalyzer()
        val iter = FileIterator(context, FileIterator.listFolder(dir.file("file3")), analyzer)

        assertTrue(iter.next())
        assertEquals("file4", iter.current())
        assertEquals(listOf("file3", "file2", "file1"), iter.moveAll { prev() })
        assertEquals((2..5).map { "file$it" }, iter.moveAll { next() })
    }

    @Test
    fun `memory is released furthest from current position`() {
        val dir = TestDir((1..4).map { "file$it" })
        // only two modules fit the limit at once
        val analyzer = TestAnalyzer(size = 50L shl 20)
        val iter = FileIterator(context, FileIterator.listFolder(dir.file("file4")), analyzer)
        val module = { name: String -> analyzer.modules.getValue(name).first() }

        repeat(3) { assertTrue(iter.prev()) }
        verify(module("file4")).release()
        verify(module("file3")).release()
        verify(module("file2"), never()).release()
        verify(module("file1"), never()).release()
        // released ones are reloaded on demand
        assertEquals("file1", iter.current())
        assertEquals(listOf("file2", "file3", "file4"), iter.moveAll { next() })
    }

    @Test
    fun `feed has no preceding files`() {
        val feed = listOf("x", "y").map { TestFile(it) }
        val dir = TestDir(listOf("a", "b"), feed = feed.iterator())
        val files = FileIterator.listFolder(dir.file("b"))

        assertEquals(emptyList<VfsFile>(), files.preceding)
        assertEquals(listOf("b", "x", "y"), files.following.asSequence().map { it.name }.toList())
    }

    @Test
    fun `unknown start position iterates whole folder`() {
        val dir = TestDir(listOf("b", "a"))
        val files = FileIterator.listFolder(TestFile("z").apply { parent = dir })

        assertEquals(emptyList<VfsFile>(), files.preceding)
        assertEquals(listOf("a", "b"), files.following.asSequence().map { it.name }.toList())
    }
}

// file name with optional track subpath, e.g. "b#2"
private fun FileIterator.current() = item.id.let { id -> id.lastPathSegment + id.fragment.orEmpty() }

private fun FileIterator.moveAll(move: FileIterator.() -> Boolean) = ArrayList<String>().apply {
    while (move()) {
        add(current())
    }
}

private fun uriOf(name: String) = Uri.parse("file:///dir/$name")

private class TestFile(override val name: String) : VfsFile {
    override val uri = uriOf(name)
    override val description = ""
    override var parent: VfsObject? = null
    override val size = ""
    override fun getExtension(id: String): Any? = null
}

private class TestDir(names: List<String>, private val feed: kotlin.collections.Iterator<VfsFile>? = null) : VfsDir {
    private val files = names.map { TestFile(it).also { file -> file.parent = this } }
    override val uri: Uri = Uri.parse("file:///dir")
    override val name = "dir"
    override val description = ""
    override val parent: VfsObject? = null
    override fun getExtension(id: String): Any? = feed.takeIf { id == VfsExtensions.FEED }
    override fun enumerate(visitor: VfsDir.Visitor) = files.forEach(visitor::onFile)

    fun file(name: String) = files.first { it.name == name }
}

// Detects `tracks[name]` modules per file (one by default), each `size` bytes
private class TestAnalyzer(
    private val tracks: Map<String, Int> = emptyMap(),
    private val failed: Set<String> = emptySet(),
    private val size: Long = 0,
) : AsyncScanner.Analyzer {
    val analyzed: MutableList<String> = Collections.synchronizedList(ArrayList())
    val modules: MutableMap<String, MutableList<Module>> = Collections.synchronizedMap(HashMap())

    override fun analyzeFile(file: VfsFile, cb: Scanner.Callback) {
        analyzed.add(file.name)
        if (file.name in failed) {
            cb.onError(Identifier(file.uri), Exception("Unsupported"))
            return
        }
        when (val count = tracks[file.name] ?: 1) {
            1 -> cb.onModule(Identifier(file.uri), makeModule(file.name))

            else -> for (idx in 1..count) {
                cb.onModule(Identifier(file.uri, "#$idx"), makeModule(file.name))
            }
        }
    }

    override fun analyzeIdentifier(id: Identifier, cb: Scanner.Callback) = cb.onModule(id, makeModule(id.dataLocation.lastPathSegment!!))

    private fun makeModule(name: String) = mock<Module> {
        on { getProperty(eq("Size"), any<Long>()) } doReturn size
    }.also {
        synchronized(modules) {
            modules.getOrPut(name) { ArrayList() }.add(it)
        }
    }
}
