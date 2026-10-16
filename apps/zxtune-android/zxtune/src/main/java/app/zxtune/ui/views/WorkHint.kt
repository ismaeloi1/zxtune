/**
 * @file
 * @brief Android Dynamic Performance Framework hints for periodic work
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import androidx.annotation.RequiresApi
import app.zxtune.Logger

private val LOG = Logger("WorkHint")

/**
 * Reports duration of each frame's work done on calling thread versus frame interval. CPU governor reacts to
 * load history, so short periodic work may keep cores at too high frequencies; with hints frequency is
 * lowered as soon as work fits into target duration (API 31+, nothing on older systems).
 * Should be used from the single thread.
 */
internal class WorkHint(context: Context) {
    private val appContext = context.applicationContext
    private var session: Any? = null
    private var isFailed = false
    private var target = 0L

    fun report(targetNanos: Long, actualNanos: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isFailed && targetNanos > 0) {
            runCatching {
                reportImpl(targetNanos, actualNanos)
            }.onFailure {
                LOG.w(it) { "Disabled" }
                isFailed = true
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun reportImpl(targetNanos: Long, actualNanos: Long) {
        val current = session as PerformanceHintManager.Session? ?: createSession(targetNanos)?.also {
            session = it
            target = targetNanos
        } ?: run {
            isFailed = true
            return
        }
        if (target != targetNanos) {
            current.updateTargetWorkDuration(targetNanos)
            target = targetNanos
        }
        current.reportActualWorkDuration(actualNanos.coerceAtLeast(1))
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createSession(targetNanos: Long) = appContext.getSystemService(PerformanceHintManager::class.java)
        ?.createHintSession(intArrayOf(Process.myTid()), targetNanos).also {
            LOG.d { "Session for ${Process.myTid()}: $it" }
        }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (session as PerformanceHintManager.Session?)?.close()
        }
        session = null
    }
}
