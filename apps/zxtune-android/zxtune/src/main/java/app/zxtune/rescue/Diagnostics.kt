package app.zxtune.rescue

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects what is needed to understand a broken installation: own logcat (allowed without
 * permissions for own uid since Android 4.1) and databases' schema versions, since another build
 * installed over this one may leave databases this build cannot open.
 */
object Diagnostics {
    fun write(ctx: Context, output: OutputStream, summary: String) {
        val writer = OutputStreamWriter(output, Charsets.UTF_8)
        writer.write("ZXTune diagnostic log ${timestamp()}\n")
        writer.write(packageInfo(ctx))
        writer.write(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(SDK ${Build.VERSION.SDK_INT})\n"
        )
        writer.write("\n---- rescue summary ----\n$summary\n")
        writer.write("\n---- databases ----\n${databases(ctx)}")
        writer.write("\n---- shared preferences ----\n${sharedPrefs(ctx)}")
        writer.write("\n---- persisted uri permissions ----\n${permissions(ctx)}")
        writer.write("\n---- logcat ----\n")
        writer.flush()
        dumpLogcat(output)
        output.flush()
    }

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

    @Suppress("DEPRECATION")
    private fun packageInfo(ctx: Context) = runCatching {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(ctx.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val installer = runCatching { pm.getInstallerPackageName(ctx.packageName) }.getOrNull()
        "Package: ${ctx.packageName} ${info.versionName} ($code), installer=$installer, " +
            "firstInstall=${Date(info.firstInstallTime)}, lastUpdate=${Date(info.lastUpdateTime)}\n"
    }.getOrElse { "Package: ${ctx.packageName} (${it.message})\n" }

    private fun databases(ctx: Context) = StringBuilder().apply {
        val dir = ctx.getDatabasePath(PlaylistDatabaseReader.DATABASE_NAME).parentFile
        dir?.listFiles()?.sortedBy { it.name }?.forEach { file ->
            append("${file.name} ${file.length()} bytes")
            if (!file.name.contains('-')) {
                runCatching {
                    SQLiteDatabase.openDatabase(
                        file.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                    ).use { append(" user_version=${it.version}") }
                }.onFailure { append(" (${it.javaClass.simpleName}: ${it.message})") }
            }
            append('\n')
        } ?: append("no databases dir\n")
    }.toString()

    private fun sharedPrefs(ctx: Context) = StringBuilder().apply {
        File(ctx.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach {
            append("${it.name} ${it.length()} bytes\n")
        } ?: append("none\n")
    }.toString()

    private fun permissions(ctx: Context) = StringBuilder().apply {
        if (Build.VERSION.SDK_INT >= 19) {
            runCatching {
                ctx.contentResolver.persistedUriPermissions.forEach {
                    append("${it.uri} read=${it.isReadPermission} write=${it.isWritePermission}\n")
                }
            }.onFailure { append("${it.message}\n") }
        }
        if (isEmpty()) {
            append("none\n")
        }
    }.toString()

    private fun dumpLogcat(output: OutputStream) {
        val withBuffers = listOf(
            "logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash"
        )
        val plain = listOf("logcat", "-d", "-v", "threadtime")
        for (cmd in listOf(withBuffers, plain)) {
            val written = runCatching {
                val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val count = process.inputStream.use { it.copyTo(output) }
                process.waitFor()
                count
            }.getOrElse {
                output.write("${cmd.joinToString(" ")} failed: ${it.message}\n".toByteArray())
                0L
            }
            // old systems reject unknown 'crash' buffer, then plain dump is tried
            if (written > 512) {
                return
            }
        }
    }
}
