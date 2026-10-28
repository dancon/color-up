package com.colorup.app

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.colorup.app.ui.ColoringCanvas
import com.colorup.core.ColoringRegion
import com.colorup.core.LineArt
import com.colorup.core.Point
import com.colorup.core.Shape
import com.colorup.core.Stroke
import com.colorup.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pixel checks exercise the platform clipping implementation in addition to the pure core rules. */
class ColoringCanvasRenderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun paintingAndErasingCannotReachTheNeighboringRegion() {
        val page = LineArt(
            id = "test", title = "边界测试", subtitle = "",
            regions = listOf(
                ColoringRegion("left", rectangle(50f, 450f)),
                ColoringRegion("right", rectangle(550f, 950f)),
            ),
            details = emptyList(),
        )
        val strokes = mutableStateOf(listOf(stroke("left", Tool.CRAYON)))
        compose.setContent {
            ColoringCanvas(page, strokes.value, null, Modifier.size(300.dp))
        }
        assertRedPigmentPatch(.25f)
        assertWhitePatch(.75f)

        compose.runOnIdle {
            strokes.value = strokes.value + stroke("right", Tool.CRAYON) + stroke("left", Tool.ERASER)
        }
        assertWhitePatch(.25f)
        assertRedPigmentPatch(.75f)
    }

    @Test fun paperMarginsCannotStartAStrokeAndOnlyTheFirstFingerOwnsTheGesture() {
        var starts = 0
        var ends = 0
        var firstPoint: Point? = null
        compose.setContent {
            ColoringCanvas(
                page = LineArt("input", "输入测试", "", emptyList(), emptyList()),
                strokes = emptyList(), activeStroke = null,
                modifier = Modifier.size(300.dp, 200.dp),
                onStrokeStart = { starts++; firstPoint = it },
                onStrokeEnd = { ends++ },
            )
        }
        val canvas = compose.onNodeWithTag("coloring_canvas")
        canvas.performTouchInput {
            down(Offset(width * .05f, height * .5f))
            moveTo(center)
            up()
        }
        compose.runOnIdle { assertEquals(0, starts) }

        canvas.performTouchInput {
            down(pointerId = 0, position = center)
            down(pointerId = 1, position = Offset(width * .6f, height * .5f))
            moveTo(pointerId = 1, position = Offset(width * .7f, height * .5f))
            up(pointerId = 0)
            moveTo(pointerId = 1, position = center)
            up(pointerId = 1)
        }
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(1, ends)
            assertEquals(500f, firstPoint!!.x, 1f)
            assertEquals(500f, firstPoint!!.y, 1f)
        }
    }

    @Test fun canceledTouchDiscardsTheGestureWithoutCommittingIt() {
        var starts = 0
        var ends = 0
        var cancels = 0
        compose.setContent {
            ColoringCanvas(
                page = LineArt("cancel", "取消测试", "", emptyList(), emptyList()),
                strokes = emptyList(), activeStroke = null,
                modifier = Modifier.size(300.dp),
                onStrokeStart = { starts++ },
                onStrokeEnd = { ends++ },
                onStrokeCancel = { cancels++ },
            )
        }
        compose.onNodeWithTag("coloring_canvas").performTouchInput {
            down(center)
            moveTo(Offset(width * .7f, height * .5f))
            cancel()
        }
        compose.runOnIdle {
            assertEquals(1, starts)
            assertEquals(0, ends)
            assertEquals(1, cancels)
        }
    }

    @Test fun aHigherRegionKeepsItsWhiteInteriorWhenTheLowerRegionIsPainted() {
        val page = LineArt(
            id = "nested", title = "图层测试", subtitle = "",
            regions = listOf(
                ColoringRegion("outer", Shape.Oval(50f, 50f, 950f, 950f)),
                ColoringRegion("inner", Shape.Oval(350f, 350f, 650f, 650f)),
            ),
            details = emptyList(),
        )
        compose.setContent {
            ColoringCanvas(page, listOf(stroke("outer", Tool.CRAYON)), null, Modifier.size(300.dp))
        }
        assertRedPigmentPatch(.25f)
        assertWhitePatch(.5f)
        assertRedPigmentPatch(.75f)
    }

    @Test fun hardwareCanvasShowsGradientColorsAndKeepsTheNeighborUnpainted() {
        val page = LineArt("gradient-gpu", "炫彩边界", "",
            listOf(ColoringRegion("paper", rectangle(50f, 950f)),
                ColoringRegion("neighbor", Shape.Oval(450f, 430f, 550f, 570f))), emptyList())
        val rainbow = Stroke("paper", 0xFFFF0000, 100f, Tool.CRAYON,
            listOf(Point(100f, 500f), Point(900f, 500f)), "test-gradient", listOf(0xFFFF0000, 0xFF0000FF))
        compose.setContent { ColoringCanvas(page, listOf(rainbow), null, Modifier.size(300.dp)) }
        val pixels = compose.onNodeWithTag("coloring_canvas").captureToImage().toPixelMap()
        fun channelDifference(xFraction: Float): Float {
            val centerX = (pixels.width * xFraction).toInt()
            val centerY = (pixels.height * .47f).toInt()
            var difference = 0f
            for (y in centerY - 3..centerY + 3) for (x in centerX - 3..centerX + 3) {
                difference += pixels[x, y].red - pixels[x, y].blue
            }
            return difference / 49f
        }
        assertTrue("GPU shader starts with red pigment", channelDifference(.25f) > .2f)
        assertTrue("GPU shader finishes with blue pigment", channelDifference(.75f) < -.2f)
        assertWhitePatch(.5f)
    }

    private fun assertWhitePatch(xFraction: Float) {
        val pixels = compose.onNodeWithTag("coloring_canvas").captureToImage().toPixelMap()
        val centerX = (pixels.width * xFraction).toInt()
        for (y in pixels.height / 2 - 8..pixels.height / 2 + 8) {
            for (x in centerX - 8..centerX + 8) {
                val actual = pixels[x, y]
                assertEquals(1f, actual.red, .01f)
                assertEquals(1f, actual.green, .01f)
                assertEquals(1f, actual.blue, .01f)
            }
        }
    }

    private fun assertRedPigmentPatch(xFraction: Float) {
        val pixels = compose.onNodeWithTag("coloring_canvas").captureToImage().toPixelMap()
        val centerX = (pixels.width * xFraction).toInt()
        var pigment = 0
        var samples = 0
        for (y in pixels.height / 2 - 8..pixels.height / 2 + 8) {
            for (x in centerX - 8..centerX + 8) {
                val actual = pixels[x, y]
                assertTrue("Red pigment must preserve its hue", actual.red > .98f)
                assertEquals(actual.green, actual.blue, .02f)
                if (actual.green < .85f) pigment++
                samples++
            }
        }
        assertTrue("The region must contain visible crayon pigment", pigment > samples / 2)
    }

    private fun rectangle(left: Float, right: Float) = Shape.Polygon(
        listOf(Point(left, 100f), Point(right, 100f), Point(right, 900f), Point(left, 900f)),
    )

    private fun stroke(region: String, tool: Tool) = Stroke(
        regionId = region,
        colorArgb = 0xFFFF0000,
        width = 90f,
        tool = tool,
        points = listOf(Point(100f, 500f), Point(900f, 500f)),
    )
}
