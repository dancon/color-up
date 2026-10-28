package com.colorup.core

import java.util.Collections

enum class ArtworkCollection { ALL, EASY, IN_PROGRESS, COMPLETED }

/** Gallery discovery over active content, independent of Android and saved-file formats. */
object ArtworkDiscovery {
    /** A product shortcut for fewer coloring areas, not an age or developmental assessment. */
    const val EASY_MAX_REGIONS = 10

    /**
     * Combines a collection with an optional theme while retaining the supplied gallery order.
     * Only [pages] defines available artwork; extra saved or retired IDs cannot add gallery items.
     * Completion is historical, so erasing or clearing a finished drawing does not remove it.
     */
    fun select(
        pages: List<LineArt>,
        drawings: Map<String, List<Stroke>>,
        completedPages: Set<String>,
        theme: String? = null,
        collection: ArtworkCollection = ArtworkCollection.ALL,
    ): List<LineArt> = pages.filter { page ->
        (theme == null || page.theme == theme) && when (collection) {
            ArtworkCollection.ALL -> true
            ArtworkCollection.EASY -> page.regions.size <= EASY_MAX_REGIONS
            ArtworkCollection.IN_PROGRESS -> page.id !in completedPages && !drawings[page.id].isNullOrEmpty()
            ArtworkCollection.COMPLETED -> page.id in completedPages
        }
    }.immutableCopy()

    /** Counts use the same theme intersection as the cards, including zero for empty collections. */
    fun counts(
        pages: List<LineArt>,
        drawings: Map<String, List<Stroke>>,
        completedPages: Set<String>,
        theme: String? = null,
    ): Map<ArtworkCollection, Int> = Collections.unmodifiableMap(
        ArtworkCollection.entries.associateWith { collection ->
            select(pages, drawings, completedPages, theme, collection).size
        },
    )
}
