package com.colorup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkDiscoveryTest {
    private val hardPartial = page("hard-partial", "space", 11)
    private val erasedFinished = page("erased-finished", "nature", 6)
    private val easyBlank = page("easy-blank", "nature", 5)
    private val easyPartial = page("easy-partial", "nature", 10)
    private val hardFinished = page("hard-finished", "space", 13)
    private val pages = listOf(hardPartial, erasedFinished, easyBlank, easyPartial, hardFinished)
    private val drawings = mapOf(
        hardPartial.id to listOf(stroke(hardPartial)),
        erasedFinished.id to emptyList(),
        easyBlank.id to emptyList(),
        easyPartial.id to listOf(stroke(easyPartial)),
        hardFinished.id to listOf(stroke(hardFinished), stroke(hardFinished).copy(tool = Tool.ERASER)),
        "retired-drawing" to listOf(stroke(hardPartial)),
    )
    private val completed = setOf(erasedFinished.id, hardFinished.id, "retired-completed")

    @Test fun `easy collection includes ten regions and retains the supplied artwork order`() {
        val result = select(ArtworkCollection.EASY)
        assertEquals(listOf(erasedFinished, easyBlank, easyPartial), result)
        assertSame(erasedFinished, result.first())
        assertFalse(result.contains(hardPartial))
    }

    @Test fun `theme and collection are combined rather than replacing one another`() {
        assertEquals(listOf(erasedFinished, easyBlank, easyPartial), select(ArtworkCollection.ALL, "nature"))
        assertEquals(listOf(erasedFinished, easyBlank, easyPartial), select(ArtworkCollection.EASY, "nature"))
        assertEquals(listOf(easyPartial), select(ArtworkCollection.IN_PROGRESS, "nature"))
        assertEquals(listOf(erasedFinished), select(ArtworkCollection.COMPLETED, "nature"))
        assertEquals(listOf(hardPartial), select(ArtworkCollection.IN_PROGRESS, "space"))
        assertTrue(select(ArtworkCollection.EASY, "space").isEmpty())
    }

    @Test fun `all unfinished drawings can be resumed without mixing finished or retired entries`() {
        assertEquals(listOf(hardPartial, easyPartial), select(ArtworkCollection.IN_PROGRESS))
        assertEquals(pages, select(ArtworkCollection.ALL))
        assertTrue(ArtworkDiscovery.select(emptyList(), drawings, completed).isEmpty())
    }

    @Test fun `completed collection keeps historical works even after clearing or erasing`() {
        assertEquals(listOf(erasedFinished, hardFinished), select(ArtworkCollection.COMPLETED))
        val cleared = drawings + (hardFinished.id to emptyList())
        assertEquals(listOf(erasedFinished, hardFinished),
            ArtworkDiscovery.select(pages, cleared, completed, collection = ArtworkCollection.COMPLETED))
        assertEquals(listOf(hardPartial, easyPartial),
            ArtworkDiscovery.select(pages, cleared, completed, collection = ArtworkCollection.IN_PROGRESS))
    }

    @Test fun `counts reflect the displayed theme scope and report empty results as zero`() {
        assertEquals(mapOf(
            ArtworkCollection.ALL to 5, ArtworkCollection.EASY to 3,
            ArtworkCollection.IN_PROGRESS to 2, ArtworkCollection.COMPLETED to 2,
        ), ArtworkDiscovery.counts(pages, drawings, completed))
        assertEquals(mapOf(
            ArtworkCollection.ALL to 2, ArtworkCollection.EASY to 0,
            ArtworkCollection.IN_PROGRESS to 1, ArtworkCollection.COMPLETED to 1,
        ), ArtworkDiscovery.counts(pages, drawings, completed, "space"))
        assertTrue(ArtworkDiscovery.counts(pages, drawings, completed, "new-theme").values.all { it == 0 })
        assertTrue(ArtworkDiscovery.counts(emptyList(), drawings, completed).values.all { it == 0 })
    }

    @Test fun `discovery does not reorder the catalog or mutate caller owned save collections`() {
        val suppliedPages = pages.toMutableList()
        val suppliedDrawings = drawings.mapValues { it.value.toMutableList() }.toMutableMap()
        val suppliedCompleted = completed.toMutableSet()
        val originalPages = suppliedPages.toList()
        val originalDrawings = suppliedDrawings.mapValues { it.value.toList() }
        val originalCompleted = suppliedCompleted.toSet()
        ArtworkCollection.entries.forEach {
            ArtworkDiscovery.select(suppliedPages, suppliedDrawings, suppliedCompleted, collection = it)
        }
        ArtworkDiscovery.counts(suppliedPages, suppliedDrawings, suppliedCompleted, "nature")
        assertEquals(originalPages, suppliedPages)
        assertEquals(originalDrawings, suppliedDrawings)
        assertEquals(originalCompleted, suppliedCompleted)
    }

    private fun select(collection: ArtworkCollection, theme: String? = null) =
        ArtworkDiscovery.select(pages, drawings, completed, theme, collection)

    private fun page(id: String, theme: String, count: Int) = LineArt(
        id, id, "", List(count) { index ->
            ColoringRegion("$id:r$index", Shape.Oval(0f, 0f, 100f, 100f))
        }, emptyList(), theme = theme,
    )

    private fun stroke(page: LineArt) = Stroke(
        page.regions.first().id, 0xFFFF8FABL, 40f, Tool.CRAYON, listOf(Point(50f, 50f)),
    )
}
