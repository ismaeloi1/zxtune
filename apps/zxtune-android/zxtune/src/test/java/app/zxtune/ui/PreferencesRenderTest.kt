package app.zxtune.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import app.zxtune.MainService
import app.zxtune.PreferencesActivity
import app.zxtune.R
import app.zxtune.preferences.Provider
import app.zxtune.ui.utils.DialogSheets
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders preferences screens and dialogs in several locales to check layout of long translations.
 * Images are stored to directory from ZXTUNE_SCREENSHOTS environment variable for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class PreferencesRenderTest {

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(Provider::class.java, "app.zxtune.preferences")
        // filter response preview connects to playback service, there's none in tests
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MainService::class.java))
    }

    @Test
    fun screens() {
        for (locale in LOCALES) {
            RuntimeEnvironment.setQualifiers("$locale-w411dp-h915dp-xxhdpi")
            val activity = Robolectric.buildActivity(PreferencesActivity::class.java).setup().get()
            idle()
            renderWindow("prefs-$locale-top", activity)
            renderList("prefs-$locale-all", currentFragment(activity))
            for (key in listOf("ui.oscilloscope", "ui.car", "zxtune.sound.mixer.3")) {
                val fragment = currentFragment(activity)
                activity.onPreferenceStartFragment(fragment, fragment.findPreference<Preference>(key)!!)
                idle()
                renderList("prefs-$locale-${key.substringAfterLast('.')}", currentFragment(activity))
                activity.supportFragmentManager.popBackStackImmediate()
                idle()
            }
        }
    }

    @Test
    fun dialogs() {
        for (locale in LOCALES) {
            RuntimeEnvironment.setQualifiers("$locale-w411dp-h915dp-xxhdpi")
            val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
            activity.setTheme(R.style.CustomTheme)
            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.car_restart_title)
                .setMessage(R.string.car_restart_message)
                .setPositiveButton(R.string.car_restart_now, null)
                .setNegativeButton(R.string.car_restart_later, null)
                .create()
            DialogSheets.apply(dialog)
            dialog.show()
            idle()
            renderDialog("dialog-$locale-alert", dialog)
            dialog.dismiss()
        }
    }

    private fun currentFragment(activity: PreferencesActivity) = activity.supportFragmentManager.fragments.filterIsInstance<PreferenceFragmentCompat>().last()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun renderWindow(name: String, activity: AppCompatActivity) {
        val decor = activity.window.decorView
        layout(decor, WIDTH, HEIGHT)
        idle()
        layout(decor, WIDTH, HEIGHT)
        save(name, draw(decor, WIDTH, HEIGHT, Color.BLACK))
    }

    // whole list without scrolling
    private fun renderList(name: String, fragment: PreferenceFragmentCompat) {
        val list = fragment.listView
        val parent = list.parent as ViewGroup
        layout(parent, WIDTH, LIST_HEIGHT)
        idle()
        layout(parent, WIDTH, LIST_HEIGHT)
        val used = (0 until list.childCount).maxOfOrNull { list.getChildAt(it).bottom }?.plus(list.paddingBottom) ?: 0
        assertTrue(name, used > 100)
        val bitmap = draw(parent, WIDTH, LIST_HEIGHT, Color.BLACK)
        save(name, Bitmap.createBitmap(bitmap, 0, 0, WIDTH, minOf(LIST_HEIGHT, used + list.top + 16)))
    }

    // floating window placed at its gravity over darkened screen
    private fun renderDialog(name: String, dialog: AlertDialog) {
        val window = dialog.window!!
        val decor = window.decorView
        val attrs = window.attributes
        val width = if (attrs.width == ViewGroup.LayoutParams.MATCH_PARENT) WIDTH else minOf(WIDTH, decor.width.takeIf { it > 0 } ?: (WIDTH * 0.9f).toInt())
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.AT_MOST),
        )
        decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(0x10, 0x10, 0x10))
        canvas.drawColor(0x99000000.toInt())
        val x = (WIDTH - decor.measuredWidth) / 2f
        val y = if (attrs.gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.BOTTOM) {
            (HEIGHT - decor.measuredHeight).toFloat()
        } else {
            (HEIGHT - decor.measuredHeight) / 2f
        }
        canvas.translate(x, y)
        decor.draw(canvas)
        save(name, bitmap)
    }

    private fun layout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun draw(view: View, width: Int, height: Int, background: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
        it.eraseColor(background)
        view.draw(Canvas(it))
    }

    private fun save(name: String, bitmap: Bitmap) {
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 2400
        const val LIST_HEIGHT = 16000
        val LOCALES = listOf("en", "fr", "de", "ru")
    }
}
