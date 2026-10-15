package app.zxtune.rescue

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Process
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import app.zxtune.R
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Makes crashes of any app process visible on the device without adb: the system "keeps stopping"
 * dialog tells nothing, and Java crashes of background processes have no trace in exit reasons.
 */
object CrashReport {
    private const val DIR = "crashes"
    private const val LAST_FILE = "last.txt"
    private const val STATE_PREFS = "crash_report"
    private const val SHOWN_KEY = "shown_until"

    // Share intent is passed via binder with ~1Mb transaction limit
    private const val MAX_TRACE_CHARS = 24 * 1024
    private const val MAX_REPORT_CHARS = 96 * 1024
    private const val MAX_EXITS = 10

    class Report(val text: String, val latest: Long)

    /**
     * Should be called as early as possible in each process
     */
    fun install(ctx: Context) = runCatching {
        val app = ctx.applicationContext ?: ctx
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(app, thread, e) }
            if (previous != null) {
                previous.uncaughtException(thread, e)
            } else {
                // without platform handler process would stay alive in broken state
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }.let { }

    private fun write(ctx: Context, thread: Thread, e: Throwable) {
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            append("Time: ${timestamp(System.currentTimeMillis())}\n")
            append("Process: ${ProcessName.get(ctx)}\n")
            append("Version: ${versionName(ctx)}\n")
            append("Thread: ${thread.name}\n")
            append(trace.take(MAX_TRACE_CHARS))
        }
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        File(dir, LAST_FILE).writeText(text)
    }

    /**
     * @param onlyNew to skip already shown crashes
     * @return null if nothing to report
     */
    fun collect(ctx: Context, onlyNew: Boolean): Report? = runCatching {
        val since = if (onlyNew) shownUntil(ctx) else 0L
        var latest = since
        val parts = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 30) {
            for (info in exits(ctx)) {
                if (info.timestamp > since) {
                    parts.add(describe(info))
                    latest = maxOf(latest, info.timestamp)
                }
            }
        }
        File(File(ctx.filesDir, DIR), LAST_FILE).takeIf { it.isFile && it.lastModified() > since }?.let {
            parts.add("---- last uncaught exception ----\n${it.readText().take(MAX_TRACE_CHARS)}")
            latest = maxOf(latest, it.lastModified())
        }
        if (parts.isEmpty()) {
            null
        } else {
            val header = "ZXTune crash report ${timestamp(System.currentTimeMillis())}\n" +
                "Version: ${versionName(ctx)}\n" +
                "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} " +
                "(SDK ${Build.VERSION.SDK_INT})\n\n"
            Report((header + parts.joinToString("\n\n")).take(MAX_REPORT_CHARS), latest)
        }
    }.getOrElse { Report("Failed to collect crash report: $it", 0L) }

    @RequiresApi(30)
    private fun exits(ctx: Context): List<ApplicationExitInfo> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getHistoricalProcessExitReasons(ctx.packageName, 0, MAX_EXITS).filter {
            it.reason == ApplicationExitInfo.REASON_CRASH ||
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                it.reason == ApplicationExitInfo.REASON_ANR
        }
    }

    @RequiresApi(30)
    private fun describe(info: ApplicationExitInfo) = buildString {
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "CRASH"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            else -> info.reason.toString()
        }
        append("---- ${info.processName} $reason at ${timestamp(info.timestamp)} ----\n")
        append("Description: ${info.description}\n")
        append("Status: ${info.status}, importance: ${info.importance}\n")
        runCatching {
            info.traceInputStream?.use { stream ->
                val raw = stream.readBytes()
                // native crash trace is a tombstone protobuf on API 31+, readable strings carry the frames
                val text = if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    "${raw.size} bytes tombstone, readable strings:\n${printableStrings(raw)}"
                } else {
                    String(raw, Charsets.UTF_8)
                }
                append(text.take(MAX_TRACE_CHARS)).append('\n')
            } ?: append("No trace\n")
        }.onFailure { append("Trace is not available: $it\n") }
    }

    internal fun printableStrings(data: ByteArray, minLength: Int = 4) = buildString {
        val current = StringBuilder()
        fun flush() {
            if (current.length >= minLength) {
                append(current).append('\n')
            }
            current.setLength(0)
        }
        for (b in data) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) {
                current.append(c.toChar())
            } else {
                flush()
            }
        }
        flush()
    }

    private fun stateOf(ctx: Context) = ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    private fun shownUntil(ctx: Context) = runCatching {
        stateOf(ctx).getLong(SHOWN_KEY, 0L)
    }.getOrDefault(0L)

    private fun markShown(ctx: Context, until: Long) = runCatching {
        stateOf(ctx).edit().putLong(SHOWN_KEY, maxOf(until, shownUntil(ctx))).apply()
    }

    /**
     * Shows not yet seen crashes, if any. Never throws.
     */
    fun showIfNew(activity: Activity) = show(activity, onlyNew = true)

    fun show(activity: Activity, onlyNew: Boolean) = runCatching {
        val ctx = activity.applicationContext
        // exit reasons and traces are read via binder and disk
        Thread {
            val report = collect(ctx, onlyNew)
            activity.runOnUiThread {
                if (report != null && !activity.isFinishing) {
                    runCatching { showDialog(activity, report) }
                } else if (report == null && !onlyNew) {
                    runCatching {
                        AlertDialog.Builder(activity).setMessage(R.string.crash_report_none)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                }
            }
        }.apply {
            name = "CrashReport"
            isDaemon = true
        }.start()
    }.let { }

    private fun showDialog(activity: Activity, report: Report) {
        val pad = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            12f,
            activity.resources.displayMetrics
        ).toInt()
        val text = TextView(activity).apply {
            text = report.text
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.crash_report_title)
            .setView(ScrollView(activity).apply { addView(text) })
            .setPositiveButton(R.string.crash_report_share) { _, _ -> share(activity, report.text) }
            .setNeutralButton(R.string.crash_report_copy) { _, _ -> copy(activity, report.text) }
            .setNegativeButton(R.string.crash_report_close, null)
            // dismissed in any way means seen, so the same crash is not shown on each start
            .setOnDismissListener { markShown(activity.applicationContext, report.latest) }
            .show()
    }

    private fun share(activity: Activity, text: String) = runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ZXTune crash report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(intent, null))
    }

    private fun copy(activity: Activity, text: String) = runCatching {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ZXTune crash report", text))
    }

    private fun timestamp(time: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(time))

    @Suppress("DEPRECATION")
    private fun versionName(ctx: Context) = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
}
