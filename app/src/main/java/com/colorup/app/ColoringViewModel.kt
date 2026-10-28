package com.colorup.app

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.colorup.core.ArtworkCatalog
import com.colorup.core.ColoringSession
import com.colorup.core.Crayon
import com.colorup.core.CrayonPalette
import com.colorup.core.PageProgress
import com.colorup.core.Point
import com.colorup.core.Stroke
import com.colorup.core.Tool
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ColoringViewModel internal constructor(
    application: Application,
    private val repository: GalleryRepository,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, GalleryRepository.get(application))
    var uiState by mutableStateOf(ColoringUiState())
        private set
    private val sessions = ArtworkCatalog.pages.associate { it.id to ColoringSession(it) }.toMutableMap()
    private var currentId = ArtworkCatalog.pages.first().id
    private val session get() = sessions.getValue(currentId)
    internal var pendingExport: ExportRequest? = null
    private val coverageDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "color-up-coverage").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    // Completed actions preserve order: even a briefly completed picture keeps its achievement.
    // At most one live preview is queued; later committed results never get dropped.
    private val coverageRequests = Channel<CoverageRequest>(Channel.UNLIMITED)
    private var coverageRevision = 0L
    private var lastLiveCoverageAt = 0L
    private var liveCoverageOutstanding = false

    init {
        viewModelScope.launch(coverageDispatcher) {
            val calculators = mutableMapOf<String, ArtworkCoverage>()
            try {
                for (request in coverageRequests) {
                    val progress = ArtworkCatalog.pages.associate { page ->
                        val strokes = request.drawings[page.id].orEmpty()
                        page.id to if (strokes.isEmpty()) PageProgress.from(page, emptyMap(), strokes)
                        else calculators.getOrPut(page.id) { ArtworkCoverage(page) }.measure(strokes)
                    }
                    withContext(Dispatchers.Main) {
                        if (request.isLive) liveCoverageOutstanding = false
                        applyProgress(progress, request)
                    }
                }
            } finally {
                calculators.values.forEach { it.close() }
            }
        }
        repository.load { loaded ->
            loaded.onSuccess { gallery ->
                ArtworkCatalog.pages.forEach { page ->
                    sessions[page.id] = ColoringSession(page, gallery.drawings[page.id].orEmpty())
                }
                currentId = gallery.pageId
                uiState = uiState.copy(
                    completedPages = gallery.completedPages,
                    soundEnabled = gallery.soundEnabled,
                    selectedColor = gallery.selectedColor,
                    selectedCrayonId = gallery.selectedCrayonId,
                    hasVisitedPage = gallery.hasVisitedPage,
                    progressByPage = ArtworkCatalog.pages.associate { page ->
                        page.id to PageProgress.from(page, gallery.coverageByPage[page.id].orEmpty(), gallery.drawings[page.id].orEmpty())
                    },
                )
            }
            publish()
            requestCoverage(allowCelebration = false, persistResult = false)
            if (loaded.isFailure) {
                val backupSucceeded = (loaded.exceptionOrNull() as? GalleryLoadException)?.backupSucceeded == true
                showMessage(if (backupSucceeded) "读取画作遇到问题，原文件已备份，可以重新画。"
                    else "读取画作遇到问题，原文件已保留，暂时停止保存。")
            }
        }
    }

    fun selectPage(id: String) {
        if (id !in sessions) return
        settleStroke()
        currentId = id
        uiState = uiState.copy(
            screen = GameScreen.PAINT, showCelebration = false,
            hasVisitedPage = true, showBrushHint = session.strokes.isEmpty(),
        )
        publish()
        save()
        requestCoverage(allowCelebration = true)
    }

    fun nextPicture() {
        val index = ArtworkCatalog.pages.indexOfFirst { it.id == currentId }
        selectPage(ArtworkCatalog.pages[(index + 1) % ArtworkCatalog.pages.size].id)
    }

    fun selectColor(color: Long) {
        CrayonPalette.colors.firstOrNull { it.argb == color && it.gradientStops.isEmpty() }?.let(::selectCrayon)
    }

    fun selectCrayon(crayon: Crayon) {
        val selected = CrayonPalette.colors.firstOrNull { it.id == crayon.id } ?: return
        uiState = uiState.copy(selectedColor = selected.argb, selectedCrayonId = selected.id, tool = Tool.CRAYON)
        save()
    }

    fun selectTool(tool: Tool) { uiState = uiState.copy(tool = tool) }
    fun navigate(screen: GameScreen) {
        settleStroke()
        if (screen == GameScreen.PAINT) { selectPage(currentId); return }
        uiState = uiState.copy(screen = screen, showCelebration = false, showBrushHint = false)
        publish()
    }
    fun toggleSound() {
        uiState = uiState.copy(soundEnabled = !uiState.soundEnabled)
        save()
    }
    fun closeCelebration() { uiState = uiState.copy(showCelebration = false) }
    fun showMessage(message: String) { uiState = uiState.copy(errorMessage = message) }
    fun dismissMessage(message: String) {
        if (uiState.errorMessage == message) uiState = uiState.copy(errorMessage = null)
    }
    fun selectWidth(width: Float) {
        if (width.isFinite() && width in ColoringSession.MIN_BRUSH_WIDTH..ColoringSession.MAX_BRUSH_WIDTH) {
            uiState = uiState.copy(brushWidth = width)
        }
    }

    fun startStroke(point: Point) {
        val crayon = CrayonPalette.colors.firstOrNull { it.id == uiState.selectedCrayonId }
            ?: CrayonPalette.colors.firstOrNull { it.argb == uiState.selectedColor && it.gradientStops.isEmpty() }
        val accepted = session.beginStroke(point, uiState.selectedColor, uiState.brushWidth, uiState.tool,
            crayonId = crayon?.id.takeIf { uiState.tool == Tool.CRAYON },
            gradientStops = if (uiState.tool == Tool.CRAYON) crayon?.gradientStops.orEmpty() else emptyList())
        if (accepted) uiState = uiState.copy(showBrushHint = false)
        publish()
        if (!accepted && session.strokes.size >= ColoringSession.MAX_STROKES) {
            showMessage("这张画已画了 300 笔，可以撤销、重来或换一张继续。")
        }
    }

    fun moveStroke(point: Point) {
        session.appendPoint(point)
        publish()
        val now = SystemClock.uptimeMillis()
        if (session.activeStroke != null && now - lastLiveCoverageAt >= LIVE_COVERAGE_INTERVAL_MS) {
            lastLiveCoverageAt = now
            requestCoverage(allowCelebration = false, persistResult = false, includeActive = true)
        }
    }

    fun endStroke() {
        if (session.activeStroke == null) return
        session.finishStroke()
        publish()
        save()
        requestCoverage(allowCelebration = true)
    }

    /** Rotation, backgrounding and pointer interruption finish the valid existing stroke. */
    fun settleStroke() { endStroke() }
    fun undo() {
        session.undo()
        publish()
        save()
        requestCoverage(allowCelebration = true)
    }
    fun clear() {
        session.clear()
        publish()
        save()
        requestCoverage(allowCelebration = false)
        showMessage("画面擦干净啦，可以重新涂；点撤销可以恢复。")
    }

    private fun publish() {
        uiState = uiState.copy(
            page = session.page,
            strokes = session.strokes,
            activeStroke = session.activeStroke,
            canUndo = session.canUndo,
            drawings = sessions.mapValues { (_, value) -> value.strokes },
            isLoading = false,
        )
    }

    private fun requestCoverage(
        allowCelebration: Boolean,
        persistResult: Boolean = true,
        includeActive: Boolean = false,
    ) {
        if (includeActive && liveCoverageOutstanding) return
        val drawings = sessions.mapValues { (_, value) -> value.strokes }.toMutableMap()
        if (includeActive) session.activeStroke?.let { drawings[currentId] = session.strokes + it }
        if (includeActive) liveCoverageOutstanding = true
        coverageRequests.trySend(CoverageRequest(++coverageRevision, currentId, drawings, allowCelebration, persistResult, includeActive))
    }

    private fun applyProgress(progress: Map<String, PageProgress>, request: CoverageRequest) {
        val newlyComplete = if (!request.isLive) progress.filterValues { it.isComplete }.keys - uiState.completedPages else emptySet()
        val celebrate = request.allowCelebration && currentId == request.pageId && currentId in newlyComplete && uiState.screen == GameScreen.PAINT
        uiState = uiState.copy(
            progressByPage = if (request.revision == coverageRevision) progress else uiState.progressByPage,
            completedPages = uiState.completedPages + newlyComplete,
            showCelebration = uiState.showCelebration || celebrate,
            completionStars = if (celebrate) progress.getValue(currentId).stars else uiState.completionStars,
            celebrationColorCount = if (celebrate) progress.getValue(currentId).usedColorCount else uiState.celebrationColorCount,
        )
        if (request.persistResult || newlyComplete.isNotEmpty()) save()
    }

    private fun save() {
        val snapshot = StoredGallery(
            pageId = currentId,
            drawings = sessions.mapValues { (_, value) -> value.strokes },
            completedPages = uiState.completedPages,
            soundEnabled = uiState.soundEnabled,
            selectedColor = uiState.selectedColor,
            selectedCrayonId = uiState.selectedCrayonId,
            hasVisitedPage = uiState.hasVisitedPage,
            coverageByPage = uiState.progressByPage.mapValues { it.value.coverageByRegion },
        )
        repository.save(snapshot) { result ->
            if (result.isFailure) showMessage("保存没有成功，再试一次吧。请检查本机存储空间。")
        }
    }

    override fun onCleared() {
        coverageRequests.close()
        coverageDispatcher.close()
        super.onCleared()
    }

    private data class CoverageRequest(
        val revision: Long,
        val pageId: String,
        val drawings: Map<String, List<Stroke>>,
        val allowCelebration: Boolean,
        val persistResult: Boolean,
        val isLive: Boolean,
    )

    companion object {
        private const val LIVE_COVERAGE_INTERVAL_MS = 120L
    }
}
