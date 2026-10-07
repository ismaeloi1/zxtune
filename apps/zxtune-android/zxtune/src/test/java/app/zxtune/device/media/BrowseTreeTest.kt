package app.zxtune.device.media

import android.net.Uri
import android.support.v4.media.MediaBrowserCompat.MediaItem
import androidx.media.utils.MediaConstants
import app.zxtune.TimeStamp
import app.zxtune.core.Identifier
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
            assertEquals(1, size)
            with(get(0)) {
                assertEquals(BrowseTree.PLAYLIST_ID, mediaId)
                assertEquals(MediaItem.FLAG_BROWSABLE, flags)
                assertEquals("Playlist", description.title)
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
    }

    companion object {
        private fun makeEntry(id: Long, title: String, author: String) = Entry(
            id,
            Identifier(Uri.parse("file:///sdcard/file.ext")),
            title,
            author,
            TimeStamp.fromSeconds(id)
        )
    }
}
