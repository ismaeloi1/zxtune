/**
 * @file
 * @brief Antialiased shapes drawn over oscilloscope picture by rendering backends
 * @author vitamin.caig@gmail.com
 */
package app.zxtune.ui.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Frame's shapes (e.g. filter response view, see [FilterScene]) in pixels of view, 4 vectors per shape:
 * - kind and its parameters
 * - first geometry vector
 * - second geometry vector
 * - straight alpha color (0..1)
 * Layout is shared with Vulkan shaders (see src/main/vulkan/shaders/shape.vert) and OpenGL ES renderer
 * (see [OscilloscopeView]), coverage is computed by distance as for traces. Buffer is reused, so no allocations per frame.
 */
internal class ScopeShapes {
    val data = FloatArray(MAX * FLOATS)

    var count = 0
        private set

    fun clear() {
        count = 0
    }

    /**
     * Polyline's segment from (x0, y0) to (x1, y1) with neighbour points (prevX, prevY) and (nextX, nextY) to split
     * joints by bisectors (no joint if neighbour is the same as segment's end), zero length one is a disc
     */
    fun segment(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        prevX: Float,
        prevY: Float,
        nextX: Float,
        nextY: Float,
        width: Float,
        color: Int,
        alpha: Float = 1f,
    ) = put(SEGMENT, width / 2, 0f, 0f, x0, y0, x1, y1, prevX, prevY, nextX, nextY, color, alpha)

    /** Separate line with round caps */
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, width: Float, color: Int, alpha: Float = 1f) = segment(x0, y0, x1, y1, x0, y0, x1, y1, width, color, alpha)

    /**
     * @param stroke ring's width, 0 for disc
     */
    fun circle(x: Float, y: Float, radius: Float, stroke: Float, color: Int, alpha: Float = 1f) = put(CIRCLE, radius, stroke, 0f, x, y, 0f, 0f, 0f, 0f, 0f, 0f, color, alpha)

    /**
     * Rectangle of view textured by part of glyphs atlas (normalized coordinates)
     */
    fun glyph(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        color: Int,
        alpha: Float = 1f,
    ) = put(GLYPH, 0f, 0f, 0f, left, top, right, bottom, u0, v0, u1, v1, color, alpha)

    /**
     * Area between top segment from (x0, y0) to (x1, y1) and bottom one from (x0, bottom0) to (x1, bottom1), vertical
     * gradient of alpha down to [bottomAlpha] part of [alpha]. Neighbour fills share edges exactly, so they are not
     * blended twice.
     */
    fun fill(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        bottom0: Float,
        bottom1: Float,
        bottomAlpha: Float,
        color: Int,
        alpha: Float,
    ) = put(FILL, 0f, bottomAlpha, 0f, x0, y0, x1, y1, bottom0, bottom1, 0f, 0f, color, alpha)

    private fun put(
        kind: Float,
        p0: Float,
        p1: Float,
        p2: Float,
        a0: Float,
        a1: Float,
        a2: Float,
        a3: Float,
        b0: Float,
        b1: Float,
        b2: Float,
        b3: Float,
        color: Int,
        alpha: Float,
    ): Boolean {
        if (count == MAX) {
            return false
        }
        val base = count * FLOATS
        data[base] = kind
        data[base + 1] = p0
        data[base + 2] = p1
        data[base + 3] = p2
        data[base + 4] = a0
        data[base + 5] = a1
        data[base + 6] = a2
        data[base + 7] = a3
        data[base + 8] = b0
        data[base + 9] = b1
        data[base + 10] = b2
        data[base + 11] = b3
        data[base + 12] = Color.red(color) / 255f
        data[base + 13] = Color.green(color) / 255f
        data[base + 14] = Color.blue(color) / 255f
        data[base + 15] = Color.alpha(color) / 255f * alpha.coerceIn(0f, 1f)
        ++count
        return true
    }

    companion object {
        /** See ScopeRenderer::MAX_SHAPES */
        const val MAX = 4096
        const val FLOATS = 16

        // see shaders/shape.glsl
        const val SEGMENT = 0f
        const val CIRCLE = 1f
        const val GLYPH = 2f
        const val FILL = 3f
    }
}

/**
 * Glyphs of text styles rendered once by platform (the same font and antialiasing as other texts) into single channel
 * texture, so per frame text is a few textured rectangles of any color (see [ScopeShapes.glyph]) instead of texture
 * upload. Glyphs are placed at whole pixels with texture's scale, so they are as sharp as canvas text.
 * Kerning is not applied, texts are short labels.
 * @param styles text paints, only size and typeface are used
 * @param chars all the characters that may be drawn
 */
internal class GlyphAtlas(styles: Array<Paint>, chars: String) {
    private val sorted = chars.toSortedSet().toCharArray()
    private val count = sorted.size

    // [style][char]: advance, then rectangle relative to pen position in pixels and in atlas
    private val advances = Array(styles.size) { FloatArray(count) }
    private val boxes = Array(styles.size) { IntArray(count * 4) }
    private val uvs = Array(styles.size) { FloatArray(count * 4) }
    private val ascents = FloatArray(styles.size)
    private val descents = FloatArray(styles.size)

    val bitmap: Bitmap

    init {
        val bounds = Rect()
        val paints = Array(styles.size) { idx ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = styles[idx].textSize
                typeface = styles[idx].typeface
                color = Color.WHITE
            }
        }
        // shelf packing by rows of the same style
        val width = ATLAS_WIDTH
        var x = 0
        var y = 0
        var rowHeight = 0
        val places = Array(styles.size) { IntArray(count * 2) }
        for (style in paints.indices) {
            val paint = paints[style]
            ascents[style] = paint.ascent()
            descents[style] = paint.descent()
            for (idx in 0 until count) {
                val text = sorted[idx].toString()
                advances[style][idx] = paint.measureText(text)
                paint.getTextBounds(text, 0, 1, bounds)
                val box = boxes[style]
                if (bounds.isEmpty) {
                    box[idx * 4 + 2] = 0
                    continue
                }
                val w = bounds.width() + 2 * PADDING
                val h = bounds.height() + 2 * PADDING
                if (x + w > width) {
                    x = 0
                    y += rowHeight
                    rowHeight = 0
                }
                box[idx * 4] = bounds.left - PADDING
                box[idx * 4 + 1] = bounds.top - PADDING
                box[idx * 4 + 2] = w
                box[idx * 4 + 3] = h
                places[style][idx * 2] = x
                places[style][idx * 2 + 1] = y
                x += w
                rowHeight = maxOf(rowHeight, h)
            }
        }
        val height = maxOf(1, y + rowHeight)
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        for (style in paints.indices) {
            val box = boxes[style]
            val uv = uvs[style]
            for (idx in 0 until count) {
                val w = box[idx * 4 + 2]
                if (w == 0) {
                    continue
                }
                val h = box[idx * 4 + 3]
                val left = places[style][idx * 2]
                val top = places[style][idx * 2 + 1]
                canvas.drawText(sorted, idx, 1, (left - box[idx * 4]).toFloat(), (top - box[idx * 4 + 1]).toFloat(), paints[style])
                uv[idx * 4] = left.toFloat() / width
                uv[idx * 4 + 1] = top.toFloat() / height
                uv[idx * 4 + 2] = (left + w).toFloat() / width
                uv[idx * 4 + 3] = (top + h).toFloat() / height
            }
        }
    }

    fun ascent(style: Int) = ascents[style]

    fun descent(style: Int) = descents[style]

    private fun index(char: Char) = sorted.binarySearch(char)

    fun width(style: Int, text: CharArray, length: Int): Float {
        var result = 0f
        for (pos in 0 until length) {
            val idx = index(text[pos])
            if (idx >= 0) {
                result += advances[style][idx]
            }
        }
        return result
    }

    /**
     * @return pen position after text
     */
    fun draw(
        shapes: ScopeShapes,
        style: Int,
        text: CharArray,
        length: Int,
        x: Float,
        baseline: Float,
        color: Int,
        alpha: Float = 1f,
    ): Float {
        var pen = x
        val y = baseline.roundToInt()
        val box = boxes[style]
        val uv = uvs[style]
        for (pos in 0 until length) {
            val idx = index(text[pos])
            if (idx < 0) {
                continue
            }
            val w = box[idx * 4 + 2]
            if (w != 0) {
                val left = (pen.roundToInt() + box[idx * 4]).toFloat()
                val top = (y + box[idx * 4 + 1]).toFloat()
                shapes.glyph(
                    left,
                    top,
                    left + w,
                    top + box[idx * 4 + 3],
                    uv[idx * 4],
                    uv[idx * 4 + 1],
                    uv[idx * 4 + 2],
                    uv[idx * 4 + 3],
                    color,
                    alpha
                )
            }
            pen += advances[style][idx]
        }
        return pen
    }

    private companion object {
        const val ATLAS_WIDTH = 512

        // antialiased edges stay inside of glyph's rectangle
        const val PADDING = 1
    }
}

/**
 * Text composed without allocations, e.g. from numbers changing at each frame
 */
internal class TextLine(capacity: Int = 96) {
    val chars = CharArray(capacity)
    var length = 0
        private set

    fun clear(): TextLine = apply { length = 0 }

    fun append(c: Char): TextLine = apply {
        if (length < chars.size) {
            chars[length++] = c
        }
    }

    fun append(s: String): TextLine = apply {
        for (c in s) {
            append(c)
        }
    }

    fun append(value: Int): TextLine = apply {
        if (value < 0) {
            append(MINUS)
            append(-value)
        } else {
            if (value >= 10) {
                append(value / 10)
            }
            append('0' + value % 10)
        }
    }

    override fun toString() = String(chars, 0, length)

    companion object {
        /** Typographic minus as in dashboard */
        const val MINUS = '−'
    }
}
