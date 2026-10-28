package com.colorup.core

import kotlin.math.abs

/** Drawing coordinates are independent of display pixels: each page is 1000 × 1000. */
data class Point(val x: Float, val y: Float) {
    val isFinite: Boolean get() = x.isFinite() && y.isFinite()
}

enum class FillRule { NON_ZERO, EVEN_ODD }
enum class ArtStrokeCap { BUTT, ROUND, SQUARE }
enum class ArtStrokeJoin { MITER, ROUND, BEVEL }

sealed interface Shape {
    fun contains(point: Point): Boolean

    /** Original Bézier path for rendering; flattened design-space contours are only for hit testing. */
    data class Vector(
        val pathData: String,
        val scale: Float = 2.5f,
        val contours: List<List<Point>>,
        val fillRule: FillRule = FillRule.NON_ZERO,
    ) : Shape {
        init {
            require(pathData.isNotBlank() && scale.isFinite() && scale > 0f)
            require(contours.isNotEmpty() && contours.all { it.isNotEmpty() && it.all(Point::isFinite) })
        }

        private val bounds = floatArrayOf(
            contours.minOf { contour -> contour.minOf { it.x } },
            contours.minOf { contour -> contour.minOf { it.y } },
            contours.maxOf { contour -> contour.maxOf { it.x } },
            contours.maxOf { contour -> contour.maxOf { it.y } },
        )

        override fun contains(point: Point): Boolean {
            if (!point.isFinite) return false
            if (point.x < bounds[0] || point.y < bounds[1] || point.x > bounds[2] || point.y > bounds[3]) return false
            var winding = 0
            var crossings = 0
            for (contour in contours) {
                if (contour.size < 3) continue
                var previous = contour.last()
                for (current in contour) {
                    val side = (current.x.toDouble() - previous.x) * (point.y.toDouble() - previous.y) -
                        (point.x.toDouble() - previous.x) * (current.y.toDouble() - previous.y)
                    if (abs(side) < 0.0001 &&
                        point.x >= minOf(previous.x, current.x) && point.x <= maxOf(previous.x, current.x) &&
                        point.y >= minOf(previous.y, current.y) && point.y <= maxOf(previous.y, current.y)
                    ) return true
                    if ((current.y > point.y) != (previous.y > point.y)) {
                        val crossingX = (previous.x.toDouble() - current.x) * (point.y - current.y) /
                            (previous.y.toDouble() - current.y) + current.x
                        if (point.x < crossingX) crossings++
                    }
                    if (previous.y <= point.y && current.y > point.y && side > 0.0) winding++
                    if (previous.y > point.y && current.y <= point.y && side < 0.0) winding--
                    previous = current
                }
            }
            return if (fillRule == FillRule.EVEN_ODD) crossings % 2 != 0 else winding != 0
        }
    }

    data class Oval(val left: Float, val top: Float, val right: Float, val bottom: Float) : Shape {
        init {
            require(listOf(left, top, right, bottom).all(Float::isFinite))
            require(right > left && bottom > top)
        }

        override fun contains(point: Point): Boolean {
            if (!point.isFinite) return false
            val dx = (point.x.toDouble() - (left.toDouble() + right) / 2.0) /
                ((right.toDouble() - left) / 2.0)
            val dy = (point.y.toDouble() - (top.toDouble() + bottom) / 2.0) /
                ((bottom.toDouble() - top) / 2.0)
            return dx * dx + dy * dy <= 1.0
        }
    }

    data class Polygon(val points: List<Point>) : Shape {
        init {
            require(points.size >= 3 && points.all(Point::isFinite))
        }

        override fun contains(point: Point): Boolean {
            if (!point.isFinite) return false
            var inside = false
            var previous = points.last()
            for (current in points) {
                // Include an exact edge hit; the last painted region wins shared edges.
                val cross = (point.x.toDouble() - previous.x) * (current.y.toDouble() - previous.y) -
                    (point.y.toDouble() - previous.y) * (current.x.toDouble() - previous.x)
                if (abs(cross) < 0.0001 &&
                    point.x >= minOf(previous.x, current.x) && point.x <= maxOf(previous.x, current.x) &&
                    point.y >= minOf(previous.y, current.y) && point.y <= maxOf(previous.y, current.y)
                ) return true
                if ((current.y > point.y) != (previous.y > point.y)) {
                    val crossingX = (previous.x.toDouble() - current.x) * (point.y - current.y) /
                        (previous.y.toDouble() - current.y) + current.x
                    if (point.x < crossingX) inside = !inside
                }
                previous = current
            }
            return inside
        }
    }
}

data class ColoringRegion(
    val id: String,
    val shape: Shape,
    val name: String = id,
    val outlineWidth: Float = 7f,
    val outlineArgb: Long = 0xFF3D3A45L,
    val outlineJoin: ArtStrokeJoin = ArtStrokeJoin.ROUND,
    val outlineDash: List<Float> = emptyList(),
)

sealed interface Detail {
    data class Line(val points: List<Point>, val width: Float = 6f) : Detail
    data class Dot(val center: Point, val radius: Float) : Detail
    data class Vector(
        val shape: Shape.Vector,
        val fillArgb: Long?,
        val strokeArgb: Long?,
        val strokeWidth: Float,
        val strokeCap: ArtStrokeCap = ArtStrokeCap.BUTT,
        val strokeJoin: ArtStrokeJoin = ArtStrokeJoin.MITER,
        val opacity: Float = 1f,
        val strokeDash: List<Float> = emptyList(),
    ) : Detail
}

sealed interface ArtLayer {
    data class RegionLayer(val regionId: String) : ArtLayer
    data class DetailLayer(val detailIndex: Int) : ArtLayer
}

/** Regions are ordered back to front. A later region obscures earlier ones. */
data class LineArt(
    val id: String,
    val title: String,
    val subtitle: String,
    val regions: List<ColoringRegion>,
    val details: List<Detail>,
    val renderOrder: List<ArtLayer> = emptyList(),
    val theme: String = "nature",
    val themeLabel: String = "自然世界",
    val sceneLabel: String = "",
    val featured: Boolean = false,
    /** Unmodified illustrated ink, displayed over clipped coloring by the Android renderer. */
    val inkAsset: String? = null,
)
