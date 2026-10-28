package com.colorup.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
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

    @Test fun clearRequiresConfirmationAndCancelPreservesTheDrawing() {
        var clears = 0
        var undos = 0
        val original = paintState()
        lateinit var state: MutableState<ColoringUiState>
        state = content(
            initialState = original,
            onClear = { clears++; state.value = state.value.copy(strokes = emptyList()) },
            onUndo = { undos++; state.value = state.value.copy(strokes = original.strokes) },
        )
        compose.onNodeWithTag("clear").performClick()
        compose.onNodeWithTag("clear_confirmation").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithTag("clear_cancel").performClick()
        compose.onNodeWithTag("clear_confirmation").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, clears)
            assertEquals(original.strokes, state.value.strokes)
        }
        compose.onNodeWithTag("clear").performClick()
        compose.onNodeWithTag("clear_confirm").performClick()
        compose.onNodeWithTag("clear_confirmation").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, clears) }
        compose.onNodeWithTag("undo").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, undos)
            assertEquals(original.strokes, state.value.strokes)
        }
    }

    @Test fun toolsUseIconsWithAccessibleDescriptionsAndDrawingHelpCanBeOpenedAgain() {
        var tool = Tool.CRAYON
        content(onTool = { tool = it })
        mapOf(
            "paint_back" to "返回画册", "tool_eraser" to "橡皮擦", "undo" to "撤销一笔",
            "clear" to "重新画，可以撤销", "export" to "另存为 PNG 图片", "sound_toggle" to "关闭声音",
        ).forEach { (tag, purpose) ->
            val button = compose.onNodeWithTag(tag).assertIsDisplayed().assertContentDescriptionEquals(purpose)
            assertTrue("$tag keeps its picture without visible text", button.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).isNullOrEmpty())
        }
        compose.onNodeWithText("涂个彩 · Color Up!").assertDoesNotExist()
        compose.onNodeWithText("涂个彩").assertDoesNotExist()
        val selectedColor = CrayonPalette.colors[1]
        compose.onNodeWithTag("crayon_1").performClick()
        compose.onNodeWithText(selectedColor.name).assertIsDisplayed()
        compose.onNodeWithTag("tool_eraser").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(Tool.ERASER, tool) }
        compose.onNodeWithText(selectedColor.name).assertIsDisplayed()
        compose.onNodeWithTag("tool_eraser").performClick().assertIsNotSelected()
        compose.runOnIdle { assertEquals(Tool.CRAYON, tool) }
        compose.onNodeWithText(selectedColor.name).assertIsDisplayed()
        repeat(2) {
            compose.onNodeWithTag("paint_help").performClick()
            compose.onNodeWithTag("paint_help_dialog").assertIsDisplayed()
            compose.onNodeWithTag("paint_help_close").performClick()
            compose.onNodeWithTag("paint_help_dialog").assertDoesNotExist()
            compose.onNodeWithTag("screen_paint").assertIsDisplayed()
        }
    }

    @Test fun firstDrawingHintWaitsForAnAcceptedStrokeInsteadOfTimingOut() {
        var startAttempts = 0
        val state = content(
            initialState = paintState().copy(strokes = emptyList(), canUndo = false, showBrushHint = true),
            onStrokeStart = { startAttempts++ },
        )
        compose.onNodeWithText(CrayonPalette.colors.first().name).assertIsDisplayed()
        compose.onNodeWithTag("brush_hint").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithTag("brush_hint").assertIsDisplayed()
        compose.onNodeWithText(CrayonPalette.colors.first().name).assertIsDisplayed()
        val selectedColor = CrayonPalette.colors[1]
        compose.onNodeWithTag("crayon_1").performClick()
        compose.onNodeWithText(selectedColor.name).assertIsDisplayed()
        compose.onNodeWithTag("brush_hint").assertIsDisplayed()
        // The host does not accept this attempt: no active stroke is published.
        compose.onNodeWithTag("coloring_canvas").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, startAttempts) }
        compose.onNodeWithTag("brush_hint").assertIsDisplayed()
        compose.onNodeWithText(selectedColor.name).assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(activeStroke = savedStroke(state.value.page.regions.first().id))
        }
        compose.onNodeWithTag("brush_hint").assertDoesNotExist()
        compose.onNodeWithText(selectedColor.name).assertIsDisplayed()
        compose.onNodeWithTag("paint_help").assertIsDisplayed()
    }

    @Test fun aSingleColorAndManyColorsReceiveTheSamePositiveCelebration() {
        val state = content(initialState = paintState().copy(
            showCelebration = true, completionStars = 1, celebrationColorCount = 1,
        ))
        compose.onNodeWithText("哇，涂满啦！").assertIsDisplayed()
        compose.onNodeWithTag("celebration_stars").assertContentDescriptionEquals("为你的创作喝彩")
        compose.onNodeWithTag("celebration_export").assertIsEnabled()
        compose.onNodeWithTag("celebration_close").assertIsEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(completionStars = 3, celebrationColorCount = 6)
        }
        compose.onNodeWithText("哇，涂满啦！").assertIsDisplayed()
        compose.onNodeWithTag("celebration_stars").assertContentDescriptionEquals("为你的创作喝彩")
        compose.onNodeWithTag("celebration_export").assertIsEnabled()
        compose.onNodeWithTag("celebration_close").assertIsEnabled()
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

    @Test fun collectionsCombineWithThemesAndKeepSeveralUnfinishedPicturesAccessible() {
        val initial = discoveryState()
        val firstTheme = initial.pages.first().theme
        val secondUnfinished = initial.pages[1]
        val completed = initial.pages.last()
        var opened = ""
        content(initialState = initial, onPage = { opened = it })
        compose.onNodeWithTag("collection_in_progress").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("gallery_grid").assertContentDescriptionEquals("3 张线稿")
        compose.onNodeWithTag("theme_$firstTheme").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("gallery_grid").assertContentDescriptionEquals("2 张线稿")
        compose.onNodeWithTag("gallery_grid").performScrollToNode(hasTestTag("page_${secondUnfinished.id}"))
        compose.onNodeWithTag("page_${secondUnfinished.id}").performClick()
        compose.runOnIdle { assertEquals(secondUnfinished.id, opened) }
        compose.onNodeWithTag("paint_back").performClick()
        compose.onNodeWithTag("gallery_grid").assertContentDescriptionEquals("2 张线稿")
        compose.onNodeWithTag("gallery_grid").performScrollToIndex(0)
        compose.onNodeWithTag("collection_in_progress").assertIsSelected()
        compose.onNodeWithTag("theme_$firstTheme").assertIsSelected()
        compose.onNodeWithTag("collection_completed").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("gallery_grid").assertContentDescriptionEquals("1 张线稿")
        // A completed picture remains a work even if all its current pigment was erased.
        compose.onNodeWithTag("gallery_grid").performScrollToNode(hasTestTag("page_${completed.id}"))
        compose.onNodeWithTag("page_${completed.id}").performClick()
        compose.runOnIdle { assertEquals(completed.id, opened) }
        compose.onNodeWithTag("coloring_canvas").assertIsDisplayed()
    }

    @Test fun anEmptyCollectionCanReturnToAllPicturesWithoutLosingTheGallery() {
        val initial = discoveryState(withDrawings = false)
        content(initialState = initial)
        compose.onNodeWithTag("theme_${initial.pages.first().theme}").performScrollTo().performClick()
        compose.onNodeWithTag("collection_completed").performScrollTo().performClick()
        compose.onNodeWithTag("gallery_grid").assertContentDescriptionEquals("0 张线稿")
        compose.onNodeWithTag("gallery_empty").assertIsDisplayed()
        compose.onNodeWithTag("gallery_reset_filters").performScrollTo().performClick()
        compose.onNodeWithTag("gallery_grid").performScrollToIndex(0)
        compose.onNodeWithTag("theme_all").assertIsSelected()
        compose.onNodeWithTag("collection_all").assertIsSelected()
        compose.onNodeWithTag("gallery_grid").assertContentDescriptionEquals("${initial.pages.size} 张线稿")
        compose.onNodeWithTag("gallery_empty").assertDoesNotExist()
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
        (listOf("paint_back", "tool_eraser", "undo", "clear", "export", "sound_toggle", "paint_help", "parent_tips",
            "palette_previous", "palette_next") + (0..5).map { "crayon_$it" }).forEach { tag ->
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
        onStrokeStart: (Point) -> Unit = {},
    ): MutableState<ColoringUiState> {
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
                    onStrokeStart = onStrokeStart, onStrokeMove = {}, onStrokeEnd = {}, onStrokeCancel = {},
                    onPalettePageChange = onPalettePageChange,
                    onNavigate = { state.value = state.value.copy(screen = it) },
                )
            }
        }
        return state
    }

    private fun paintState() = ColoringUiState(
        strokes = listOf(Stroke(ArtworkCatalog.pages.first().regions.first().id, CrayonPalette.colors.first().argb, 40f, Tool.CRAYON, listOf(Point(500f, 500f)))),
        selectedCrayonId = CrayonPalette.colors.first().id,
        canUndo = true, screen = GameScreen.PAINT, isLoading = false,
    )

    private fun savedStroke(regionId: String) = Stroke(
        regionId, CrayonPalette.colors.first().argb, 40f, Tool.CRAYON, listOf(Point(500f, 500f)),
    )

    private fun discoveryState(withDrawings: Boolean = true): ColoringUiState {
        val source = ArtworkCatalog.pages.first()
        val pages = (0..3).map { index ->
            source.copy(
                id = "${source.id}-discovery-$index", title = "发现画稿 $index",
                theme = if (index == 2) "fixture-b" else "fixture-a",
                themeLabel = if (index == 2) "主题乙" else "主题甲",
            )
        }
        val drawings = if (withDrawings) pages.take(3).associate { page ->
            page.id to listOf(savedStroke(page.regions.first().id))
        } + mapOf("retired-id" to listOf(savedStroke(source.regions.first().id))) else emptyMap()
        return ColoringUiState(
            page = pages.first(), pages = pages, screen = GameScreen.GALLERY, isLoading = false,
            drawings = drawings,
            completedPages = if (withDrawings) setOf(pages.last().id, "retired-id") else emptySet(),
        )
    }

    private fun tagStartsWith(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
}
