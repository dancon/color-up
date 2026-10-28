package com.colorup.app

import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colorup.core.ArtworkCatalog
import com.colorup.core.CrayonPalette
import com.colorup.core.Point
import com.colorup.core.Stroke
import com.colorup.core.Tool
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GalleryRepositoryTest {
    private lateinit var directory: File
    private lateinit var repository: GalleryRepository
    private val galleryFile: File get() = File(directory, "gallery-v1.json")

    @Before fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(target.cacheDir, "gallery-repository-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val isolated = object : ContextWrapper(target) {
            override fun getFilesDir(): File = directory
        }
        // Keep the production application singleton and its real files completely outside these tests.
        repository = GalleryRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(isolated)
    }

    @After fun tearDown() {
        if (::directory.isInitialized) {
            Os.chmod(directory.absolutePath, OsConstants.S_IRWXU)
            directory.deleteRecursively()
        }
    }

    @Test fun queuedSavesAndReopenedLoadKeepCallOrderAndReturnOnTheMainThread() {
        val first = gallery(ArtworkCatalog.pages.first().id, soundEnabled = true)
        val latest = gallery(ArtworkCatalog.pages.last().id, soundEnabled = false)
        val finished = CountDownLatch(3)
        val callbacks = mutableListOf<String>()
        var firstSaved = false
        var latestSaved = false
        var restored: Result<StoredGallery>? = null
        var allOnMain = true

        repository.save(first) { result ->
            allOnMain = allOnMain && Looper.myLooper() == Looper.getMainLooper()
            callbacks += "first-save"
            firstSaved = result.isSuccess
            finished.countDown()
        }
        repository.save(latest) { result ->
            allOnMain = allOnMain && Looper.myLooper() == Looper.getMainLooper()
            callbacks += "latest-save"
            latestSaved = result.isSuccess
            finished.countDown()
        }
        repository.load { result ->
            allOnMain = allOnMain && Looper.myLooper() == Looper.getMainLooper()
            callbacks += "reopened-load"
            restored = result
            finished.countDown()
        }

        assertTrue("Queued gallery operations timed out", finished.await(10, TimeUnit.SECONDS))
        assertTrue(firstSaved)
        assertTrue(latestSaved)
        assertTrue(allOnMain)
        assertEquals(listOf("first-save", "latest-save", "reopened-load"), callbacks)
        assertEquals(latest, requireNotNull(restored).getOrThrow())
    }

    @Test fun corruptSaveIsBackedUpBeforeANewQueuedDrawingCanReplaceIt() {
        val original = "{\"version\":999,\"drawings\":{}}".toByteArray()
        galleryFile.writeBytes(original)
        val replacement = gallery(ArtworkCatalog.pages.last().id, soundEnabled = false)
        val finished = CountDownLatch(3)
        var loadFailure: Throwable? = null
        var saved = false
        var restored: Result<StoredGallery>? = null

        repository.load { result -> loadFailure = result.exceptionOrNull(); finished.countDown() }
        repository.save(replacement) { result -> saved = result.isSuccess; finished.countDown() }
        repository.load { result -> restored = result; finished.countDown() }

        assertTrue("Gallery recovery timed out", finished.await(10, TimeUnit.SECONDS))
        assertTrue(loadFailure is GalleryLoadException)
        assertTrue((loadFailure as GalleryLoadException).backupSucceeded)
        assertTrue(saved)
        assertEquals(replacement, requireNotNull(restored).getOrThrow())
        val recovery = directory.listFiles().orEmpty().single { it.name.startsWith("gallery-recovery-") }
        assertArrayEquals(original, File(recovery, galleryFile.name).readBytes())
    }

    @Test fun failedRecoveryDisablesLaterSavingEvenIfStorageBecomesWritableAgain() {
        val original = "{broken".toByteArray()
        galleryFile.writeBytes(original)
        val failure = try {
            Os.chmod(directory.absolutePath, OsConstants.S_IRUSR or OsConstants.S_IXUSR)
            awaitResult<StoredGallery> { callback -> repository.load(callback) }.exceptionOrNull()
        } finally {
            Os.chmod(directory.absolutePath, OsConstants.S_IRWXU)
        }

        assertTrue(failure is GalleryLoadException)
        assertFalse((failure as GalleryLoadException).backupSucceeded)
        val saved = awaitResult<Unit> { callback ->
            repository.save(gallery(ArtworkCatalog.pages.first().id, soundEnabled = true), callback)
        }

        assertTrue(saved.isFailure)
        assertArrayEquals(original, galleryFile.readBytes())
    }

    @Test fun queuedSaveSnapshotsCallerOwnedGradientStopsAndPointsBeforeReturning() {
        val page = ArtworkCatalog.pages.first()
        val points = mutableListOf(Point(250f, 250f), Point(500f, 500f))
        val stops = mutableListOf(0xFFFF0000L, 0xFF00FF00L, 0xFF0000FFL)
        val original = Stroke(page.regions.first().id, 0xFF123456L, 40f, Tool.CRAYON, points,
            crayonId = "future-gradient-a", gradientStops = stops)
        val expected = original.copy(points = points.toList(), gradientStops = stops.toList())
        val release = CountDownLatch(1)
        val executor = GalleryRepository::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }.get(repository) as Executor
        executor.execute { release.await(10, TimeUnit.SECONDS) }
        try {
            val saved = CountDownLatch(1)
            var saveResult: Result<Unit>? = null
            repository.save(gallery(page.id, soundEnabled = true).copy(
                drawings = ArtworkCatalog.pages.associate { it.id to if (it.id == page.id) listOf(original) else emptyList() },
            )) { result -> saveResult = result; saved.countDown() }
            points.clear()
            stops[0] = 0xFF000000L
            stops.clear()
            release.countDown()

            assertTrue("Queued save timed out", saved.await(10, TimeUnit.SECONDS))
            requireNotNull(saveResult).getOrThrow()
            val restored = awaitResult<StoredGallery> { repository.load(it) }.getOrThrow()
            assertEquals(listOf(expected), restored.drawings.getValue(page.id))
        } finally {
            release.countDown()
        }
    }

    private fun gallery(pageId: String, soundEnabled: Boolean) = StoredGallery(
        pageId = pageId,
        drawings = ArtworkCatalog.pages.associate { it.id to emptyList() },
        completedPages = setOf(pageId),
        soundEnabled = soundEnabled,
        selectedCrayonId = CrayonPalette.colors.first().id,
    )

    private fun <T> awaitResult(submit: ((Result<T>) -> Unit) -> Unit): Result<T> {
        val finished = CountDownLatch(1)
        var result: Result<T>? = null
        submit { received -> result = received; finished.countDown() }
        assertTrue("Gallery operation timed out", finished.await(10, TimeUnit.SECONDS))
        return requireNotNull(result)
    }
}
