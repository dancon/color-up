package com.colorup.app

import android.graphics.Color
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.colorup.app.ui.ColoringCanvas
import com.colorup.app.ui.drawArtwork
import com.colorup.core.ArtworkCatalog
import com.colorup.core.Point
import com.colorup.core.Stroke
import com.colorup.core.Tool
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Checks the real raster ink and the derived region paths together, on GPU and PNG canvases. */
class IllustratedArtworkRenderTest {
    @get:Rule val compose = createComposeRule()
    private val page get() = ArtworkCatalog.pages.first()
    private val sweep = buildList {
        for ((row, y) in (0..1000 step 80).withIndex()) {
            add(Point(if (row % 2 == 0) 0f else 1000f, y.toFloat()))
            add(Point(if (row % 2 == 0) 1000f else 0f, y.toFloat()))
        }
    }
    private fun pigment(region: String) = Stroke(region, 0xFFFF5C5C, 200f, Tool.CRAYON, sweep)

    @Test fun originalInkStaysDarkAndThePngDoesNotHideTexturedPigment() {
        val blank = render(emptyList())
        val strokes = page.regions.map { pigment(it.id) }
        val painted = render(strokes)
        var darkInk = 0
        var coloredPaper = 0
        for (index in blank.indices) {
            if (Color.red(blank[index]) < 50 && Color.green(blank[index]) < 50 && Color.blue(blank[index]) < 50) {
                darkInk++
                assertTrue("Original ink must remain above every color", Color.red(painted[index]) < 65 &&
                    Color.green(painted[index]) < 65 && Color.blue(painted[index]) < 65)
            }
            if (Color.green(blank[index]) > 250 && Color.green(painted[index]) < 220) coloredPaper++
        }
        assertTrue("The illustration must contain its real ink", darkInk > 10000)
        assertTrue("White PNG paper must let colored wax show through", coloredPaper > 300000)
        assertArrayEquals(painted, render(strokes.map { it.copy(points = it.points.toList()) }))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "illustrated-export-${UUID.randomUUID()}.png")
        try {
            ArtworkExporter.write(context.contentResolver, Uri.fromFile(file), ExportRequest(page, strokes))
            val png = requireNotNull(BitmapFactory.decodeFile(file.path))
            try {
                assertEquals(1080, png.width)
                assertEquals(1200, png.height)
                val paper = IntArray(1000 * 1000)
                png.getPixels(paper, 0, 1000, 40, 40, 1000, 1000)
                // The exporter translates its canvas by 40px. Android's bitmap filter can round
                // a channel one level differently at that origin; the artwork/grain must still match.
                val largestDifference = paper.indices.maxOf { index ->
                    maxOf(kotlin.math.abs(Color.red(paper[index]) - Color.red(painted[index])),
                        kotlin.math.abs(Color.green(paper[index]) - Color.green(painted[index])),
                        kotlin.math.abs(Color.blue(paper[index]) - Color.blue(painted[index])))
                }
                assertTrue("PNG must preserve illustrated ink and grain (max channel difference=$largestDifference)",
                    largestDifference <= 2)
            } finally { png.recycle() }
        } finally { file.delete() }
    }

    @Test fun paintingOneDerivedRegionLeavesOtherWhiteAreasUntouched() {
        val selected = page.regions.drop(1).ifEmpty { page.regions }.maxBy { region ->
            (25..975 step 50).sumOf { y -> (25..975 step 50).count { x ->
                region.shape.contains(Point(x.toFloat(), y.toFloat()))
            } }
        }
        val blank = render(emptyList())
        val painted = render(listOf(pigment(selected.id)))
        var sampledNeighbors = 0
        var sampledPigment = 0
        for (y in 10..985 step 5) for (x in 10..985 step 5) {
            val point = Point(x.toFloat(), y.toFloat())
            val index = y * 1000 + x
            if (selected.shape.contains(point)) {
                if (Color.green(painted[index]) < Color.green(blank[index]) - 20) sampledPigment++
            } else if (listOf(Point(point.x - 3, point.y), Point(point.x + 3, point.y),
                    Point(point.x, point.y - 3), Point(point.x, point.y + 3)).none { selected.shape.contains(it) }) {
                assertEquals("A derived mask must protect its neighboring areas", blank[index], painted[index])
                sampledNeighbors++
            }
        }
        assertTrue(sampledNeighbors > 1000)
        assertTrue(sampledPigment > 1000)
    }

    @Test fun gpuCanvasDisplaysOriginalInkAndCrayonTogether() {
        compose.setContent {
            ColoringCanvas(page, page.regions.map { pigment(it.id) }, null, Modifier.size(300.dp))
        }
        val pixels = compose.onNodeWithTag("coloring_canvas").captureToImage().toPixelMap()
        var ink = 0
        var color = 0
        for (y in 0 until pixels.height step 2) for (x in 0 until pixels.width step 2) {
            val pixel = pixels[x, y]
            if (pixel.red < .2f && pixel.green < .2f && pixel.blue < .2f) ink++
            if (pixel.red > .8f && pixel.green < .8f && pixel.red - pixel.green > .15f) color++
        }
        assertTrue("GPU must retain the illustrated line art", ink > 1000)
        assertTrue("GPU multiply blending must retain the pigment", color > 20000)
    }

    private fun render(strokes: List<Stroke>): IntArray {
        val bitmap = createBitmap(1000, 1000)
        try {
            val native = android.graphics.Canvas(bitmap)
            native.drawColor(Color.WHITE)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(native), Size(1000f, 1000f)) {
                drawArtwork(page, strokes)
            }
            return IntArray(1000 * 1000).also { bitmap.getPixels(it, 0, 1000, 0, 0, 1000, 1000) }
        } finally { bitmap.recycle() }
    }
}
