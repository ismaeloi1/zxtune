package app.zxtune.rescue

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.getSystemService
import app.zxtune.fs.local.Utils.mountPoint
import java.io.File

/**
 * Maps playlist identifiers to filesystem paths and back.
 *
 * Deliberately does not use VFS roots: they need a working app state (persisted grants,
 * preferences, service) which is exactly what may be broken when this is needed.
 *
 * @param mounts storage root id (as in ExternalStorageProvider documents, e.g. "primary") to
 * its mount point
 * @param nativeRootIds identifiers in `file://<root>/<path>` form are only understood by the
 * SAF-based local VFS, legacy one expects `file:///<absolute path>`
 */
class LocationPaths(
    private val mounts: Map<String, String>,
    private val nativeRootIds: Boolean,
) {
    fun mountOf(root: String) = mounts[root] ?: if (root == PRIMARY_ROOT) {
        DEFAULT_PRIMARY_MOUNT
    } else {
        // ExternalStorageProvider names removable volumes by fs uuid, mounted at /storage/<uuid>
        "/storage/$root"
    }

    /**
     * @return absolute filesystem path of the data file (subpath is not included) or null if
     * location is not a local file
     */
    fun resolvePath(location: String): String? {
        val uri = runCatching { Uri.parse(location) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            null -> uri.path?.takeIf { it.startsWith('/') }

            SCHEME_FILE -> {
                val path = uri.path ?: return null
                val root = uri.authority
                if (root.isNullOrEmpty()) {
                    path
                } else {
                    join(mountOf(root), path.trimStart('/'))
                }
            }

            SCHEME_CONTENT -> documentIdOf(uri)?.let { fromDocumentId(uri.authority, it) }

            else -> null
        }
    }

    // Same document id formats as ExternalStorageProvider and DownloadStorageProvider
    private fun fromDocumentId(authority: String?, id: String): String? = when {
        id.startsWith(RAW_PREFIX) -> id.substring(RAW_PREFIX.length).takeIf { it.startsWith('/') }

        authority != EXTERNAL_STORAGE_AUTHORITY -> null

        id.startsWith(HOME_PREFIX) -> join(
            join(mountOf(PRIMARY_ROOT), "Documents"),
            id.substring(HOME_PREFIX.length)
        )

        else -> {
            val delimiter = id.indexOf(':', 1)
            if (delimiter == -1) {
                mountOf(id)
            } else {
                join(mountOf(id.substring(0, delimiter)), id.substring(delimiter + 1))
            }
        }
    }

    /**
     * @return pair of storage root id and path relative to it
     */
    fun splitPath(path: String): Pair<String, String>? {
        val candidates = mounts.entries.map { it.key to it.value } +
            (PRIMARY_ROOT to DEFAULT_PRIMARY_MOUNT)
        // longest mount point wins to not attribute nested volumes to the outer one
        for ((root, mount) in candidates.sortedByDescending { it.second.length }) {
            val rel = path.relativeTo(mount) ?: continue
            return root to rel
        }
        if (path.startsWith(STORAGE_PREFIX)) {
            val rest = path.substring(STORAGE_PREFIX.length)
            val root = rest.substringBefore('/')
            if (root.isNotEmpty() && root != "emulated" && root != "self") {
                return root to rest.substringAfter('/', "")
            }
        }
        return null
    }

    /**
     * @param encodedFragment subpath inside data file as stored in identifiers (already encoded)
     */
    fun toIdentifier(path: String, encodedFragment: String? = null): String {
        val split = splitPath(path).takeIf { nativeRootIds }
        val base = if (split != null) {
            Uri.Builder().scheme(SCHEME_FILE).authority(split.first).path(split.second).build()
        } else {
            Uri.Builder().scheme(SCHEME_FILE).path(path).build()
        }
        return base.buildUpon().encodedFragment(encodedFragment?.takeIf { it.isNotEmpty() })
            .build().toString()
    }

    /**
     * @return relative path from directory to target if both are on the same storage volume
     */
    fun relativize(target: String, baseDir: String): String? {
        val targetRoot = splitPath(target)?.first
        val baseRoot = splitPath(baseDir)?.first
        if (targetRoot == null || targetRoot != baseRoot) {
            return null
        }
        val targetParts = target.split('/').filter { it.isNotEmpty() }
        val baseParts = baseDir.split('/').filter { it.isNotEmpty() }
        var common = 0
        while (common < targetParts.size && common < baseParts.size &&
            targetParts[common] == baseParts[common]
        ) {
            ++common
        }
        val ups = List(baseParts.size - common) { ".." }
        return (ups + targetParts.drop(common)).joinToString("/")
    }

    companion object {
        const val PRIMARY_ROOT = "primary"
        private const val DEFAULT_PRIMARY_MOUNT = "/storage/emulated/0"
        private const val STORAGE_PREFIX = "/storage/"
        private const val SCHEME_FILE = "file"
        private const val SCHEME_CONTENT = "content"
        private const val RAW_PREFIX = "raw:"
        private const val HOME_PREFIX = "home:"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        fun create(ctx: Context) = LocationPaths(
            detectMounts(ctx),
            Build.VERSION.SDK_INT >= app.zxtune.Features.StorageAccessFramework.REQUIRED_SDK
        )

        private fun detectMounts(ctx: Context) = HashMap<String, String>().apply {
            runCatching {
                @Suppress("DEPRECATION")
                put(PRIMARY_ROOT, Environment.getExternalStorageDirectory().absolutePath)
            }
            if (Build.VERSION.SDK_INT >= 24) {
                runCatching {
                    ctx.getSystemService<StorageManager>()?.storageVolumes?.forEach { vol ->
                        val mount = runCatching { vol.mountPoint() }.getOrNull() ?: return@forEach
                        val root = if (vol.isPrimary) PRIMARY_ROOT else vol.uuid ?: return@forEach
                        put(root, mount.absolutePath)
                    }
                }
            }
        }

        // tree and document uris: content://auth/tree/<treeId>[/document/<docId>]
        fun documentIdOf(uri: Uri): String? {
            val segments = uri.pathSegments
            return when {
                segments.size >= 2 && segments[0] == "document" -> segments[1]
                segments.size >= 4 && segments[0] == "tree" && segments[2] == "document" -> segments[3]
                segments.size == 2 && segments[0] == "tree" -> segments[1]
                else -> null
            }
        }

        private fun join(dir: String, sub: String) = when {
            sub.isEmpty() -> dir
            dir.endsWith('/') -> dir + sub
            else -> "$dir/$sub"
        }

        private fun String.relativeTo(dir: String) = when {
            this == dir -> ""

            startsWith(dir) && getOrNull(dir.length) == File.separatorChar ->
                substring(dir.length + 1)

            else -> null
        }
    }
}
