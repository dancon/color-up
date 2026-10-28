package com.colorup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ColoringSessionTest {
    private val page = LineArt(
        "test", "Test", "", listOf(
            ColoringRegion("body", Shape.Oval(100f, 100f, 900f, 900f)),
            ColoringRegion("cheek", Shape.Oval(400f, 400f, 600f, 600f)),
        ), emptyList(),
    )
    private val red = 0xFFE85265L

    @Test fun `topmost region wins overlapping shapes`() {
        val session = ColoringSession(page)
        assertEquals("cheek", session.hitTest(Point(500f, 500f))?.id)
        assertEquals("body", session.hitTest(Point(300f, 500f))?.id)
        assertNull(session.hitTest(Point(50f, 50f)))
    }

    @Test fun `a stroke started in blank space cannot acquire a region later`() {
        val session = ColoringSession(page)
        assertFalse(session.beginStroke(Point(0f, 0f), red, 30f, Tool.CRAYON))
        session.appendPoint(Point(500f, 500f))
        session.finishStroke()
        assertTrue(session.strokes.isEmpty())
    }

    @Test fun `crossing another region and returning never changes the selected region`() {
        val session = ColoringSession(page)
        assertTrue(session.beginStroke(Point(300f, 500f), red, 30f, Tool.CRAYON))
        session.appendPoint(Point(500f, 500f))
        session.appendPoint(Point(950f, 500f))
        session.appendPoint(Point(300f, 500f))
        session.finishStroke()
        assertEquals("body", session.strokes.single().regionId)
        assertEquals(4, session.strokes.single().points.size)
    }

    @Test fun `a second pointer cannot replace an active stroke`() {
        val session = ColoringSession(page)
        session.beginStroke(Point(300f, 500f), red, 30f, Tool.CRAYON)
        assertFalse(session.beginStroke(Point(500f, 500f), red, 20f, Tool.ERASER))
        assertEquals("body", session.activeStroke?.regionId)
        assertEquals(Tool.CRAYON, session.activeStroke?.tool)
    }

    @Test fun `a tap is a committed one-point stroke`() {
        val session = ColoringSession(page)
        session.beginStroke(Point(500f, 500f), red, 30f, Tool.CRAYON)
        assertTrue(session.strokes.isEmpty())
        session.finishStroke()
        assertNull(session.activeStroke)
        assertEquals(listOf(Point(500f, 500f)), session.strokes.single().points)
    }

    @Test fun `cancelling a gesture leaves committed work intact`() {
        val session = ColoringSession(page)
        drawDot(session)
        session.beginStroke(Point(300f, 500f), red, 40f, Tool.ERASER)
        session.cancelStroke()
        assertEquals(1, session.strokes.size)
        assertNull(session.activeStroke)
    }

    @Test fun `undo removes the complete eraser stroke and preserves the earlier crayon`() {
        val session = ColoringSession(page)
        drawDot(session)
        session.beginStroke(Point(500f, 500f), red, 40f, Tool.ERASER)
        session.appendPoint(Point(550f, 500f))
        session.finishStroke()
        assertEquals(Tool.ERASER, session.strokes.last().tool)
        session.undo()
        assertEquals(Tool.CRAYON, session.strokes.single().tool)
        session.undo()
        session.undo()
        assertTrue(session.strokes.isEmpty())
    }

    @Test fun `clear removes active and committed strokes`() {
        val session = ColoringSession(page)
        drawDot(session)
        session.beginStroke(Point(300f, 500f), red, 30f, Tool.CRAYON)
        session.clear()
        assertNull(session.activeStroke)
        assertTrue(session.strokes.isEmpty())
        assertTrue(session.canUndo)
    }

    @Test fun `undo clear restores every crayon and eraser stroke without copying their immutable points`() {
        val session = ColoringSession(page)
        drawDot(session)
        session.beginStroke(Point(500f, 500f), red, 40f, Tool.ERASER)
        session.appendPoint(Point(550f, 500f))
        session.finishStroke()
        val before = session.strokes

        session.clear()
        assertTrue(session.strokes.isEmpty())
        assertTrue(session.canUndo)
        session.undo()

        assertEquals(before, session.strokes)
        assertSame(before.first(), session.strokes.first())
        assertSame(before.first().points, session.strokes.first().points)
        session.undo()
        assertEquals(Tool.CRAYON, session.strokes.single().tool)
        session.undo()
        assertTrue(session.strokes.isEmpty())
        assertFalse(session.canUndo)
    }

    @Test fun `clearing an empty drawing cancels active input without adding undo history`() {
        val session = ColoringSession(page)
        session.clear()
        assertFalse(session.canUndo)
        session.beginStroke(Point(500f, 500f), red, 30f, Tool.CRAYON)
        session.clear()
        assertNull(session.activeStroke)
        assertFalse(session.canUndo)
    }

    @Test fun `consecutive empty clears do not hide the original clear snapshot`() {
        val session = ColoringSession(page)
        drawDot(session)
        val before = session.strokes
        session.clear()
        session.clear()
        session.undo()
        assertEquals(before, session.strokes)
        session.clear()
        session.undo()
        assertEquals(before, session.strokes)
        session.undo()
        assertTrue(session.strokes.isEmpty())
        assertFalse(session.canUndo)
    }

    @Test fun `a stroke drawn after clear is undone before the clear itself`() {
        val session = ColoringSession(page)
        drawDot(session)
        val original = session.strokes
        session.clear()
        drawDot(session)
        session.undo()
        assertTrue(session.strokes.isEmpty())
        assertTrue(session.canUndo)
        session.undo()
        assertEquals(original, session.strokes)
    }

    @Test fun `undo history is bounded without removing old visible artwork`() {
        val session = ColoringSession(page)
        repeat(ColoringSession.MAX_UNDO_STEPS + 10) { drawDot(session) }
        repeat(ColoringSession.MAX_UNDO_STEPS) { session.undo() }
        assertEquals(10, session.strokes.size)
        assertFalse(session.canUndo)
        session.undo()
        assertEquals(10, session.strokes.size)
    }

    @Test fun `restored strokes can be undone and keep ARGB values absent from the new palette`() {
        assertFalse(CrayonPalette.colors.any { it.argb == red })
        val saved = listOf(
            Stroke("cheek", red, 30f, Tool.CRAYON, listOf(Point(500f, 500f))),
            Stroke("cheek", 0xFF000000L, 40f, Tool.ERASER, listOf(Point(500f, 500f))),
        )
        val session = ColoringSession(page, saved)
        assertEquals(saved, session.strokes)
        assertTrue(session.canUndo)
        session.undo()
        assertEquals(red, session.strokes.single().colorArgb)
        session.undo()
        assertTrue(session.strokes.isEmpty())
        assertFalse(session.canUndo)
    }

    @Test fun `invalid coordinates widths and transparent colors are rejected`() {
        val session = ColoringSession(page)
        assertFalse(session.beginStroke(Point(Float.NaN, 500f), red, 30f, Tool.CRAYON))
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 201f)) {
            assertFalse(session.beginStroke(Point(500f, 500f), red, width, Tool.CRAYON))
        }
        assertFalse(session.beginStroke(Point(500f, 500f), 0x00FFFFFFL, 30f, Tool.CRAYON))
        assertTrue(session.beginStroke(Point(500f, 500f), red, 30f, Tool.CRAYON))
        session.appendPoint(Point(Float.POSITIVE_INFINITY, 0f))
        session.appendPoint(Point(500f, Float.NaN))
        assertEquals(1, session.activeStroke?.points?.size)
    }

    @Test fun `long strokes are bounded and retain the first and last points`() {
        val session = ColoringSession(page)
        val start = Point(500f, 500f)
        session.beginStroke(start, red, 30f, Tool.CRAYON)
        repeat(10_000) { session.appendPoint(Point(it.toFloat(), 450f)) }
        session.finishStroke()
        val points = session.strokes.single().points
        assertTrue(points.size <= ColoringSession.MAX_POINTS_PER_STROKE)
        assertEquals(start, points.first())
        assertEquals(Point(9999f, 450f), points.last())
    }

    @Test fun `page capacity refuses a new stroke without silently deleting artwork`() {
        val session = ColoringSession(page)
        repeat(ColoringSession.MAX_STROKES) { drawDot(session) }
        assertFalse(session.beginStroke(Point(500f, 500f), red, 30f, Tool.CRAYON))
        assertEquals(ColoringSession.MAX_STROKES, session.strokes.size)
        session.undo()
        assertTrue(session.beginStroke(Point(500f, 500f), red, 30f, Tool.CRAYON))
    }

    @Test fun `restore snapshots the caller's lists and validates corrupted strokes`() {
        val points = mutableListOf(Point(500f, 500f))
        val valid = Stroke("cheek", red, 30f, Tool.CRAYON, points)
        val restored = mutableListOf(valid, valid.copy(regionId = "missing"), valid.copy(width = Float.NaN))
        val session = ColoringSession(page, restored)
        points.clear()
        restored.clear()
        assertEquals(1, session.strokes.size)
        assertEquals(Point(500f, 500f), session.strokes.single().points.single())
    }

    @Test fun `snapshots do not change as the next sample arrives`() {
        val session = ColoringSession(page)
        session.beginStroke(Point(500f, 500f), red, 30f, Tool.CRAYON)
        val activeSnapshot = session.activeStroke!!
        session.appendPoint(Point(510f, 500f))
        assertEquals(1, activeSnapshot.points.size)
        session.finishStroke()
        val completedSnapshot = session.strokes
        drawDot(session)
        assertEquals(1, completedSnapshot.size)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `a returned points collection cannot be mutated`() {
        val session = ColoringSession(page)
        drawDot(session)
        (session.strokes.single().points as MutableList<Point>).clear()
    }

    @Test fun `page input is copied before a caller can mutate polygon geometry`() {
        val vertices = mutableListOf(Point(0f, 0f), Point(100f, 0f), Point(100f, 100f))
        val regions = mutableListOf(ColoringRegion("triangle", Shape.Polygon(vertices)))
        val session = ColoringSession(LineArt("p", "", "", regions, emptyList()))
        vertices.clear()
        regions.clear()
        assertNotNull(session.hitTest(Point(80f, 20f)))
    }

    @Test fun `gradient selection is snapshotted and survives clear undo without changing saved ARGB`() {
        val stops = mutableListOf(0xFFFF5C5CL, 0xFFFF8FABL, 0xFFFFE9A8L)
        val expected = stops.toList()
        val session = ColoringSession(page)
        assertTrue(session.beginStroke(Point(500f, 500f), red, 40f, Tool.CRAYON, "rainbow-01", stops))
        stops.clear()
        assertEquals(expected, session.activeStroke?.gradientStops)
        session.finishStroke()
        session.clear()
        session.undo()
        assertEquals("rainbow-01", session.strokes.single().crayonId)
        assertEquals(expected, session.strokes.single().gradientStops)
        assertEquals(red, session.strokes.single().colorArgb)
    }

    @Test fun `restoring an unknown safe gradient identity keeps its explicit stops and original color`() {
        val stops = mutableListOf(0xFF000000L, 0xFFFFE9A8L)
        val saved = Stroke("cheek", red, 40f, Tool.CRAYON, listOf(Point(500f, 500f)), "future-style-01", stops)
        val session = ColoringSession(page, listOf(saved))
        stops.clear()
        assertEquals("future-style-01", session.strokes.single().crayonId)
        assertEquals(listOf(0xFF000000L, 0xFFFFE9A8L), session.strokes.single().gradientStops)
        assertEquals(red, session.strokes.single().colorArgb)
    }

    @Test fun `invalid gradient stops and unsafe identities cannot enter the session`() {
        val session = ColoringSession(page)
        val point = Point(500f, 500f)
        assertFalse(session.beginStroke(point, red, 40f, Tool.CRAYON, "", emptyList()))
        assertFalse(session.beginStroke(point, red, 40f, Tool.CRAYON, "unsafe/style", emptyList()))
        assertFalse(session.beginStroke(point, red, 40f, Tool.CRAYON, "x".repeat(81), emptyList()))
        assertFalse(session.beginStroke(point, red, 40f, Tool.CRAYON, "rainbow-01", listOf(red)))
        assertFalse(session.beginStroke(point, red, 40f, Tool.CRAYON, "rainbow-01", List(6) { red }))
        assertFalse(session.beginStroke(point, red, 40f, Tool.CRAYON, "rainbow-01", listOf(red, 0x00FFFFFFL)))
        val invalidRestore = Stroke("cheek", red, 40f, Tool.CRAYON, listOf(point), "rainbow-01", listOf(red))
        assertTrue(ColoringSession(page, listOf(invalidRestore)).strokes.isEmpty())
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `a returned gradient stop collection cannot be mutated`() {
        val session = ColoringSession(page)
        session.beginStroke(Point(500f, 500f), red, 40f, Tool.CRAYON, "rainbow-01", listOf(red, 0xFF000000L))
        session.finishStroke()
        (session.strokes.single().gradientStops as MutableList<Long>).clear()
    }

    @Test fun `full bleed source geometry does not allow a stroke to start outside paper`() {
        val fullBleed = LineArt("bleed", "", "", listOf(
            ColoringRegion("background", Shape.Oval(-20f, -20f, 1020f, 1020f)),
        ), emptyList())
        val session = ColoringSession(fullBleed)
        assertNull(session.hitTest(Point(-10f, 500f)))
        assertFalse(session.beginStroke(Point(-10f, 500f), red, 40f, Tool.CRAYON))
        assertNotNull(session.hitTest(Point(0f, 500f)))
    }

    private fun drawDot(session: ColoringSession) {
        assertTrue(session.beginStroke(Point(500f, 500f), red, 30f, Tool.CRAYON))
        session.finishStroke()
    }
}
