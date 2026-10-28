package com.colorup.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.colorup.app.ui.ColorUpScreen
import com.colorup.core.ArtworkCatalog
import com.colorup.core.Crayon
import com.colorup.core.CrayonPalette
import com.colorup.core.Point
import com.colorup.core.Stroke
import com.colorup.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ColoringScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun all108CrayonsSelectTheirExactStyleAndEraserCanBeChosen() {
        var chosen: Crayon? = null
        var chosenTool = Tool.CRAYON
        content(onCrayon = { chosen = it }, onTool = { chosenTool = it })
        assertEquals(108, CrayonPalette.colors.size)
        CrayonPalette.colors.forEachIndexed { index, crayon ->
            if (index > 0 && index % 6 == 0) compose.onNodeWithTag("palette_next").performClick()
            compose.onNodeWithTag("crayon_$index").performClick().assertIsSelected()
            compose.runOnIdle {
                assertEquals(crayon.id, chosen?.id)
                assertEquals(crayon.gradientStops, chosen?.gradientStops)
            }
            val label = (if (crayon.gradientStops.isEmpty()) "" else "✦ ") + crayon.name
            compose.onNodeWithText(label).assertIsDisplayed()
            val otherIndex = if (index % 6 == 0) index + 1 else index - 1
            compose.onNodeWithTag("crayon_$otherIndex").assertIsNotSelected()
        }
        compose.onNodeWithTag("tool_eraser").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(Tool.ERASER, chosenTool) }
    }

    @Test fun clearRunsImmediatelyAndUndoRemainsAvailable() {
        var clears = 0
        var undos = 0
        content(onClear = { clears++ }, onUndo = { undos++ })
        compose.onNodeWithTag("clear").performClick()
        compose.runOnIdle { assertEquals(1, clears) }
        compose.onNodeWithTag("undo").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, undos) }
    }

    @Test fun eighteenPalettePagesWrapBothWaysWithoutChangingTheSelectedCrayon() {
        var flips = 0
        content(onPalettePageChange = { flips++ })
        compose.onNodeWithTag("crayon_0").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("palette_previous").performClick()
        compose.onNodeWithTag("crayon_102").assertIsDisplayed()
        compose.onNodeWithTag("page_dot_17").assertIsSelected()
        compose.onNodeWithText(CrayonPalette.colors.first().name).assertIsDisplayed()
        compose.onNodeWithTag("palette_next").performClick()
        compose.onNodeWithTag("crayon_0").assertIsDisplayed().assertIsSelected()
        repeat(18) { compose.onNodeWithTag("palette_next").performClick() }
        compose.onNodeWithTag("crayon_0").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("page_dot_0").assertIsSelected()
        compose.runOnIdle { assertEquals(20, flips) }
    }

    @Test fun argbFallbackSelectsASolidCrayonInsteadOfAGradientWithTheSameBaseColor() {
        val gradient = CrayonPalette.colors.first { candidate ->
            candidate.gradientStops.isNotEmpty() && CrayonPalette.colors.any { it.gradientStops.isEmpty() && it.argb == candidate.argb }
        }
        val solidIndex = CrayonPalette.colors.indexOfFirst { it.gradientStops.isEmpty() && it.argb == gradient.argb }
        content(initialState = paintState().copy(selectedColor = gradient.argb, selectedCrayonId = null))
        compose.onNodeWithTag("crayon_$solidIndex").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText(CrayonPalette.colors[solidIndex].name).assertIsDisplayed()
    }

    @Test fun homeShowsUpToFourFeaturedPicturesAndAllPicturesEntryOpensTheGallery() {
        val pages = ArtworkCatalog.pages
        val featuredPages = pages.filter { it.featured }.ifEmpty { pages }.take(4)
        content(initialState = ColoringUiState(screen = GameScreen.HOME, isLoading = false))
        compose.onAllNodes(tagStartsWith("home_page_")).assertCountEquals(featuredPages.size)
        featuredPages.forEach { page ->
            compose.onNodeWithTag("home_page_${page.id}").assertExists()
        }
        compose.onNodeWithTag("home_completion_count").performScrollTo().assertTextEquals("完成了 0 / ${pages.size} 张")
        compose.onNodeWithTag("view_all_artwork").performScrollTo().performClick()
        compose.onNodeWithTag("screen_gallery").assertIsDisplayed()
        compose.onNodeWithTag("gallery_grid").assertContentDescriptionEquals("${pages.size} 张线稿")
    }

    @Test fun continueLastPictureResumesTheCurrentIllustration() {
        val page = ArtworkCatalog.pages.first()
        val strokes = listOf(savedStroke(page.regions.first().id))
        content(initialState = ColoringUiState(
            page = page, screen = GameScreen.HOME, isLoading = false, hasVisitedPage = true,
            strokes = strokes, drawings = mapOf(page.id to strokes),
        ))
        compose.onNodeWithTag("continue_coloring").performScrollTo().performClick()
        compose.onNodeWithTag("screen_paint").assertIsDisplayed()
        compose.onNodeWithText(page.title).assertIsDisplayed()
    }

    @Test fun everySeriesFiltersToItsPicturesAndEveryPictureCanOpen() {
        val total = ArtworkCatalog.pages.size
        val series = ArtworkCatalog.pages.groupBy { it.theme }
        var opened = ""
        content(initialState = ColoringUiState(screen = GameScreen.GALLERY, isLoading = false), onPage = { opened = it })
        compose.onNodeWithTag("gallery_grid").assertContentDescriptionEquals("$total 张线稿")
        series.forEach { (theme, pages) ->
            compose.onNodeWithTag("gallery_grid").performScrollToIndex(0)
            compose.onNodeWithTag("theme_$theme").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("gallery_grid").assertContentDescriptionEquals("${pages.size} 张线稿")
            pages.forEach { page ->
                compose.onNodeWithTag("gallery_grid").performScrollToNode(hasTestTag("page_${page.id}"))
                compose.onNodeWithTag("page_${page.id}").performClick()
                compose.onNodeWithTag("screen_paint").assertIsDisplayed()
                compose.runOnIdle { assertEquals(page.id, opened) }
                compose.onNodeWithTag("paint_back").performClick()
                compose.onNodeWithTag("gallery_grid").assertContentDescriptionEquals("${pages.size} 张线稿")
            }
        }
        compose.onNodeWithTag("gallery_grid").performScrollToIndex(0)
        compose.onNodeWithTag("theme_all").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("gallery_grid").assertContentDescriptionEquals("$total 张线稿")
    }

    @Test fun paletteControlsStayLargeAndEighteenIndicatorsFitBelowTheBoard() {
        content()
        val toolbar = compose.onNodeWithTag("paint_toolbar").fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag("paint_board_frame").fetchSemanticsNode().boundsInRoot
        val tray = compose.onNodeWithTag("crayon_tray").fetchSemanticsNode().boundsInRoot
        val minTouch = with(compose.density) { 48.dp.toPx() }
        val safeGap = with(compose.density) { 12.dp.toPx() }
        assertTrue("Toolbar must remain above the paper", toolbar.bottom + safeGap <= board.top + 1f)
        assertTrue("The palette must remain below the paper", board.bottom <= tray.top)
        (listOf("palette_previous", "palette_next") + (0..5).map { "crayon_$it" }).forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag needs a 48dp target", bounds.width + 1f >= minTouch && bounds.height + 1f >= minTouch)
        }
        compose.onAllNodes(tagStartsWith("page_dot_")).assertCountEquals(18)
        val lastDot = compose.onNodeWithTag("page_dot_17").fetchSemanticsNode().boundsInRoot
        assertTrue("Page indicators must fit inside the tray", lastDot.right <= tray.right && lastDot.bottom <= tray.bottom)
    }

    private fun content(
        initialState: ColoringUiState = paintState(),
        onCrayon: (Crayon) -> Unit = {},
        onTool: (Tool) -> Unit = {},
        onClear: () -> Unit = {},
        onUndo: () -> Unit = {},
        onPalettePageChange: () -> Unit = {},
        onPage: (String) -> Unit = {},
    ) {
        val state = mutableStateOf(initialState)
        compose.setContent {
            MaterialTheme {
                ColorUpScreen(
                    state = state.value,
                    onPageSelected = { id ->
                        state.value = state.value.copy(
                            page = state.value.pages.first { it.id == id },
                            strokes = state.value.drawings[id].orEmpty(), activeStroke = null, screen = GameScreen.PAINT,
                        )
                        onPage(id)
                    },
                    onCrayonSelected = { crayon ->
                        state.value = state.value.copy(selectedColor = crayon.argb, selectedCrayonId = crayon.id, tool = Tool.CRAYON)
                        onCrayon(crayon)
                    },
                    onToolSelected = { tool -> state.value = state.value.copy(tool = tool); onTool(tool) },
                    onWidthSelected = {}, onUndo = onUndo, onClear = onClear,
                    onStrokeStart = {}, onStrokeMove = {}, onStrokeEnd = {}, onStrokeCancel = {},
                    onPalettePageChange = onPalettePageChange,
                    onNavigate = { state.value = state.value.copy(screen = it) },
                )
            }
        }
    }

    private fun paintState() = ColoringUiState(
        strokes = listOf(Stroke(ArtworkCatalog.pages.first().regions.first().id, CrayonPalette.colors.first().argb, 40f, Tool.CRAYON, listOf(Point(500f, 500f)))),
        selectedCrayonId = CrayonPalette.colors.first().id,
        canUndo = true, screen = GameScreen.PAINT, isLoading = false,
    )

    private fun savedStroke(regionId: String) = Stroke(
        regionId, CrayonPalette.colors.first().argb, 40f, Tool.CRAYON, listOf(Point(500f, 500f)),
    )

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
