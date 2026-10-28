package com.colorup.app.ui

import android.graphics.Bitmap
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.core.graphics.createBitmap
import com.colorup.core.ColoringSession
import com.colorup.core.Stroke

/**
 * Pigment is anchored to the 1000-unit paper, rather than to a gesture or the device frame.
 * The fixed paper grain therefore survives redraws, undo/restore and PNG export unchanged.
 */
internal fun DrawScope.drawCrayonStroke(stroke: Stroke) {
    if (stroke.points.isEmpty()) return
    val pigment = CrayonPigments.forStroke(stroke)
    val path = if (stroke.points.size > 1) Path().apply {
        moveTo(stroke.points.first().x, stroke.points.first().y)
        for (index in 1 until stroke.points.size) {
            val point = stroke.points[index]
            lineTo(point.x, point.y)
        }
    } else null
    if (path == null) {
        val center = Offset(stroke.points.first().x, stroke.points.first().y)
        drawCircle(pigment.edge, stroke.width / 2f, center)
        drawCircle(pigment.core, stroke.width * .44f, center)
    } else {
        drawPath(path, pigment.edge, style = DrawStroke(stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, pigment.core, style = DrawStroke(stroke.width * .88f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    if (stroke.gradientStops.size >= 2) {
        // Short, rounded white flecks follow the same clipped stroke, just like the prototype.
        if (path == null) {
            drawCircle(Color.White.copy(alpha = .5f), stroke.width * .08f,
                Offset(stroke.points.first().x, stroke.points.first().y))
        } else {
            drawPath(path, Color.White.copy(alpha = .5f), style = DrawStroke(
                width = maxOf(5f, stroke.width * .28f), cap = StrokeCap.Round,
                join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.75f, 27.5f)),
            ))
        }
    }
}

private data class CrayonPigment(val edge: Brush, val core: Brush)

/**
 * ShaderBrush caches mutable drawing state internally. Each drawing thread therefore owns its
 * bounded pigment cache; the UI and background PNG export never apply the same brush concurrently.
 * Each thread retains 128 solid pigments and enough gradient shaders for a full page plus an
 * active stroke. Their separate caches prevent a long gradient drawing from evicting itself on
 * every redraw. Gradients share two alpha tiles, so extending a stroke never allocates grain
 * bitmaps. Cached solid pixels use about 5.7 MiB for the complete 81-color preset palette.
 * No in-use bitmap is recycled.
 */
private object CrayonPigments {
    private const val TILE_SIZE = 96
    private const val CACHE_SIZE = 128
    private const val GRADIENT_CACHE_SIZE = ColoringSession.MAX_STROKES + 20
    private val solidCache = ThreadLocal.withInitial {
        LinkedHashMap<PigmentKey, CrayonPigment>(CACHE_SIZE, .75f, true)
    }
    private val gradientCache = ThreadLocal.withInitial {
        LinkedHashMap<PigmentKey, CrayonPigment>(GRADIENT_CACHE_SIZE, .75f, true)
    }
    private val paperShaders = ThreadLocal.withInitial {
        pigmentShader(0xFFFFFFFF, edgeAlpha) to pigmentShader(0xFFFFFFFF, coreAlpha)
    }
    private val edgeAlpha = IntArray(TILE_SIZE * TILE_SIZE)
    private val coreAlpha = IntArray(TILE_SIZE * TILE_SIZE)

    init {
        for (y in 0 until TILE_SIZE) {
            for (x in 0 until TILE_SIZE) {
                val fine = noise(x, y, 17)
                val clustered = noise(x / 2, y / 2, 41)
                val fiberSeed = noise(x / 12, y / 24, 83)
                val fiberX = 2 + fiberSeed % 9
                val fiberY = 2 + (fiberSeed / 11) % 6
                val fiberLength = 6 + (fiberSeed / 67) % 12
                val fiber = fiberSeed % 3 == 0 && x % 12 == fiberX && y % 24 in fiberY until fiberY + fiberLength
                // Small bare-paper islands and short dry streaks are shared by both pigment layers.
                val barePaper = fine % 29 == 0 || clustered % 23 == 0 || fiber
                val index = y * TILE_SIZE + x
                if (!barePaper) {
                    val mottling = noise(x / 6, y / 6, 127) % 39
                    edgeAlpha[index] = 18 + fine % 47
                    coreAlpha[index] = (105 + fine % 82 - mottling).coerceIn(68, 186)
                }
            }
        }
    }

    private data class PigmentKey(
        val argb: Long, val stops: List<Long>,
        val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f,
    )

    fun forStroke(stroke: Stroke): CrayonPigment {
        val stops = stroke.gradientStops
        val key = if (stops.size < 2) PigmentKey(stroke.colorArgb, emptyList()) else {
            // The gradient spans this stroke, so even a short swipe reveals the chosen colors.
            val radius = stroke.width / 2f
            PigmentKey(stroke.colorArgb, stops.toList(),
                stroke.points.minOf { it.x } - radius, stroke.points.minOf { it.y } - radius,
                stroke.points.maxOf { it.x } + radius, stroke.points.maxOf { it.y } + radius)
        }
        val cache = checkNotNull(if (key.stops.isEmpty()) solidCache.get() else gradientCache.get())
        val limit = if (key.stops.isEmpty()) CACHE_SIZE else GRADIENT_CACHE_SIZE
        return cache[key] ?: CrayonPigment(
            edge = if (key.stops.isEmpty()) pigmentBrush(key.argb, edgeAlpha) else gradientBrush(key, checkNotNull(paperShaders.get()).first),
            core = if (key.stops.isEmpty()) pigmentBrush(key.argb, coreAlpha) else gradientBrush(key, checkNotNull(paperShaders.get()).second),
        ).also { pigment ->
            cache[key] = pigment
            if (cache.size > limit) cache.remove(cache.keys.first())
        }
    }

    private fun gradientBrush(key: PigmentKey, grain: Shader): Brush {
        val color = LinearGradient(key.left, key.top, key.right, key.bottom,
            key.stops.map(Long::toInt).toIntArray(), null, Shader.TileMode.CLAMP)
        // DST_IN multiplies gradient alpha by the grain without repeating the gradient colors.
        return ShaderBrush(ComposeShader(color, grain, PorterDuff.Mode.DST_IN))
    }

    private fun pigmentBrush(argb: Long, alpha: IntArray): Brush = ShaderBrush(pigmentShader(argb, alpha))

    private fun pigmentShader(argb: Long, alpha: IntArray): Shader {
        val rgb = argb.toInt() and 0x00FFFFFF
        val pixels = IntArray(alpha.size) { index -> (alpha[index] shl 24) or rgb }
        val bitmap = createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
        return ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)
    }

    private fun noise(x: Int, y: Int, salt: Int): Int {
        var value = x * 0x1f123bb5 + y * 0x5f356495 + salt * 0x2c1b3c6d
        value = (value xor (value ushr 16)) * 0x45d9f3b
        value = (value xor (value ushr 16)) * 0x45d9f3b
        return (value xor (value ushr 16)) and Int.MAX_VALUE
    }
}
