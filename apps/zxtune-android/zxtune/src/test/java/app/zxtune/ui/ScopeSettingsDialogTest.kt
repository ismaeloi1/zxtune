package app.zxtune.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.viewpager.widget.ViewPager
import app.zxtune.R
import app.zxtune.preferences.Provider
import java.io.File
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Live settings panel: pages of oscilloscope and emulation settings, preset selection, picture over oscilloscope.
 * Images are stored to directory from ZXTUNE_SCREENSHOTS environment variable for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class ScopeSettingsDialogTest {

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(Provider::class.java, "app.zxtune.preferences")
    }

    private fun show(): Pair<AppCompatActivity, ScopeSettingsDialog> {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(R.style.CustomTheme)
        ScopeSettingsDialog.show(activity.supportFragmentManager)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = activity.supportFragmentManager.findFragmentByTag("scope_settings") as ScopeSettingsDialog
        return activity to dialog
    }

    @Test
    fun `emulation pages are taken from preferences categories`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val titles = ScopeSettingsDialog.pagesOf(ctx).map { it.title }
        assertEquals(ctx.getString(R.string.pref_oscilloscope_title), titles.first())
        assertTrue(titles.toString(), ctx.getString(R.string.pref_zxtune_sid_title) in titles)
        assertTrue(titles.toString(), ctx.getString(R.string.pref_zxtune_aym_title) in titles)
        // not emulation
        assertTrue(titles.toString(), ctx.getString(R.string.pref_main_screen_title) !in titles)
        assertTrue(titles.toString(), ctx.getString(R.string.rescue_title) !in titles)
    }

    @Test
    fun `preset selection sets parameters`() {
        val (_, dialog) = show()
        val page = currentPage(dialog)
        val preset = page.findPreference<ListPreference>(TriggerPresets.PRESET_KEY)!!
        assertEquals(TriggerPresets.DEFAULT, preset.value)
        val width = page.findPreference<SeekBarPreference>(TriggerPresets.PREFIX + "width")!!
        assertEquals(40, width.value)
        preset.callChangeListener("bass")
        assertEquals(100, width.value)
        // manual change
        val edge = page.findPreference<SeekBarPreference>(TriggerPresets.PREFIX + "edge_strength")!!
        edge.callChangeListener(250)
        assertEquals(TriggerPresets.CUSTOM, preset.value)
        edge.callChangeListener(100)
        assertEquals("bass", preset.value)
    }

    private fun currentPage(dialog: ScopeSettingsDialog): PreferenceFragmentCompat {
        val pager = dialog.requireView().findViewById<ViewPager>(R.id.scope_settings_pager)
        val adapter = pager.adapter!!
        return adapter.instantiateItem(pager, pager.currentItem) as PreferenceFragmentCompat
    }

    @Test
    fun render() {
        val (activity, dialog) = show()
        val pager = dialog.requireView().findViewById<ViewPager>(R.id.scope_settings_pager)
        renderOver("display-overlay-scope", dialog)
        val sid = ScopeSettingsDialog.pagesOf(activity).indexOfFirst { it.title == activity.getString(R.string.pref_zxtune_sid_title) }
        pager.setCurrentItem(sid, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(currentPage(dialog).findPreference("zxtune.core.sid.clock"))
        renderOver("display-overlay-sid", dialog)
    }

    // panel's window over picture of oscilloscope (it's drawn on another surface on device)
    private fun renderOver(name: String, dialog: ScopeSettingsDialog) {
        val width = 1080
        val height = 2400
        val panelHeight = (height * 0.6f).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        drawFakeScope(canvas, width, height)
        val decor = dialog.dialog!!.window!!.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(panelHeight, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, width, panelHeight)
        // lists are filled at layout pass
        shadowOf(Looper.getMainLooper()).idle()
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(panelHeight, View.MeasureSpec.EXACTLY)
        )
        decor.layout(0, 0, width, panelHeight)
        canvas.save()
        canvas.translate(0f, (height - panelHeight).toFloat())
        decor.draw(canvas)
        canvas.restore()
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun drawFakeScope(canvas: Canvas, width: Int, height: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 4f
        }
        val rows = 6
        val rowH = height / rows.toFloat()
        for (row in 0 until rows) {
            val cy = rowH * (row + 0.5f)
            var prevY = cy
            for (x in 1 until width step 2) {
                val y = cy - rowH * 0.35f * (if (sin(x / (20.0 + row * 7)) > 0) 1f else -1f) * 0.8f
                canvas.drawLine(x - 2f, prevY, x.toFloat(), y, paint)
                prevY = y
            }
        }
    }
}
