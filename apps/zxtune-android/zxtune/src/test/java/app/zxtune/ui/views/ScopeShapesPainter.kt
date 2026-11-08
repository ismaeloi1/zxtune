package app.zxtune.ui.views

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Canvas reference of GPU backends for render tests: the same shapes (see [ScopeShapes]) drawn approximately, over
 * static background and white traces as oscilloscope does
 */
internal object ScopeShapesPainter {

    fun picture(
        width: Int,
        height: Int,
        background: Bitmap,
        traces: FloatArray,
        channels: Int,
        samples: (Int, Int) -> Float,
        shapes: ScopeShapes,
        atlas: Bitmap,
    ): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(background, 0f, 0f, Paint().apply { color = Color.WHITE })
        val trace = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        for (chan in 0 until channels) {
            val path = Path()
            val left = traces[chan * 4]
            val w = traces[chan * 4 + 1]
            val center = traces[chan * 4 + 2]
            val ampl = traces[chan * 4 + 3]
            for (idx in 0 until POINTS) {
                val x = left + w * idx / (POINTS - 1)
                val y = center - ampl * samples(chan, idx)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, trace)
        }
        draw(canvas, shapes, atlas)
        return result
    }

    fun draw(canvas: Canvas, shapes: ScopeShapes, atlas: Bitmap) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val d = shapes.data
        for (idx in 0 until shapes.count) {
            val b = idx * ScopeShapes.FLOATS
            paint.reset()
            paint.isAntiAlias = true
            paint.color = Color.argb((d[b + 15] * 255).toInt(), (d[b + 12] * 255).toInt(), (d[b + 13] * 255).toInt(), (d[b + 14] * 255).toInt())
            when (d[b]) {
                ScopeShapes.SEGMENT -> {
                    paint.strokeWidth = d[b + 1] * 2
                    paint.strokeCap = Paint.Cap.ROUND
                    canvas.drawLine(d[b + 4], d[b + 5], d[b + 6], d[b + 7], paint)
                }

                ScopeShapes.CIRCLE -> {
                    val stroke = d[b + 2]
                    if (stroke > 0) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = stroke
                        canvas.drawCircle(d[b + 4], d[b + 5], d[b + 1] - stroke / 2, paint)
                    } else {
                        canvas.drawCircle(d[b + 4], d[b + 5], d[b + 1], paint)
                    }
                }

                ScopeShapes.GLYPH -> {
                    val src = Rect(
                        (d[b + 8] * atlas.width).toInt(),
                        (d[b + 9] * atlas.height).toInt(),
                        (d[b + 10] * atlas.width + 0.5f).toInt(),
                        (d[b + 11] * atlas.height + 0.5f).toInt()
                    )
                    canvas.drawBitmap(atlas, src, RectF(d[b + 4], d[b + 5], d[b + 6], d[b + 7]), paint)
                }

                else -> {
                    val path = Path().apply {
                        moveTo(d[b + 4], d[b + 5])
                        lineTo(d[b + 6], d[b + 7])
                        lineTo(d[b + 6], d[b + 9])
                        lineTo(d[b + 4], d[b + 8])
                        close()
                    }
                    val top = minOf(d[b + 5], d[b + 7])
                    val bottom = maxOf(d[b + 8], d[b + 9])
                    val color = paint.color
                    val bottomColor = Color.argb((Color.alpha(color) * d[b + 2]).toInt(), Color.red(color), Color.green(color), Color.blue(color))
                    paint.isAntiAlias = false
                    paint.shader = LinearGradient(0f, top, 0f, maxOf(bottom, top + 1), color, bottomColor, Shader.TileMode.CLAMP)
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    /**
     * Scene for Vulkan host test (see src/main/vulkan/test/offscreen.cpp): little endian u32 width, height, channels,
     * shapes count, atlas width, atlas height, then background alpha, traces, shapes, atlas alpha
     */
    fun dump(file: File, background: Bitmap, traces: FloatArray, channels: Int, shapes: ScopeShapes, atlas: Bitmap) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            fun f32(v: Float) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array())
            u32(background.width)
            u32(background.height)
            u32(channels)
            u32(shapes.count)
            u32(atlas.width)
            u32(atlas.height)
            out.write(alphaOf(background))
            for (idx in 0 until channels * 4) f32(traces[idx])
            for (idx in 0 until shapes.count * ScopeShapes.FLOATS) f32(shapes.data[idx])
            out.write(alphaOf(atlas))
        }
    }

    private fun alphaOf(bitmap: Bitmap): ByteArray {
        val result = ByteArray(bitmap.width * bitmap.height)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                result[y * bitmap.width + x] = Color.alpha(bitmap.getPixel(x, y)).toByte()
            }
        }
        return result
    }

    const val POINTS = 512
}
