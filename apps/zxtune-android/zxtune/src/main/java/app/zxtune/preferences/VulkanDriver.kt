package app.zxtune.preferences

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * User provided Vulkan driver (e.g. Mesa Turnip for Adreno GPUs) loaded instead of system one via libadrenotools,
 * as Android emulators do. Driver is copied to private storage: libraries can't be loaded from shared storage.
 * Packages are either zip archives with meta.json (adrenotools drivers format) or single library file.
 */
object VulkanDriver {
    const val PATH_KEY = "ui.oscilloscope.vulkan_driver"
    const val ENABLED_KEY = "ui.oscilloscope.vulkan_driver.enabled"

    private const val META_NAME = "meta.json"

    // drivers are about 10-30MiB
    private const val MAX_FILE_SIZE = 128L shl 20

    // see libadrenotools
    private const val MIN_API = Build.VERSION_CODES.P
    private const val HOOK_LIBRARY = "libmain_hook.so"

    class Info(
        /** Directory with library */
        val dir: File,
        val libraryName: String,
        /** Human readable description */
        val name: String,
    )

    class InvalidPackageException(message: String) : Exception(message)

    fun getDir(ctx: Context) = File(ctx.filesDir, "vulkan_driver")

    /**
     * Hooks are shipped as separate libraries for arm64 only and should be extracted from package
     */
    fun isSupported(ctx: Context) = Build.VERSION.SDK_INT >= MIN_API &&
        File(ctx.applicationInfo.nativeLibraryDir, HOOK_LIBRARY).isFile

    fun getHooksDir(ctx: Context): String = ctx.applicationInfo.nativeLibraryDir

    /**
     * @return installed driver or null
     */
    fun getInstalled(ctx: Context): Info? = getDir(ctx).let { dir ->
        val meta = File(dir, META_NAME).takeIf { it.isFile }?.readText()?.let { parseMeta(it) } ?: return null
        meta.takeIf { File(dir, it.libraryName).isFile }?.let { Info(dir, it.libraryName, it.name) }
    }

    class Meta(val libraryName: String, val name: String, val minApi: Int)

    /**
     * @return null if required fields are missing
     */
    fun parseMeta(json: String): Meta? = runCatching {
        val obj = JSONObject(json)
        val library = obj.getString("libraryName").takeIf { isSafeName(it) } ?: return null
        val name = listOf(obj.optString("name"), obj.optString("driverVersion"))
            .filter { it.isNotBlank() }.joinToString(" ").ifEmpty { library }
        Meta(library, name, obj.optInt("minApi", 0))
    }.getOrNull()

    private fun isSafeName(name: String) = name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && name != "." && name != ".."

    /**
     * 64-bit little endian ARM shared object, as the only supported by libadrenotools
     */
    fun isArm64Elf(header: ByteArray) = header.size >= 20 &&
        header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() &&
        header[3] == 'F'.code.toByte() && header[4] == 2.toByte() && header[5] == 1.toByte() &&
        (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8) == EM_AARCH64

    private const val EM_AARCH64 = 183

    /**
     * Replaces installed driver
     * @param displayName name of selected document to detect its type
     */
    suspend fun install(ctx: Context, uri: Uri, displayName: String?): Info = withContext(Dispatchers.IO) {
        val target = getDir(ctx)
        val staging = File(ctx.cacheDir, "vulkan_driver.tmp").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val resolver = ctx.contentResolver
            val meta = resolver.openInputStream(uri)?.use { input ->
                if (displayName?.endsWith(".so", ignoreCase = true) == true) {
                    val name = displayName.substringAfterLast('/')
                    copyLimited(input, File(staging, name))
                    Meta(name, name, 0).also {
                        File(staging, META_NAME).writeText(JSONObject().put("libraryName", name).put("name", name).toString())
                    }
                } else {
                    extractZip(input, staging)
                }
            } ?: throw InvalidPackageException("Cannot open")
            if (meta.minApi > Build.VERSION.SDK_INT) {
                throw InvalidPackageException("Requires API ${meta.minApi}")
            }
            val library = File(staging, meta.libraryName)
            val header = ByteArray(20)
            val read = library.inputStream().use { it.read(header) }
            if (read != header.size || !isArm64Elf(header)) {
                throw InvalidPackageException("Not an arm64 library")
            }
            target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
            }
            Info(target, meta.libraryName, meta.name)
        } finally {
            staging.deleteRecursively()
        }
    }

    fun remove(ctx: Context) = getDir(ctx).deleteRecursively()

    private fun extractZip(input: InputStream, dir: File): Meta {
        var meta: Meta? = null
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // flat packages only, nested paths are ignored to not escape target dir
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || !isSafeName(name)) {
                    continue
                }
                if (name == META_NAME) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    File(dir, META_NAME).writeText(text)
                    meta = parseMeta(text) ?: throw InvalidPackageException("Invalid $META_NAME")
                } else if (name.endsWith(".so")) {
                    copyLimited(zip, File(dir, name))
                }
            }
        }
        return meta?.takeIf { File(dir, it.libraryName).isFile } ?: throw InvalidPackageException("No driver")
    }

    private fun copyLimited(input: InputStream, target: File) = target.outputStream().use { output ->
        val buffer = ByteArray(65536)
        var total = 0L
        while (true) {
            val size = input.read(buffer)
            if (size < 0) {
                break
            }
            total += size
            if (total > MAX_FILE_SIZE) {
                throw InvalidPackageException("Too big")
            }
            output.write(buffer, 0, size)
        }
    }
}
