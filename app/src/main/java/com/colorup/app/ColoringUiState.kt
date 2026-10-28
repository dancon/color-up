package com.colorup.app

import com.colorup.core.ArtworkCatalog
import com.colorup.core.CrayonPalette
import com.colorup.core.LineArt
import com.colorup.core.PageProgress
import com.colorup.core.Stroke
import com.colorup.core.Tool

enum class GameScreen { HOME, GALLERY, PAINT }

data class ColoringUiState(
    val page: LineArt = ArtworkCatalog.pages.first(),
    val pages: List<LineArt> = ArtworkCatalog.pages,
    val strokes: List<Stroke> = emptyList(),
    val activeStroke: Stroke? = null,
    val selectedColor: Long = CrayonPalette.colors.first().argb,
    val selectedCrayonId: String? = null,
    val tool: Tool = Tool.CRAYON,
    val brushWidth: Float = 40f,
    val canUndo: Boolean = false,
    val screen: GameScreen = GameScreen.HOME,
    val drawings: Map<String, List<Stroke>> = emptyMap(),
    val completedPages: Set<String> = emptySet(),
    val showCelebration: Boolean = false,
    val progressByPage: Map<String, PageProgress> = emptyMap(),
    val completionStars: Int = 1,
    val celebrationColorCount: Int = 0,
    val hasVisitedPage: Boolean = false,
    val showBrushHint: Boolean = false,
    val soundEnabled: Boolean = true,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
)
