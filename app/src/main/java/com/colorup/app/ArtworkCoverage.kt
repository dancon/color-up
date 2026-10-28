package com.colorup.app

import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import com.colorup.app.ui.drawColoringStroke
import com.colorup.app.ui.toPath
import com.colorup.core.LineArt
import com.colorup.core.PageProgress
import com.colorup.core.Stroke

/**
 * Samples the actual textured paint, without counting line art as pigment.
 * Each mask subtracts all foreground regions, so only a region's visible area is its denominator.
 * One instance belongs to the single coverage worker; Android paths/bitmaps are never shared with UI.
 */
internal class ArtworkCoverage(private val page: LineArt) : AutoCloseable {
    private val bitmap = createBitmap(RESOLUTION, RESOLUTION)
    private val canvas = android.graphics.Canvas(bitmap)
    private val pixels = IntArray(RESOLUTION * RESOLUTION)
    private val paths = page.regions.map { it.shape.toPath().asAndroidPath() }
    private val masks = buildMasks()
    private val previous = mutableMapOf<String, List<Stroke>>()
    private val coverage = mutableMapOf<String, Float>()

    fun measure(strokes: List<Stroke>): PageProgress {
        val byRegion = strokes.groupBy { it.regionId }
        page.regions.forEachIndexed { index, region ->
            val regionStrokes = byRegion[region.id].orEmpty()
            if (previous[region.id] != regionStrokes) {
                coverage[region.id] = measureRegion(regionStrokes, masks[index])
                previous[region.id] = regionStrokes
            }
        }
        return PageProgress.from(page, coverage, strokes)
    }

    private fun buildMasks(): List<IntArray> {
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        return paths.indices.map { index ->
            bitmap.eraseColor(Color.TRANSPARENT)
            canvas.withScale(SCALE, SCALE) {
                drawPath(paths[index], white)
                for (foreground in index + 1 until paths.size) drawPath(paths[foreground], clear)
            }
            bitmap.getPixels(pixels, 0, RESOLUTION, 0, 0, RESOLUTION, RESOLUTION)
            pixels.indices.filter { Color.alpha(pixels[it]) > MASK_ALPHA_THRESHOLD }.toIntArray()
        }
    }

    private fun measureRegion(strokes: List<Stroke>, mask: IntArray): Float {
        if (strokes.isEmpty() || mask.isEmpty()) return 0f
        bitmap.eraseColor(Color.WHITE)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(canvas), Size(RESOLUTION.toFloat(), RESOLUTION.toFloat())) {
            scale(SCALE, SCALE, pivot = Offset.Zero) {
                strokes.forEach { drawColoringStroke(it) }
            }
        }
        bitmap.getPixels(pixels, 0, RESOLUTION, 0, 0, RESOLUTION, RESOLUTION)
        val painted = mask.count { index ->
            val pixel = pixels[index]
            Color.red(pixel) < PAPER_CHANNEL_THRESHOLD || Color.green(pixel) < PAPER_CHANNEL_THRESHOLD || Color.blue(pixel) < PAPER_CHANNEL_THRESHOLD
        }
        return painted.toFloat() / mask.size
    }

    override fun close() { bitmap.recycle() }

    companion object {
        const val RESOLUTION = 160
        private const val SCALE = RESOLUTION / 1000f
        private const val MASK_ALPHA_THRESHOLD = 16
        private const val PAPER_CHANNEL_THRESHOLD = 250
    }
}
