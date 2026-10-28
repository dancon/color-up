package com.colorup.app

import android.content.Context
import android.util.AtomicFile
import com.colorup.core.ArtworkCatalog
import com.colorup.core.ColoringSession
import com.colorup.core.CrayonPalette
import com.colorup.core.Point
import com.colorup.core.Stroke
import com.colorup.core.Tool
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

internal data class StoredGallery(
    val pageId: String,
    val drawings: Map<String, List<Stroke>>,
    val completedPages: Set<String> = emptySet(),
    val soundEnabled: Boolean = true,
    val selectedColor: Long = CrayonPalette.colors.first().argb,
    val hasVisitedPage: Boolean = false,
    val coverageByPage: Map<String, Map<String, Float>> = emptyMap(),
    val selectedCrayonId: String? = null,
)

/**
 * V2 commits a small index only after changed immutable page files are durable.
 * V1 stays readable and is retained as a pre-migration recovery source.
 */
internal class GalleryStore(
    context: Context,
    private val indexWriter: (AtomicFile, ByteArray) -> Unit = { file, bytes -> writeAtomically(file, bytes) },
) {
    private val root = context.filesDir
    private val legacy = AtomicFile(File(root, "gallery-v1.json"))
    private val directory = File(root, "gallery-v2")
    private val index = AtomicFile(File(directory, "index.json"))
    private var references: Map<String, String>? = null
    private var previousDrawings: Map<String, List<Stroke>> = emptyMap()

    fun load(): StoredGallery {
        val committed = readCandidate(index)
        if (committed != null) return loadVersionTwo(committed)
        // An orphan index.new is uncommitted: a first migration must still read its v1 source.
        val old = readCandidate(legacy)
        if (old == null) {
            references = emptyMap()
            previousDrawings = emptyMap()
            return StoredGallery(ArtworkCatalog.pages.first().id, emptyMap())
        }
        val json = readJson(old, MAX_PAGE_BYTES)
        require(json.getInt("version") == 1) { "Unsupported legacy gallery format" }
        val drawings = json.getJSONObject("drawings")
        val pages = ArtworkCatalog.pages.associate { page ->
            page.id to decodeDrawing(page.id, drawings.optJSONArray(page.id) ?: JSONArray())
        }
        val result = decodeMetadata(json, pages)
        references = emptyMap()
        previousDrawings = result.drawings.mapValues { (_, strokes) -> snapshot(strokes) }
        return result
    }

    private fun loadVersionTwo(source: File): StoredGallery {
        val json = readJson(source, MAX_INDEX_BYTES)
        require(json.getInt("version") == 2) { "Unsupported gallery index format" }
        val refs = decodeReferences(json)
        val pages = ArtworkCatalog.pages.associate { it.id to emptyList<Stroke>() }.toMutableMap()
        // Parse one bounded page at a time; never build a whole-gallery JSON tree/string.
        refs.forEach { (pageId, name) ->
            val sourcePage = readCandidate(AtomicFile(File(directory, name)))
                ?: throw IOException("Missing saved drawing: $pageId")
            val drawing = readJson(sourcePage, MAX_PAGE_BYTES)
            require(drawing.getInt("version") == 2 && drawing.getString("pageId") == pageId) {
                "Saved drawing identity does not match its index"
            }
            val entries = drawing.getJSONArray("strokes")
            if (pages.containsKey(pageId)) pages[pageId] = decodeDrawing(pageId, entries)
        }
        val result = decodeMetadata(json, pages)
        references = refs
        previousDrawings = result.drawings.mapValues { (_, strokes) -> snapshot(strokes) }
        return result
    }

    fun save(gallery: StoredGallery) {
        if (references == null) load()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create gallery directory")
        val oldReferences = checkNotNull(references)
        val knownIds = ArtworkCatalog.pages.map { it.id }.toSet()
        // Retired or unknown page files stay recoverable; they are never mapped onto current art.
        val nextReferences = oldReferences.filterKeys { it !in knownIds }.toMutableMap()
        val nextDrawings = mutableMapOf<String, List<Stroke>>()
        ArtworkCatalog.pages.forEach { page ->
            val strokes = gallery.drawings[page.id].orEmpty()
            if (strokes.isEmpty()) {
                nextDrawings[page.id] = emptyList()
            } else if (oldReferences[page.id] != null && previousDrawings[page.id] == strokes) {
                nextReferences[page.id] = oldReferences.getValue(page.id)
                nextDrawings[page.id] = previousDrawings.getValue(page.id)
            } else {
                val bytes = JSONObject().put("version", 2).put("pageId", page.id)
                    .put("strokes", encodeDrawing(strokes)).toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= MAX_PAGE_BYTES) { "Saved drawing is too large" }
                val name = "page-${UUID.randomUUID()}.json"
                val target = AtomicFile(File(directory, name))
                writeAtomically(target, bytes)
                verifyWrite(target, bytes)
                nextReferences[page.id] = name
                nextDrawings[page.id] = snapshot(strokes)
            }
        }
        val entries = JSONObject()
        nextReferences.forEach { (pageId, name) -> entries.put(pageId, name) }
        val bytes = encodeMetadata(gallery).put("version", 2).put("pages", entries)
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_INDEX_BYTES) { "Gallery index is too large" }
        // Failure leaves the old index and every old referenced generation untouched.
        // Newly written unreferenced pages are retained until a later successful commit/GC.
        indexWriter(index, bytes)
        verifyWrite(index, bytes)
        references = nextReferences.toMap()
        previousDrawings = nextDrawings.toMap()
        cleanupUnreferencedPages()
    }

    /** Copy all original v1/v2 bytes before allowing replacement of an unreadable gallery. */
    fun backupUnreadableFiles(): File {
        val sources = mutableListOf<Pair<File, String>>()
        listOf(legacy.baseFile, File(legacy.baseFile.path + ".bak"), File(legacy.baseFile.path + ".new"))
            .filter(File::exists).forEach { sources += it to it.name }
        fun collect(file: File, relative: String) {
            if (file.isDirectory) {
                val children = file.listFiles() ?: throw IOException("Cannot inspect saved gallery recovery files")
                children.forEach { collect(it, "$relative/${it.name}") }
            } else sources += file to relative
        }
        if (directory.exists()) collect(directory, directory.name)
        if (sources.isEmpty()) throw IOException("No saved gallery file could be backed up")
        val backup = File(root, "gallery-recovery-${UUID.randomUUID()}")
        if (!backup.mkdir()) throw IOException("Cannot create gallery recovery directory")
        sources.forEach { (source, relative) ->
            val target = File(backup, relative)
            val parent = checkNotNull(target.parentFile)
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create recovery subdirectory")
            val expectedLength = source.length()
            val copied = source.inputStream().use { input ->
                FileOutputStream(target).use { output ->
                    val count = input.copyTo(output)
                    output.fd.sync()
                    count
                }
            }
            if (copied != expectedLength || source.length() != expectedLength) {
                throw IOException("Saved gallery changed during recovery backup")
            }
        }
        // Only a complete backup permits replacing unreadable originals with a fresh index.
        references = emptyMap()
        previousDrawings = emptyMap()
        return backup
    }

    private fun cleanupUnreferencedPages() {
        val keep = mutableSetOf<String>()
        for (candidate in listOf(index.baseFile, File(index.baseFile.path + ".bak"), File(index.baseFile.path + ".new"))) {
            if (!candidate.exists()) continue
            val refs = runCatching { decodeReferences(readJson(candidate, MAX_INDEX_BYTES)) }.getOrNull()
                ?: return // An unreadable recovery index may still reference any generation.
            keep += refs.values
        }
        directory.listFiles().orEmpty().forEach { file ->
            val name = file.name.removeSuffix(".bak").removeSuffix(".new")
            if (PAGE_FILE_PATTERN.matches(name) && name !in keep) file.delete()
        }
    }

    private fun decodeReferences(json: JSONObject): Map<String, String> {
        require(json.getInt("version") == 2) { "Unsupported gallery index format" }
        val pages = json.getJSONObject("pages")
        require(pages.length() <= MAX_PAGE_REFERENCES) { "Too many drawing references" }
        return pages.keys().asSequence().associateWith { pageId ->
            val name = pages.getString(pageId)
            require(PAGE_FILE_PATTERN.matches(name)) { "Unsafe saved drawing reference" }
            name
        }
    }

    private fun decodeDrawing(pageId: String, entries: JSONArray): List<Stroke> {
        val page = ArtworkCatalog.pages.first { it.id == pageId }
        val strokes = (0 until minOf(entries.length(), ColoringSession.MAX_STROKES)).mapNotNull { position ->
            runCatching { decodeStroke(entries.getJSONObject(position)) }.getOrNull()
        }
        return ColoringSession(page, strokes).strokes
    }

    private fun decodeMetadata(json: JSONObject, pages: Map<String, List<Stroke>>): StoredGallery {
        val pageIds = ArtworkCatalog.pages.map { it.id }.toSet()
        val savedPage = json.optString("pageId").takeIf { it in pageIds }
        val continuedPage = savedPage ?: pages.entries.firstOrNull { it.value.isNotEmpty() }?.key
        val selected = continuedPage ?: ArtworkCatalog.pages.first().id
        val completed = json.optJSONArray("completedPages") ?: JSONArray()
        val completedPages = (0 until completed.length()).mapNotNull { completed.optString(it).takeIf(pageIds::contains) }.toSet()
        val savedColor = json.optLong("selectedColor", CrayonPalette.colors.first().argb)
        val selectedId = safeCrayonId(json.opt("selectedCrayonId") as? String)
        val selectedCrayon = CrayonPalette.colors.firstOrNull { it.id == selectedId }
            ?: CrayonPalette.colors.firstOrNull { it.gradientStops.isEmpty() && it.argb == savedColor }
            ?: CrayonPalette.colors.first { it.gradientStops.isEmpty() }
        val savedCoverage = json.optJSONObject("coverageByPage")
        val coverageByPage = ArtworkCatalog.pages.mapNotNull { page ->
            val values = savedCoverage?.optJSONObject(page.id) ?: return@mapNotNull null
            val coverage = page.regions.mapNotNull entry@{ region ->
                val value = (values.opt(region.id) as? Number)?.toDouble() ?: return@entry null
                if (!value.isFinite()) return@entry null
                region.id to value.coerceIn(0.0, 1.0).toFloat()
            }.toMap()
            page.id to coverage
        }.toMap()
        return StoredGallery(selected, pages, completedPages, json.optBoolean("soundEnabled", true),
            selectedCrayon.argb, json.optBoolean("hasVisitedPage", true) && continuedPage != null, coverageByPage, selectedCrayon.id)
    }

    private fun encodeMetadata(gallery: StoredGallery): JSONObject {
        val completed = JSONArray()
        val coverage = JSONObject()
        ArtworkCatalog.pages.forEach { page ->
            if (page.id in gallery.completedPages) completed.put(page.id)
            gallery.coverageByPage[page.id]?.let { values ->
                val entries = JSONObject()
                page.regions.forEach { region ->
                    values[region.id]?.takeIf(Float::isFinite)?.let { entries.put(region.id, it.coerceIn(0f, 1f)) }
                }
                coverage.put(page.id, entries)
            }
        }
        return JSONObject().put("pageId", gallery.pageId).put("completedPages", completed)
            .put("soundEnabled", gallery.soundEnabled).put("selectedColor", gallery.selectedColor)
            .put("selectedCrayonId", safeCrayonId(gallery.selectedCrayonId) ?: JSONObject.NULL)
            .put("hasVisitedPage", gallery.hasVisitedPage).put("coverageByPage", coverage)
    }

    private fun encodeDrawing(strokes: List<Stroke>): JSONArray = JSONArray().also { entries ->
        strokes.forEach { stroke ->
            val points = JSONArray()
            stroke.points.forEach { points.put(JSONArray().put(it.x).put(it.y)) }
            val stops = JSONArray()
            normalizedStops(stroke.gradientStops).forEach { stops.put(it) }
            entries.put(JSONObject().put("regionId", stroke.regionId).put("color", stroke.colorArgb)
                .put("width", stroke.width).put("tool", stroke.tool.name).put("points", points)
                .put("crayonId", safeCrayonId(stroke.crayonId) ?: JSONObject.NULL).put("gradientStops", stops))
        }
    }

    private fun decodeStroke(json: JSONObject): Stroke {
        val points = json.getJSONArray("points")
        require(points.length() in 1..ColoringSession.MAX_POINTS_PER_STROKE)
        return Stroke(json.getString("regionId"), json.getLong("color"), json.getDouble("width").toFloat(),
            Tool.valueOf(json.getString("tool")), (0 until points.length()).map { position ->
                val point = points.getJSONArray(position)
                Point(point.getDouble(0).toFloat(), point.getDouble(1).toFloat())
            }, safeCrayonId(json.opt("crayonId") as? String), decodeStops(json.optJSONArray("gradientStops")))
    }

    private fun decodeStops(array: JSONArray?): List<Long> {
        if (array == null || array.length() !in 2..5) return emptyList()
        val stops = ArrayList<Long>(array.length())
        for (position in 0 until array.length()) {
            val number = array.opt(position) as? Number ?: return emptyList()
            val value = number.toLong()
            if (!number.toDouble().isFinite() || number.toDouble() != value.toDouble() || !opaqueArgb(value)) return emptyList()
            stops += value
        }
        return stops.toList()
    }

    private fun normalizedStops(stops: List<Long>) = if (stops.size in 2..5 && stops.all(::opaqueArgb)) stops.toList() else emptyList()
    private fun safeCrayonId(id: String?): String? = id?.takeIf(CRAYON_ID_PATTERN::matches)
    private fun opaqueArgb(value: Long) = value in 0xFF000000L..0xFFFFFFFFL
    private fun snapshot(strokes: List<Stroke>) = strokes.map { it.copy(points = it.points.toList(), gradientStops = it.gradientStops.toList()) }

    companion object {
        private const val MAX_PAGE_BYTES = 24 * 1024 * 1024
        private const val MAX_INDEX_BYTES = 1024 * 1024
        private const val MAX_PAGE_REFERENCES = 256
        private val CRAYON_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}")
        private val PAGE_FILE_PATTERN = Regex("page-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.json")

        private fun readCandidate(file: AtomicFile): File? {
            val backup = File(file.baseFile.path + ".bak")
            return when {
                backup.exists() -> backup
                file.baseFile.exists() -> file.baseFile
                else -> null
            }
        }

        private fun readBytes(source: File, limit: Int): ByteArray = source.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit) { "Saved gallery file is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

        private fun readJson(source: File, limit: Int) = JSONObject(readBytes(source, limit).toString(Charsets.UTF_8))

        private fun writeAtomically(file: AtomicFile, bytes: ByteArray) {
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

        private fun verifyWrite(file: AtomicFile, bytes: ByteArray) {
            val source = readCandidate(file) ?: throw IOException("Gallery commit is missing")
            if (!readBytes(source, bytes.size).contentEquals(bytes)) throw IOException("Gallery commit did not complete")
        }
    }
}
