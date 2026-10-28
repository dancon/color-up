package com.colorup.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.colorup.app.ui.ColorUpScreen
import com.colorup.core.ArtworkCatalog
import com.colorup.core.CrayonPalette
import com.colorup.core.Point
import com.colorup.core.Stroke
import com.colorup.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Window constraints are real Compose constraints; only their display density is fitted to the host. */
class ResponsiveLayoutTest {
    @get:Rule val compose = createComposeRule()
    private var renderedDensity = 1f

    @Test fun tabletLandscape1280By800KeepsControlsOutsideSquarePaper() = assertPaintLayout(1280, 800)
    @Test fun tabletLandscape1024By768KeepsControlsOutsideSquarePaper() = assertPaintLayout(1024, 768)
    @Test fun tabletPortrait800By1280KeepsControlsOutsideSquarePaper() = assertPaintLayout(800, 1280)
    @Test fun tabletPortrait768By1024KeepsControlsOutsideSquarePaper() = assertPaintLayout(768, 1024)
    @Test fun narrowWindow600By960KeepsEveryCrayonTouchable() = assertPaintLayout(600, 960)
    @Test fun smallerWindow500By800CanWrapToolbarWithoutCoveringPaper() = assertPaintLayout(500, 800)

    @Test fun homeActionsRemainInsideNarrowAndWideHeroColumnsWithLongPictureTitles() {
        val window = mutableStateOf(600 to 960)
        val home = paintState().copy(
            screen = GameScreen.HOME,
            hasVisitedPage = true,
            page = ArtworkCatalog.pages.first().copy(title = "小朋友和动物伙伴一起开启奇妙的彩色森林之旅"),
        )
        compose.setContent { FittedWindow(window.value.first, window.value.second) { Screen(home) } }
        listOf(600 to 960, 1024 to 768).forEach { size ->
            compose.runOnIdle { window.value = size }
            listOf("start_coloring", "continue_coloring").forEach { tag ->
                compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                val action = bounds(tag)
                val windowBounds = bounds("responsive_window")
                assertInside(windowBounds, action, tag)
                assertTrue("$tag needs a 48dp touch target", action.height >= 48f * renderedDensity - 1f)
            }
        }
    }

    @Test fun palettePageSurvivesWindowRotationAndSavedStateRecreation() {
        val restoration = StateRestorationTester(compose)
        val window = mutableStateOf(800 to 1280)
        val crayon = CrayonPalette.colors[15]
        val state = paintState().copy(selectedCrayonId = crayon.id, selectedColor = crayon.argb, tool = Tool.ERASER)
        restoration.setContent { FittedWindow(window.value.first, window.value.second) { Screen(state) } }
        repeat(2) { compose.onNodeWithTag("palette_next").performClick() }
        compose.onNodeWithTag("page_dot_4").assertIsSelected()
        compose.runOnIdle { window.value = 1280 to 800 }
        compose.onNodeWithTag("page_dot_4").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("page_dot_4").assertIsSelected()
        compose.onNodeWithTag("tool_eraser").assertIsSelected()
        (24..29).forEach { compose.onNodeWithTag("crayon_$it").assertIsDisplayed() }
    }

    private fun assertPaintLayout(width: Int, height: Int) {
        compose.setContent { FittedWindow(width, height) { Screen(paintState()) } }
        compose.waitForIdle()
        val workspace = bounds("paint_workspace")
        val paper = bounds("paint_board_frame")
        val toolbar = bounds("paint_toolbar")
        val tray = bounds("crayon_tray")
        assertEquals("Square paper", paper.width, paper.height, 1f)
        assertTrue("Paper should remain large enough to draw", paper.width / renderedDensity >= minOf(width, height) * .5f)
        assertInside(workspace, paper, "paper")
        assertInside(workspace, toolbar, "toolbar")
        assertInside(workspace, tray, "crayon tray")
        assertEquals("Keep toolbar close to paper in both orientations", 14f, (paper.top - toolbar.bottom) / renderedDensity, 1f)
        assertTrue("Crayons must not overlap paper", tray.top >= paper.bottom + 7f * renderedDensity)
        if (height > width) {
            assertEquals("Portrait edit tools align to the toolbar's start", toolbar.left, bounds("paint_back").left, 1f)
            assertEquals("Portrait file tools align to the toolbar's end", toolbar.right, bounds("sound_toggle").right, 1f)
            assertEquals("Portrait title stays centered independently of unequal tool groups", paper.center.x, bounds("paint_title").center.x, 1f)
        } else {
            assertEquals("Landscape tools remain centered above paper", paper.center.x, toolbar.center.x, 1f)
        }
        val controls = listOf("paint_back", "tool_eraser", "undo", "clear", "export", "sound_toggle", "parent_tips", "paint_help", "palette_previous", "palette_next") + (0..5).map { "crayon_$it" }
        controls.forEach { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val control = bounds(tag)
            assertInside(workspace, control, tag)
            assertTrue("$tag width must be at least 48dp", control.width >= 48f * renderedDensity - 1f)
            assertTrue("$tag height must be at least 48dp", control.height >= 48f * renderedDensity - 1f)
            assertTrue("$tag must stay outside the painting surface", control.right <= paper.left || control.left >= paper.right || control.bottom <= paper.top || control.top >= paper.bottom)
        }
        (0..17).forEach { compose.onNodeWithTag("page_dot_$it").assertIsDisplayed() }
        compose.onNodeWithText("把平板横过来，画板更大哦～").assertDoesNotExist()
        compose.onNodeWithTag("paint_help").performClick()
        compose.onNodeWithTag("paint_help_dialog").assertIsDisplayed()
        compose.onNodeWithTag("paint_help_close").performClick()
        compose.onNodeWithTag("parent_tips").assertIsDisplayed()
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertInside(parent: Rect, child: Rect, label: String) {
        assertTrue("$label left outside window", child.left >= parent.left - 1f)
        assertTrue("$label right outside window", child.right <= parent.right + 1f)
        assertTrue("$label top outside window", child.top >= parent.top - 1f)
        assertTrue("$label bottom outside window", child.bottom <= parent.bottom + 1f)
    }

    @Composable
    private fun FittedWindow(width: Int, height: Int, content: @Composable () -> Unit) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val hostDensity = LocalDensity.current
            val density = minOf(1f, maxWidth.value * hostDensity.density / width, maxHeight.value * hostDensity.density / height)
            renderedDensity = density
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                MaterialTheme {
                    Box(Modifier.requiredSize(width.dp, height.dp).testTag("responsive_window")) { content() }
                }
            }
        }
    }

    @Composable
    private fun Screen(state: ColoringUiState) {
        ColorUpScreen(
            state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
        )
    }

    private fun paintState(): ColoringUiState {
        val page = ArtworkCatalog.pages.first()
        val stroke = Stroke(page.regions.first().id, 0xFFFF8FABL, 40f, Tool.CRAYON, listOf(Point(100f, 100f), Point(250f, 250f)))
        return ColoringUiState(page = page, screen = GameScreen.PAINT, isLoading = false, strokes = listOf(stroke), drawings = mapOf(page.id to listOf(stroke)), canUndo = true)
    }
}
