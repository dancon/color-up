package com.colorup.app

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colorup.core.ArtworkCatalog
import com.colorup.core.ColoringSession
import com.colorup.core.CrayonPalette
import com.colorup.core.Point
import com.colorup.core.Stroke
import com.colorup.core.Tool
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Uses an isolated cache directory so these checks never overwrite a child's saved drawings. */
@RunWith(AndroidJUnit4::class)
class GalleryStoreTest {
    private lateinit var directory: File
    private lateinit var store: GalleryStore
    private lateinit var isolatedContext: Context
    private val galleryFile: File get() = File(directory, "gallery-v1.json")

    @Before fun setUp() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(targetContext.cacheDir, "gallery-store-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        isolatedContext = object : ContextWrapper(targetContext) {
            override fun getFilesDir(): File = directory
        }
        store = GalleryStore(isolatedContext)
    }

    @After fun tearDown() {
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun absentGalleryStartsOnTheFirstPageWithoutCreatingAFile() {
        val loaded = store.load()

        assertEquals(ArtworkCatalog.pages.first().id, loaded.pageId)
        assertTrue(loaded.drawings.isEmpty())
        assertEquals(CrayonPalette.colors.first().argb, loaded.selectedColor)
        assertFalse(loaded.hasVisitedPage)
        assertTrue(loaded.coverageByPage.isEmpty())
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test fun allDrawingsAndSelectedPageRoundTripWithOrderedPaintAndEraserStrokes() {
        val gradient = CrayonPalette.colors.first { it.gradientStops.isNotEmpty() }
        val drawings = ArtworkCatalog.pages.mapIndexed { index, page ->
            page.id to listOf(
                stroke(page.regions.first().id, Tool.CRAYON, index).copy(
                    colorArgb = gradient.argb, crayonId = gradient.id, gradientStops = gradient.gradientStops,
                ),
                stroke(page.regions.first().id, Tool.ERASER, index + 1),
                stroke(page.regions.last().id, Tool.CRAYON, index + 2),
            )
        }.toMap()
        val expected = StoredGallery(
            pageId = ArtworkCatalog.pages.last().id,
            drawings = drawings,
            completedPages = setOf(ArtworkCatalog.pages.first().id, ArtworkCatalog.pages.last().id),
            soundEnabled = false,
            selectedColor = CrayonPalette.colors.last().argb,
            hasVisitedPage = true,
            coverageByPage = ArtworkCatalog.pages.associate { page ->
                page.id to mapOf(page.regions.first().id to .75f, page.regions.last().id to .5f)
            },
            selectedCrayonId = CrayonPalette.colors.last().id,
        )

        store.save(expected)
        val restored = store.load()

        // Data-class equality checks every point, width, ARGB color, tool, region, and list position.
        assertEquals(expected, restored)
        assertTrue(File(directory, "gallery-v2/index.json").exists())
    }

    @Test fun olderVersionOneSaveUsesCompatibleDefaultsForMissingOptionalFields() {
        writeLegacy(StoredGallery(ArtworkCatalog.pages.first().id, emptyMap()))
        val root = JSONObject(galleryFile.readText())
        root.remove("completedPages")
        root.remove("soundEnabled")
        root.remove("selectedColor")
        root.remove("hasVisitedPage")
        root.remove("coverageByPage")
        root.remove("selectedCrayonId")
        galleryFile.writeText(root.toString())

        val loaded = store.load()

        assertTrue(loaded.soundEnabled)
        assertTrue(loaded.completedPages.isEmpty())
        assertEquals(CrayonPalette.colors.first().argb, loaded.selectedColor)
        assertEquals(CrayonPalette.colors.first().id, loaded.selectedCrayonId)
        assertTrue(loaded.hasVisitedPage)
        assertTrue(loaded.coverageByPage.isEmpty())
    }

    @Test fun invalidSelectedColorFallsBackWithoutRecoloringSavedStrokes() {
        val page = ArtworkCatalog.pages.first()
        val oldStroke = stroke(page.regions.first().id, Tool.CRAYON, 0)
        writeLegacy(StoredGallery(page.id, mapOf(page.id to listOf(oldStroke))))
        val root = JSONObject(galleryFile.readText()).put("selectedColor", -1)
        galleryFile.writeText(root.toString())

        val loaded = store.load()

        assertEquals(CrayonPalette.colors.first().argb, loaded.selectedColor)
        assertEquals(listOf(oldStroke), loaded.drawings.getValue(page.id))
        assertFalse(loaded.hasVisitedPage)
    }

    @Test fun aGradientSelectionUsesItsIdAndLegacyColorOnlySelectionResolvesToASolid() {
        val gradient = CrayonPalette.colors.first { candidate ->
            candidate.gradientStops.isNotEmpty() && CrayonPalette.colors.any {
                it.gradientStops.isEmpty() && it.argb == candidate.argb
            }
        }
        val solid = CrayonPalette.colors.first { it.gradientStops.isEmpty() && it.argb == gradient.argb }
        val firstSolid = CrayonPalette.colors.first { it.gradientStops.isEmpty() }
        val page = ArtworkCatalog.pages.first()
        writeLegacy(StoredGallery(
            pageId = page.id,
            drawings = emptyMap(),
            selectedColor = firstSolid.argb,
            selectedCrayonId = gradient.id,
        ))

        val selectedGradient = store.load()
        assertEquals(gradient.id, selectedGradient.selectedCrayonId)
        assertEquals(gradient.argb, selectedGradient.selectedColor)

        val root = JSONObject(galleryFile.readText())
            .put("selectedColor", solid.argb)
            .put("selectedCrayonId", "future-missing-preset")
        galleryFile.writeText(root.toString())
        assertEquals("An unknown selection must not choose a gradient with the same representative ARGB",
            solid.id, store.load().selectedCrayonId)
        root.remove("selectedCrayonId")
        galleryFile.writeText(root.toString())
        assertEquals(solid.id, store.load().selectedCrayonId)
    }

    @Test fun unknownGradientIdentitiesAndSavedStopsSurviveWhileMalformedStyleFieldsFallBackSafely() {
        val page = ArtworkCatalog.pages.first()
        val original = stroke(page.regions.first().id, Tool.CRAYON, 0)
        writeLegacy(StoredGallery(page.id, mapOf(page.id to listOf(original))))
        val root = JSONObject(galleryFile.readText())
        val template = root.getJSONObject("drawings").getJSONArray(page.id).getJSONObject(0)
        val savedStops = listOf(0xFFFF0000L, 0xFF00FF00L, 0xFF0000FFL)
        fun stopArray(values: List<Long>) = JSONArray().also { array -> values.forEach { array.put(it) } }
        fun entry(id: String, values: List<Long>) = JSONObject(template.toString())
            .put("crayonId", id).put("gradientStops", stopArray(values))
        root.getJSONObject("drawings").put(page.id, JSONArray()
            .put(entry("future-gradient-a", savedStops))
            .put(entry("future-gradient-b", savedStops.reversed()))
            .put(entry("invalid style id", savedStops))
            .put(entry("future-gradient-c", List(6) { 0xFF000000L + it }))
            .put(entry("future-gradient-d", listOf(0x80FF0000L, 0xFF0000FFL))))
        galleryFile.writeText(root.toString())

        assertEquals(listOf(
            original.copy(crayonId = "future-gradient-a", gradientStops = savedStops),
            original.copy(crayonId = "future-gradient-b", gradientStops = savedStops.reversed()),
            original.copy(gradientStops = savedStops),
            original.copy(crayonId = "future-gradient-c"),
            original.copy(crayonId = "future-gradient-d"),
        ), store.load().drawings.getValue(page.id))
    }

    @Test fun aRetiredVersionOneDrawingIsNotMappedAndItsOriginalFileRemainsRecoverable() {
        val retired = JSONObject().put("version", 1).put("pageId", "cat")
            .put("hasVisitedPage", true).put("completedPages", JSONArray().put("cat"))
            .put("drawings", JSONObject().put("cat", JSONArray().put(JSONObject()
                .put("regionId", "cat:r00").put("color", 0xFFFF5C5CL).put("width", 40)
                .put("tool", "CRAYON").put("points", JSONArray().put(JSONArray().put(50).put(50))))))
        galleryFile.writeText(retired.toString())
        val original = galleryFile.readBytes()
        val loaded = store.load()
        assertEquals(ArtworkCatalog.pages.first().id, loaded.pageId)
        assertFalse(loaded.hasVisitedPage)
        assertTrue(loaded.drawings.values.all { it.isEmpty() })
        assertTrue(loaded.completedPages.isEmpty())
        val page = ArtworkCatalog.pages.first()
        val paint = stroke(page.regions.first().id, Tool.CRAYON, 0)
        store.save(loaded.copy(drawings = mapOf(page.id to listOf(paint)), hasVisitedPage = true))
        assertArrayEquals("Deleting bundled art must not delete the old local save", original, galleryFile.readBytes())
        assertEquals(listOf(paint), store.load().drawings.getValue(page.id))
    }

    @Test fun retiredVersionTwoPageFilesArePreservedWhenCurrentArtIsSaved() {
        val pagesDirectory = File(directory, "gallery-v2").apply { mkdirs() }
        val name = "page-00000000-0000-0000-0000-000000000001.json"
        val retired = File(pagesDirectory, name)
        val original = "{\"version\":2,\"pageId\":\"cat\",\"strokes\":[{\"regionId\":\"cat:r00\",\"color\":4294925404,\"points\":[[50,50]]}]}"
            .toByteArray(Charsets.UTF_8)
        retired.writeBytes(original)
        File(pagesDirectory, "index.json").writeText(JSONObject().put("version", 2)
            .put("pageId", "cat").put("hasVisitedPage", true)
            .put("completedPages", JSONArray().put("cat"))
            .put("pages", JSONObject().put("cat", name)).toString())
        val loaded = store.load()
        assertFalse(loaded.hasVisitedPage)
        assertFalse(loaded.drawings.containsKey("cat"))
        assertTrue(loaded.drawings.values.all { it.isEmpty() })
        val page = ArtworkCatalog.pages.first()
        val paint = stroke(page.regions.first().id, Tool.CRAYON, 0)
        store.save(loaded.copy(drawings = mapOf(page.id to listOf(paint)), hasVisitedPage = true))
        assertArrayEquals(original, retired.readBytes())
        assertEquals(name, indexJson().getJSONObject("pages").getString("cat"))
        assertEquals(listOf(paint), GalleryStore(isolatedContext).load().drawings.getValue(page.id))
    }

    @Test fun coverageIgnoresUnknownOrInvalidEntriesAndClampsValidNumbers() {
        val page = ArtworkCatalog.pages.first()
        val regions = page.regions
        writeLegacy(StoredGallery(page.id, emptyMap()))
        val coverage = JSONObject()
            .put(regions[0].id, .55)
            .put(regions[1].id, -.5)
            .put(regions[2].id, 2.5)
            .put(regions[3].id, "NaN")
            .put("unknown-region", .8)
        val root = JSONObject(galleryFile.readText()).put("coverageByPage", JSONObject()
            .put(page.id, coverage)
            .put("unknown-page", JSONObject().put(regions[0].id, .9)))
        galleryFile.writeText(root.toString())

        assertEquals(
            mapOf(page.id to mapOf(regions[0].id to .55f, regions[1].id to 0f, regions[2].id to 1f)),
            store.load().coverageByPage,
        )
    }

    @Test fun completedPagesIgnoreIdsThatAreNotInTheCurrentCatalog() {
        val knownPage = ArtworkCatalog.pages.last().id
        writeLegacy(StoredGallery(knownPage, emptyMap()))
        val root = JSONObject(galleryFile.readText()).put(
            "completedPages", JSONArray().put("unknown-page").put(knownPage).put(knownPage),
        )
        galleryFile.writeText(root.toString())

        assertEquals(setOf(knownPage), store.load().completedPages)
    }

    @Test fun invalidStrokeEntriesAreSkippedWithoutLosingTheValidDrawing() {
        val page = ArtworkCatalog.pages.first()
        val expected = listOf(
            stroke(page.regions.first().id, Tool.CRAYON, 0),
            stroke(page.regions.first().id, Tool.ERASER, 1),
        )
        writeLegacy(StoredGallery(page.id, mapOf(page.id to expected)))
        val root = JSONObject(galleryFile.readText())
        val drawings = root.getJSONObject("drawings")
        val original = drawings.getJSONArray(page.id)
        val validPaint = original.getJSONObject(0)
        val validEraser = original.getJSONObject(1)
        fun changedPaint() = JSONObject(validPaint.toString())

        drawings.put(page.id, JSONArray()
            .put(changedPaint().put("regionId", "unknown-region"))
            .put(changedPaint().put("points", JSONArray()))
            .put(changedPaint().put("points", JSONArray().put(JSONArray().put(250))))
            .put(changedPaint().put("points", JSONArray().put("not-a-coordinate")))
            // A finite JSON double can overflow a Float and must not enter the restored session.
            .put(changedPaint().put("points", JSONArray().put(JSONArray().put(1e100).put(250))))
            .put(validPaint)
            .put(validEraser))
        galleryFile.writeText(root.toString())

        val loaded = store.load()

        assertEquals(page.id, loaded.pageId)
        assertEquals(expected, loaded.drawings.getValue(page.id))
        ArtworkCatalog.pages.drop(1).forEach { otherPage ->
            assertTrue(loaded.drawings.getValue(otherPage.id).isEmpty())
        }
    }

    @Test fun damagedJsonReportsFailureAndPreservesTheOriginalFile() {
        val damaged = "{\"version\":1,\"drawings\":"
        galleryFile.writeText(damaged)

        val failure = runCatching { store.load() }.exceptionOrNull()

        assertNotNull("A damaged save must report a load error", failure)
        assertTrue(galleryFile.exists())
        assertEquals(damaged, galleryFile.readText())
    }

    @Test fun failedLoadAndRecoveryBackupPreserveTheBaseAndAtomicBackupByteForByte() {
        val base = byteArrayOf(0x00, 0x01, 0x7F, 0x42)
        val legacy = "{\"version\":2}".toByteArray()
        val pending = "unfinished save".toByteArray()
        galleryFile.writeBytes(base)
        val backupFile = File(directory, "gallery-v1.json.bak").apply { writeBytes(legacy) }
        val pendingFile = File(directory, "gallery-v1.json.new").apply { writeBytes(pending) }

        assertTrue(runCatching { store.load() }.isFailure)
        val recovery = store.backupUnreadableFiles()

        assertArrayEquals(base, galleryFile.readBytes())
        assertArrayEquals(legacy, backupFile.readBytes())
        assertArrayEquals(pending, pendingFile.readBytes())
        assertArrayEquals(base, File(recovery, galleryFile.name).readBytes())
        assertArrayEquals(legacy, File(recovery, backupFile.name).readBytes())
        assertArrayEquals(pending, File(recovery, pendingFile.name).readBytes())
    }

    @Test fun legacyMigrationRetainsTheOriginalAndOnlyRewritesChangedPages() {
        val pages = ArtworkCatalog.pages.take(2)
        writeLegacy(StoredGallery(pages[0].id, pages.associate { page ->
            page.id to listOf(stroke(page.regions.first().id, Tool.CRAYON, 0))
        }))
        val original = galleryFile.readBytes()
        val loaded = store.load()
        store.save(loaded)
        val firstIndex = indexJson()
        val firstName = firstIndex.getJSONObject("pages").getString(pages[0].id)
        val secondName = firstIndex.getJSONObject("pages").getString(pages[1].id)
        assertEquals(2, firstIndex.getInt("version"))
        assertArrayEquals("Migration must keep the original v1 recovery source", original, galleryFile.readBytes())

        val edited = loaded.copy(drawings = loaded.drawings + (pages[0].id to
            (loaded.drawings.getValue(pages[0].id) + stroke(pages[0].regions.first().id, Tool.ERASER, 1))))
        store.save(edited)
        val nextPages = indexJson().getJSONObject("pages")

        assertTrue(firstName != nextPages.getString(pages[0].id))
        assertEquals("An unchanged drawing must reuse its immutable page", secondName, nextPages.getString(pages[1].id))
        assertFalse("Unreferenced generations are removed after a successful index commit",
            File(directory, "gallery-v2/$firstName").exists())
        assertEquals(edited, GalleryStore(isolatedContext).load())
    }

    @Test fun failedIndexCommitKeepsTheOldGalleryAndDoesNotAdvanceTheRetryCache() {
        val page = ArtworkCatalog.pages.first()
        store.save(StoredGallery(page.id, mapOf(page.id to listOf(stroke(page.regions.first().id, Tool.CRAYON, 0)))))
        val originalIndex = File(directory, "gallery-v2/index.json").readBytes()
        var rejectCommit = true
        val retrying = GalleryStore(isolatedContext) { file, bytes ->
            if (rejectCommit) throw IOException("Injected index commit failure")
            writeAtomicFixture(file, bytes)
        }
        val original = retrying.load()
        val edited = original.copy(drawings = original.drawings + (page.id to
            (original.drawings.getValue(page.id) + stroke(page.regions.first().id, Tool.ERASER, 1))))
        val before = pageFileNames()

        assertTrue(runCatching { retrying.save(edited) }.isFailure)
        val uncommittedPages = pageFileNames() - before
        assertTrue("The injected failure must happen after a changed page was written", uncommittedPages.isNotEmpty())
        assertArrayEquals(originalIndex, File(directory, "gallery-v2/index.json").readBytes())
        assertEquals(original, GalleryStore(isolatedContext).load())

        rejectCommit = false
        retrying.save(edited)
        val committedName = indexJson().getJSONObject("pages").getString(page.id)
        assertFalse("A failed attempt must not become the cache baseline", committedName in uncommittedPages)
        assertEquals(edited, GalleryStore(isolatedContext).load())
        assertTrue(uncommittedPages.none { File(directory, "gallery-v2/$it").exists() })
    }

    @Test fun anUncommittedFirstIndexStillReadsTheLegacyGallery() {
        val page = ArtworkCatalog.pages.first()
        val original = stroke(page.regions.first().id, Tool.CRAYON, 0)
        writeLegacy(StoredGallery(page.id, mapOf(page.id to listOf(original))))
        File(directory, "gallery-v2").mkdirs()
        File(directory, "gallery-v2/index.json.new").writeText("{unfinished migration")

        assertEquals(listOf(original), GalleryStore(isolatedContext).load().drawings.getValue(page.id))
    }

    @Test fun aDamagedReferencedPageDoesNotFallBackToLegacyAndRecoveryPreservesEveryOriginal() {
        val page = ArtworkCatalog.pages.first()
        writeLegacy(StoredGallery(page.id, mapOf(page.id to listOf(stroke(page.regions.first().id, Tool.CRAYON, 0)))))
        store.save(store.load())
        val indexBytes = File(directory, "gallery-v2/index.json").readBytes()
        val name = indexJson().getJSONObject("pages").getString(page.id)
        val damaged = "{damaged current drawing".toByteArray()
        File(directory, "gallery-v2/$name").writeBytes(damaged)
        val recovering = GalleryStore(isolatedContext)

        assertTrue("A current broken page must not silently restore stale v1 artwork", runCatching { recovering.load() }.isFailure)
        val backup = recovering.backupUnreadableFiles()
        assertArrayEquals(indexBytes, File(backup, "gallery-v2/index.json").readBytes())
        assertArrayEquals(damaged, File(backup, "gallery-v2/$name").readBytes())
        assertArrayEquals(galleryFile.readBytes(), File(backup, galleryFile.name).readBytes())
        recovering.save(StoredGallery(page.id, emptyMap()))
        assertTrue(GalleryStore(isolatedContext).load().drawings.values.all { it.isEmpty() })
        assertArrayEquals(damaged, File(backup, "gallery-v2/$name").readBytes())
    }

    @Test fun indexReferencesCannotEscapeThePrivatePageDirectory() {
        val page = ArtworkCatalog.pages.first()
        store.save(StoredGallery(page.id, mapOf(page.id to listOf(stroke(page.regions.first().id, Tool.CRAYON, 0)))))
        val outside = File(directory, "outside.json").apply { writeText("original unrelated file") }
        val json = indexJson()
        json.getJSONObject("pages").put(page.id, "../outside.json")
        File(directory, "gallery-v2/index.json").writeText(json.toString())

        val error = runCatching { GalleryStore(isolatedContext).load() }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("original unrelated file", outside.readText())
    }

    @Test fun nineLegalLargeDrawingsCanExceedTheOldWholeGalleryLimitAndReopen() {
        val points = List(ColoringSession.MAX_POINTS_PER_STROKE) { position ->
            Point(123.15625f + (position % 100) * .0625f, 567.84375f)
        }
        val pages = ArtworkCatalog.pages.take(9)
        val drawings = pages.associate { page ->
            page.id to List(150) {
                Stroke(page.regions.first().id, CrayonPalette.colors.first().argb, 40f, Tool.CRAYON, points,
                    crayonId = CrayonPalette.colors.first().id)
            }
        }
        store.save(StoredGallery(pages.first().id, drawings))
        val pageFiles = pageFileNames().map { File(directory, "gallery-v2/$it") }

        assertTrue("The fixture must exercise data exceeding the old 24 MiB total ceiling",
            pageFiles.sumOf { it.length() } > 24L * 1024 * 1024)
        assertTrue(File(directory, "gallery-v2/index.json").length() < 1024 * 1024)
        val restored = GalleryStore(isolatedContext).load()
        pages.forEach { page ->
            val strokes = restored.drawings.getValue(page.id)
            assertEquals(150, strokes.size)
            assertEquals(ColoringSession.MAX_POINTS_PER_STROKE, strokes.first().points.size)
            assertEquals(points[511], strokes.last().points[511])
        }
    }

    private fun indexJson() = JSONObject(File(directory, "gallery-v2/index.json").readText())
    private fun pageFileNames() = File(directory, "gallery-v2").listFiles().orEmpty()
        .filter { it.name.startsWith("page-") && it.name.endsWith(".json") }.map { it.name }.toSet()

    private fun writeAtomicFixture(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun writeLegacy(gallery: StoredGallery) {
        val drawings = JSONObject()
        gallery.drawings.forEach { (pageId, strokes) ->
            val entries = JSONArray()
            strokes.forEach { stroke ->
                val points = JSONArray()
                stroke.points.forEach { points.put(JSONArray().put(it.x).put(it.y)) }
                val stops = JSONArray()
                stroke.gradientStops.forEach { stops.put(it) }
                entries.put(JSONObject().put("regionId", stroke.regionId).put("color", stroke.colorArgb)
                    .put("width", stroke.width).put("tool", stroke.tool.name).put("points", points)
                    .put("crayonId", stroke.crayonId ?: JSONObject.NULL).put("gradientStops", stops))
            }
            drawings.put(pageId, entries)
        }
        val coverage = JSONObject()
        gallery.coverageByPage.forEach { (page, values) ->
            coverage.put(page, JSONObject().also { entries -> values.forEach { (region, value) -> entries.put(region, value) } })
        }
        galleryFile.writeText(JSONObject().put("version", 1).put("pageId", gallery.pageId)
            .put("drawings", drawings).put("completedPages", JSONArray(gallery.completedPages.toList()))
            .put("soundEnabled", gallery.soundEnabled).put("selectedColor", gallery.selectedColor)
            .put("hasVisitedPage", gallery.hasVisitedPage).put("coverageByPage", coverage)
            .put("selectedCrayonId", gallery.selectedCrayonId ?: JSONObject.NULL).toString())
    }

    private fun stroke(regionId: String, tool: Tool, offset: Int) = Stroke(
        regionId = regionId,
        colorArgb = 0xFFFF975EL + offset,
        width = 24.5f + offset,
        tool = tool,
        points = listOf(
            Point(250.25f + offset, 300.5f),
            Point(500.75f, 450.25f + offset),
            Point(-20.5f, 1020.75f),
        ),
    )
}
