package app.zxtune.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import app.zxtune.MainService
import app.zxtune.ui.views.ScopePanelLayout
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Portrait fullscreen with camera cutout at the top: content starts below it, dashboard follows the oscilloscope
 * and keeps the full width
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-port-xxhdpi")
class OscilloscopeActivityInsetsTest {
    @Test
    fun `portrait cutout`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MainService::class.java))
        val activity = Robolectric.buildActivity(OscilloscopeActivity::class.java).setup().get()
        val panel = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0) as ScopePanelLayout
        val cutout = 120
        shadowOf(Looper.getMainLooper()).idle()
        // cutouts can't be emulated by Robolectric, so the padding of a phone with camera hole and rounded corners
        val padding = OscilloscopeActivity.contentPadding(true, intArrayOf(0, cutout, 0, 0), intArrayOf(30, 30, 30, 30))
        assertEquals(listOf(0, cutout, 0, 30), padding)
        assertEquals(listOf(80, 0, 0, 0), OscilloscopeActivity.contentPadding(false, intArrayOf(80, 0, 0, 0), intArrayOf(30, 30, 30, 30)))
        panel.setPadding(padding[0], padding[1], padding[2], padding[3])
        val root = activity.window.decorView
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(panel.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(panel.height, View.MeasureSpec.EXACTLY)
        )
        panel.layout(panel.left, panel.top, panel.right, panel.bottom)
        val scope = panel.getChildAt(0)
        val dashboard = panel.getChildAt(1)
        assertEquals(cutout, scope.top)
        assertEquals(0, scope.left)
        assertEquals(panel.width, scope.width)
        assertEquals(0, dashboard.left)
        assertEquals(panel.width, dashboard.width)
        assertEquals(scope.bottom, dashboard.top)
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(dir).mkdirs()
            File(dir, "fullscreen-portrait-cutout.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
