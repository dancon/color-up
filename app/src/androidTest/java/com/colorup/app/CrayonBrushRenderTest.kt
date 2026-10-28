package com.colorup.app

import android.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.createBitmap
import com.colorup.app.ui.drawArtwork
import com.colorup.core.ColoringRegion
import com.colorup.core.LineArt
import com.colorup.core.Point
import com.colorup.core.Shape
import com.colorup.core.Stroke
import com.colorup.core.Tool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses logical paper pixels directly so grain remains observable independent of emulator density. */
class CrayonBrushRenderTest {
    private val paper = LineArt(
        "texture", "蜡笔质感", "",
        listOf(ColoringRegion("paper", Shape.Oval(50f, 50f, 950f, 950f))),
        emptyList(),
    )

    @Test fun grainHasBarePaperAndPigmentVariationAndOverlappingPassesGetDarker() {
        val stroke = crayon(64f)
        val single = render(listOf(stroke))
        val double = render(listOf(stroke, stroke))
        var white = 0
        var pigment = 0
        var darkest = 255
        var lightestPigment = 0
        var firstSum = 0L
        var secondSum = 0L
        var count = 0
        for (y in 487..513) {
            for (x in 200..800) {
                val first = single[y * 1000 + x]
                val second = double[y * 1000 + x]
                val green = Color.green(first)
                assertEquals("Grain must keep the chosen red hue", 255, Color.red(first))
                assertEquals(Color.green(first), Color.blue(first))
                if (first == Color.WHITE) white++
                if (green < 220) {
                    pigment++
                    darkest = minOf(darkest, green)
                    lightestPigment = maxOf(lightestPigment, green)
                }
                assertTrue("A second pass cannot lighten pigment", Color.green(second) <= green)
                firstSum += green
                secondSum += Color.green(second)
                count++
            }
        }
        assertTrue("Crayon should leave tiny bare-paper islands", white > 0)
        assertTrue("Most of a stroke should contain pigment", pigment > count * .6)
        assertTrue("The pigment itself should have visible grain", lightestPigment - darkest > 30)
        assertTrue("Overlapping passes should visibly deepen the color", secondSum.toDouble() / count < firstSum.toDouble() / count - 20)
    }

    @Test fun committedActiveAndRestoredStrokesRenderExactlyTheSameGrain() {
        val stroke = crayon(64f)
        val committed = render(listOf(stroke))
        val restored = render(listOf(stroke.copy(points = stroke.points.map { Point(it.x, it.y) })))
        val active = render(emptyList(), activeStroke = stroke)
        assertArrayEquals("Replaying restored strokes must not change grain", committed, restored)
        assertArrayEquals("Lifting the finger must not change grain", committed, active)
    }

    @Test fun singlePointUsesTexturedPigmentAtEveryBrushWidth() {
        for (width in listOf(18f, 36f, 64f)) {
            val pointStroke = crayon(width).copy(points = listOf(Point(500f, 500f)))
            val pixels = render(listOf(pointStroke))
            val radius = (width * .28f).toInt()
            var pigment = 0
            for (y in 500 - radius..500 + radius) {
                for (x in 500 - radius..500 + radius) {
                    val pixel = pixels[y * 1000 + x]
                    if (Color.red(pixel) == 255 && Color.green(pixel) < 220 && Color.blue(pixel) == Color.green(pixel)) pigment++
                }
            }
            assertTrue("A ${width.toInt()}-unit tap should contain crayon pigment", pigment > 0)
            assertEquals("A tap must not escape its brush radius", Color.WHITE, pixels[500 * 1000 + 500 + width.toInt() / 2 + 3])
        }
    }

    @Test fun gradientRetainsMultipleColorsPaperGrainAndStableReplay() {
        val stroke = crayon(64f).copy(crayonId = "test-gradient",
            gradientStops = listOf(0xFFFF0000, 0xFF0000FF))
        val pixels = render(listOf(stroke))
        // Sample away from the centerline glitter: color follows the stroke, grain follows paper.
        var leftRed = 0L
        var leftBlue = 0L
        var rightRed = 0L
        var rightBlue = 0L
        var bare = 0
        var pigment = 0
        for (y in 481..487) for (x in 160..260) {
            val left = pixels[y * 1000 + x]
            val right = pixels[y * 1000 + 1000 - x]
            leftRed += Color.red(left); leftBlue += Color.blue(left)
            rightRed += Color.red(right); rightBlue += Color.blue(right)
            if (left == Color.WHITE) bare++ else pigment++
        }
        assertTrue("A gradient stroke must start red", leftRed > leftBlue + 30000)
        assertTrue("A gradient stroke must finish blue", rightBlue > rightRed + 30000)
        assertTrue("Gradient pigment must retain paper holes", bare > 0)
        assertTrue("Gradient must contain textured pigment", pigment > 500)
        assertArrayEquals(pixels, render(emptyList(), stroke))
        assertArrayEquals(pixels, render(listOf(stroke.copy(points = stroke.points.toList(),
            gradientStops = stroke.gradientStops.toList()))))
    }

    @Test fun eraserRemovesGradientAndGlitterAndBothStayInsideLockedRegion() {
        val gradient = crayon(64f).copy(points = listOf(Point(0f, 500f), Point(1000f, 500f)),
            crayonId = "test-gradient", gradientStops = listOf(0xFFFF0000, 0xFF00AA00, 0xFF0000FF))
        val erased = gradient.copy(tool = Tool.ERASER, width = 24f,
            points = listOf(Point(350f, 500f), Point(650f, 500f)))
        val pixels = render(listOf(gradient, erased))
        assertEquals("Gradient cannot escape paper", Color.WHITE, pixels[500 * 1000 + 20])
        for (y in 493..507) for (x in 380..620) {
            assertEquals("Eraser must remove pigment and glitter", Color.WHITE, pixels[y * 1000 + x])
        }
        assertTrue("Eraser keeps nearby pigment", (480..485).any { y ->
            (400..600).any { x -> pixels[y * 1000 + x] != Color.WHITE }
        })
    }

    private fun crayon(width: Float) = Stroke(
        regionId = "paper", colorArgb = 0xFFFF0000, width = width, tool = Tool.CRAYON,
        points = listOf(Point(100f, 500f), Point(900f, 500f)),
    )

    private fun render(strokes: List<Stroke>, activeStroke: Stroke? = null): IntArray {
        val bitmap = createBitmap(1000, 1000)
        try {
            val nativeCanvas = android.graphics.Canvas(bitmap)
            nativeCanvas.drawColor(Color.WHITE)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(nativeCanvas), Size(1000f, 1000f)) {
                drawArtwork(paper, strokes, activeStroke)
            }
            return IntArray(1000 * 1000).also { bitmap.getPixels(it, 0, 1000, 0, 0, 1000, 1000) }
        } finally {
            bitmap.recycle()
        }
    }
}
