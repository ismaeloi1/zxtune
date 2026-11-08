/**
 * @file
 * @brief Dialogs as bottom sheets
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.utils

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import app.zxtune.R

/**
 * Floating dialogs are attached to the bottom edge, in reach of thumb, with rounded top corners (see CustomDialog
 * theme). Window gravity is not themeable, so it's set for every dialog fragment when started; standalone dialogs
 * call [apply] themselves. Full screen dialogs and panels (not floating windows) are left as is.
 */
object DialogSheets {
    fun install(app: Application) = app.registerActivityLifecycleCallbacks(ActivityCallbacks)

    fun <T : Dialog> apply(dialog: T): T = dialog.apply {
        val window = window ?: return@apply
        // floating flag is known after decor is made from theme, standalone dialogs are not shown yet
        window.decorView
        if (!window.isFloating) {
            return@apply
        }
        val res = context.resources
        val width = minOf(res.displayMetrics.widthPixels, res.getDimensionPixelSize(R.dimen.lhuna_sheet_max_width))
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setBackgroundDrawableResource(R.drawable.lhuna_sheet_background)
        window.setWindowAnimations(R.style.Animation_Lhuna_Sheet)
    }

    private object ActivityCallbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            (activity as? FragmentActivity)?.supportFragmentManager?.registerFragmentLifecycleCallbacks(FragmentCallbacks, true)
        }

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private object FragmentCallbacks : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
            (f as? DialogFragment)?.dialog?.let { apply(it) }
        }
    }
}
