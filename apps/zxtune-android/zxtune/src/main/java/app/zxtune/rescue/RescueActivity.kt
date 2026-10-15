package app.zxtune.rescue

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import app.zxtune.Features
import app.zxtune.R
import app.zxtune.fs.local.Identifier as LocalIdentifier
import app.zxtune.fs.local.PersistablePermissions
import app.zxtune.playlist.Database
import app.zxtune.playlist.PlaylistQuery
import app.zxtune.preferences.Preferences
import app.zxtune.rescue.PlaylistDatabaseReader.describe
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Last resort playlist backup/restore.
 *
 * Intentionally built on plain framework views, own process and direct database access: it should
 * keep working when the regular UI, playback service or app state are broken (e.g. after another
 * build of the app was installed over this one).
 */
class RescueActivity : Activity() {

    companion object {
        fun createIntent(ctx: Context) = Intent(ctx, RescueActivity::class.java)

        private const val REQ_EXPORT_XSPF = 1
        private const val REQ_EXPORT_M3U = 2
        private const val REQ_EXPORT_SAVED = 3
        private const val REQ_IMPORT = 4
        private const val REQ_LOG = 5
        private const val REQ_GRANT_FOLDER = 6
        private const val REQ_STORAGE_PERMISSION = 7

        // as in device/PersistentStorage.kt and playlist/xspf/XspfStorage.kt
        private const val PERSISTENT_STORAGE_KEY = "persistent_storage"
        private const val PERSISTENT_STORAGE_DEFAULT_DIR = "ZXTune"
        private const val PLAYLISTS_DIR = "Playlists"
        private const val XSPF_EXTENSION = ".xspf"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val paths by lazy { LocationPaths.create(this) }

    private lateinit var status: TextView
    private lateinit var includeSaved: CheckBox
    private lateinit var grantFolder: Button
    private lateinit var grantStorage: Button

    private var dbResult: PlaylistDatabaseReader.Result? = null
    private var savedPlaylists: List<DocumentFile> = emptyList()
    private var uncoveredFolders: List<LocalIdentifier> = emptyList()
    private var exportBaseName = ""
    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.rescue_title)
        setContentView(buildUi())
        savedInstanceState?.getString("log")?.let { log.append(it) }
        savedInstanceState?.getString("base")?.let { exportBaseName = it }
        status.text = log
        refresh()
        if (savedInstanceState == null) {
            CrashReport.showIfNew(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("log", log.toString())
        outState.putString("base", exportBaseName)
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            16f,
            resources.displayMetrics
        ).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun button(text: Int, onClick: () -> Unit) = Button(this).apply {
            setText(text)
            setOnClickListener { onClick() }
            column.addView(this)
        }
        column.addView(TextView(this).apply { setText(R.string.rescue_intro) })
        val hasPicker = Build.VERSION.SDK_INT >= 19
        button(R.string.rescue_export) { startExport() }.isEnabled = hasPicker
        includeSaved = CheckBox(this).apply {
            visibility = View.GONE
            column.addView(this)
        }
        button(R.string.rescue_import) { startImport() }.isEnabled = hasPicker
        grantFolder = button(R.string.rescue_grant_folder) { grantNextFolder() }.apply {
            visibility = View.GONE
        }
        grantStorage = button(R.string.rescue_grant_storage) { requestStoragePermission() }.apply {
            visibility = View.GONE
        }
        button(R.string.rescue_export_log) { startLogExport() }.isEnabled = hasPicker
        button(R.string.rescue_crash_report) { CrashReport.show(this, onlyNew = false) }
        status = TextView(this).apply {
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            setPadding(0, pad, 0, 0)
            column.addView(this)
        }
        if (!hasPicker) {
            report(getString(R.string.rescue_unsupported))
        }
        return ScrollView(this).apply {
            // enforced edge-to-edge on new systems would put content under system bars
            fitsSystemWindows = true
            addView(column)
        }
    }

    private fun report(line: String) {
        log.append(line).append('\n')
        status.text = log
    }

    private fun background(task: () -> String) {
        report(getString(R.string.rescue_working))
        executor.execute {
            val result = runCatching(task).getOrElse { it.describe() }
            runOnUiThread {
                if (!isFinishing) {
                    report(result)
                    refresh()
                }
            }
        }
    }

    private fun refresh() = executor.execute {
        val db = readDatabase()
        val saved = runCatching { findSavedPlaylists() }.getOrDefault(emptyList())
        val uncovered = runCatching { findUncoveredFolders(db.entries) }.getOrDefault(emptyList())
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            val firstRead = dbResult == null
            dbResult = db
            savedPlaylists = saved
            uncoveredFolders = uncovered
            if (firstRead) {
                report(describe(db))
            }
            includeSaved.visibility = if (saved.isEmpty()) View.GONE else View.VISIBLE
            includeSaved.text = getString(R.string.rescue_include_saved, saved.size)
            grantFolder.visibility = if (uncovered.isEmpty()) View.GONE else View.VISIBLE
            uncovered.firstOrNull()?.let {
                grantFolder.text = getString(R.string.rescue_grant_folder, "${it.root}:${it.path}")
            }
            grantStorage.visibility = if (needStoragePermission()) View.VISIBLE else View.GONE
        }
    }

    private fun describe(db: PlaylistDatabaseReader.Result) = buildString {
        append(
            getString(
                R.string.rescue_db_summary,
                db.entries.size,
                db.source,
                db.userVersion?.toString() ?: "?"
            )
        )
        db.notes.forEach { append("\n  ").append(it) }
    }

    private fun readDatabase() = PlaylistDatabaseReader.read(
        getDatabasePath(PlaylistDatabaseReader.DATABASE_NAME),
        File(cacheDir, "rescue")
    )

    // ---- export ----

    private fun startExport() {
        exportBaseName = "zxtune-playlist-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        createDocument(REQ_EXPORT_XSPF, "application/xspf+xml", "$exportBaseName.xspf")
    }

    private fun createDocument(code: Int, mime: String, name: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startPicker(intent, code)
    }

    private fun startPicker(intent: Intent, code: Int) = runCatching {
        @Suppress("DEPRECATION")
        startActivityForResult(intent, code)
    }.onFailure { report(it.describe()) }

    private fun exportPlaylist(target: Uri, write: (java.io.OutputStream, List<ExportEntry>) -> Unit) = background {
        val db = readDatabase()
        val dir = resolveDocumentPath(target)?.substringBeforeLast('/', "")
        val entries = db.entries.map { e ->
            val path = paths.resolvePath(e.location) ?: queryMediaPath(e.location)
            ExportEntry(e, path, dir?.let { d -> path?.let { paths.relativize(it, d) } })
        }
        checkNotNull(contentResolver.openOutputStream(target, "wt")) {
            "cannot open $target"
        }.use { write(it, entries) }
        buildString {
            append(getString(R.string.rescue_exported, entries.size, displayName(target)))
            val unresolved = entries.count { it.path == null }
            if (unresolved != 0) {
                append('\n').append(getString(R.string.rescue_unresolved, unresolved))
            }
        }
    }

    private fun exportSaved(target: Uri) = background {
        var count = 0
        checkNotNull(contentResolver.openOutputStream(target, "wt")).use { out ->
            ZipOutputStream(out).use { zip ->
                savedPlaylists.forEach { doc ->
                    runCatching {
                        contentResolver.openInputStream(doc.uri)?.use { input ->
                            zip.putNextEntry(ZipEntry(doc.name ?: "playlist$count.xspf"))
                            input.copyTo(zip)
                            zip.closeEntry()
                            ++count
                        }
                    }
                }
            }
        }
        getString(R.string.rescue_saved_exported, count, displayName(target))
    }

    private fun findSavedPlaylists(): List<DocumentFile> {
        val location = Preferences.getDefaultSharedPreferences(this)
            .getString(PERSISTENT_STORAGE_KEY, null).orEmpty()
        val root = when {
            location.startsWith("content:") && Build.VERSION.SDK_INT >= 21 ->
                DocumentFile.fromTreeUri(this, Uri.parse(location))

            location.isNotEmpty() -> DocumentFile.fromFile(File(location))

            Features.StorageAccessFramework.isEnabled() -> null

            else ->
                @Suppress("DEPRECATION")
                DocumentFile.fromFile(
                    File(
                        android.os.Environment.getExternalStorageDirectory(),
                        PERSISTENT_STORAGE_DEFAULT_DIR
                    )
                )
        } ?: return emptyList()
        val dir = root.findFile(PLAYLISTS_DIR)?.takeIf { it.isDirectory } ?: return emptyList()
        return dir.listFiles().filter {
            it.isFile && it.name?.endsWith(XSPF_EXTENSION, ignoreCase = true) == true
        }
    }

    // ---- import ----

    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // m3u mime types differ between providers, so filtering is left to the user
            type = "*/*"
        }
        startPicker(intent, REQ_IMPORT)
    }

    private fun importPlaylist(source: Uri) = background {
        val name = displayName(source)
        val entries = checkNotNull(contentResolver.openInputStream(source)) {
            "cannot open $source"
        }.use { RescueFormats.parse(it, name) }
        val dir = resolveDocumentPath(source)?.substringBeforeLast('/', "")
        val plan = RescueImport.plan(entries, paths, dir)
        val existing = queryExistingLocations()
        var added = 0
        var present = 0
        plan.items.forEach { item ->
            if (existing.add(item.location)) {
                insert(item)
                ++added
            } else {
                ++present
            }
        }
        // Main UI observes notifications, not inserts, see playlist/Provider.kt
        contentResolver.notifyChange(PlaylistQuery.ALL, null)
        buildString {
            append(getString(R.string.rescue_imported, added, present, plan.skipped.size))
            plan.skipped.take(10).forEach {
                append('\n').append(getString(R.string.rescue_skipped_item, it))
            }
        }
    }

    private fun queryExistingLocations() = HashSet<String>().apply {
        val column = Database.Tables.Playlist.Fields.location.name
        contentResolver.query(PlaylistQuery.ALL, arrayOf(column), null, null, null)?.use {
            while (it.moveToNext()) {
                it.getString(0)?.let { loc -> add(loc) }
            }
        }
    }

    // Raw values instead of playlist.Item to store location verbatim, Identifier may re-encode it
    private fun insert(item: ImportItem) {
        val values = ContentValues().apply {
            put(Database.Tables.Playlist.Fields.location.name, item.location)
            put(Database.Tables.Playlist.Fields.author.name, item.author)
            put(Database.Tables.Playlist.Fields.title.name, item.title)
            put(Database.Tables.Playlist.Fields.duration.name, item.durationMs)
        }
        contentResolver.insert(PlaylistQuery.ALL, values)
    }

    // ---- access ----

    private fun findUncoveredFolders(entries: List<RescueEntry>): List<LocalIdentifier> = if (Features.StorageAccessFramework.isEnabled()) {
        findUncoveredFoldersSaf(entries)
    } else {
        emptyList()
    }

    @RequiresApi(30)
    private fun findUncoveredFoldersSaf(entries: List<RescueEntry>): List<LocalIdentifier> {
        val permissions = PersistablePermissions(contentResolver)
        val uncovered = LinkedHashMap<String, MutableList<LocalIdentifier>>()
        entries.forEach { e ->
            val dir = localDirOf(e.location) ?: return@forEach
            if (permissions.findAncestor(dir) != null) return@forEach
            val segments = dir.path.split('/')
            // Android 11+ refuses to grant volume root and Download folder itself
            val depth = if (segments.firstOrNull() == "Download") 2 else 1
            val key = dir.root + ":" + segments.take(depth).joinToString("/")
            uncovered.getOrPut(key) { ArrayList() }.add(dir)
        }
        return uncovered.values.map { dirs ->
            val common = dirs.map { it.path.split('/') }.reduce { acc, parts ->
                acc.zip(parts).takeWhile { it.first == it.second }.map { it.first }
            }
            LocalIdentifier(dirs.first().root, common.joinToString("/"))
        }
    }

    @RequiresApi(24)
    private fun localDirOf(location: String): LocalIdentifier? {
        val uri = runCatching { Uri.parse(location) }.getOrNull() ?: return null
        if (uri.scheme != "file") return null
        val (root, path) = if (uri.authority.isNullOrEmpty()) {
            paths.splitPath(uri.path ?: return null) ?: return null
        } else {
            uri.authority!! to uri.path.orEmpty().trimStart('/')
        }
        val parent = path.substringBeforeLast('/', "")
        return LocalIdentifier(root, parent).takeIf { parent.isNotEmpty() }
    }

    private fun grantNextFolder() {
        val folder = uncoveredFolders.firstOrNull() ?: return
        if (Build.VERSION.SDK_INT < 26) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, folder.documentUri)
        }
        startPicker(intent, REQ_GRANT_FOLDER)
    }

    private fun needStoragePermission() = Build.VERSION.SDK_INT in 23 until
        Features.StorageAccessFramework.REQUIRED_SDK &&
        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) !=
        PackageManager.PERMISSION_GRANTED

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQ_STORAGE_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ---- log ----

    private fun startLogExport() = createDocument(
        REQ_LOG,
        "text/plain",
        "zxtune-log-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt"
    )

    private fun exportLog(target: Uri) = background {
        val summary = log.toString()
        checkNotNull(contentResolver.openOutputStream(target, "wt")).use {
            Diagnostics.write(this, it, summary)
        }
        getString(R.string.rescue_log_saved, displayName(target))
    }

    // ---- results ----

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data.takeIf { resultCode == RESULT_OK }
        if (uri == null) {
            report(getString(R.string.rescue_cancelled))
            if (requestCode == REQ_EXPORT_XSPF || requestCode == REQ_EXPORT_M3U) {
                continueExport(requestCode)
            }
            return
        }
        when (requestCode) {
            REQ_EXPORT_XSPF -> {
                exportPlaylist(uri) { out, entries ->
                    RescueFormats.writeXspf(out, exportBaseName, entries)
                }
                continueExport(requestCode)
            }

            REQ_EXPORT_M3U -> {
                exportPlaylist(uri) { out, entries ->
                    RescueFormats.writeM3u(out, exportBaseName, entries)
                }
                continueExport(requestCode)
            }

            REQ_EXPORT_SAVED -> exportSaved(uri)

            REQ_IMPORT -> importPlaylist(uri)

            REQ_LOG -> exportLog(uri)

            REQ_GRANT_FOLDER -> {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    report(getString(R.string.rescue_folder_granted, uri.toString()))
                }.onFailure { report(it.describe()) }
                refresh()
            }
        }
    }

    // single CREATE_DOCUMENT gives access to one file only, so every format is asked separately
    private fun continueExport(finished: Int) {
        when (finished) {
            REQ_EXPORT_XSPF -> {
                report(getString(R.string.rescue_choose_m3u))
                createDocument(REQ_EXPORT_M3U, "audio/x-mpegurl", "$exportBaseName.m3u8")
            }

            REQ_EXPORT_M3U -> if (includeSaved.isChecked && savedPlaylists.isNotEmpty()) {
                createDocument(REQ_EXPORT_SAVED, "application/zip", "$exportBaseName-saved.zip")
            }
        }
    }

    // ---- helpers ----

    private fun displayName(uri: Uri) = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()

    private fun resolveDocumentPath(uri: Uri) = paths.resolvePath(uri.toString()) ?: queryMediaPath(uri.toString())

    // MediaStore still reports paths for own and granted items, other providers just fail
    private fun queryMediaPath(location: String): String? {
        val uri = Uri.parse(location).takeIf { it.scheme == "content" } ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            val column = android.provider.MediaStore.MediaColumns.DATA
            contentResolver.query(uri.buildUpon().fragment(null).build(), arrayOf(column), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()?.takeIf { it.startsWith('/') }
    }
}
