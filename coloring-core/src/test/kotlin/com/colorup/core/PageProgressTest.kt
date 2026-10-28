package com.colorup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageProgressTest {
    private val page = LineArt("progress", "进度测试", "", listOf(
        ColoringRegion("a", Shape.Oval(0f, 0f, 100f, 100f)),
        ColoringRegion("b", Shape.Oval(200f, 0f, 300f, 100f)),
        ColoringRegion("c", Shape.Oval(400f, 0f, 500f, 100f)),
    ), emptyList())

    @Test fun `region completion uses the inclusive configurable prototype threshold`() {
        val progress = PageProgress.from(page, mapOf("a" to .55f, "b" to .549f, "c" to 1f), emptyList())
        assertEquals(.55f, PageProgress.COMPLETION_THRESHOLD, 0f)
        assertEquals(2, progress.completedRegions)
        assertFalse(progress.isComplete)
        val complete = PageProgress.from(page, page.regions.associate { it.id to .55f }, emptyList())
        assertEquals(3, complete.completedRegions)
        assertTrue(complete.isComplete)
    }

    @Test fun `coverage average includes missing regions as zero and does not count unknown regions`() {
        val progress = PageProgress.from(page, mapOf("a" to 1f, "b" to .5f, "unknown" to 1f), emptyList())
        assertEquals(mapOf("a" to 1f, "b" to .5f, "c" to 0f), progress.coverageByRegion)
        assertEquals(3, progress.totalRegions)
        assertEquals(.5f, progress.averageCoverage, .00001f)
        assertEquals(1, progress.completedRegions)
        assertFalse(progress.isComplete)
    }

    @Test fun `platform measurements are clamped and nonfinite values become zero`() {
        val progress = PageProgress.from(page, mapOf("a" to -1f, "b" to 2f, "c" to Float.NaN), emptyList())
        assertEquals(mapOf("a" to 0f, "b" to 1f, "c" to 0f), progress.coverageByRegion)
        assertEquals(1f / 3f, progress.averageCoverage, .00001f)
        val nonfinite = PageProgress.from(page,
            mapOf("a" to Float.POSITIVE_INFINITY, "b" to Float.NEGATIVE_INFINITY), emptyList())
        assertEquals(0f, nonfinite.averageCoverage, 0f)
    }

    @Test fun `factory snapshots mutable renderer input before publishing progress`() {
        val measurements = mutableMapOf("a" to 1f, "b" to 1f, "c" to 1f)
        val progress = PageProgress.from(page, measurements, emptyList())
        measurements.clear()
        assertTrue(progress.isComplete)
        assertEquals(1f, progress.averageCoverage, 0f)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `normalized coverage cannot be mutated through its exposed map`() {
        val progress = PageProgress.from(page, emptyMap(), emptyList())
        (progress.coverageByRegion as MutableMap<String, Float>).clear()
    }

    @Test fun `black pale and legacy crayons count independently while erasing adds no color`() {
        val strokes = listOf(
            stroke("a", 0xFF000000L), stroke("b", 0xFFFFFCF4L),
            stroke("c", 0xFFE85265L), stroke("a", 0xFF000000L),
            stroke("a", 0xFF3358D4L, Tool.ERASER), stroke("unknown", 0xFF00FF00L),
        )
        val progress = PageProgress.from(page, emptyMap(), strokes)
        assertEquals(3, progress.usedColorCount)
        assertEquals(2, progress.stars)
        assertFalse(progress.isComplete)
    }

    @Test fun `stars follow distinct crayon count thresholds independently of drawing completion`() {
        for (count in 0..2) assertEquals(1, PageProgress(emptyMap(), 0, count).stars)
        for (count in 3..5) assertEquals(2, PageProgress(emptyMap(), 0, count).stars)
        for (count in listOf(6, 7, 36)) assertEquals(3, PageProgress(emptyMap(), 0, count).stars)
    }

    @Test fun `legacy and new solid identities count once while different gradient styles remain distinct`() {
        val color = 0xFFFF8FABL
        val legacy = stroke("a", color)
        val namedSolid = legacy.copy(crayonId = solidCrayonId(color))
        val rainbow = legacy.copy(crayonId = "rainbow-01", gradientStops = listOf(0xFFFF5C5CL, color, 0xFFFFE9A8L))
        val unknown = legacy.copy(crayonId = "archived-gradient", gradientStops = listOf(0xFF000000L, color))
        val eraser = rainbow.copy(crayonId = "rainbow-02", tool = Tool.ERASER)
        val progress = PageProgress.from(page, emptyMap(), listOf(legacy, namedSolid, rainbow, unknown, eraser))
        assertEquals(3, progress.usedColorCount)
        assertEquals(2, progress.stars)
    }

    @Test fun `an empty page has zero progress and never completes vacuously`() {
        assertEquals(0, PageProgress.EMPTY.completedRegions)
        assertEquals(0f, PageProgress.EMPTY.averageCoverage, 0f)
        assertFalse(PageProgress.EMPTY.isComplete)
        assertEquals(1, PageProgress.EMPTY.stars)
    }

    private fun stroke(region: String, color: Long, tool: Tool = Tool.CRAYON) =
        Stroke(region, color, 40f, tool, listOf(Point(50f, 50f)))
}
