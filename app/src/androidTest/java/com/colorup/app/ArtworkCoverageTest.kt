package com.colorup.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.colorup.core.ArtworkCatalog
import com.colorup.core.ColoringRegion
import com.colorup.core.CrayonPalette
import com.colorup.core.LineArt
import com.colorup.core.PageProgress
import com.colorup.core.Point
import com.colorup.core.Shape
import com.colorup.core.Stroke
import com.colorup.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real textured rendering and visibility masks without opening or modifying a gallery. */
@RunWith(AndroidJUnit4::class)
class ArtworkCoverageTest {
    @Test fun everyRealArtworkRegionCanReachCompletionWithTheActualCrayonTexture() {
        val wholePaper = buildList {
            for ((row, y) in (0..1000 step 80).withIndex()) {
                add(Point(if (row % 2 == 0) 0f else 1000f, y.toFloat()))
                add(Point(if (row % 2 == 0) 1000f else 0f, y.toFloat()))
            }
        }
        val unreachable = mutableListOf<String>()
        ArtworkCatalog.pages.forEach { page ->
            val painted = page.regions.map { region -> stroke(region.id, wholePaper, width = 200f) }
            ArtworkCoverage(page).use { coverage ->
                val progress = coverage.measure(painted)
                page.regions.forEach { region ->
                    val fraction = progress.coverageByRegion.getValue(region.id)
                    if (fraction < PageProgress.COMPLETION_THRESHOLD) {
                        unreachable += "${page.id}/${region.id} (${region.name})=$fraction"
                    }
                }
                if (!progress.isComplete) unreachable += "${page.id}: page is not complete"
            }
        }
        assertTrue("Every real region must be completable despite paper grain and small masks: $unreachable",
            unreachable.isEmpty())
    }

    @Test fun blankPaperHasZeroProgressAndOneSmallMarkCannotCompleteARegion() {
        val page = page(region("paper", 50f, 50f, 950f, 950f))

        ArtworkCoverage(page).use { coverage ->
            val blank = coverage.measure(emptyList())
            assertEquals(1, blank.totalRegions)
            assertEquals(0f, blank.coverageByRegion.getValue("paper"), .0001f)
            assertEquals(0f, blank.averageCoverage, .0001f)
            assertEquals(0, blank.completedRegions)
            assertFalse(blank.isComplete)

            val marked = coverage.measure(listOf(stroke("paper", listOf(Point(500f, 500f)), width = 40f)))
            val fraction = marked.coverageByRegion.getValue("paper")
            assertTrue("A small crayon mark must count as some coverage", fraction > 0f)
            assertTrue("One small mark must not count the entire touched region", fraction < .1f)
            assertEquals(0, marked.completedRegions)
            assertFalse(marked.isComplete)
        }
    }

    @Test fun onlyTheVisibleOuterRingContributesToItsCoverageDenominator() {
        val page = page(
            region("outer", 0f, 0f, 1000f, 1000f),
            region("inner", 100f, 100f, 900f, 900f),
        )
        val hiddenStroke = stroke("outer", listOf(Point(200f, 500f), Point(800f, 500f)), width = 100f)
        val visibleRing = stroke("outer", listOf(
            Point(50f, 50f), Point(950f, 50f), Point(950f, 950f),
            Point(50f, 950f), Point(50f, 50f),
        ), width = 100f)

        ArtworkCoverage(page).use { coverage ->
            val hidden = coverage.measure(listOf(hiddenStroke))
            assertEquals("Paint hidden underneath a higher region cannot increase visible coverage",
                0f, hidden.coverageByRegion.getValue("outer"), .0001f)
            assertEquals(0f, hidden.coverageByRegion.getValue("inner"), .0001f)

            val paintedRing = coverage.measure(listOf(hiddenStroke, visibleRing))
            assertTrue("The thin visible ring must reach the threshold without painting hidden area",
                paintedRing.coverageByRegion.getValue("outer") >= PageProgress.COMPLETION_THRESHOLD)
            assertEquals(0f, paintedRing.coverageByRegion.getValue("inner"), .0001f)
            assertEquals(1, paintedRing.completedRegions)
            assertFalse("The untouched inner region must still prevent page completion", paintedRing.isComplete)
        }
    }

    @Test fun aCrossingStrokeCannotIncreaseTheNeighboringRegionsCoverage() {
        val page = page(
            region("left", 50f, 50f, 450f, 950f),
            region("right", 550f, 50f, 950f, 950f),
        )
        val crossing = stroke("left", listOf(Point(100f, 500f), Point(900f, 500f)), width = 120f)

        ArtworkCoverage(page).use { coverage ->
            val progress = coverage.measure(listOf(crossing))

            assertTrue(progress.coverageByRegion.getValue("left") > 0f)
            assertEquals("The stroke's region ID must constrain coverage as well as displayed pigment",
                0f, progress.coverageByRegion.getValue("right"), .0001f)
            assertEquals(0, progress.completedRegions)
            assertFalse(progress.isComplete)
        }
    }

    @Test fun broadCrayonCoverageCanBeErasedAndUndoReplayRestoresADeterministicResult() {
        val page = page(region("paper", 50f, 50f, 950f, 950f))
        val paint = listOf(stroke("paper", zigzag(100f, 900f), width = 160f))
        val halfEraser = stroke("paper", zigzag(100f, 450f), width = 200f, tool = Tool.ERASER)
        val fullEraser = stroke("paper", zigzag(100f, 900f), width = 200f, tool = Tool.ERASER)

        ArtworkCoverage(page).use { coverage ->
            val painted = coverage.measure(paint)
            val paintedFraction = painted.coverageByRegion.getValue("paper")
            assertTrue("Broad zigzag painting must reach the current completion threshold",
                paintedFraction >= PageProgress.COMPLETION_THRESHOLD)
            assertTrue("Sampled coverage must stay normalized", paintedFraction <= 1f)
            assertTrue(painted.isComplete)

            val halfErased = coverage.measure(paint + halfEraser)
            val halfFraction = halfErased.coverageByRegion.getValue("paper")
            assertTrue("Erasing half the paper must substantially lower measured coverage",
                halfFraction < paintedFraction - .15f)
            assertTrue("The un-erased side must retain pigment", halfFraction > .1f)

            val erased = coverage.measure(paint + halfEraser + fullEraser)
            assertTrue("A full white eraser sweep must remove the colored coverage",
                erased.coverageByRegion.getValue("paper") < .02f)
            assertFalse(erased.isComplete)

            // Removing the eraser operations represents undo, using the original immutable strokes.
            val restored = coverage.measure(paint)
            assertEquals(painted, restored)
            assertEquals("Fixed paper grain and clean measurement state must give stable repeated results",
                restored, coverage.measure(paint))
        }
    }

    private fun page(vararg regions: ColoringRegion) = LineArt(
        id = "coverage-test", title = "覆盖率测试", subtitle = "",
        regions = regions.toList(), details = emptyList(),
    )

    private fun region(id: String, left: Float, top: Float, right: Float, bottom: Float) = ColoringRegion(
        id = id,
        shape = Shape.Polygon(listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))),
        outlineWidth = 0f,
    )

    private fun stroke(regionId: String, points: List<Point>, width: Float, tool: Tool = Tool.CRAYON) = Stroke(
        regionId = regionId,
        colorArgb = CrayonPalette.colors.first().argb,
        width = width,
        tool = tool,
        points = points,
    )

    private fun zigzag(left: Float, right: Float): List<Point> = buildList {
        for (row in 0..8) {
            val y = 100f + row * 100f
            add(Point(if (row % 2 == 0) left else right, y))
            add(Point(if (row % 2 == 0) right else left, y))
        }
    }
}
