package app.zxtune.rescue

import android.database.sqlite.SQLiteDatabase
import app.zxtune.playlist.xspf.XspfIterator
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RescueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val paths = LocationPaths(
        mapOf("primary" to "/storage/emulated/0", "1234-ABCD" to "/storage/1234-ABCD"),
        nativeRootIds = true
    )

    private val legacyPaths = LocationPaths(mapOf("primary" to "/storage/emulated/0"), false)

    // Same as playlist/Database.java v2
    private fun createDatabase(file: File, version: Int = 2) = SQLiteDatabase.openOrCreateDatabase(file, null).apply {
        execSQL(
            "CREATE TABLE tracks (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "location TEXT NOT NULL, author TEXT, title TEXT, " +
                "duration INTEGER NOT NULL, properties BLOB);"
        )
        execSQL(
            "CREATE TABLE positions (pos INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "track_id INTEGER UNIQUE NOT NULL);"
        )
        execSQL(
            "CREATE VIEW playlist AS SELECT _id, pos, location, author, title, duration, " +
                "properties FROM positions LEFT OUTER JOIN tracks " +
                "ON positions.track_id=tracks._id ORDER BY pos ASC;"
        )
        execSQL(
            "CREATE TRIGGER playlist_insert INSTEAD OF INSERT ON playlist FOR EACH ROW BEGIN " +
                "INSERT INTO tracks(location, author, title, duration, properties) VALUES(" +
                "new.location, new.author, new.title, new.duration, new.properties);" +
                "INSERT INTO positions(track_id) SELECT MAX(_id) FROM tracks; END;"
        )
        this.version = version
    }

    private fun SQLiteDatabase.add(location: String, title: String?, author: String?, ms: Long) = execSQL(
        "INSERT INTO playlist(location, title, author, duration) VALUES(?, ?, ?, ?)",
        arrayOf(location, title, author, ms)
    )

    private val samples = listOf(
        "file://primary/Music/AY/demo%20tune.ay#%232",
        "file:///storage/emulated/0/Music/archive.zip#dir/song.pt3",
        "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fsingle.sid",
        "joshw:/nsf/m/Mega%20Man.7z#Mega%20Man.nsf/%233",
        "file://1234-ABCD/Mods/space.mod",
    )

    private fun fillDatabase(file: File, version: Int = 2) = createDatabase(file, version).use { db ->
        db.add(samples[0], "Demo", "Author A", 123456)
        db.add(samples[1], "Song", null, 1000)
        db.add(samples[2], "Single", "SID guy", 0)
        db.add(samples[3], "", "", 60000)
        db.add(samples[4], "Space", "Modder", 2500)
        // reorder: move last to the first position
        db.execSQL("UPDATE positions SET pos = 0 WHERE track_id = 5")
    }

    @Test
    fun `read database in playlist order`() {
        val file = tmp.newFile("playlist").also { it.delete() }
        fillDatabase(file)
        val result = PlaylistDatabaseReader.read(file, tmp.newFolder("work"))
        assertEquals("playlist", result.source)
        assertEquals(2, result.userVersion)
        assertEquals(
            listOf(samples[4], samples[0], samples[1], samples[2], samples[3]),
            result.entries.map { it.location }
        )
        with(result.entries[1]) {
            assertEquals("Demo", title)
            assertEquals("Author A", author)
            assertEquals(123456L, durationMs)
        }
        assertEquals("", result.entries[2].author)
    }

    @Test
    fun `read unknown schema version and layout`() {
        val file = tmp.newFile("playlist").also { it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE items (_id INTEGER PRIMARY KEY, location TEXT, rating INTEGER)")
            db.execSQL("INSERT INTO items(location, rating) VALUES('file:///a.pt3', 5)")
            db.version = 7
        }
        val result = PlaylistDatabaseReader.read(file, tmp.newFolder("work"))
        assertEquals("items", result.source)
        assertEquals(7, result.userVersion)
        assertEquals("file:///a.pt3", result.entries.single().location)
        assertEquals(mapOf("rating" to "5"), result.entries.single().extras)
    }

    @Test
    fun `missing database is reported`() {
        val result = PlaylistDatabaseReader.read(File(tmp.root, "absent"), tmp.newFolder("work"))
        assertTrue(result.entries.isEmpty())
        assertTrue(result.notes.single().contains("does not exist"))
    }

    @Test
    fun `resolve paths`() {
        assertEquals("/storage/emulated/0/Music/AY/demo tune.ay", paths.resolvePath(samples[0]))
        assertEquals("/storage/emulated/0/Music/archive.zip", paths.resolvePath(samples[1]))
        assertEquals("/storage/emulated/0/Music/single.sid", paths.resolvePath(samples[2]))
        assertNull(paths.resolvePath(samples[3]))
        assertEquals("/storage/1234-ABCD/Mods/space.mod", paths.resolvePath(samples[4]))
        assertEquals(
            "/storage/emulated/0/Download/x.ay",
            paths.resolvePath(
                "content://com.android.providers.downloads.documents/document/" +
                    "raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fx.ay"
            )
        )
        assertEquals(
            "/storage/emulated/0/Music/t.ay",
            paths.resolvePath(
                "content://com.android.externalstorage.documents/tree/primary%3AMusic/" +
                    "document/primary%3AMusic%2Ft.ay"
            )
        )
        assertNull(paths.resolvePath("content://com.android.providers.downloads.documents/document/msf%3A12"))
        assertEquals("../Music/AY/a.ay", paths.relativize("/storage/emulated/0/Music/AY/a.ay", "/storage/emulated/0/Download"))
        assertNull(paths.relativize("/storage/1234-ABCD/Mods/a.mod", "/storage/emulated/0/Download"))
        assertEquals("file://primary/Music/a%20b.ay", paths.toIdentifier("/storage/emulated/0/Music/a b.ay"))
        assertEquals("file://1234-ABCD/x.ay#%231", paths.toIdentifier("/storage/1234-ABCD/x.ay", "%231"))
        assertEquals("file:/storage/emulated/0/x.ay", legacyPaths.toIdentifier("/storage/emulated/0/x.ay"))
    }

    private fun export(writer: (java.io.OutputStream) -> Unit) = ByteArrayOutputStream().also(writer).toByteArray()

    private fun exportEntries(): List<ExportEntry> {
        val file = tmp.newFile("playlist").also { it.delete() }
        fillDatabase(file)
        return PlaylistDatabaseReader.read(file, tmp.newFolder("work")).entries.map {
            val path = paths.resolvePath(it.location)
            ExportEntry(it, path, path?.let { p -> paths.relativize(p, "/storage/emulated/0/Download") })
        }
    }

    @Test
    fun `xspf export contains everything and is understood by app`() {
        val entries = exportEntries()
        val data = export { RescueFormats.writeXspf(it, "backup", entries) }
        val text = String(data, Charsets.UTF_8)
        samples.forEach { assertTrue(it, text.contains("<location>$it</location>")) }
        assertTrue(text.contains(">/storage/emulated/0/Music/AY/demo tune.ay</meta>"))
        assertTrue(text.contains(">../Music/single.sid</meta>"))
        assertTrue(text.contains("<creator>SID guy</creator>"))

        // regular app import
        val app = XspfIterator.parse(data.inputStream())
        assertEquals(listOf(samples[4]) + samples.take(4), app.map { it.location })
        assertEquals("Demo", app[1].title)
        assertEquals(123456L, app[1].duration.toMilliseconds())

        // rescue import
        val parsed = RescueFormats.parse(data.inputStream(), "x.xspf")
        assertEquals("/storage/emulated/0/Music/single.sid", parsed[3].target)
        val plan = RescueImport.plan(parsed, paths, null)
        assertEquals(
            listOf(
                samples[4],
                samples[0],
                samples[1],
                // content uri grants do not survive reinstall
                "file://primary/Music/single.sid",
                samples[3],
            ),
            plan.items.map { it.location }
        )
        assertEquals("Author A", plan.items[1].author)
        assertEquals(1000L, plan.items[2].durationMs)
        assertTrue(plan.skipped.isEmpty())
    }

    @Test
    fun `m3u export and import`() {
        val entries = exportEntries()
        val text = String(export { RescueFormats.writeM3u(it, "backup", entries) }, Charsets.UTF_8)
        assertTrue(text.startsWith("#EXTM3U\n"))
        assertTrue(text.contains("#EXTINF:123,Author A - Demo\n#ZXTUNE-ID:${samples[0]}\n"))
        assertTrue(text.contains("\n/storage/emulated/0/Music/AY/demo tune.ay\n"))
        assertTrue(text.contains("#ZXTUNE-RELATIVE:../Music/archive.zip\n"))
        // no local path, identifier is the only reference
        assertTrue(text.contains("\n${samples[3]}\n"))

        val parsed = RescueFormats.parse(text.byteInputStream(), "x.m3u8")
        assertEquals(5, parsed.size)
        val plan = RescueImport.plan(parsed, paths, null)
        assertEquals(
            listOf(samples[4], samples[0], samples[1], "file://primary/Music/single.sid", samples[3]),
            plan.items.map { it.location }
        )
        assertEquals("Demo", plan.items[1].title)
        assertEquals("Author A", plan.items[1].author)
        assertEquals(123456L, plan.items[1].durationMs)
    }

    @Test
    fun `foreign m3u import`() {
        val text = """
            #EXTM3U
            #EXTINF:65,Some title
            ../Music/a.ay
            /storage/1234-ABCD/b.mod
            http://example.com/stream.mp3
            sub/c.pt3
        """.trimIndent()
        val parsed = RescueFormats.parse(text.byteInputStream(), "list.m3u")
        val plan = RescueImport.plan(parsed, paths, "/storage/emulated/0/Download")
        assertEquals(
            listOf(
                "file://primary/Music/a.ay",
                "file://1234-ABCD/b.mod",
                "http://example.com/stream.mp3",
                "file://primary/Download/sub/c.pt3",
            ),
            plan.items.map { it.location }
        )
        assertEquals("Some title", plan.items[0].title)
        assertEquals(65000L, plan.items[0].durationMs)

        val noDir = RescueImport.plan(parsed, legacyPaths, null)
        assertEquals("file:/storage/1234-ABCD/b.mod", noDir.items[0].location)
        assertEquals(listOf("../Music/a.ay", "sub/c.pt3"), noDir.skipped)
    }
}
