package app.zxtune.ui.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import app.zxtune.playback.VoiceGauges
import app.zxtune.playback.VoiceGauges.Pcm
import app.zxtune.playback.VoicesLayout
import java.io.File
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders dashboards of wavetable PCM chips (YMW258 of Sega Model 1, SCSP of Model 2/Saturn) with synthetic state.
 * Images are stored to directory from ZXTUNE_SCREENSHOTS environment variable as pcmchips-*.png for review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class PcmDashboardRenderTest {

    private class Slot(val fields: IntArray, val level: Float, val flags: Int)

    private fun put16(f: IntArray, at: Int, value: Int) {
        f[at] = value and 0xff
        f[at + 1] = (value shr 8) and 0xff
    }

    // stage 1..4 or 0 for silent slot
    private fun slot(chip: Int, idx: Int, stage: Int): Slot {
        val f = IntArray(VoiceGauges.FIELDS)
        val scsp = chip == Pcm.CHIP_SCSP
        f[Pcm.CHIP] = chip
        val playing = stage != 0
        if (playing) {
            f[Pcm.STATE] = stage or Pcm.ST_PLAYING or (if (stage != 4) Pcm.ST_KEY else 0) or Pcm.ST_LOOPED
            f[Pcm.EG] = intArrayOf(0, 120, 240, 200, 90)[stage]
        }
        put16(f, Pcm.TONE, 17 + idx * 7)
        put16(f, Pcm.START, 0x1000 + idx * 0x2400)
        f[Pcm.START + 2] = 0x01
        put16(f, Pcm.LOOP, 1200 + idx * 10)
        put16(f, Pcm.END, 4000 + idx * 50)
        put16(f, Pcm.POS, 2500 + idx * 30)
        f[Pcm.FORMAT] = (if (scsp) 2 else idx % 2) or ((if (scsp) idx % 4 else 1) shl 2) or (if (scsp && idx == 9) 1 shl 4 else 0)
        f[Pcm.OCT] = (idx % 4 - 1) and 0xff
        put16(f, Pcm.FNUM, (idx * 97) % 1024)
        f[Pcm.TL] = idx * 3
        f[Pcm.PAN] = if (scsp) idx % 32 else idx % 16
        f[Pcm.SEND] = if (scsp) 7 - idx % 3 else 7
        f[Pcm.AR] = if (scsp) 31 - idx % 8 else 15 - idx % 4
        f[Pcm.D1R] = if (scsp) 12 else 4 + idx % 5
        f[Pcm.DL] = if (scsp) 6 + idx % 10 else 2 + idx % 6
        f[Pcm.D2R] = if (scsp) idx % 3 else idx % 4
        f[Pcm.RR] = if (scsp) 14 else 8
        f[Pcm.KRS] = 15
        f[Pcm.RATES] = if (scsp) 62 - idx % 8 * 2 else 63 - idx % 4 * 4
        f[Pcm.RATES + 1] = if (scsp) 24 else 16 + idx % 5 * 4
        f[Pcm.RATES + 2] = if (scsp) idx % 3 * 2 else idx % 4 * 4
        f[Pcm.RATES + 3] = if (scsp) 28 else 32
        f[Pcm.LFOF] = if (scsp) 20 else 5
        f[Pcm.PLFO] = if (idx % 3 == 0) 3 or (if (scsp) 2 shl 4 else 0) else 0
        f[Pcm.ALFO] = if (idx % 5 == 0) 2 else 0
        if (scsp && idx == 4) {
            f[Pcm.MODE] = Pcm.MODE_FM
            f[Pcm.MDL] = 11
            f[Pcm.MDXSL] = 63
            f[Pcm.MDYSL] = 62
            f[Pcm.ISEL] = 2
            f[Pcm.IMXL] = 5
            f[Pcm.EFSDL] = 3
            f[Pcm.EFPAN] = 0x10
        }
        val clock = if (scsp) 22579200 else 10022168
        put16(f, Pcm.CLOCK, clock and 0xffff)
        put16(f, Pcm.CLOCK + 2, clock shr 16)
        put16(f, Pcm.RATE, if (scsp) 44100 else 44744)
        f[Pcm.MVOL] = 15
        f[Pcm.GLOBAL] = if (scsp) Pcm.GL_MEM4MB else Pcm.GL_BANKING
        put16(f, Pcm.BANK0, 0x10)
        put16(f, Pcm.BANK1, 0x18)
        f[Pcm.ACTIVE] = 20
        val egDb = f[Pcm.EG] * 96f / 255 - 96
        return Slot(f, if (playing) egDb - idx * 1.1f else -96f, 4 or (if (playing && stage != 4) 1 else 0))
    }

    private fun slots(chip: Int, count: Int) = (0 until count).map { slot(chip, it, if (it % 7 == 6) 0 else it % 4 + 1) }

    private fun makeData(kinds: List<Int>, slots: List<Slot?>): ByteArray {
        val data = ByteArray(slots.size * VoiceGauges.SIZE)
        slots.forEachIndexed { idx, slot ->
            val offset = idx * VoiceGauges.SIZE
            for (col in 0 until VoiceGauges.COLUMNS) {
                fun put(gauge: Int, min: Int, max: Int) {
                    data[offset + (gauge * VoiceGauges.COLUMNS + col) * 2] = min.coerceIn(0, 255).toByte()
                    data[offset + (gauge * VoiceGauges.COLUMNS + col) * 2 + 1] = max.coerceIn(0, 255).toByte()
                }
                val wave = (128 + 100 * sin(col / 3.0 + idx)).toInt()
                put(VoiceGauges.WAVE, wave - 20, wave + 20)
                val level = (170 + 60 * sin(col / 11.0 + idx)).toInt()
                put(VoiceGauges.LEVEL, level - 10, level)
                val pitch = 110 + ((col / 20 + idx) % 4) * 10
                put(VoiceGauges.FREQUENCY, pitch, pitch)
            }
            val state = offset + VoiceGauges.GAUGES * VoiceGauges.COLUMNS * 2
            putFloat(data, state, 0f)
            putFloat(data, state + 4, slot?.level ?: -10f)
            data[state + 8] = (slot?.flags ?: 7).toByte()
            data[state + 9] = kinds[idx].toByte()
            slot?.fields?.forEachIndexed { f, value -> data[state + 10 + f] = value.toByte() }
        }
        return data
    }

    private fun putFloat(data: ByteArray, at: Int, value: Float) {
        val bits = value.toRawBits()
        for (idx in 0 until 4) {
            data[at + idx] = (bits shr (idx * 8)).toByte()
        }
    }

    private fun layoutOf(groups: List<Pair<String, List<String>>>) = VoicesLayout.parse(
        "audio\n" + groups.joinToString("\n") { (name, voices) -> name + "\t" + voices.joinToString("\t") }
    )

    private fun slotNames(count: Int) = (1..count).map { "$it" }

    private fun view(layout: VoicesLayout, kinds: List<Int>, slots: List<Slot?>, status: String) = SidDashboardView(ApplicationProvider.getApplicationContext()).apply {
        setVoicesData(layout, makeData(kinds, slots), slots.size, status)
    }

    private fun render(name: String, view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        repeat(6) {
            bitmap.eraseColor(0)
            view.draw(Canvas(bitmap))
        }
        var lit = 0
        for (y in 0 until height step 4) {
            for (x in 0 until width step 4) {
                if (bitmap.getPixel(x, y) and 0xffffff != 0) {
                    ++lit
                }
            }
        }
        assertTrue(name, lit > 1000)
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "pcmchips-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun tap(view: View, x: Float, y: Float) {
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(0L, 0L, action, x, y, 0)
            view.onTouchEvent(event)
            event.recycle()
        }
    }

    private fun visible(grid: SidDashboardView.CellGrid) = grid.specs.indices.filter { grid.visible[it] }.map { grid.specs[it].caption }

    // cells never overlap and fit width
    private fun checkGrid(grid: SidDashboardView.CellGrid, width: Float) {
        for (a in grid.specs.indices) {
            if (!grid.visible[a]) continue
            assertTrue(grid.left[a] >= 0f && grid.right[a] <= width && grid.left[a] < grid.right[a])
            for (b in a + 1 until grid.specs.size) {
                if (grid.visible[b] && grid.line[a] == grid.line[b]) {
                    assertTrue("$a $b", grid.right[a] <= grid.left[b])
                }
            }
        }
    }

    // Sega Model 1: YM3438 and two YMW258
    private val model1Layout = layoutOf(
        listOf(
            "YM3438" to listOf("FM 1", "FM 2", "FM 3", "FM 4", "FM 5", "FM 6", "DAC"),
            "YMW258/1" to slotNames(28),
            "YMW258/2" to slotNames(28),
        )
    )
    private val model1Kinds = List(6) { VoiceGauges.Kind.OPN_FM } + VoiceGauges.Kind.OPN_DAC + List(56) { VoiceGauges.Kind.PCM_SLOT }
    private val model1Slots = List(7) { null } + slots(Pcm.CHIP_YMW258, 28) + slots(Pcm.CHIP_YMW258, 28).reversed()
    private val model1Status = "YM3438 MAME 8.00MHz, YMW258/1 MAME 10.02MHz, YMW258/2 MAME 10.02MHz\nscope: 63/63 ch"

    private fun model1() = view(model1Layout, model1Kinds, model1Slots, model1Status)

    private val scspLayout = layoutOf(listOf("SCSP" to slotNames(32)))
    private fun scsp() = view(scspLayout, List(32) { VoiceGauges.Kind.PCM_SLOT }, slots(Pcm.CHIP_SCSP, 32), "SCSP MAME 22.58MHz\nscope: 32/32 ch")

    @Test
    fun model1Portrait() {
        val view = model1()
        render("model1-portrait", view, 1080, 1700)
        // the first PCM chip is selected
        assertEquals(1, view.pcmTab)
        assertEquals(1, view.pcmColumns)
        assertTrue(view.pcmTabsHeight > 0f)
        val columns = visible(view.pcmTable)
        assertTrue(columns.toString(), columns.containsAll(listOf("Sample", "Note", "Rate", "Level", "Stage", "Pan")))
        checkGrid(view.pcmTable, 1080f)
        checkGrid(view.pcmGlobalGrid, 1080f)
        assertTrue(visible(view.pcmGlobalGrid).toString(), visible(view.pcmGlobalGrid).containsAll(listOf("Chip", "Clock", "Playing", "Banks")))
        // tabs select chips
        tap(view, 1079f, view.pcmTabsHeight / 2)
        assertEquals(2, view.pcmTab)
        render("model1-portrait-chip2", view, 1080, 1700)
        tap(view, 1f, view.pcmTabsHeight / 2)
        assertEquals(0, view.pcmTab)
        render("model1-portrait-fm", view, 1080, 1700)
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-xxhdpi")
    fun model1Landscape() {
        val view = model1()
        render("model1-landscape", view, 2400, 1000)
        assertEquals(2, view.pcmColumns)
        checkGrid(view.pcmTable, 1200f)
    }

    // tap on slot opens its inspector, the next tap returns to the table
    @Test
    fun model1Inspector() {
        val view = model1()
        render("model1-table", view, 1080, 1700)
        tap(view, 500f, view.pcmTabsHeight + view.pcmRowHeight * 3.5f)
        // the third slot of the first YMW258 (after 7 voices of YM3438)
        assertEquals(7 + 2, view.pcmFocus)
        render("model1-inspector", view, 1080, 1700)
        val cells = visible(view.pcmSlotGrid)
        assertTrue(cells.toString(), cells.containsAll(listOf("Sample", "Level", "Stage", "Note", "Rate", "Attack", "Decay 1", "Release")))
        // SCSP only cells
        assertTrue(cells.toString(), !cells.contains("FM from"))
        checkGrid(view.pcmSlotGrid, 1080f)
        tap(view, 500f, 800f)
        assertEquals(-1, view.pcmFocus)
    }

    @Test
    fun scspPortrait() {
        val view = scsp()
        render("scsp-portrait", view, 1080, 1700)
        // single chip has no tabs
        assertEquals(0f, view.pcmTabsHeight, 0f)
        val columns = visible(view.pcmTable)
        assertTrue(columns.toString(), columns.contains("Mode"))
        checkGrid(view.pcmTable, 1080f)
        assertTrue(visible(view.pcmGlobalGrid).toString(), !visible(view.pcmGlobalGrid).contains("Banks"))
    }

    @Test
    fun scspInspector() {
        val view = scsp()
        view.openPcmInspector(4)
        render("scsp-inspector", view, 1080, 1700)
        val cells = visible(view.pcmSlotGrid)
        assertTrue(cells.toString(), cells.containsAll(listOf("FM from", "DSP send", "Mode", "Attack", "Sustain")))
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-xxhdpi")
    fun scspLandscapeInspector() {
        val view = scsp()
        view.openPcmInspector(2)
        render("scsp-inspector-landscape", view, 2400, 1000)
    }

    // oscilloscope cells of 63 voices: YM3438 column and 4 columns of 7 slots per YMW258
    @Test
    fun model1ScopeGrid() {
        val grid = ScopeGrid.of(model1Layout, 63)
        assertEquals(63, grid.channels)
        assertEquals(7, grid.rows)
        assertEquals(9, grid.columns.columns)
        renderGrid("scope-model1", grid, 1080, 1100)
        renderGrid("scope-model1-landscape", grid, 2400, 800)
        renderGrid("scope-scsp", ScopeGrid.of(scspLayout, 32), 1080, 1100)
    }

    private fun renderGrid(name: String, grid: ScopeGrid, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.BLACK)
        val density = ApplicationProvider.getApplicationContext<android.content.Context>().resources.displayMetrics.density
        grid.drawBackground(canvas, width, height, 12 * density)
        // traces as sines in their areas
        val traces = FloatArray(grid.channels * 4)
        grid.traces(width, height, 12 * density, traces)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE }
        for (chan in 0 until grid.channels) {
            val left = traces[chan * 4]
            val w = traces[chan * 4 + 1]
            for (x in 0 until w.toInt()) {
                val y = traces[chan * 4 + 2] - traces[chan * 4 + 3] * 0.6f * sin(x * 0.2 + chan).toFloat()
                canvas.drawPoint(left + x, y, paint)
            }
        }
        System.getenv("ZXTUNE_SCREENSHOTS")?.let { dir ->
            File(dir, "pcmchips-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-xxhdpi")
    fun scspLandscape() {
        val view = scsp()
        render("scsp-landscape", view, 2400, 1000)
    }
}
