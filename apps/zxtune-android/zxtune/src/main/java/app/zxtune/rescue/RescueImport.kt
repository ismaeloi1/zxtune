package app.zxtune.rescue

import android.net.Uri
import java.io.File

class ImportItem(
    val location: String,
    val title: String,
    val author: String,
    val durationMs: Long,
)

class ImportPlan(
    val items: List<ImportItem>,
    // human-readable reasons
    val skipped: List<String>,
)

object RescueImport {
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    /**
     * @param playlistDir filesystem folder of the imported playlist to resolve relative entries
     */
    fun plan(entries: List<ParsedEntry>, paths: LocationPaths, playlistDir: String?): ImportPlan {
        val items = ArrayList<ImportItem>()
        val skipped = ArrayList<String>()
        entries.forEach { e ->
            val location = mapLocation(e, paths, playlistDir)
            if (location == null) {
                skipped.add(e.target ?: e.id ?: e.title)
            } else {
                items.add(ImportItem(location, e.title, e.author, e.durationMs))
            }
        }
        return ImportPlan(items, skipped)
    }

    fun mapLocation(e: ParsedEntry, paths: LocationPaths, playlistDir: String?): String? {
        val target = e.target?.takeIf { it.isNotBlank() }?.let { resolveTarget(it, playlistDir) }
        val id = e.id?.takeIf { it.isNotBlank() }
        if (id != null) {
            if (!SCHEME.containsMatchIn(id)) {
                // plain path in xspf location written by third-party tools
                return resolveTarget(id, playlistDir)?.let { toLocation(it, paths) }
            }
            // Grants for content uris die with the uninstalled app, while plain files stay
            // reachable after granting their folder again
            if (id.startsWith("content:", ignoreCase = true)) {
                val path = target?.takeIf { it.startsWith('/') } ?: paths.resolvePath(id)
                if (path != null) {
                    return paths.toIdentifier(path, Uri.parse(id).encodedFragment)
                }
            }
            return id
        }
        return target?.let { toLocation(it, paths) }
    }

    private fun toLocation(target: String, paths: LocationPaths) = if (target.startsWith('/')) {
        paths.toIdentifier(target)
    } else {
        target
    }

    // @return absolute path or uri
    private fun resolveTarget(target: String, playlistDir: String?): String? = when {
        target.startsWith('/') -> target
        SCHEME.containsMatchIn(target) -> target
        playlistDir != null -> File(playlistDir, target).normalize().path
        else -> null
    }

    private fun File.normalize(): File {
        val parts = ArrayList<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(part)
            }
        }
        return File("/" + parts.joinToString("/"))
    }
}
