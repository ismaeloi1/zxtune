package app.zxtune.ui.views

import android.view.View
import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScopePanelLayoutTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    // portrait fullscreen: camera cutout at the top, rounded corners at the bottom
    @Test
    fun `children are placed inside padding`() {
        val top = View(ctx)
        val bottom = View(ctx)
        val panel = ScopePanelLayout(ctx).apply {
            addView(top)
            addView(bottom)
            setPadding(0, 100, 0, 30)
        }
        panel.measure(MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(2400, MeasureSpec.EXACTLY))
        panel.layout(0, 0, 1080, 2400)
        assertEquals(0, top.left)
        assertEquals(100, top.top)
        assertEquals(1080, top.width)
        assertEquals(1080 * 9 / 16, top.height)
        // dashboard follows oscilloscope, not overlaps it
        assertEquals(0, bottom.left)
        assertEquals(top.bottom, bottom.top)
        assertEquals(1080, bottom.width)
        assertEquals(2400 - 30, bottom.bottom)
    }

    @Test
    fun `side padding of landscape`() {
        val top = View(ctx)
        val bottom = View(ctx)
        val panel = ScopePanelLayout(ctx).apply {
            addView(top)
            addView(bottom)
            setPadding(80, 0, 0, 0)
        }
        panel.measure(MeasureSpec.makeMeasureSpec(2400, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(1080, MeasureSpec.EXACTLY))
        panel.layout(0, 0, 2400, 1080)
        assertEquals(80, top.left)
        assertEquals(80, bottom.left)
        assertEquals(2400 - 80, bottom.width)
    }
}
