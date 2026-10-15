package app.zxtune.rescue

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Application is created in each process (ui, :service, :rescue...), so per-process init needs a name
 */
object ProcessName {
    const val RESCUE_SUFFIX = ":rescue"

    @Volatile
    private var cached: String? = null

    @JvmStatic
    fun get(ctx: Context): String = cached ?: read(ctx).also { cached = it }

    @JvmStatic
    fun isRescue(ctx: Context) = get(ctx).endsWith(RESCUE_SUFFIX)

    private fun read(ctx: Context) = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            File("/proc/self/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray()
                .toString(Charsets.UTF_8)
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: ctx.packageName
}
