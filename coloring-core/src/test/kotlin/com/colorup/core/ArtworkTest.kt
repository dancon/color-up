package com.colorup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkTest {
    @Test fun `palette has 81 opaque solids and 27 distinct gradient styles in 18 source pages`() {
        assertEquals(108, CrayonPalette.colors.size)
        assertEquals(108, CrayonPalette.colors.map { it.id }.distinct().size)
        assertEquals(108, CrayonPalette.colors.map { it.name }.distinct().size)
        assertEquals(81, CrayonPalette.colors.count { it.gradientStops.isEmpty() })
        assertEquals(27, CrayonPalette.colors.count { it.gradientStops.isNotEmpty() })
        assertEquals(81, CrayonPalette.colors.filter { it.gradientStops.isEmpty() }.map { it.argb }.distinct().size)
        assertTrue(CrayonPalette.colors.all { it.argb in 0xFF000000L..0xFFFFFFFFL })
        CrayonPalette.colors.chunked(6).forEachIndexed { index, crayons ->
            val solidCount = if (index < 9) 5 else 4
            assertTrue(crayons.take(solidCount).all { it.gradientStops.isEmpty() })
            assertTrue(crayons.drop(solidCount).all { it.gradientStops.size in 2..5 })
            crayons.filter { it.gradientStops.isNotEmpty() }.forEach { crayon ->
                assertTrue(crayon.gradientStops.all { it in 0xFF000000L..0xFFFFFFFFL })
                assertEquals(crayon.gradientStops[crayon.gradientStops.size / 2], crayon.argb)
            }
        }
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `the palette cannot be changed by casting its list`() {
        (CrayonPalette.colors as MutableList<Crayon>).clear()
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `a catalog region list cannot be changed by casting its list`() {
        (ArtworkCatalog.pages.first().regions as MutableList<ColoringRegion>).clear()
    }

    @Test fun `the gallery contains original illustrations with safe independent clipping regions`() {
        assertTrue(ArtworkCatalog.expectedPageCount > 0)
        assertEquals(ArtworkCatalog.expectedPageCount, ArtworkCatalog.pages.size)
        assertEquals(ArtworkCatalog.pages.size, ArtworkCatalog.pages.map { it.id }.distinct().size)
        ArtworkCatalog.pages.forEach { page ->
            assertTrue(page.id.matches(Regex("[a-z][a-z0-9-]*")))
            assertTrue(page.inkAsset?.startsWith("illustrated/") == true)
            assertTrue(page.inkAsset?.endsWith(".png") == true)
            assertTrue(page.title.isNotBlank() && page.subtitle.isNotBlank())
            assertTrue(page.theme.isNotBlank() && page.themeLabel.isNotBlank())
            assertTrue(page.regions.size in 1..20)
            assertTrue(page.details.isEmpty() && page.renderOrder.isEmpty())
            page.regions.forEachIndexed { index, region ->
                assertEquals("${page.id}:r${index.toString().padStart(2, '0')}", region.id)
                assertTrue(region.name.isNotBlank())
                assertEquals(0f, region.outlineWidth)
                val shape = region.shape as Shape.Vector
                assertEquals(1f, shape.scale)
                assertEquals(FillRule.EVEN_ODD, shape.fillRule)
                assertTrue(shape.contours.isNotEmpty())
                assertTrue(shape.contours.all { contour -> contour.size >= 3 && contour.all {
                    it.isFinite && it.x in 0f..1000f && it.y in 0f..1000f
                } })
            }
        }
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `a catalog contour cannot be changed by casting its nested list`() {
        val shape = ArtworkCatalog.pages.first().regions.first().shape as Shape.Vector
        (shape.contours.first() as MutableList<Point>).clear()
    }

    @Test fun `every illustrated mask region can be selected with the production hit test`() {
        ArtworkCatalog.pages.forEach { page ->
            val session = ColoringSession(page)
            // Accepted masks have a >=44-unit inscribed diameter: a 20-unit grid reaches each one.
            val selected = buildSet {
                for (y in 0..1000 step 20) for (x in 0..1000 step 20) {
                    session.hitTest(Point(x.toFloat(), y.toFloat()))?.let { add(it.id) }
                }
            }
            assertEquals("Untappable illustrated region in ${page.id}", page.regions.map { it.id }.toSet(), selected)
        }
    }

    @Test fun `vector winding rules handle subpath holes rather than unioning their bounds`() {
        val outer = listOf(Point(0f, 0f), Point(100f, 0f), Point(100f, 100f), Point(0f, 100f))
        val inner = listOf(Point(25f, 25f), Point(75f, 25f), Point(75f, 75f), Point(25f, 75f))
        val sameDirection = Shape.Vector("M0 0Z", contours = listOf(outer, inner))
        assertTrue(sameDirection.contains(Point(50f, 50f)))
        assertFalse(sameDirection.copy(fillRule = FillRule.EVEN_ODD).contains(Point(50f, 50f)))
        assertFalse(sameDirection.copy(contours = listOf(outer, inner.reversed())).contains(Point(50f, 50f)))
        assertTrue(sameDirection.contains(Point(10f, 10f)))
        assertFalse(sameDirection.contains(Point(110f, 50f)))
    }

    @Test fun `oval boundary is closed and excludes the bounding rectangle corners`() {
        val oval = Shape.Oval(0f, 0f, 100f, 100f)
        assertTrue(oval.contains(Point(50f, 50f)))
        assertTrue(oval.contains(Point(100f, 50f)))
        assertFalse(oval.contains(Point(100f, 100f)))
        assertFalse(oval.contains(Point(Float.NaN, 50f)))
    }

    @Test fun `polygon includes edges and correctly handles a concave cutout`() {
        val polygon = Shape.Polygon(listOf(
            Point(0f, 0f), Point(100f, 0f), Point(100f, 40f),
            Point(40f, 40f), Point(40f, 100f), Point(0f, 100f),
        ))
        assertTrue(polygon.contains(Point(20f, 80f)))
        assertTrue(polygon.contains(Point(80f, 20f)))
        assertTrue(polygon.contains(Point(40f, 70f)))
        assertFalse(polygon.contains(Point(80f, 80f)))
        assertFalse(polygon.contains(Point(-1f, 50f)))
    }
}
