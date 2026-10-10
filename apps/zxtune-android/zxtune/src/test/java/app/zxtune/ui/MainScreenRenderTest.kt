package app.zxtune.ui

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import app.zxtune.MainActivity
import app.zxtune.MainService
import app.zxtune.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders main screen to check compact header: visualizer controls are in toolbar, no author line by default.
 * Images are stored in directory from ZXTUNE_SCREENSHOTS environment variable.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class MainScreenRenderTest {

    @Test
    fun nowPlaying() {
        // no playback service in tests
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).declareComponentUnbindable(ComponentName(app, MainService::class.java))
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        val toolbar = activity.findViewById<Toolbar>(R.id.main_top_panel)
        assertNotNull(toolbar.menu.findItem(R.id.action_visualizer_mode))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.brief_subtitle).visibility)
        val visualizer = activity.findViewById<View>(R.id.visualizer)
        // visualizer starts right below toolbar
        assertTrue(visualizer.top - toolbar.bottom <= 0)
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            File(dir).mkdirs()
            File(dir, "main.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
