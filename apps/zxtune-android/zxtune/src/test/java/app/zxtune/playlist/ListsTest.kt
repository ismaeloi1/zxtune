package app.zxtune.playlist

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ListsTest {
    private lateinit var db: Database
    private lateinit var lists: Lists

    @Before
    fun setUp() {
        db = Database(ApplicationProvider.getApplicationContext())
        lists = Lists(db.getWritable())
    }

    private fun values(location: String) = ContentValues().apply {
        put("location", location)
        put("title", location)
        put("author", "")
        put("duration", 1000)
    }

    private fun current() = ArrayList<String>().apply {
        db.queryPlaylistItems(arrayOf("location"), null, null, null).use { c ->
            while (c.moveToNext()) add(c.getString(0))
        }
    }

    @Test
    fun `switching keeps contents of both lists`() {
        val first = lists.active()
        assertTrue(first > 0)
        assertTrue(lists.add(first, values("a")))
        assertTrue(lists.add(first, values("b")))
        val second = lists.create("second")
        assertFalse(lists.add(second, values("c")))
        assertEquals(2, lists.all().first { it.id == first }.count)
        assertEquals(1, lists.all().first { it.id == second }.count)

        lists.switchTo(second)
        assertEquals(second, lists.active())
        assertEquals(listOf("c"), current())
        lists.switchTo(first)
        assertEquals(listOf("a", "b"), current())
    }

    @Test
    fun `favorites toggle and deletion of active list`() {
        val fav = lists.favorites()
        assertEquals(fav, lists.favorites())
        assertTrue(lists.toggleFavorite(values("x")))
        assertTrue(lists.isFavorite("x"))
        assertFalse(lists.toggleFavorite(values("x")))
        assertFalse(lists.isFavorite("x"))

        val copy = lists.duplicate(lists.active(), "copy")
        lists.switchTo(copy)
        lists.delete(copy)
        assertTrue(lists.active() != copy)
        assertTrue(lists.all().none { it.id == copy })
    }
}
