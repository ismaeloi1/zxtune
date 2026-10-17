package app.zxtune.device.car

import android.graphics.Bitmap
import android.graphics.Color
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.FrameResult
import app.zxtune.playback.Visualizer
import app.zxtune.playback.VoiceGauges
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarVisualRendererTest {

    private class FakeVisualizer(
        private val layout: String,
        private val channels: Int,
        private val voices: Int = 0,
        private val chips: Int = 0,
    ) : Visualizer {
        var scopeRequests = 0
        var layoutRequests = 0

        override fun getSpectrum(levels: ByteArray) = 0

        override fun getScope(data: ShortArray, points: Int, windowMs: Int): Int {
            ++scopeRequests
            for (chan in 0 until channels) {
                for (idx in 0 until points) {
                    data[chan * points + idx] = (8000 * sin(2 * PI * (chan + 1) * idx / points)).toInt().toShort()
                }
            }
            return (LAYOUT_ID shl 16) or channels
        }

        override fun getGauges(data: ByteArray, waveWindowMs: Int): Int {
            fillGauges(data, chips * ChipGauges.SIZE)
            return chips
        }

        override fun getFrame(data: ShortArray, points: Int, windowMs: Int, gauges: ByteArray, gaugesKind: Int, waveWindowMs: Int) = FrameResult.request(this, data, points, windowMs, gauges, gaugesKind, waveWindowMs)

        override fun getVoiceGauges(data: ByteArray, waveWindowMs: Int): Int {
            fillGauges(data, voices * VoiceGauges.SIZE)
            return voices
        }

        override fun getLayout(): String {
            ++layoutRequests
            return layout
        }

        override fun getStatus() = ""

        private fun fillGauges(data: ByteArray, size: Int) {
            for (idx in 0 until minOf(size, data.size)) {
                data[idx] = (idx * 7).toByte()
            }
        }
    }

    private val renderer = CarVisualRenderer()

    @Test
    fun scopeOfMasterChannel() {
        val bitmap = render(FakeVisualizer("", 1), CarVisualSettings.Mode.OSCILLOSCOPE)
        assertEquals(CarVisualRenderer.DEFAULT_SIZE, bitmap.width)
        assertEquals(CarVisualRenderer.DEFAULT_SIZE, bitmap.height)
        assertTrue(litPixels(bitmap) > 100)
    }

    @Test
    fun scopeOfVoices() {
        val src = FakeVisualizer("voices\nAY\tA\tB\tC\nAY\tA\tB\tC", 6)
        val frame = CarVisualFrame()
        frame.capture(src, 40, 10, false)
        frame.capture(src, 40, 10, false)
        assertEquals(6, frame.channels)
        assertEquals(2, frame.layout.groups.size)
        // layout is requested only on change
        assertEquals(1, src.layoutRequests)
        assertTrue(litPixels(renderer.render(frame, CarVisualSettings.Mode.OSCILLOSCOPE, renderer.createBitmap())) > 100)
    }

    @Test
    fun voicesDashboard() {
        val src = FakeVisualizer("voices\nYM2612\t1\t2\t3\t4\t5\t6", 6, voices = 6)
        val frame = CarVisualFrame()
        frame.capture(src, 40, 10, true)
        assertEquals(6, frame.voices)
        assertTrue(litPixels(renderer.render(frame, CarVisualSettings.Mode.DASHBOARD, renderer.createBitmap())) > 100)
        assertTrue(litPixels(renderer.render(frame, CarVisualSettings.Mode.BOTH, renderer.createBitmap())) > 100)
    }

    @Test
    fun chipsDashboard() {
        val src = FakeVisualizer("registers\nSID\tV1\tV2\tV3", 3, chips = 1)
        val frame = CarVisualFrame()
        frame.capture(src, 40, 10, true)
        assertEquals(1, frame.chips)
        assertTrue(litPixels(renderer.render(frame, CarVisualSettings.Mode.DASHBOARD, renderer.createBitmap())) > 100)
    }

    @Test
    fun noData() {
        val bitmap = render(FakeVisualizer("", 0), CarVisualSettings.Mode.BOTH)
        assertEquals(0, litPixels(bitmap))
    }

    @Test
    fun grid() {
        val frame = CarVisualFrame()
        // long chips are split to columns
        frame.capture(FakeVisualizer("voices\nOPL3\t" + (1..18).joinToString("\t"), 18), 40, 10, false)
        with(CarVisualRenderer.Grid.of(frame.layout, 18)) {
            assertEquals(3, cols)
            assertEquals(6, rows)
            assertEquals("OPL3 1", labels[0])
        }
        // less described voices than channels
        with(CarVisualRenderer.Grid.of(frame.layout, 20)) {
            assertEquals(1, cols)
            assertEquals(20, rows)
        }
    }

    private fun render(src: Visualizer, mode: CarVisualSettings.Mode): Bitmap {
        val frame = CarVisualFrame()
        frame.capture(src, 40, 10, mode != CarVisualSettings.Mode.OSCILLOSCOPE)
        return renderer.render(frame, mode, renderer.createBitmap())
    }

    private fun litPixels(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { it != Color.BLACK }
    }

    companion object {
        private const val LAYOUT_ID = 1
    }
}
