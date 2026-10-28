package com.colorup.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.Executors

/** A failed load is recoverable only after every original save file has been backed up. */
internal class GalleryLoadException(
    val backupSucceeded: Boolean,
    cause: Throwable,
) : IOException(
    if (backupSucceeded) "Saved gallery could not be read; originals were backed up"
    else "Saved gallery could not be backed up; saving is disabled to protect the originals",
    cause,
)

/**
 * One application-scoped queue orders loads and saves across Activity/ViewModel recreation.
 * Work already submitted survives a ViewModel being cleared; callbacks always run on the main thread.
 */
internal class GalleryRepository private constructor(context: Context) {
    private val store = GalleryStore(context)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "color-up-gallery").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    // Accessed only by the serial worker. Once recovery fails, no later operation may enable writes.
    private var saveBlock: Throwable? = null
    private var inspectedExistingGallery = false

    fun load(callback: (Result<StoredGallery>) -> Unit) {
        executor.execute {
            val result = runCatching { loadSafely() }
            main.post { callback(result) }
        }
    }

    fun save(gallery: StoredGallery, callback: (Result<Unit>) -> Unit) {
        // Capture caller-owned collections before handing them to the worker.
        val snapshot = gallery.copy(
            drawings = gallery.drawings.mapValues { (_, strokes) ->
                strokes.map { stroke -> stroke.copy(points = stroke.points.toList(), gradientStops = stroke.gradientStops.toList()) }
            },
            completedPages = gallery.completedPages.toSet(),
            coverageByPage = gallery.coverageByPage.mapValues { (_, coverage) -> coverage.toMap() },
        )
        executor.execute {
            val result = runCatching {
                // Also protect callers that submit a save without an initial load.
                if (!inspectedExistingGallery) {
                    try {
                        loadSafely()
                    } catch (failure: GalleryLoadException) {
                        if (!failure.backupSucceeded) throw failure
                    }
                }
                saveBlock?.let { throw IOException("Saving is disabled because recovery backup failed", it) }
                store.save(snapshot)
            }
            main.post { callback(result) }
        }
    }

    private fun loadSafely(): StoredGallery {
        try {
            return store.load().also { inspectedExistingGallery = true }
        } catch (readError: Exception) {
            val backup = runCatching { store.backupUnreadableFiles() }
            val failure = GalleryLoadException(backup.isSuccess, readError)
            backup.exceptionOrNull()?.let { backupError ->
                failure.addSuppressed(backupError)
                saveBlock = failure
            }
            inspectedExistingGallery = true
            throw failure
        }
    }

    companion object {
        @Volatile private var instance: GalleryRepository? = null

        fun get(context: Context): GalleryRepository = instance ?: synchronized(this) {
            instance ?: GalleryRepository(context.applicationContext).also { instance = it }
        }
    }
}
