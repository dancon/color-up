package com.colorup.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colorup.core.ArtworkCatalog
import com.colorup.core.CrayonPalette
import com.colorup.core.LineArt
import com.colorup.core.Point
import com.colorup.core.Stroke
import com.colorup.core.Tool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Business integration only: a private test gallery, real coverage worker, and no production singleton. */
@RunWith(AndroidJUnit4::class)
class ColoringViewModelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val models = ViewModelStore()
    private lateinit var directory: File
    private lateinit var application: Application
    private lateinit var store: GalleryStore
    private lateinit var repository: GalleryRepository

    @Before fun setUp() {
        val target = instrumentation.targetContext
        application = target.applicationContext as Application
        directory = File(target.cacheDir, "coloring-view-model-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val isolated = object : ContextWrapper(target) {
            override fun getFilesDir(): File = directory
        }
        store = GalleryStore(isolated)
        repository = GalleryRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(isolated)
    }

    @After fun tearDown() {
        try {
            // Clearing the owner cancels ViewModel work and releases its coverage dispatcher/bitmaps.
            onMain { models.clear() }
            if (::repository.isInitialized) loadSavedGallery() // Drain earlier queued writes before cleanup.
        } finally {
            if (::directory.isInitialized) directory.deleteRecursively()
        }
    }

    @Test fun completionCelebratesOnceAndSurvivesClearUndoAndOpeningTheNextPicture() {
        val fixture = preparePicture(complete = false)
        val cat = fixture.page
        val model = createModel()

        val home = awaitPartialCoverage(model, cat)
        assertEquals(GameScreen.HOME, home.screen)
        assertFalse("Loading coverage on Home must not open a celebration", home.showCelebration)
        assertFalse(cat.id in home.completedPages)
        assertEquals(fixture.selectedColor, home.selectedColor)

        onMain { model.selectPage(cat.id) }
        assertFalse("Opening a partial picture must not celebrate", onMain { model.uiState.showCelebration })
        paintMissingRegion(model, cat)
        val celebrated = awaitState(model, "Automatic picture completion") {
            it.screen == GameScreen.PAINT && it.showCelebration && cat.id in it.completedPages
        }
        assertEquals(cat.regions.size, celebrated.progressByPage.getValue(cat.id).completedRegions)
        assertTrue(celebrated.progressByPage.getValue(cat.id).usedColorCount >= 6)
        assertEquals(3, celebrated.completionStars)
        val completedDrawing = celebrated.strokes

        onMain {
            model.closeCelebration()
            model.clear()
        }
        val cleared = awaitState(model, "Cleared coverage") {
            it.strokes.isEmpty() && it.progressByPage[cat.id]?.averageCoverage == 0f
        }
        assertTrue("Clearing the whole picture must remain undoable", cleared.canUndo)
        assertTrue("A previously earned completion remains in the gallery", cat.id in cleared.completedPages)
        assertFalse(cleared.showCelebration)

        onMain { model.undo() }
        val restored = awaitState(model, "Undo restored full coverage") {
            it.strokes.size == completedDrawing.size && it.progressByPage[cat.id]?.isComplete == true
        }
        assertEquals(completedDrawing, restored.strokes)
        assertTrue(cat.id in restored.completedPages)
        assertFalse("Undoing a clear must not celebrate an already completed picture again", restored.showCelebration)

        onMain { model.nextPicture() }
        val next = awaitState(model, "Next picture navigation") {
            it.screen == GameScreen.PAINT && it.page.id == ArtworkCatalog.pages[1 % ArtworkCatalog.pages.size].id
        }
        assertFalse(next.showCelebration)
        assertTrue(cat.id in next.completedPages)

        val saved = loadSavedGallery()
        assertEquals(ArtworkCatalog.pages[1 % ArtworkCatalog.pages.size].id, saved.pageId)
        assertTrue(cat.id in saved.completedPages)
        assertEquals(fixture.selectedColor, saved.selectedColor)
        assertTrue(saved.hasVisitedPage)
    }

    @Test fun restoringAFullDrawingSilentlyRepairsMissingCompletionHistory() {
        val fixture = preparePicture(complete = true)
        val model = createModel()

        val home = awaitState(model, "Restored completion history") {
            !it.isLoading && it.progressByPage[fixture.page.id]?.isComplete == true &&
                fixture.page.id in it.completedPages
        }

        assertEquals(GameScreen.HOME, home.screen)
        assertFalse("Crash recovery must not replay an old completion popup", home.showCelebration)
        assertTrue(fixture.page.id in loadSavedGallery().completedPages)
    }

    @Test fun clearingImmediatelyAfterTheFinishingStrokeCannotLoseCompletionHistory() {
        val fixture = preparePicture(complete = false)
        val cat = fixture.page
        val model = createModel()
        awaitPartialCoverage(model, cat)
        onMain { model.selectPage(cat.id) }

        // Both actions happen in one main-thread turn, before asynchronous measurement can reply.
        paintMissingRegion(model, cat, clearImmediately = true)
        val cleared = awaitState(model, "Completion retained across immediate clear") {
            it.strokes.isEmpty() && it.progressByPage[cat.id]?.averageCoverage == 0f &&
                cat.id in it.completedPages
        }

        assertTrue(cleared.canUndo)
        val saved = loadSavedGallery()
        assertTrue("The briefly reached achievement must be durable", cat.id in saved.completedPages)
        assertTrue(saved.drawings.getValue(cat.id).isEmpty())
    }

    @Test fun gradientSelectionKeepsItsIdentityWhenASolidSharesTheSameBaseColor() {
        val gradient = CrayonPalette.colors.first { crayon -> crayon.gradientStops.isNotEmpty() &&
            CrayonPalette.colors.any { it.gradientStops.isEmpty() && it.argb == crayon.argb } }
        val solid = CrayonPalette.colors.first { it.gradientStops.isEmpty() && it.argb == gradient.argb }
        val model = createModel()
        val selectedPage = ArtworkCatalog.pages.first()
        val start = sequence {
            for (y in 10..990 step 20) for (x in 10..990 step 20) yield(Point(x.toFloat(), y.toFloat()))
        }.first { point -> selectedPage.regions.any { it.shape.contains(point) } }
        awaitState(model, "Loaded empty gallery") { !it.isLoading }
        onMain {
            model.selectPage(selectedPage.id)
            model.selectCrayon(gradient)
            model.startStroke(start)
            model.moveStroke(Point(800f, 20f))
            model.endStroke()
        }
        val drawn = onMain { model.uiState }
        assertEquals(gradient.id, drawn.selectedCrayonId)
        assertEquals(gradient.id, drawn.strokes.single().crayonId)
        assertEquals(gradient.gradientStops, drawn.strokes.single().gradientStops)
        val saved = loadSavedGallery()
        assertEquals(gradient.id, saved.selectedCrayonId)
        assertEquals(gradient.gradientStops, saved.drawings.getValue(selectedPage.id).single().gradientStops)
        onMain {
            model.selectCrayon(solid)
            model.startStroke(start)
            model.endStroke()
        }
        val final = onMain { model.uiState }
        assertEquals(solid.id, final.selectedCrayonId)
        assertTrue(final.strokes.last().gradientStops.isEmpty())
        assertEquals("Selecting a matching solid must not recolor the original gradient",
            gradient.gradientStops, final.strokes.first().gradientStops)
    }

    @Test fun aRetiredArtworkIdCannotOpenOrReplaceTheCurrentDrawing() {
        val fixture = preparePicture(complete = false)
        val model = createModel()
        val loaded = awaitPartialCoverage(model, fixture.page)
        val before = loaded.drawings.getValue(fixture.page.id)
        onMain { model.selectPage("cat") }
        val state = onMain { model.uiState }
        assertEquals(fixture.page.id, state.page.id)
        assertEquals(before, state.strokes)
        assertEquals(GameScreen.HOME, state.screen)
        assertTrue(state.pages.none { it.id == "cat" })
        assertEquals(before, loadSavedGallery().drawings.getValue(fixture.page.id))
    }

    private data class PictureFixture(val page: LineArt, val selectedColor: Long)

    private fun preparePicture(complete: Boolean): PictureFixture {
        val cat = ArtworkCatalog.pages.first()
        // Six distinct pigments remain testable even when the first theme has fewer regions.
        val extraColor = Stroke(cat.regions.first().id, CrayonPalette.colors[5].argb,
            200f, Tool.CRAYON, denseZigzag())
        val full = listOf(extraColor) + cat.regions.mapIndexed { index, region ->
            Stroke(
                regionId = region.id,
                colorArgb = CrayonPalette.colors[index % 6].argb,
                width = 200f,
                tool = Tool.CRAYON,
                points = denseZigzag(),
            )
        }
        val selectedColor = CrayonPalette.colors.last { it.gradientStops.isEmpty() }.argb
        store.save(StoredGallery(
            pageId = cat.id,
            drawings = mapOf(cat.id to if (complete) full else full.dropLast(1)),
            completedPages = emptySet(),
            selectedColor = selectedColor,
            hasVisitedPage = true,
        ))
        return PictureFixture(cat, selectedColor)
    }

    private fun createModel(): ColoringViewModel = onMain {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ColoringViewModel::class.java)
                return modelClass.cast(ColoringViewModel(application, repository))
            }
        }
        ViewModelProvider(models, factory)[ColoringViewModel::class.java]
    }

    private fun awaitPartialCoverage(model: ColoringViewModel, page: LineArt): ColoringUiState =
        awaitState(model, "Loaded partial gallery coverage") {
            !it.isLoading && it.progressByPage[page.id]?.completedRegions == page.regions.size - 1
        }

    private fun paintMissingRegion(model: ColoringViewModel, page: LineArt, clearImmediately: Boolean = false) {
        val lastRegion = page.regions.last()
        val firstVisiblePoint = sequence {
            for (y in 5..995 step 10) for (x in 5..995 step 10) yield(Point(x.toFloat(), y.toFloat()))
        }.first { point -> page.regions.lastOrNull { it.shape.contains(point) }?.id == lastRegion.id }
        onMain {
            model.selectWidth(200f)
            model.startStroke(firstVisiblePoint)
            assertEquals("The real gesture must begin in the missing visible region",
                lastRegion.id, model.uiState.activeStroke?.regionId)
            denseZigzag().forEach(model::moveStroke)
            model.endStroke()
            if (clearImmediately) model.clear()
        }
    }

    private fun denseZigzag(): List<Point> = buildList {
        for ((row, y) in (0..1000 step 80).withIndex()) {
            add(Point(if (row % 2 == 0) 0f else 1000f, y.toFloat()))
            add(Point(if (row % 2 == 0) 1000f else 0f, y.toFloat()))
        }
    }

    private fun awaitState(
        model: ColoringViewModel,
        description: String,
        predicate: (ColoringUiState) -> Boolean,
    ): ColoringUiState {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var latest = onMain { model.uiState }
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            latest = onMain { model.uiState }
            if (predicate(latest)) return latest
            // Poll on the instrumentation thread; the main thread remains free for repository callbacks.
            Thread.sleep(25)
        }
        throw AssertionError("$description timed out: screen=${latest.screen}, " +
            "page=${latest.page.id}, strokes=${latest.strokes.size}, progress=${latest.progressByPage}")
    }

    private fun loadSavedGallery(): StoredGallery {
        val finished = CountDownLatch(1)
        var result: Result<StoredGallery>? = null
        repository.load { loaded -> result = loaded; finished.countDown() }
        assertTrue("Gallery queue did not drain", finished.await(10, TimeUnit.SECONDS))
        return requireNotNull(result).getOrThrow()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
}
