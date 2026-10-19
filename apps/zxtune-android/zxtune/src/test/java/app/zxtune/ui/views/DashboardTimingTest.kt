package app.zxtune.ui.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Picture
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.zxtune.playback.ChipGauges
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoicesLayout
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Per frame cost of dashboard at phone size (1080x1000 px card area, as on 6.7" 120Hz phones). Software rasterization
 * is an upper bound of RenderThread work, recording into [Picture] approximates UI thread work of hardware rendering.
 * Timings are printed for comparison between changes, limits only catch gross regressions on slow CI hosts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class DashboardTimingTest {

    private val width = 1080
    private val height = 1000

    private class Timing(val record: Double, val raster: Double)

    private fun measure(name: String, view: SidDashboardView, update: (Int) -> Unit): Timing {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val picture = Picture()
        // warm up caches and JIT
        for (frame in 0 until WARMUP) {
            update(frame)
            view.draw(canvas)
            view.draw(picture.beginRecording(width, height))
            picture.endRecording()
        }
        var record = 0L
        var raster = 0L
        for (frame in 0 until FRAMES) {
            update(WARMUP + frame)
            val start = System.nanoTime()
            view.draw(picture.beginRecording(width, height))
            picture.endRecording()
            val mid = System.nanoTime()
            bitmap.eraseColor(0)
            view.draw(canvas)
            raster += System.nanoTime() - mid
            record += mid - start
        }
        val profiler = ProfilingCanvas(bitmap)
        for (frame in 0 until 20) {
            update(frame)
            view.draw(profiler)
        }
        println("dashboard $name raster by calls: ${profiler.report(20)}")
        // view's own work without drawing
        val noop = ProfilingCanvas(bitmap, draw = false)
        var logic = 0L
        for (frame in 0 until FRAMES) {
            update(frame)
            val start = System.nanoTime()
            view.draw(noop)
            logic += System.nanoTime() - start
        }
        println(String.format(java.util.Locale.US, "dashboard %s: logic %.3f ms", name, logic / 1e6 / FRAMES))
        val result = Timing(record / 1e6 / FRAMES, raster / 1e6 / FRAMES)
        println(String.format(java.util.Locale.US, "dashboard %s: record %.3f ms, raster %.3f ms", name, result.record, result.raster))
        return result
    }

    // raster cost by drawing calls
    private class ProfilingCanvas(bitmap: Bitmap, private val draw: Boolean = true) : Canvas(bitmap) {
        val nanos = HashMap<String, Long>()
        val calls = HashMap<String, Int>()
        private inline fun <T> time(name: String, block: () -> T): T {
            val start = System.nanoTime()
            val res = if (draw) block() else Unit as T
            nanos.merge(name, System.nanoTime() - start, Long::plus)
            calls.merge(name, 1, Int::plus)
            return res
        }
        override fun drawLines(pts: FloatArray, offset: Int, count: Int, paint: android.graphics.Paint) = time("lines") { super.drawLines(pts, offset, count, paint) }
        override fun drawLines(pts: FloatArray, paint: android.graphics.Paint) = time("lines") { super.drawLines(pts, paint) }
        override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: android.graphics.Paint) = time("line") { super.drawLine(startX, startY, stopX, stopY, paint) }
        override fun drawText(text: String, x: Float, y: Float, paint: android.graphics.Paint) = time("text") { super.drawText(text, x, y, paint) }
        override fun drawText(text: String, start: Int, end: Int, x: Float, y: Float, paint: android.graphics.Paint) = time("text") { super.drawText(text, start, end, x, y, paint) }
        override fun drawPath(path: android.graphics.Path, paint: android.graphics.Paint) = time("path") { super.drawPath(path, paint) }
        override fun drawRect(rect: android.graphics.RectF, paint: android.graphics.Paint) = time("rect") { super.drawRect(rect, paint) }
        override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: android.graphics.Paint?) = time("bitmap") { super.drawBitmap(bitmap, left, top, paint) }
        fun report(frames: Int) = nanos.entries.sortedByDescending { it.value }.joinToString { (k, v) ->
            String.format(java.util.Locale.US, "%s %.2fms/%d", k, v / 1e6 / frames, calls.getValue(k) / frames)
        }
    }

    private fun sidData(chips: Int, frame: Int): ByteArray {
        val data = ByteArray(chips * ChipGauges.SIZE)
        for (chip in 0 until chips) {
            val offset = chip * ChipGauges.SIZE
            for (col in 0 until ChipGauges.COLUMNS) {
                fun put(gauge: Int, min: Int, max: Int) {
                    data[offset + (gauge * ChipGauges.COLUMNS + col) * 2] = min.coerceIn(0, 255).toByte()
                    data[offset + (gauge * ChipGauges.COLUMNS + col) * 2 + 1] = max.coerceIn(0, 255).toByte()
                }
                val t = col + frame * 3
                put(ChipGauges.wave(0), (t * 7919) % 256, (t * 7919) % 256)
                put(ChipGauges.wave(1), if ((t / 20) % 3 == 0) 255 else 0, if ((t / 20) % 3 == 0) 255 else 0)
                val tri = (t * 8) % 512
                put(ChipGauges.wave(2), if (tri < 256) tri else 511 - tri, if (tri < 256) tri else 511 - tri)
                for (voice in 0 until 3) {
                    val env = (240 - (col + frame + voice * 40) % 80 * 3).coerceAtLeast(0)
                    put(ChipGauges.envelope(voice), env - 5, env)
                    val pitch = 120 + ((col / 24 + voice) % 4) * 15
                    put(ChipGauges.frequency(voice), pitch, pitch)
                }
                put(ChipGauges.VOLUME, 255, 255)
                put(ChipGauges.RESONANCE, 170, 170)
                put(ChipGauges.CUTOFF, 40 + (col % 64) - 2, 40 + (col % 64))
            }
            val state = offset + ChipGauges.GAUGES * ChipGauges.COLUMNS * 2
            val regs = intArrayOf(
                0xd0 + frame % 16, 0x22, 0x00, 0x08, 0x41, 0x09, 0x9a,
                0x67, 0x11, 0xe8 - frame % 32, 0x05, 0x43, 0x5a, 0xf4,
                0xe7, 0x02, 0x00, 0x00, 0x21, 0x00, 0x09,
                0x07, 0x40 + frame % 8, 0xa5, 0x1f,
                frame % 256, 0xff, 0x40,
                0x99, 0xd0 - frame % 64, 0x40
            )
            regs.forEachIndexed { idx, value -> data[state + idx] = value.toByte() }
            data[offset + ChipGauges.SIZE - 1] = (frame * 17).toByte()
        }
        return data
    }

    private fun sidStatus(chips: Int) = "PAL, MOS6581, reSIDfp, resample\n" +
        "PlaySID one-file format (PSID), 50 Hz VBI (PAL), $chips SID, song 1/1\n" +
        "scope: 3/3 ch, trigger 699us (max 2181, wait 0), sync device, gauges 37us (max 54)\n" +
        "output: 48000Hz buffer=160ms sync=timestamp\nrender: 323% realtime"

    private fun sid(chips: Int): Timing {
        val view = SidDashboardView(ApplicationProvider.getApplicationContext())
        val stats = "vulkan: 47.5 fps (15 same), frame 21.03 ms (max 95.1), draw 0.62 ms (max 1.2), data 2.85 ms"
        view.setSource(null, { stats })
        return measure("${chips}sid", view) { frame ->
            view.setGaugesData(sidData(chips, frame), chips, sidStatus(chips))
        }
    }

    private fun megaDriveData(frame: Int): ByteArray {
        val voices = 11
        val data = ByteArray(voices * VoiceGauges.SIZE)
        for (idx in 0 until voices) {
            val offset = idx * VoiceGauges.SIZE
            for (col in 0 until VoiceGauges.COLUMNS) {
                fun put(gauge: Int, min: Int, max: Int) {
                    data[offset + (gauge * VoiceGauges.COLUMNS + col) * 2] = min.coerceIn(0, 255).toByte()
                    data[offset + (gauge * VoiceGauges.COLUMNS + col) * 2 + 1] = max.coerceIn(0, 255).toByte()
                }
                val t = col + frame
                val level = (180 + 60 * sin(t / 9.0 + idx)).toInt()
                put(VoiceGauges.WAVE, 128 + (100 * sin(t / 3.0)).toInt() - 5, 128 + (100 * sin(t / 3.0)).toInt())
                put(VoiceGauges.LEVEL, level - 10, level)
                val pitch = 100 + ((t / 16 + idx) % 5) * 12
                put(VoiceGauges.FREQUENCY, pitch, pitch)
            }
            val state = offset + VoiceGauges.GAUGES * VoiceGauges.COLUMNS * 2
            val (kind, fields) = when {
                idx < 3 -> VoiceGauges.Kind.PSG_TONE to intArrayOf(idx * 4, 0xfe - frame % 8, 0, 3)

                idx == 3 -> VoiceGauges.Kind.PSG_NOISE to intArrayOf(2, 1, 0, 3)

                idx < 10 -> VoiceGauges.Kind.OPN_FM to intArrayOf(
                    idx - 3, 3, 200, 120, 255 - frame % 64, 230, (idx - 4) % 8, 3, 0, 0, 15, 4
                )

                else -> VoiceGauges.Kind.OPN_DAC to intArrayOf(1, 200)
            }
            putFloat(data, state, 220f * (idx + 1) + frame % 7)
            putFloat(data, state + 4, -10f - frame % 5)
            data[state + 8] = (1 or 2 or 4).toByte()
            data[state + 9] = kind.toByte()
            fields.forEachIndexed { f, value -> data[state + 10 + f] = value.toByte() }
            data[offset + VoiceGauges.SIZE - 1] = (frame * 17).toByte()
        }
        return data
    }

    private fun putFloat(data: ByteArray, at: Int, value: Float) {
        val bits = value.toRawBits()
        for (idx in 0 until 4) {
            data[at + idx] = (bits shr (idx * 8)).toByte()
        }
    }

    @Test
    fun timings() {
        val results = listOf(sid(1), sid(2), sid(3), megaDrive())
        // gross regressions only, CI hosts are much slower than phones
        results.forEach { assertTrue(it.record < 50) }
    }

    private fun megaDrive(): Timing {
        val view = SidDashboardView(ApplicationProvider.getApplicationContext())
        val layout = VoicesLayout.parse("audio\nSEGA PSG\t1\t2\t3\tNoise\nYM2612\tFM 1\tFM 2\tFM 3\tFM 4\tFM 5\tFM 6\tDAC")
        return measure("megadrive", view) { frame ->
            view.setVoicesData(layout, megaDriveData(frame), 11, "Emulation description\nscope: statistics")
        }
    }

    private companion object {
        const val WARMUP = 60
        const val FRAMES = 120
    }
}
