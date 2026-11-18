/**
 * @file
 * @brief Named playlists
 */
package app.zxtune.playlist

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction

/**
 * Named playlists (as AIMP, foobar2000, DeaDBeeF). The active one is edited and played in place as the current playlist
 * (tracks and positions tables), others are kept in list_items. Switching stores the current playlist into the active
 * list and loads the selected one, so playback and its iterators work with the single current playlist as before.
 */
internal class Lists(private val db: SQLiteDatabase) {

    data class Info(val id: Long, val name: String, val kind: Int, val count: Int, val active: Boolean)

    init {
        ensureActive()
    }

    fun all(): List<Info> {
        val current = currentCount()
        return ArrayList<Info>().apply {
            db.rawQuery(
                "SELECT _id, name, kind, active, (SELECT COUNT(*) FROM list_items WHERE list_id=lists._id) " +
                    "FROM lists ORDER BY kind DESC, _id ASC",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val active = c.getInt(3) != 0
                    add(Info(c.getLong(0), c.getString(1), c.getInt(2), if (active) current else c.getInt(4), active))
                }
            }
        }
    }

    fun active() = db.rawQuery("SELECT _id FROM lists WHERE active=1", null).use { c ->
        if (c.moveToFirst()) c.getLong(0) else -1L
    }

    fun create(name: String, kind: Int = KIND_REGULAR) = db.insert(TABLE, null, ContentValues().apply {
        put("name", name)
        put("kind", kind)
        put("active", 0)
    })

    fun rename(id: Long, name: String) {
        db.update(TABLE, ContentValues().apply { put("name", name) }, "_id=$id", null)
    }

    fun delete(id: Long) = db.transaction {
        if (id == active()) {
            // the next one becomes active
            val next = all().firstOrNull { it.id != id }?.id ?: create(DEFAULT_NAME)
            switchTo(next)
        }
        delete("list_items", "list_id=$id", null)
        delete(TABLE, "_id=$id", null)
    }

    fun duplicate(id: Long, name: String) = db.transaction {
        storeActive()
        val copy = create(name)
        execSQL(
            "INSERT INTO list_items(list_id, pos, location, author, title, duration, properties) " +
                "SELECT $copy, pos, location, author, title, duration, properties FROM list_items WHERE list_id=$id"
        )
        copy
    }

    fun switchTo(id: Long) = db.transaction {
        if (id == active()) {
            return@transaction
        }
        storeActive()
        delete("positions", null, null)
        delete("tracks", null, null)
        execSQL(
            "INSERT INTO playlist(location, author, title, duration, properties) " +
                "SELECT location, author, title, duration, properties FROM list_items WHERE list_id=$id ORDER BY pos"
        )
        execSQL("UPDATE lists SET active=(_id=$id)")
    }

    // @return true if added to current playlist
    fun add(id: Long, values: ContentValues): Boolean {
        if (id == active()) {
            db.insert(Database.Tables.Playlist.NAME, null, values)
            return true
        }
        db.insert("list_items", null, ContentValues(values).apply {
            put("list_id", id)
            put("pos", nextPosition(id))
        })
        return false
    }

    fun favorites() = db.rawQuery("SELECT _id FROM lists WHERE kind=$KIND_FAVORITES", null).use { c ->
        if (c.moveToFirst()) c.getLong(0) else null
    } ?: create(FAVORITES_NAME, KIND_FAVORITES)

    fun isFavorite(location: String): Boolean {
        val id = favorites()
        val query = if (id == active()) {
            "SELECT 1 FROM tracks WHERE location=? LIMIT 1"
        } else {
            "SELECT 1 FROM list_items WHERE list_id=$id AND location=? LIMIT 1"
        }
        return db.rawQuery(query, arrayOf(location)).use { it.moveToFirst() }
    }

    // @return true if became favorite
    fun toggleFavorite(values: ContentValues): Boolean {
        val location = requireNotNull(values.getAsString("location"))
        val id = favorites()
        if (!isFavorite(location)) {
            add(id, values)
            return true
        }
        if (id == active()) {
            db.delete(Database.Tables.Playlist.NAME, "location=?", arrayOf(location))
        } else {
            db.delete("list_items", "list_id=$id AND location=?", arrayOf(location))
        }
        return false
    }

    private fun storeActive() {
        val id = active()
        db.delete("list_items", "list_id=$id", null)
        db.execSQL(
            "INSERT INTO list_items(list_id, pos, location, author, title, duration, properties) " +
                "SELECT $id, pos, location, author, title, duration, properties FROM playlist"
        )
    }

    private fun ensureActive() {
        if (active() < 0) {
            val first = db.rawQuery("SELECT _id FROM lists WHERE kind=$KIND_REGULAR ORDER BY _id LIMIT 1", null)
                .use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: create(DEFAULT_NAME)
            // current playlist content becomes the content of this list
            db.execSQL("UPDATE lists SET active=(_id=$first)")
        }
    }

    private fun currentCount() = db.rawQuery("SELECT COUNT(*) FROM positions", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    private fun nextPosition(id: Long) = db.rawQuery("SELECT IFNULL(MAX(pos), 0) + 1 FROM list_items WHERE list_id=$id", null).use { c ->
        if (c.moveToFirst()) c.getLong(0) else 1L
    }

    companion object {
        private const val TABLE = "lists"
        const val KIND_REGULAR = 0
        const val KIND_FAVORITES = 1

        // names are localized by UI for these kinds/defaults
        const val DEFAULT_NAME = "Playlist"
        const val FAVORITES_NAME = "Favorites"

        fun create(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS lists (_id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
                    "kind INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 0);"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS list_items (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "list_id INTEGER NOT NULL, pos INTEGER NOT NULL, location TEXT NOT NULL, author TEXT, title TEXT, " +
                    "duration INTEGER NOT NULL, properties BLOB);"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS list_items_list ON list_items(list_id, pos);")
        }
    }
}
