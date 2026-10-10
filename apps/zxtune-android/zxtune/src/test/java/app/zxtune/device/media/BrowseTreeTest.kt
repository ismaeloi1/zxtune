package app.zxtune.device.media

import android.net.Uri
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.utils.MediaConstants
import app.zxtune.R
import app.zxtune.TimeStamp
import app.zxtune.core.Identifier
import app.zxtune.fs.VfsDir
import app.zxtune.fs.VfsExtensions
import app.zxtune.fs.VfsFile
import app.zxtune.fs.VfsObject
import app.zxtune.playback.Item
import app.zxtune.playlist.PlaylistQuery
import app.zxtune.ui.playlist.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class BrowseTreeTest {

    private val entries = listOf(
        makeEntry(1, "Commando", "Rob Hubbard"),
        makeEntry(5, "", ""),
        makeEntry(3, "Monty on the Run", "Rob Hubbard"),
    )

    @Test
    fun root() {
        with(BrowseTree.createRootExtras()) {
            assertTrue(getBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED))
            assertEquals(
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE)
            )
            assertEquals(
                MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                getInt(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE)
            )
        }
        BrowseTree.getRootChildren(RuntimeEnvironment.getApplication()).run {
            assertEquals(2, size)
            with(get(0)) {
                assertEquals(BrowseTree.PLAYLIST_ID, mediaId)
                assertEquals(MediaItem.FLAG_BROWSABLE, flags)
                assertEquals("Playlist", description.title)
                // car hosts require package authority
                assertEquals("android.resource://app.zxtune/drawable/ic_launcher", description.iconUri.toString())
            }
            with(get(1)) {
                assertEquals(BrowseTree.BROWSER_ID, mediaId)
                assertEquals(MediaItem.FLAG_BROWSABLE, flags)
                assertEquals("Browser", description.title)
            }
        }
    }

    @Test
    fun playlist() {
        BrowseTree.getPlaylistChildren(entries).run {
            assertEquals(3, size)
            with(get(0)) {
                assertEquals(PlaylistQuery.uriFor(1).toString(), mediaId)
                assertEquals(MediaItem.FLAG_PLAYABLE, flags)
                assertEquals("Commando", description.title)
                assertEquals("Rob Hubbard", description.subtitle)
            }
            with(get(1)) {
                assertEquals(PlaylistQuery.uriFor(5).toString(), mediaId)
                // fallback to filename
                assertEquals("file.ext", description.title)
                assertNull(description.subtitle)
            }
        }
    }

    @Test
    fun playlistLimit() {
        val huge = (1L..2000L).map { makeEntry(it, "Title $it", "") }
        assertEquals(BrowseTree.MAX_ITEMS, BrowseTree.getPlaylistChildren(huge).size)
    }

    @Test
    fun search() {
        assertEquals(listOf(entries[2]), BrowseTree.search(entries, "monty"))
        assertEquals(listOf(entries[0], entries[2]), BrowseTree.search(entries, "HUBBARD"))
        assertEquals(listOf(entries[1]), BrowseTree.search(entries, "file"))
        assertEquals(emptyList<Entry>(), BrowseTree.search(entries, "Galway"))
    }

    @Test
    fun playableUri() {
        val item = PlaylistQuery.uriFor(3)
        assertEquals(item, BrowseTree.getPlayableUri(item.toString()))
        assertEquals(item, BrowseTree.getPlayableUri(entries[2]))
        // not playable ids
        assertNull(BrowseTree.getPlayableUri(BrowseTree.ROOT_ID))
        assertNull(BrowseTree.getPlayableUri(BrowseTree.PLAYLIST_ID))
        assertNull(BrowseTree.getPlayableUri(PlaylistQuery.ALL.toString()))
        assertNull(BrowseTree.getPlayableUri(PlaylistQuery.STATISTICS.toString()))
        assertNull(BrowseTree.getPlayableUri("file:///sdcard/music/file.mod"))
        assertNull(BrowseTree.getPlayableUri(""))
        assertNull(BrowseTree.getPlayableUri(BrowseTree.BROWSER_ID))
        assertNull(BrowseTree.getPlayableUri(BrowseTree.RECENT_ROOT_ID))
        // vfs objects
        val file = Uri.parse("file:///sdcard/music/file.mod")
        assertEquals(file, BrowseTree.getPlayableUri(BrowseTree.getVfsMediaId(file)))
        assertNull(BrowseTree.getPlayableUri("vfs/"))
        assertNull(BrowseTree.getPlayableUri("vfs/no_scheme"))
        // playlist uris are not accepted as vfs objects
        assertNull(BrowseTree.getVfsUri(BrowseTree.getVfsMediaId(item)))
    }

    @Test
    fun vfs() {
        val ctx = RuntimeEnvironment.getApplication()
        val root = FakeDir(Uri.parse("root:/"), "root")
        val dirs = listOf(FakeDir(Uri.parse("file:///b"), "b"), FakeDir(Uri.parse("file:///a"), "a"))
        val files = listOf(
            FakeFile(Uri.parse("file:///track10.mod"), "track10.mod", "1:00"),
            FakeFile(Uri.parse("file:///track2.mod"), "track2.mod", ""),
        )
        BrowseTree.getVfsChildren(ctx, root, dirs, files).run {
            assertEquals(listOf("a", "b", "track2.mod", "track10.mod"), map { it.description.title })
            with(get(0)) {
                assertEquals(MediaItem.FLAG_BROWSABLE, flags)
                assertEquals(Uri.parse("file:///a"), BrowseTree.getVfsUri(mediaId!!))
                assertEquals("android.resource://app.zxtune/drawable/ic_browser_folder", description.iconUri.toString())
            }
            with(get(3)) {
                assertEquals(MediaItem.FLAG_PLAYABLE, flags)
                assertEquals("1:00", description.subtitle)
                assertNull(description.iconUri)
            }
            assertNull(get(2).description.subtitle)
        }
        val many = (1..1000).map { FakeFile(Uri.parse("file:///$it"), "$it", "") }
        assertEquals(BrowseTree.MAX_ITEMS, BrowseTree.getVfsChildren(ctx, root, emptyList(), many).size)
    }

    @Test
    fun recent() {
        assertEquals(emptyList<MediaItem>(), BrowseTree.getRecentChildren(null))
        assertEquals(emptyList<MediaItem>(), BrowseTree.getRecentChildren(makeItem(Uri.EMPTY)))
        BrowseTree.getRecentChildren(makeItem(PlaylistQuery.uriFor(3))).single().run {
            assertEquals(PlaylistQuery.uriFor(3).toString(), mediaId)
            assertEquals(MediaItem.FLAG_PLAYABLE, flags)
            assertEquals("Title", description.title)
            assertEquals("Author", description.subtitle)
        }
        val file = Uri.parse("file:///sdcard/file.ext")
        BrowseTree.getRecentChildren(makeItem(file)).single().run {
            assertEquals(file, BrowseTree.getPlayableUri(mediaId!!))
        }
    }

    @Test
    fun queue() {
        BrowseTree.getQueue(entries).run {
            assertEquals(3, size)
            assertEquals(listOf(1L, 5L, 3L), map { it.queueId })
            assertEquals("Monty on the Run", get(2).description.title)
            assertEquals(PlaylistQuery.uriFor(3).toString(), get(2).description.mediaId)
        }
        assertEquals(7L, BrowseTree.getQueueId(PlaylistQuery.uriFor(7)))
        assertEquals(
            MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong(),
            BrowseTree.getQueueId(Uri.parse("file:///sdcard/file.ext"))
        )
    }

    private class FakeDir(override val uri: Uri, override val name: String) : VfsDir {
        override val description = ""
        override val parent: VfsObject? = null

        override fun getExtension(id: String): Any? = if (id == VfsExtensions.ICON) R.drawable.ic_browser_folder else null

        override fun enumerate(visitor: VfsDir.Visitor) = Unit
    }

    private class FakeFile(override val uri: Uri, override val name: String, override val description: String) : VfsFile {
        override val parent: VfsObject? = null
        override val size = ""

        override fun getExtension(id: String): Any? = null
    }

    companion object {
        private fun makeItem(id: Uri) = object : Item {
            override val id = id
            override val dataId = Identifier(Uri.parse("file:///sdcard/file.ext"))
            override val title = "Title"
            override val author = "Author"
            override val program = ""
            override val comment = ""
            override val strings = ""
            override val duration = TimeStamp.EMPTY
            override val size = 0L
        }

        private fun makeEntry(id: Long, title: String, author: String) = Entry(
            id,
            Identifier(Uri.parse("file:///sdcard/file.ext")),
            title,
            author,
            TimeStamp.fromSeconds(id)
        )
    }
}
