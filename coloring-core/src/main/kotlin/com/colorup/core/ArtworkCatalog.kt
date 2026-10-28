package com.colorup.core

/** Original illustrations and their immutable clipping geometry form the complete gallery. */
object ArtworkCatalog {
    val expectedPageCount: Int = GeneratedIllustrations.expectedPageCount
    val pages: List<LineArt> = GeneratedIllustrations.pages.map(LineArt::immutableSnapshot).immutableCopy()
}
