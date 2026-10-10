package app.zxtune.ui

import android.app.ActivityManager
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import app.zxtune.MainActivity
import app.zxtune.MainService
import app.zxtune.R
import app.zxtune.device.car.DrivingDisclaimer

/**
 * Legal notice shown before enabling moving pictures in car.
 *
 * Cannot be skipped: dismissing by back/outside touch is disabled and both buttons become available
 * only after [DrivingDisclaimer.READING_TIME_SECONDS], so the user has to stay on the text.
 */
class CarDisclaimerDialog : DialogFragment() {

    private val handler = Handler(Looper.getMainLooper())

    // Absolute deadline survives configuration changes, so rotation does not restart or skip countdown
    private var deadline = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
        deadline = savedInstanceState?.getLong(DEADLINE_KEY)?.takeIf { it != 0L }
            ?: (SystemClock.elapsedRealtime() + DrivingDisclaimer.READING_TIME_SECONDS * 1000L)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(DEADLINE_KEY, deadline)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val country = DrivingDisclaimer.countryOf(ctx)
        val text = DrivingDisclaimer.getText(ctx, DrivingDisclaimer.regionOf(country))
        val message = if (country.isNotEmpty()) {
            text + "\n\n" + getString(R.string.car_disclaimer_country, country)
        } else {
            text
        }
        return AlertDialog.Builder(ctx)
            .setTitle(R.string.car_disclaimer_title)
            .setMessage(message)
            .setPositiveButton(R.string.car_disclaimer_accept) { _, _ -> reply(true) }
            .setNegativeButton(R.string.car_disclaimer_cancel) { _, _ -> reply(false) }
            .create().apply {
                setCanceledOnTouchOutside(false)
                setOnShowListener { tick() }
            }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun tick() {
        val dialog = dialog as? AlertDialog ?: return
        val accept = dialog.getButton(DialogInterface.BUTTON_POSITIVE) ?: return
        val cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE) ?: return
        val left = remainingSeconds(deadline, SystemClock.elapsedRealtime())
        update(accept, cancel, left)
        if (left > 0) {
            handler.postDelayed(::tick, TICK_MS)
        }
    }

    private fun update(accept: Button, cancel: Button, left: Int) {
        val ready = left == 0
        accept.isEnabled = ready
        cancel.isEnabled = ready
        accept.text = if (ready) {
            getString(R.string.car_disclaimer_accept)
        } else {
            getString(R.string.car_disclaimer_wait, left)
        }
    }

    private fun reply(accepted: Boolean) = setFragmentResult(REQUEST_KEY, bundleOf(ACCEPTED_KEY to accepted))

    companion object {
        const val REQUEST_KEY = "car_disclaimer"
        const val ACCEPTED_KEY = "accepted"
        private const val DEADLINE_KEY = "deadline"
        private const val TAG = "car_disclaimer"
        private const val TICK_MS = 250L

        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) {
                CarDisclaimerDialog().show(manager, TAG)
            }
        }

        // rounded up to not enable buttons while '1 s' is still displayed
        internal fun remainingSeconds(deadline: Long, now: Long) = ((deadline - now + 999) / 1000).coerceAtLeast(0).toInt()

        /**
         * Informational version without countdown, available from settings at any time
         */
        fun showInformation(ctx: Context) {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.car_disclaimer_title)
                .setMessage(DrivingDisclaimer.getText(ctx))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        fun askRestart(ctx: Context) {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.car_restart_title)
                .setMessage(R.string.car_restart_message)
                .setPositiveButton(R.string.car_restart_now) { _, _ -> restartApplication(ctx) }
                .setNegativeButton(R.string.car_restart_later, null)
                .show()
        }

        /**
         * Playback service lives in separate process bound by car host, so whole application is
         * restarted: all own processes are killed and main activity is started in a fresh one.
         */
        fun restartApplication(ctx: Context) {
            val app = ctx.applicationContext
            runCatching {
                app.stopService(Intent(app, MainService::class.java))
            }
            val activities = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activities?.runningAppProcesses?.filter {
                it.pid != Process.myPid() && it.processName.startsWith(app.packageName)
            }?.forEach {
                Process.killProcess(it.pid)
            }
            app.startActivity(Intent.makeRestartActivityTask(ComponentName(app, MainActivity::class.java)))
            Runtime.getRuntime().exit(0)
        }
    }
}
