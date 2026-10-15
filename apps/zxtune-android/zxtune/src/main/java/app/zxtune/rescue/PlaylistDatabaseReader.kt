package app.zxtune.rescue

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

class RescueEntry(
    // kept verbatim as stored by the app (identifier uri including subpath)
    val location: String,
    val title: String = "",
    val author: String = "",
    val durationMs: Long = 0,
    // unknown columns from newer/older schemas, preserved for manual recovery
    val extras: Map<String, String> = emptyMap(),
)

/**
 * Reads playlist database without SQLiteOpenHelper to avoid any schema upgrade/downgrade logic:
 * a database left by another build of the app may have a version this build refuses to open.
 */
object PlaylistDatabaseReader {
    const val DATABASE_NAME = "playlist"

    private val SIDE_FILES = arrayOf("-wal", "-shm", "-journal")

    class Result(
        val entries: List<RescueEntry>,
        val source: String,
        val userVersion: Int?,
        val notes: List<String>,
    )

    fun read(dbFile: File, workDir: File): Result {
        val notes = ArrayList<String>()
        if (!dbFile.exists()) {
            notes.add("${dbFile.absolutePath} does not exist")
        } else {
            runCatching {
                return open(dbFile, SQLiteDatabase.OPEN_READONLY).use { read(it, notes) }
            }.onFailure { notes.add("Direct read failed: ${it.describe()}") }
            // Lock or broken journal of the original files should not affect a private copy
            runCatching {
                val copy = copyWithSideFiles(dbFile, workDir)
                return open(copy, SQLiteDatabase.OPEN_READWRITE).use { db ->
                    read(db, notes).let { Result(it.entries, "copy/${it.source}", it.userVersion, notes) }
                }
            }.onFailure { notes.add("Read of copy failed: ${it.describe()}") }
        }
        return Result(emptyList(), "none", null, notes)
    }

    private fun open(file: File, mode: Int) = SQLiteDatabase.openDatabase(
        file.absolutePath,
        null,
        mode or SQLiteDatabase.NO_LOCALIZED_COLLATORS
    )

    private fun copyWithSideFiles(dbFile: File, workDir: File): File {
        workDir.mkdirs()
        val target = File(workDir, dbFile.name)
        (listOf("") + SIDE_FILES).forEach { suffix ->
            File(workDir, dbFile.name + suffix).delete()
            File(dbFile.path + suffix).takeIf { it.isFile }?.copyTo(File(target.path + suffix))
        }
        return target
    }

    private fun read(db: SQLiteDatabase, notes: MutableList<String>): Result {
        val version = runCatching { db.version }.getOrNull()
        val objects = listObjects(db)
        var fallback: Pair<String, List<RescueEntry>>? = null
        for (query in candidateQueries(objects)) {
            val entries = ArrayList<RescueEntry>()
            // partially read rows of a corrupted table are still worth exporting
            runCatching {
                db.rawQuery(query.second, null).use { cursor -> readRows(cursor, entries) }
            }.onFailure {
                notes.add("${query.first}: ${it.describe()}")
            }
            if (entries.isNotEmpty()) {
                return Result(entries, query.first, version, notes)
            }
            if (fallback == null) {
                fallback = query.first to entries
            }
        }
        return Result(
            fallback?.second.orEmpty(),
            fallback?.first ?: "no table with location",
            version,
            notes
        )
    }

    private fun listObjects(db: SQLiteDatabase) = HashMap<String, List<String>>().apply {
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type IN ('table', 'view')",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)
                runCatching { put(name, columnsOf(db, name)) }
            }
        }
    }

    private fun columnsOf(db: SQLiteDatabase, name: String) = ArrayList<String>().apply {
        db.rawQuery("PRAGMA table_info(${quote(name)})", null).use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                add(cursor.getString(nameIdx))
            }
        }
    }

    // Ordered from the most to the least faithful to the playlist order
    private fun candidateQueries(objects: Map<String, List<String>>) = ArrayList<Pair<String, String>>().apply {
        val playlist = objects["playlist"]
        if (playlist != null && LOCATION in playlist) {
            val order = if ("pos" in playlist) " ORDER BY pos" else ""
            add("playlist" to "SELECT * FROM playlist$order")
        }
        val tracks = objects["tracks"]
        val positions = objects["positions"]
        if (tracks != null && LOCATION in tracks && positions != null &&
            "track_id" in positions && "pos" in positions
        ) {
            add(
                "tracks+positions" to
                    "SELECT tracks.* FROM tracks LEFT JOIN positions " +
                    "ON positions.track_id = tracks._id " +
                    // orphaned tracks go last, NULL sorts first otherwise
                    "ORDER BY positions.pos IS NULL, positions.pos, tracks._id"
            )
        }
        objects.forEach { (name, columns) ->
            if (name != "playlist" && LOCATION in columns) {
                val order = if ("_id" in columns) " ORDER BY _id" else ""
                add(name to "SELECT * FROM ${quote(name)}$order")
            }
        }
    }

    private fun readRows(cursor: Cursor, out: MutableList<RescueEntry>) {
        val names = cursor.columnNames
        while (cursor.moveToNext()) {
            val values = HashMap<String, String>()
            for (idx in names.indices) {
                when (cursor.getType(idx)) {
                    Cursor.FIELD_TYPE_NULL, Cursor.FIELD_TYPE_BLOB -> Unit
                    else -> values[names[idx]] = cursor.getString(idx)
                }
            }
            val location = values.remove(LOCATION)?.takeIf { it.isNotBlank() } ?: continue
            val title = values.remove("title").orEmpty()
            val author = values.remove("author").orEmpty()
            val duration = values.remove("duration")?.toLongOrNull() ?: 0
            values.remove("_id")
            values.remove("pos")
            out.add(RescueEntry(location, title, author, duration, values))
        }
    }

    private fun quote(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

    private const val LOCATION = "location"

    internal fun Throwable.describe() = "${javaClass.simpleName}: $message"
}
