package app.zxtune.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.viewpager.widget.ViewPager
import app.zxtune.R
import app.zxtune.preferences.Provider
import java.io.File
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private fun show(emulation: EmulationContext = SID): Pair<AppCompatActivity, ScopeSettingsDialog> {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        activity.setTheme(R.style.CustomTheme)
        ScopeSettingsDialog.show(activity.supportFragmentManager, emulation)
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = activity.supportFragmentManager.findFragmentByTag("scope_settings") as ScopeSettingsDialog
        return activity to dialog
    }

    private val ctx
        get() = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun titles(emulation: EmulationContext) = ScopeSettingsDialog.pagesOf(ctx, emulation).map { it.title }

    @Test
    fun `emulation categories are taken from preferences`() {
        val ids = ScopeSettingsDialog.emulationCategories(ctx).map { it.id }
        assertEquals(listOf("general", "aym", "z80", "fm", "dac", "saa", "mt32", "sid"), ids)
    }

    @Test
    fun `pages are contextual`() {
        val scope = ctx.getString(R.string.scope_settings_tab_scope)
        val trigger = ctx.getString(R.string.pref_oscilloscope_trigger_title)
        val general = ctx.getString(R.string.scope_settings_tab_general)
        val all = ctx.getString(R.string.scope_settings_tab_all)
        assertEquals(listOf(scope, trigger, "SID", general, all), titles(SID))
        assertEquals(listOf(scope, trigger, "AY/YM + Z80", general, all), titles(EmulationContext.detect("AY", emptyList(), "")))
        assertEquals(listOf(scope, trigger, "YM2612 + SN76496", general, all), titles(VGM))
        assertEquals(listOf(scope, trigger, "S-DSP", general, all), titles(SPC))
        // nothing is playing
        assertEquals(listOf(scope, trigger, general, all), titles(EmulationContext.NONE))
        val categories = ScopeSettingsDialog.emulationCategories(ctx)
        val pages = ScopeSettingsDialog.pagesOf(ctx, SID)
        assertEquals(listOf(categories.first { it.id == "sid" }.index), pages[2].categories.toList())
        assertEquals(categories.map { it.index }, pages.last().categories.toList())
        assertTrue(ScopeSettingsDialog.pagesOf(ctx, VGM)[2].categories.isEmpty())
    }

    @Test
    fun `chip page contains only its settings`() {
        val (_, dialog) = show(SID)
        val page = select(dialog, 2)
        assertNotNull(page.findPreference("zxtune.core.sid.clock"))
        assertNull(page.findPreference("zxtune.core.aym.clockrate"))
        assertNull(page.findPreference("zxtune.sound.gain"))
        // engine specific filter settings
        val engine = page.findPreference<ListPreference>(SidEngineBinding.ENGINE_KEY)!!
        val bias = page.findPreference<Preference>("zxtune.core.sid.filter_bias")!!
        val curve = page.findPreference<Preference>("zxtune.core.sid.filter_6581_curve")!!
        assertEquals("1", engine.value)
        assertFalse(bias.isEnabled)
        assertTrue(curve.isEnabled)
        engine.callChangeListener("0")
        assertTrue(bias.isEnabled)
        assertFalse(curve.isEnabled)
        val general = select(dialog, 3)
        assertNotNull(general.findPreference("zxtune.sound.gain"))
        val duration = general.findPreference<Preference>("zxtune.core.plugins.default_duration")!!
        assertTrue(duration.title.toString(), duration.title!!.endsWith(ctx.getString(R.string.scope_settings_next_track)))
        assertFalse(general.findPreference<Preference>("zxtune.sound.gain")!!.title!!.contains(ctx.getString(R.string.scope_settings_next_track)))
    }

    @Test
    fun `filter curve stops live updates with its page`() {
        val (activity, dialog) = show(SID)
        val curve = select(dialog, 2).findPreference<FilterCurvePreference>("ui.sid.filter_curve")!!
        dialog.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
        activity.supportFragmentManager.executePendingTransactions()
        val job = FilterCurvePreference::class.java.getDeclaredField("liveJob").apply { isAccessible = true }.get(curve)
        assertNull(job)
    }

    @Test
    fun `preset selection sets parameters`() {
        val (_, dialog) = show()
        val page = select(dialog, 1)
        val preset = page.findPreference<ListPreference>(TriggerPresets.PRESET_KEY)!!
        assertEquals(TriggerPresets.DEFAULT, preset.value)
        val width = page.findPreference<SeekBarPreference>(TriggerPresets.PREFIX + "width")!!
        assertEquals(60, width.value)
        preset.callChangeListener("bass")
        assertEquals(100, width.value)
        // manual change
        val edge = page.findPreference<SeekBarPreference>(TriggerPresets.PREFIX + "edge_strength")!!
        edge.callChangeListener(250)
        assertEquals(TriggerPresets.CUSTOM, preset.value)
        edge.callChangeListener(50)
        assertEquals("bass", preset.value)
    }

    private fun select(dialog: ScopeSettingsDialog, idx: Int): PreferenceFragmentCompat {
        val pager = dialog.requireView().findViewById<ViewPager>(R.id.scope_settings_pager)
        pager.setCurrentItem(idx, false)
        shadowOf(Looper.getMainLooper()).idle()
        return pager.adapter!!.instantiateItem(pager, idx) as PreferenceFragmentCompat
    }

    @Test
    fun render() {
        for ((name, emulation) in listOf("sid" to SID, "ym2612-psg" to VGM, "spc" to SPC, "ay" to AY)) {
            val (_, dialog) = show(emulation)
            select(dialog, 2)
            renderOver("settings-$name", dialog)
            dialog.dismiss()
            shadowOf(Looper.getMainLooper()).idle()
        }
        val (_, dialog) = show(SID)
        renderOver("settings-scope", dialog)
        select(dialog, 3)
        renderOver("settings-general", dialog)
    }

    private companion object {
        val SID = EmulationContext.detect("SID", listOf("SID 1"), "PAL, MOS6581, reSIDfp")
        val VGM = EmulationContext.detect("VGM", listOf("YM2612", "SN76496"), "YM2612, SN76496, libvgm")
        val SPC = EmulationContext.detect("SPC", listOf("S-DSP"), "S-DSP 32kHz, snes_spc")
        val AY = EmulationContext.detect("AY", listOf("AY-3-8910"), "AY-3-8910 ABC")
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
