package com.colorup.core

import java.util.Collections

/**
 * Presentation of coverage measured by the platform renderer, without estimating geometry here.
 * Prefer [from] to include every valid region, normalize measurements, and snapshot the input map.
 */
data class PageProgress(
    val coverageByRegion: Map<String, Float>,
    val totalRegions: Int,
    val usedColorCount: Int,
) {
    init {
        require(totalRegions >= 0 && usedColorCount >= 0)
    }

    val completedRegions: Int
        get() = coverageByRegion.values.take(totalRegions)
            .count { normalizedCoverage(it) >= COMPLETION_THRESHOLD }

    val averageCoverage: Float
        get() = if (totalRegions == 0) 0f else
            (coverageByRegion.values.take(totalRegions).sumOf { normalizedCoverage(it).toDouble() } /
                totalRegions).toFloat()

    val isComplete: Boolean get() = totalRegions > 0 && completedRegions == totalRegions

    val stars: Int get() = when {
        usedColorCount >= 6 -> 3
        usedColorCount >= 3 -> 2
        else -> 1
    }

    companion object {
        /** Current prototype value; tune this threshold after testing the real crayon brush on tablets. */
        const val COMPLETION_THRESHOLD = 0.55f
        val EMPTY = PageProgress(emptyMap(), 0, 0)

        fun from(page: LineArt, coverageByRegion: Map<String, Float>, strokes: List<Stroke>): PageProgress {
            val regionIds = page.regions.map { it.id }.toSet()
            val normalized = page.regions.associate { region ->
                region.id to normalizedCoverage(coverageByRegion[region.id] ?: 0f)
            }
            // Stable style IDs distinguish gradients; legacy ARGB becomes the matching solid ID.
            // Archived/light/black crayons remain valid colors; erasing does not add a color.
            val colors = strokes.asSequence()
                .filter { it.regionId in regionIds && it.tool == Tool.CRAYON }
                .map { it.crayonId ?: solidCrayonId(it.colorArgb) }
                .distinct().count()
            return PageProgress(Collections.unmodifiableMap(normalized), page.regions.size, colors)
        }

        private fun normalizedCoverage(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    }
}
