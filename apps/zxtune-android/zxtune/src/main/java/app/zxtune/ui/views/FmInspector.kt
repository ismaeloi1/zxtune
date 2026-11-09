/**
 * @file
 * @brief Full state of a single FM channel: algorithm figure, operators table with envelopes, channel and chip values
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import app.zxtune.playback.VoiceGauges
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Inspector of FM channel opened by tap on its dashboard card. Values are shown twice: as registers (for those who
 * know chip or write music in tracker) and in units (Hz, dB, ms) with words, so the same screen is readable without
 * datasheet. Layout (portrait / landscape):
 * - bar: back to all channels, tabs of chip's FM channels with live levels
 * - algorithm figure (operators boxes are tappable) with legend of what carriers, modulators and feedback are
 * - channel values: note, pitch, F-number, algorithm, feedback, output, LFO sensitivity
 * - operators table: multiple and detune, total level, envelope rates with times, sustain, envelope shape with
 *   current stage and level, switches (AM, key scale, SSG-EG, OPL waveform, vibrato)
 * - chip values: LFO, channel 3 mode, DAC, rhythm, OPL3 4 operators pairs
 */
internal class FmInspector(private val density: Float, scaledDensity: Float) {

    private val sp = scaledDensity
    private val widgets = FmWidgets(density, 12 * sp)
    private val channel = FmChannel()
    private val tabChannel = FmChannel()

    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 255, 255, 255)
        textSize = CAPTION_SP * sp
    }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        textSize = VALUE_SP * sp
        fontFeatureSettings = "tnum"
    }
    private val cellText = Paint(value).apply {
        textSize = TABLE_SP * sp
    }
    private val dimText = Paint(cellText).apply {
        color = Color.argb(150, 255, 255, 255)
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = TITLE_SP * sp
        typeface = Typeface.DEFAULT_BOLD
    }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = density * 1.2f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dimStroke = Paint(stroke).apply {
        color = Color.argb(100, 255, 255, 255)
    }
    private val boldStroke = Paint(stroke).apply {
        strokeWidth = density * 2.2f
        color = Color.WHITE
    }
    private val dashStroke = Paint(dimStroke).apply {
        pathEffect = DashPathEffect(floatArrayOf(density * 2, density * 2), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val baseTableSize = cellText.textSize
    private val baseCaptionSize = caption.textSize

    private val path = Path()
    private val r1 = RectF()
    private val r2 = RectF()
    private val graph = RectF()
    private val texts = LiveTexts(TEXTS)

    // values written by players at each tick (vibrato, slides, volume envelopes), see [RealtimeReadout]
    private var readoutsVoice = -1
    private val noteReadout = NoteReadout()
    private val pitchReadout = RealtimeReadout(deadband = 1.0 / 48, modulationSpread = 1.0 / 12, isLogarithmic = true)
    private val volumeReadout = RealtimeReadout(deadband = 1.0, modulationSpread = 6.0)
    private val operatorReadouts = Array(4) { RealtimeReadout(deadband = 1.0 / 48, modulationSpread = Double.MAX_VALUE, isLogarithmic = true) }
    private val lfoReadout = RealtimeReadout(deadband = 1.0 / 48, modulationSpread = Double.MAX_VALUE, isLogarithmic = true)
    private val csmReadout = RealtimeReadout(deadband = 1.0 / 48, modulationSpread = Double.MAX_VALUE, isLogarithmic = true)
    private val xs = FloatArray(5)
    private val ys = FloatArray(5)
    private val columns = FloatArray(COLUMNS + 1)

    // hit regions of the last frame
    private val backRect = RectF()
    private val tabRects = Array(MAX_TABS) { RectF() }
    private var tabsCount = 0
    private val opBoxes = Array(4) { RectF() }
    private val opRows = Array(4) { RectF() }
    private var opsCount = 0

    /**
     * Tabs of channels to switch between
     * @param names channels names
     * @param voices indices of channels voices in gauges data
     */
    class Tabs(val chip: String, val names: Array<String>, val voices: IntArray)

    /**
     * @param data voices gauges, see [VoiceGauges]
     * @param tab current tab index
     * @param smooth eased values storage of current channel (at least [ChipWidgets.SMOOTH_SIZE])
     * @param selected highlighted operator or -1
     * @param nanos monotonic time of frame, 0 for single picture (values are shown at once)
     */
    fun draw(canvas: Canvas, rect: RectF, data: ByteArray, tabs: Tabs, tab: Int, smooth: FloatArray, selected: Int, nanos: Long = 0L) {
        val g = VoiceGauges(data, tabs.voices[tab] * VoiceGauges.SIZE)
        channel.update(g)
        updateReadouts(tabs.voices[tab], nanos)
        widgets.easeLevels(channel, smooth)
        val pad = density * 6
        var top = rect.top + pad
        top = drawBar(canvas, rect.left + pad, top, rect.width() - pad * 2, data, tabs, tab) + pad
        val left = rect.left + pad
        val right = rect.right - pad
        val bottom = rect.bottom - pad
        val landscape = rect.width() > rect.height() * 1.4f
        if (landscape) {
            val split = left + (right - left) * 0.4f
            var y = drawFigure(canvas, left, top, split - pad, min((bottom - top) * 0.45f, density * 130), selected)
            y = drawChannelCells(canvas, left, y + pad, split - left - pad)
            drawChipCells(canvas, left, y + pad, split - left - pad, tabs.chip)
            drawOperators(canvas, split + pad, top, right, bottom, selected)
        } else {
            val chipEstimate = cellsHeight(right - left, chipCellsCount(), CHIP_TEMPLATE)
            val cellsEstimate = cellsHeight(right - left, CHANNEL_CELLS, CHANNEL_TEMPLATE)
            val tableH = caption.fontSpacing + density * (MAX_ROW_DP * channel.operatorsCount + 4)
            val figureH = bottom - top - chipEstimate - cellsEstimate - tableH - pad * 3
            var y = drawFigure(canvas, left, top, right, figureH.coerceIn(density * 70, density * 170), selected)
            y = drawChannelCells(canvas, left, y + pad, right - left)
            // chip values are at the bottom, operators take the rest
            val chipH = cellsHeight(right - left, chipCellsCount(), CHIP_TEMPLATE)
            drawOperators(canvas, left, y + pad, right, bottom - chipH - pad, selected)
            drawChipCells(canvas, left, bottom - chipH, right - left, tabs.chip)
        }
    }

    /** Note, pitch, volume and frequencies of operators as of the last frame */
    fun readoutTexts() = listOf(texts[T_NOTE], texts[T_PITCH], texts[T_VOLUME]) +
        (0 until channel.operatorsCount).map { texts[T_OPERATORS + it * T_PER_OPERATOR + 1] }

    private fun updateReadouts(voice: Int, nanos: Long) {
        if (voice != readoutsVoice) {
            // history of other channel is not shown
            readoutsVoice = voice
            noteReadout.reset()
            pitchReadout.reset()
            volumeReadout.reset()
            operatorReadouts.forEach { it.reset() }
            lfoReadout.reset()
            csmReadout.reset()
        }
        val ch = channel
        val hz = ch.frequency
        val heard = ch.levelDb > -96f && hz > 0f
        noteReadout.update(if (heard) hz.toDouble() else 0.0, nanos)
        pitchReadout.update(if (hz > 0f) hz.toDouble() else Double.NaN, nanos)
        volumeReadout.update(if (ch.levelDb <= -95.5f) Double.NaN else ch.levelDb.toDouble(), nanos)
        for (op in 0 until ch.operatorsCount) {
            val opHz = ch.operatorHz(ch.operators[op])
            operatorReadouts[op].update(if (opHz > 0f) opHz.toDouble() else Double.NaN, nanos)
        }
        lfoReadout.update(if (ch.hasLfo && ch.lfoHz > 0f) ch.lfoHz.toDouble() else Double.NaN, nanos)
        csmReadout.update(if (ch.ch3Mode == 2 && ch.csmHz > 0f) ch.csmHz.toDouble() else Double.NaN, nanos)
    }

    /**
     * @return [HIT_BACK], [HIT_TAB] or [HIT_OPERATOR] in high 32 bits with index in low ones, [HIT_NONE] otherwise
     */
    fun hit(x: Float, y: Float): Long {
        val slop = density * 6
        if (backRect.contains(x, y)) {
            return HIT_BACK shl 32
        }
        for (idx in 0 until tabsCount) {
            if (tabRects[idx].contains(x, y)) {
                return (HIT_TAB shl 32) or idx.toLong()
            }
        }
        for (op in 0 until opsCount) {
            val b = opBoxes[op]
            if (x >= b.left - slop && x <= b.right + slop && y >= b.top - slop && y <= b.bottom + slop) {
                return (HIT_OPERATOR shl 32) or op.toLong()
            }
            if (opRows[op].contains(x, y)) {
                return (HIT_OPERATOR shl 32) or op.toLong()
            }
        }
        return HIT_NONE
    }

    // back button with chip name and tabs of channels, @return bottom
    private fun drawBar(canvas: Canvas, left: Float, top: Float, width: Float, data: ByteArray, tabs: Tabs, tab: Int): Float {
        val h = density * BAR_DP
        val backW = min(width * 0.3f, title.measureText(BACK_MARK + tabs.chip) + density * 16)
        backRect.set(left, top, left + backW, top + h)
        canvas.drawRoundRect(backRect, density * 4, density * 4, frame)
        drawFitted(canvas, BACK_MARK + tabs.chip, backRect.left + density * 6, backRect.centerY() - (title.ascent() + title.descent()) / 2, backW - density * 10, title)
        tabsCount = min(tabs.voices.size, MAX_TABS)
        val tabsLeft = backRect.right + density * 6
        val tabW = (left + width - tabsLeft) / max(tabsCount, 1)
        for (idx in 0 until tabsCount) {
            val r = tabRects[idx]
            r.set(tabsLeft + tabW * idx, top, tabsLeft + tabW * (idx + 1) - density * 3, top + h)
            val g = VoiceGauges(data, tabs.voices[idx] * VoiceGauges.SIZE)
            tabChannel.update(g)
            if (idx == tab) {
                fill.color = Color.argb(70, 255, 255, 255)
                canvas.drawRect(r, fill)
                canvas.drawRect(r, stroke)
            } else {
                canvas.drawRect(r, frame)
            }
            // live level of channel at the bottom of tab
            val level = ((tabChannel.levelDb + 48f) / 48f).coerceIn(0f, 1f)
            fill.color = Color.argb(if (g.isKeyOn) 200 else 90, 255, 255, 255)
            canvas.drawRect(r.left, r.bottom - density * 3, r.left + r.width() * level, r.bottom, fill)
            val name = tabs.names[idx]
            cellText.textAlign = Paint.Align.CENTER
            drawFitted(canvas, name, r.centerX(), r.centerY() - (cellText.ascent() + cellText.descent()) / 2 - density, r.width() - density * 4, cellText)
            cellText.textAlign = Paint.Align.LEFT
        }
        return top + h
    }

    // algorithm figure and legend, @return bottom
    private fun drawFigure(canvas: Canvas, left: Float, top: Float, right: Float, height: Float, selected: Int): Float {
        val w = right - left
        val legendW = min(w * 0.45f, density * 200)
        r1.set(left, top, right - legendW - density * 8, top + height)
        widgets.drawDiagram(canvas, r1, channel, widgets.easedLevels, selected, true)
        opsCount = widgets.drawnOperators
        for (op in 0 until opsCount) {
            opBoxes[op].set(widgets.boxes[op])
        }
        drawLegend(canvas, right - legendW, top, legendW, height)
        return top + height
    }

    private fun drawLegend(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        val lineH = caption.fontSpacing * 1.15f
        val lines = 4
        caption.textSize = baseCaptionSize * min(1f, height / (lineH * lines))
        val step = caption.fontSpacing * 1.15f
        var y = top + max(0f, (height - step * lines) / 2)
        val glyphW = caption.textSize * 1.6f
        val textX = left + glyphW + density * 6
        val textW = width - glyphW - density * 6
        for (line in 0 until lines) {
            val cy = y + step / 2
            val gh = caption.textSize * 0.9f
            r2.set(left, cy - gh / 2, left + glyphW, cy + gh / 2)
            when (line) {
                0 -> widgets.drawLegendBox(canvas, r2, true)

                1 -> widgets.drawLegendBox(canvas, r2, false)

                2 -> drawLoopGlyph(canvas, r2)

                else -> {
                    canvas.drawRect(r2, stroke)
                    fill.color = Color.argb(170, 255, 255, 255)
                    canvas.drawRect(r2.left, r2.top + r2.height() * 0.4f, r2.right, r2.bottom, fill)
                }
            }
            val words = when (line) {
                0 -> LEGEND_CARRIER
                1 -> LEGEND_MODULATOR
                2 -> if (channel.feedback != 0) feedbackLegend() else LEGEND_NO_FEEDBACK
                else -> LEGEND_FILL
            }
            val shown = if (caption.measureText(words[0]) <= textW) words[0] else words[1]
            val count = caption.breakText(shown, true, textW, null)
            canvas.drawText(shown, 0, count, textX, cy - (caption.ascent() + caption.descent()) / 2, caption)
            y += step
        }
        caption.textSize = baseCaptionSize
    }

    private fun feedbackLegend(): Array<String> {
        val fb = channel.feedback
        val long = texts.text(T_FEEDBACK_LEGEND, fb.toLong()) { "Feedback $fb: OP1 modulates itself (harsher)" }
        val short = texts.text(T_FEEDBACK_LEGEND + 1, fb.toLong()) { "Feedback $fb: OP1 to itself" }
        legendFeedback[0] = long
        legendFeedback[1] = short
        return legendFeedback
    }

    private val legendFeedback = arrayOf("", "")

    private fun drawLoopGlyph(canvas: Canvas, r: RectF) {
        path.reset()
        path.moveTo(r.right, r.centerY())
        path.lineTo(r.right, r.top)
        path.lineTo(r.left, r.top)
        path.lineTo(r.left, r.bottom)
        path.lineTo(r.centerX(), r.bottom)
        canvas.drawPath(path, dimStroke)
    }

    // values of channel as labelled cells, @return bottom
    private fun drawChannelCells(canvas: Canvas, left: Float, top: Float, width: Float): Float {
        val ch = channel
        cellsCount = 0
        val note = noteReadout.note
        cell("Note", texts.text(T_NOTE, note.toLong()) { if (note != NoteReadout.NONE) ChipWidgets.noteName(note) else DASH })
        cell("Pitch", texts.text(T_PITCH, pitchReadout.version.toLong()) {
            if (pitchReadout.value.isNaN()) DASH else pitchReadout.text { formatHz(it.toFloat(), pitchReadout.isModulated) }
        })
        if (ch.isDetailed) {
            if (ch.family == FmChannel.FAMILY_OPM) {
                cell("Key code", texts.text(T_FNUM, (ch.keyCode shl 8 or ch.keyFraction).toLong()) {
                    "%02X.%d".format(ch.keyCode, ch.keyFraction)
                })
            } else {
                cell("Block/F-num", texts.text(T_FNUM, (ch.block shl 12 or ch.fnum).toLong()) { "${ch.block}/${ch.fnum}" })
            }
        }
        cell("Algorithm", texts.text(T_ALG, (ch.algorithm or (ch.operatorsCount shl 4) or (ch.family shl 8)).toLong()) {
            FmWidgets.algorithmName(ch)
        })
        cell("Feedback", texts.text(T_FB, ch.feedback.toLong()) { if (ch.feedback == 0) OFF else "${ch.feedback}" })
        if (ch.hasPan) {
            cell("Output", PAN_WORDS[ch.pan and 3])
        }
        if (ch.hasLfo) {
            cell("LFO sens", texts.text(T_LFO_SENS, (ch.ams shl 4 or ch.pms).toLong()) { "AM${ch.ams} PM${ch.pms}" })
        }
        cell("Volume", texts.text(T_VOLUME, volumeReadout.version.toLong()) {
            if (volumeReadout.value.isNaN()) OFF else volumeReadout.text { formatDb(it.roundToInt().toFloat()) }
        })
        return drawCells(canvas, left, top, width, CHANNEL_TEMPLATE)
    }

    private fun chipCellsCount() = when (channel.family) {
        FmChannel.FAMILY_OPL -> 5
        FmChannel.FAMILY_OPM -> 5
        else -> 4
    }

    // chip-wide values, @return bottom
    private fun drawChipCells(canvas: Canvas, left: Float, top: Float, width: Float, chip: String): Float {
        val ch = channel
        cellsCount = 0
        cell("Chip", chip)
        if (!ch.isDetailed) {
            cell("Details", "not reported by emulation")
            return drawCells(canvas, left, top, width, CHIP_TEMPLATE)
        }
        when (ch.family) {
            FmChannel.FAMILY_OPL -> {
                val gl = ch.global
                cell("Rhythm mode", if (gl and FmChannel.GLOBAL_OPL_RHYTHM != 0) ON else OFF)
                cell("Tremolo depth", if (gl and FmChannel.GLOBAL_OPL_AM_DEPTH != 0) "4.8 dB" else "1 dB")
                cell("Vibrato depth", if (gl and FmChannel.GLOBAL_OPL_VIB_DEPTH != 0) "14 cents" else "7 cents")
                if (ch.chip == FmChannel.CHIP_OPL3) {
                    cell("4-op pairs", texts.text(T_PAIRS, (ch.global2 or (gl shl 8)).toLong()) { opl3Pairs(ch) })
                } else if (ch.chip == FmChannel.CHIP_OPL2) {
                    cell("Waveforms", if (gl and FmChannel.GLOBAL_OPL_WSE != 0) "Enabled" else "Sine only")
                }
                if (gl and FmChannel.GLOBAL_OPL_CSM != 0) {
                    cell("CSM", ON)
                }
            }

            FmChannel.FAMILY_OPM -> {
                cell("LFO", texts.text(T_LFO, lfoReadout.version.toLong() or ((ch.global.toLong() and 3) shl 32)) {
                    "${formatHz(lfoReadout.value.toFloat())} ${LFO_WAVES[ch.global and 3]}"
                })
                cell("AM depth", texts.text(T_AMD, ch.amd.toLong()) { "${ch.amd}" })
                cell("PM depth", texts.text(T_PMD, ch.pmd.toLong()) { "${ch.pmd}" })
                cell("Noise (ch 8)", texts.text(T_NOISE, (ch.noise or (ch.global shl 8)).toLong()) {
                    if (ch.global and FmChannel.GLOBAL_OPM_NOISE != 0) "On, ${ch.noise}" else OFF
                })
            }

            else -> {
                if (ch.hasLfo) {
                    cell("LFO", texts.text(T_LFO, lfoReadout.version.toLong()) {
                        if (lfoReadout.value.isNaN()) OFF else formatHz(lfoReadout.value.toFloat())
                    })
                }
                cell("Channel 3", texts.text(T_CH3, ch.ch3Mode.toLong() or (csmReadout.version.toLong() shl 4)) {
                    when {
                        ch.ch3Mode == 1 -> "Own op. pitches"
                        ch.ch3Mode == 2 && !csmReadout.value.isNaN() -> "CSM ${formatHz(csmReadout.value.toFloat())}"
                        ch.ch3Mode == 2 -> "CSM"
                        else -> "Normal"
                    }
                })
                if (ch.chip == FmChannel.CHIP_OPN2) {
                    cell("DAC", if (ch.global and FmChannel.GLOBAL_OPN_DAC != 0) "On, replaces FM 6" else OFF)
                }
            }
        }
        return drawCells(canvas, left, top, width, CHIP_TEMPLATE)
    }

    private val cellCaptions = Array(MAX_CELLS) { "" }
    private val cellValues = Array(MAX_CELLS) { "" }
    private var cellsCount = 0

    private fun cell(cap: String, text: String) {
        if (cellsCount < MAX_CELLS) {
            cellCaptions[cellsCount] = cap
            cellValues[cellsCount] = text
            ++cellsCount
        }
    }

    private fun cellWidth(idx: Int, template: String) = max(max(caption.measureText(cellCaptions[idx]), value.measureText(cellValues[idx])), value.measureText(template)) +
        density * CELL_GAP_DP

    private fun cellsHeight(width: Float, count: Int, template: String): Float {
        val lineH = caption.fontSpacing + value.fontSpacing + density * 2
        // estimation with templates, real values are cut to template width
        val cellW = value.measureText(template) + density * CELL_GAP_DP
        val perLine = max(1, (width / cellW).toInt())
        return lineH * ((count + perLine - 1) / perLine)
    }

    // flow of caption/value cells with separators, @return bottom
    private fun drawCells(canvas: Canvas, left: Float, top: Float, width: Float, template: String): Float {
        val count = cellsCount
        val lineH = caption.fontSpacing + value.fontSpacing + density * 2
        var x = left
        var y = top
        for (idx in 0 until count) {
            val w = min(cellWidth(idx, template), width)
            if (x > left && x + w > left + width) {
                x = left
                y += lineH
            }
            if (x > left) {
                canvas.drawLine(x - density * CELL_GAP_DP / 2, y + density * 2, x - density * CELL_GAP_DP / 2, y + lineH - density * 2, frame)
            }
            drawFitted(canvas, cellCaptions[idx], x, y - caption.ascent(), w - density * CELL_GAP_DP, caption)
            drawFitted(canvas, cellValues[idx], x, y + caption.fontSpacing - value.ascent(), w - density * CELL_GAP_DP, value)
            x += w
        }
        return y + lineH
    }

    // operators table, header captions and a row (registers line and units line, envelope shape) per operator
    private fun drawOperators(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, selected: Int) {
        val ch = channel
        val ops = ch.operatorsCount
        for (op in ops until 4) {
            opRows[op].setEmpty()
        }
        if (!ch.isDetailed) {
            for (op in 0 until ops) {
                opRows[op].setEmpty()
            }
            drawFitted(canvas, NO_DETAILS, left, top - caption.ascent(), right - left, caption)
            return
        }
        val isOpl = ch.isOpl
        val headers = if (isOpl) OPL_HEADERS else OPN_HEADERS
        val templates = if (isOpl) OPL_TEMPLATES else OPN_TEMPLATES
        // table font shrinks to keep envelope column at least of minimal width
        val minGraph = density * MIN_GRAPH_DP
        cellText.textSize = baseTableSize
        var textCols = templates.sumOf { cellText.measureText(it).toDouble() }.toFloat() + density * CELL_GAP_DP * templates.size
        if (textCols > right - left - minGraph) {
            val scale = max(MIN_TABLE_SCALE, (right - left - minGraph - density * CELL_GAP_DP * templates.size) / (textCols - density * CELL_GAP_DP * templates.size))
            cellText.textSize = baseTableSize * scale
            textCols = templates.sumOf { cellText.measureText(it).toDouble() }.toFloat() + density * CELL_GAP_DP * templates.size
        }
        dimText.textSize = cellText.textSize
        caption.textSize = min(baseCaptionSize, cellText.textSize * 0.9f)
        columns[0] = left
        for (col in templates.indices) {
            columns[col + 1] = columns[col] + cellText.measureText(templates[col]) + density * CELL_GAP_DP
        }
        val graphLeft = columns[templates.size]
        // header
        val headerH = caption.fontSpacing + density * 2
        for (col in templates.indices) {
            drawFitted(canvas, headers[col], columns[col], top - caption.ascent(), columns[col + 1] - columns[col] - density * 2, caption)
        }
        drawFitted(canvas, ENVELOPE_HEADER, graphLeft, top - caption.ascent(), right - graphLeft, caption)
        val rowsTop = top + headerH
        val rowH = min((bottom - rowsTop) / ops, density * MAX_ROW_DP)
        val textH = cellText.fontSpacing * 2
        for (op in 0 until ops) {
            val rowTop = rowsTop + rowH * op
            val row = opRows[op]
            row.set(left, rowTop, right, rowTop + rowH)
            canvas.drawLine(left, rowTop, right, rowTop, frame)
            if (op == selected) {
                fill.color = Color.argb(45, 255, 255, 255)
                canvas.drawRect(row, fill)
            }
            val y1 = rowTop + max(density * 2, (rowH - textH) / 2) - cellText.ascent()
            val y2 = y1 + cellText.fontSpacing
            drawOperatorTexts(canvas, op, y1, y2, templates.size)
            graph.set(graphLeft, rowTop + density * 3, right, rowTop + rowH - density * 3)
            drawEnvelope(canvas, graph, ch.operators[op], op)
        }
        cellText.textSize = baseTableSize
        dimText.textSize = baseTableSize
        caption.textSize = baseCaptionSize
    }

    private fun drawOperatorTexts(canvas: Canvas, op: Int, y1: Float, y2: Float, cols: Int) {
        val ch = channel
        val o = ch.operators[op]
        val slot = T_OPERATORS + op * T_PER_OPERATOR
        val carrier = ch.isCarrier(op)
        // name and role
        cell(canvas, 0, y1, FmWidgets.OP_NAMES[op], cellText)
        cell(canvas, 0, y2, if (carrier) ROLE_CARRIER else ROLE_MODULATOR, dimText)
        if (ch.isOpl) {
            // waveform glyph next to name
            val w = columns[1] - columns[0]
            val glyphW = min(w * 0.4f, density * 16)
            val gx = columns[0] + cellText.measureText(FmWidgets.OP_NAMES[op]) + density * 4
            if (gx + glyphW <= columns[1]) {
                widgets.drawWaveform(canvas, o.wave, gx, y1 + cellText.ascent() / 2 + density, glyphW, -cellText.ascent() * 0.4f)
            }
        }
        val mulKey = (o.mul or (o.dt shl 4) or (o.dt2 shl 8)).toLong()
        cell(canvas, 1, y1, texts.text(slot, mulKey or (if (ch.isOpl) 1L shl 20 else 0)) { multipleText(o) }, cellText)
        val opHz = operatorReadouts[op]
        cell(canvas, 1, y2, texts.text(slot + 1, opHz.version.toLong()) { if (opHz.value.isNaN()) DASH else formatHz(opHz.value.toFloat()) }, dimText)
        cell(canvas, 2, y1, texts.text(slot + 2, (o.tl or (o.ksl shl 8)).toLong()) {
            "TL ${o.tl}"
        }, cellText)
        cell(canvas, 2, y2, texts.text(slot + 3, o.tl.toLong()) { formatDb(-FmChannel.totalLevelDb(o.tl)) }, dimText)
        val arEff = ch.effectiveRate(o, o.ar)
        val drEff = ch.effectiveRate(o, o.dr)
        val rrEff = ch.effectiveRate(o, o.rr, true)
        cell(canvas, 3, y1, texts.text(slot + 4, o.ar.toLong()) { "AR ${o.ar}" }, cellText)
        cell(canvas, 3, y2, texts.text(slot + 5, arEff.toLong() or (ch.envelopeRate.toLong() shl 8)) { formatMs(ch.attackMs(arEff)) }, dimText)
        cell(canvas, 4, y1, texts.text(slot + 6, o.dr.toLong()) { "DR ${o.dr}" }, cellText)
        cell(canvas, 4, y2, texts.text(slot + 7, drEff.toLong() or (o.sl.toLong() shl 8) or (ch.envelopeRate.toLong() shl 16)) {
            formatMs(ch.decayMs(drEff) * FmChannel.sustainDb(o.sl) / 96f)
        }, dimText)
        cell(canvas, 5, y1, texts.text(slot + 8, o.sl.toLong()) { "SL ${o.sl}" }, cellText)
        cell(canvas, 5, y2, texts.text(slot + 9, o.sl.toLong()) { formatDb(-FmChannel.sustainDb(o.sl).toFloat()) }, dimText)
        if (ch.isOpl) {
            // percussive envelope fades at release rate even with key pressed
            cell(canvas, 6, y1, if (o.sustained) "EG hold" else "EG fade", cellText)
            cell(canvas, 6, y2, if (o.sustained) "sustains" else "decays", dimText)
            cell(canvas, 7, y1, texts.text(slot + 12, o.rr.toLong()) { "RR ${o.rr}" }, cellText)
            cell(canvas, 7, y2, texts.text(slot + 13, rrEff.toLong() or (ch.envelopeRate.toLong() shl 8)) { formatMs(ch.decayMs(rrEff)) }, dimText)
        } else {
            val srEff = ch.effectiveRate(o, o.sr)
            cell(canvas, 6, y1, texts.text(slot + 10, o.sr.toLong()) { "SR ${o.sr}" }, cellText)
            cell(canvas, 6, y2, texts.text(slot + 11, srEff.toLong() or (ch.envelopeRate.toLong() shl 8)) {
                if (o.sr == 0) "Hold" else formatMs(ch.decayMs(srEff))
            }, dimText)
            cell(canvas, 7, y1, texts.text(slot + 12, o.rr.toLong()) { "RR ${o.rr}" }, cellText)
            cell(canvas, 7, y2, texts.text(slot + 13, rrEff.toLong() or (ch.envelopeRate.toLong() shl 8)) { formatMs(ch.decayMs(rrEff)) }, dimText)
        }
    }

    private fun cell(canvas: Canvas, col: Int, y: Float, text: String, paint: Paint) {
        drawFitted(canvas, text, columns[col], y, columns[col + 1] - columns[col] - density * 2, paint)
    }

    private fun multipleText(o: FmOperator): String {
        val mul = channel.multiplier(o)
        val base = if (mul < 1f) "×½" else "×${mul.roundToInt()}"
        val dt = if (channel.isOpl) 0 else FmChannel.detune(o.dt)
        val dt2 = if (o.dt2 != 0) " D${o.dt2}" else ""
        return when {
            dt > 0 -> "$base +$dt$dt2"
            dt < 0 -> "$base $dt$dt2"
            else -> "$base$dt2"
        }
    }

    /**
     * Envelope shape in dB (as chip computes it, so segments are straight lines) over log time: attack, decay to
     * sustain level, sustain (1 second of hold, sloped by D2R or OPL percussive mode), release. The current stage is
     * bright with a dot at the current envelope level, switches are tags at top right.
     */
    private fun drawEnvelope(canvas: Canvas, r: RectF, o: FmOperator, op: Int) {
        if (r.width() < density * 20 || r.height() < density * 10) {
            return
        }
        val ch = channel
        val slDb = FmChannel.sustainDb(o.sl).toFloat()
        val attack = ch.attackMs(ch.effectiveRate(o, o.ar))
        val decay = ch.decayMs(ch.effectiveRate(o, o.dr)) * slDb / 96f
        val releaseFull = ch.decayMs(ch.effectiveRate(o, o.rr, true))
        val sustainFull = when {
            ch.isOpl -> if (o.sustained) Float.POSITIVE_INFINITY else releaseFull
            else -> ch.decayMs(ch.effectiveRate(o, o.sr))
        }
        val hold = HOLD_MS
        val sustainEnd = min(96f, slDb + (if (sustainFull.isInfinite()) 0f else 96f * hold / sustainFull))
        val release = releaseFull * (96f - sustainEnd) / 96f
        fun span(ms: Float) = if (ms.isInfinite()) INFINITE_SPAN else max(MIN_SPAN, log10(1f + ms / 2f))
        val a = if (attack.isInfinite()) INFINITE_SPAN else max(MIN_SPAN, log10(1f + attack / 2f))
        val d = if (o.ar == 0) 0f else span(decay)
        val s = SUSTAIN_SPAN
        val rl = span(release)
        val unit = r.width() / (a + d + s + rl)
        fun yOf(db: Float) = r.top + r.height() * (db / 96f).coerceIn(0f, 1f)
        xs[0] = r.left
        xs[1] = r.left + a * unit
        xs[2] = xs[1] + d * unit
        xs[3] = xs[2] + s * unit
        xs[4] = r.right
        ys[0] = r.bottom
        ys[1] = if (attack.isInfinite()) r.bottom else r.top
        ys[2] = if (attack.isInfinite()) r.bottom else yOf(slDb)
        ys[3] = if (attack.isInfinite()) r.bottom else yOf(sustainEnd)
        ys[4] = if (release.isInfinite()) ys[3] else r.bottom
        val current = when (o.stage) {
            FmChannel.STAGE_ATTACK -> 0
            FmChannel.STAGE_DECAY -> 1
            FmChannel.STAGE_SUSTAIN -> 2
            FmChannel.STAGE_RELEASE -> 3
            else -> -1
        }
        canvas.drawRect(r, frame)
        for (seg in 0 until 4) {
            path.reset()
            path.moveTo(xs[seg], r.bottom)
            path.lineTo(xs[seg], ys[seg])
            path.lineTo(xs[seg + 1], ys[seg + 1])
            path.lineTo(xs[seg + 1], r.bottom)
            path.close()
            fill.color = Color.argb(if (seg == current) 120 else 35, 255, 255, 255)
            canvas.drawPath(path, fill)
            canvas.drawLine(xs[seg], ys[seg], xs[seg + 1], ys[seg + 1], if (seg == current) boldStroke else dimStroke)
        }
        // key off moment
        canvas.drawLine(xs[3], r.top, xs[3], r.bottom, dashStroke)
        if (current >= 0) {
            val db = -FmChannel.levelDb(o.envelope)
            val y = yOf(db)
            val y0 = ys[current]
            val y1 = ys[current + 1]
            val x = if (abs(y1 - y0) < 1f) {
                (xs[current] + xs[current + 1]) / 2
            } else {
                xs[current] + (xs[current + 1] - xs[current]) * ((y - y0) / (y1 - y0)).coerceIn(0f, 1f)
            }
            fill.color = Color.WHITE
            canvas.drawCircle(x, y, density * 3, fill)
        }
        caption.color = Color.argb(200, 255, 255, 255)
        canvas.drawText(STAGE_WORDS[o.stage], r.left + density * 3, r.bottom - caption.descent() - density, caption)
        caption.color = Color.argb(140, 255, 255, 255)
        // switches
        val tags = texts.text(T_OPERATORS + op * T_PER_OPERATOR + 14, tagsKey(o)) { tagsText(o) }
        if (tags.isNotEmpty()) {
            caption.textAlign = Paint.Align.RIGHT
            val tw = caption.measureText(tags)
            fill.color = Color.argb(200, 0, 0, 0)
            canvas.drawRect(r.right - tw - density * 4, r.top + density, r.right - density, r.top + caption.fontSpacing, fill)
            canvas.drawText(tags, r.right - density * 2, r.top - caption.ascent(), caption)
            caption.textAlign = Paint.Align.LEFT
        }
    }

    private fun tagsKey(o: FmOperator) = (o.ks.toLong()) or (o.ssg.toLong() shl 4) or ((if (o.am) 1L else 0L) shl 8) or ((if (o.vib) 1L else 0L) shl 9) or
        ((if (o.ksr) 1L else 0L) shl 10) or (o.ksl.toLong() shl 12) or (channel.family.toLong() shl 16)

    private fun tagsText(o: FmOperator) = buildString {
        fun add(tag: String) {
            if (isNotEmpty()) append(' ')
            append(tag)
        }
        if (o.am) add("AM")
        if (channel.isOpl) {
            if (o.vib) add("VIB")
            if (o.ksr) add("KSR")
            if (o.ksl != 0) add("KSL ${KSL_WORDS[o.ksl]}")
        } else {
            if (o.ks != 0) add("KS${o.ks}")
            if (o.ssg and 8 != 0) add("SSG ${SSG_SHAPES[o.ssg and 7]}")
        }
    }

    // shrinks text (down to 75%) and then cuts it to fit width
    private fun drawFitted(canvas: Canvas, str: String, x: Float, y: Float, maxWidth: Float, paint: Paint) {
        if (maxWidth <= 0f) {
            return
        }
        val width = paint.measureText(str)
        if (width <= maxWidth) {
            canvas.drawText(str, x, y, paint)
            return
        }
        val size = paint.textSize
        paint.textSize = size * max(maxWidth / width, 0.75f)
        val count = paint.breakText(str, true, maxWidth, null)
        canvas.drawText(str, 0, count, x, y, paint)
        paint.textSize = size
    }

    companion object {
        const val HIT_NONE = 0L
        const val HIT_BACK = 1L
        const val HIT_TAB = 2L
        const val HIT_OPERATOR = 3L

        private const val MAX_TABS = 9
        private const val CHANNEL_CELLS = 8
        private const val MAX_CELLS = 10
        private const val COLUMNS = 8
        private const val BAR_DP = 30
        private const val CELL_GAP_DP = 10
        private const val MIN_GRAPH_DP = 64
        private const val MAX_ROW_DP = 64
        private const val MIN_TABLE_SCALE = 0.75f

        private const val CAPTION_SP = 10f
        private const val VALUE_SP = 13f
        private const val TABLE_SP = 12f
        private const val TITLE_SP = 13f

        // envelope graph time axis
        private const val HOLD_MS = 1000f
        private const val SUSTAIN_SPAN = 1.2f
        private const val INFINITE_SPAN = 2.2f
        private const val MIN_SPAN = 0.08f

        // texts slots
        private const val T_NOTE = 0
        private const val T_PITCH = 1
        private const val T_FNUM = 2
        private const val T_ALG = 3
        private const val T_FB = 4
        private const val T_LFO_SENS = 5
        private const val T_VOLUME = 6
        private const val T_LFO = 7
        private const val T_AMD = 8
        private const val T_PMD = 9
        private const val T_NOISE = 10
        private const val T_CH3 = 11
        private const val T_PAIRS = 12
        private const val T_FEEDBACK_LEGEND = 13
        private const val T_OPERATORS = 16
        private const val T_PER_OPERATOR = 16
        private const val TEXTS = T_OPERATORS + 4 * T_PER_OPERATOR

        private const val BACK_MARK = "‹ "
        private const val ENVELOPE_HEADER = "Envelope ● now"
        private const val ROLE_CARRIER = "carrier"
        private const val ROLE_MODULATOR = "mod."
        private const val NO_DETAILS = "Operators parameters are not reported by this emulation core"

        private val LEGEND_CARRIER = arrayOf("Carrier: heard, goes to output", "Carrier: heard")
        private val LEGEND_MODULATOR = arrayOf("Modulator: shapes timbre of its target", "Modulator: timbre")
        private val LEGEND_NO_FEEDBACK = arrayOf("No feedback on OP1", "No feedback")
        private val LEGEND_FILL = arrayOf("Fill: level now, thick: key on", "Fill: level now")

        private val OPN_HEADERS = arrayOf("Op", "Multiple", "Level", "Attack", "Decay", "Sustain", "Decay 2", "Release")
        private val OPN_TEMPLATES = arrayOf("carrier", "4.40 kHz", "\u221295.3 dB", "1.05 s", "738 ms", "\u221293 dB", "29.5 s", "923 ms")
        private val OPL_HEADERS = arrayOf("Op", "Multiple", "Level", "Attack", "Decay", "Sustain", "Type", "Release")
        private val OPL_TEMPLATES = arrayOf("carrier ∿∿", "4.40 kHz", "\u221247.3 dB", "1.05 s", "738 ms", "\u221293 dB", "sustains", "923 ms")
        private val CHANNEL_TEMPLATE = "220.0 Hz"
        private val CHIP_TEMPLATE = "48.1 Hz"
        private val PAN_WORDS = arrayOf("Muted", "Right", "Left", "Both")
        private val LFO_WAVES = arrayOf("saw", "square", "triangle", "noise")

        // OPL key scale level attenuation per octave
        private val KSL_WORDS = arrayOf("", "3dB/oct", "1.5dB/oct", "6dB/oct")
        private val STAGE_WORDS = arrayOf("off", "attack", "decay", "sustain", "release")

        // SSG-EG shapes (with bit 3 set): repeat, once, alternate, once and hold, for both directions
        private val SSG_SHAPES = arrayOf("\\\\\\", "\\__", "\\/\\", "\\‾‾", "///", "/‾‾", "/\\/", "/__")

        // the same notation as other dashboards regardless of locale: decimal point, minus sign, em dash for none
        private const val DASH = "\u2014"
        private const val MINUS = "\u2212"
        private const val ON = "On"
        private const val OFF = "Off"

        private fun String.formatUs(value: Float) = String.format(java.util.Locale.US, this, value)

        /**
         * @param coarse 3 significant digits, e.g. for bounds of range
         */
        fun formatHz(hz: Float, coarse: Boolean = false) = when {
            hz >= 10000f -> "%.1f kHz".formatUs(hz / 1000f)
            hz >= 1000f -> "%.2f kHz".formatUs(hz / 1000f)
            coarse && hz >= 100f -> "${hz.roundToInt()} Hz"
            hz >= 100f -> "%.1f Hz".formatUs(hz)
            hz >= 1f -> "%.2f Hz".formatUs(hz)
            else -> "%.3f Hz".formatUs(hz)
        }

        fun formatMs(ms: Float) = when {
            ms.isInfinite() -> "∞"
            ms < 0.05f -> "0 ms"
            ms < 10f -> "%.1f ms".formatUs(ms)
            ms < 1000f -> "${ms.roundToInt()} ms"
            ms < 10000f -> "%.2f s".formatUs(ms / 1000f)
            else -> "%.1f s".formatUs(ms / 1000f)
        }

        fun formatDb(db: Float) = if (db == 0f) {
            "0 dB"
        } else if (db % 1f == 0f) {
            "${db.toInt()} dB".replace("-", MINUS)
        } else {
            "%.1f dB".formatUs(db).replace("-", MINUS)
        }

        private fun opl3Pairs(ch: FmChannel): String {
            if (ch.global and FmChannel.GLOBAL_OPL3 == 0) {
                return "OPL2 mode"
            }
            val mask = ch.global2 and 0x3f
            if (mask == 0) {
                return "None"
            }
            return buildString {
                for (bit in 0 until 6) {
                    if (mask and (1 shl bit) != 0) {
                        val first = (bit % 3) + 1 + (bit / 3) * 9
                        if (isNotEmpty()) append(' ')
                        append(first).append('+').append(first + 3)
                    }
                }
            }
        }
    }
}

/**
 * Text rebuilt only when [key] is changed
 */
internal inline fun LiveTexts.text(idx: Int, key: Long, make: () -> String): String {
    if (changed(idx, key)) {
        set(idx, make())
    }
    return get(idx)
}
