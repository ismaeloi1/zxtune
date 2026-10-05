package app.zxtune.preferences

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MT-32 ROM images are copied from user selected folder to private storage,
 * because native code cannot access documents tree. Images are identified by contents on playback.
 */
object Mt32Roms {
    const val PREF_KEY = "zxtune.core.mt32.roms_path"

    // largest known image is CM-32L PCM ROM
    private const val MAX_FILE_SIZE = 1L shl 20

    fun getDir(ctx: Context) = File(ctx.filesDir, "mt32_roms")

    fun countFiles(path: String?) = path?.let { File(it).listFiles()?.count { file -> file.isFile } } ?: 0

    /**
     * @return copied files count
     */
    suspend fun copyFromTree(ctx: Context, tree: Uri): Int = withContext(Dispatchers.IO) {
        val target = getDir(ctx).apply {
            deleteRecursively()
            mkdirs()
        }
        val resolver = ctx.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        var copied = 0
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                val isDir = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                val size = cursor.getLong(3)
                if (isDir || size <= 0 || size > MAX_FILE_SIZE) {
                    continue
                }
                val doc = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                resolver.openInputStream(doc)?.use { input ->
                    File(target, name.replace('/', '_')).outputStream().use { output ->
                        input.copyTo(output)
                    }
                    ++copied
                }
            }
        }
        copied
    }
}
