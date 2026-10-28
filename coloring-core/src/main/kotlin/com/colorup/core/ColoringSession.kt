package com.colorup.core

import java.util.ArrayDeque
import java.util.Collections

enum class Tool { CRAYON, ERASER }

data class Stroke(
    val regionId: String,
    val colorArgb: Long,
    val width: Float,
    val tool: Tool,
    val points: List<Point>,
    val crayonId: String? = null,
    val gradientStops: List<Long> = emptyList(),
)

/**
 * Owns the drawing rules, without Android or rendering dependencies.
 * A renderer must clip each stroke to its region and respect page layer order.
 * Coordinates outside the selected shape remain in the stroke so clipping is continuous.
 */
class ColoringSession(page: LineArt, initialStrokes: List<Stroke> = emptyList()) {
    val page: LineArt = page.immutableSnapshot()
    private val completed = ArrayList<Stroke>()
    private val undoHistory = ArrayDeque<UndoAction>()
    private var active: ActiveStroke? = null

    val strokes: List<Stroke> get() = completed.immutableCopy()
    val activeStroke: Stroke? get() = active?.snapshot()
    val canUndo: Boolean get() = undoHistory.isNotEmpty()

    init {
        require(this.page.regions.isNotEmpty()) { "A page needs at least one closed region" }
        require(this.page.regions.map { it.id }.distinct().size == this.page.regions.size) {
            "Region IDs must be unique within a page"
        }
        val regionIds = this.page.regions.map { it.id }.toSet()
        // Restore only structurally valid, bounded strokes from local storage.
        initialStrokes.take(MAX_STROKES).forEach { stroke ->
            if (stroke.regionId in regionIds && validWidth(stroke.width) && validColor(stroke.colorArgb) &&
                validCrayon(stroke.crayonId, stroke.gradientStops) &&
                stroke.points.isNotEmpty() && stroke.points.all(Point::isFinite)
            ) {
                completed += stroke.copy(points = boundedPoints(stroke.points).immutableCopy(), gradientStops = stroke.gradientStops.immutableCopy())
                rememberUndo(UndoAction.RemoveLastStroke)
            }
        }
    }

    fun hitTest(point: Point): ColoringRegion? =
        if (!point.isFinite || point.x !in 0f..1000f || point.y !in 0f..1000f) null
        else page.regions.lastOrNull { it.shape.contains(point) }

    /** Refuses a second pointer, invalid input, a blank start, or a full page. */
    fun beginStroke(
        point: Point, colorArgb: Long, width: Float, tool: Tool,
        crayonId: String? = null, gradientStops: List<Long> = emptyList(),
    ): Boolean {
        if (active != null || completed.size >= MAX_STROKES || !point.isFinite ||
            !validWidth(width) || !validColor(colorArgb) || !validCrayon(crayonId, gradientStops)
        ) return false
        val region = hitTest(point) ?: return false
        active = ActiveStroke(region.id, colorArgb, width, tool, arrayListOf(point), crayonId, gradientStops.immutableCopy())
        return true
    }

    fun appendPoint(point: Point) {
        val stroke = active ?: return
        if (!point.isFinite || point == stroke.points.last()) return
        if (stroke.points.size >= MAX_POINTS_PER_STROKE) {
            // Reduce old samples while retaining the original start and latest end.
            val reduced = boundedPoints(stroke.points, MAX_POINTS_PER_STROKE / 2)
            stroke.points.clear()
            stroke.points.addAll(reduced)
        }
        stroke.points += point
    }

    fun finishStroke() {
        val stroke = active ?: return
        completed += stroke.snapshot()
        rememberUndo(UndoAction.RemoveLastStroke)
        active = null
    }

    fun cancelStroke() {
        active = null
    }

    fun undo() {
        cancelStroke()
        when (val action = undoHistory.pollLast() ?: return) {
            UndoAction.RemoveLastStroke -> if (completed.isNotEmpty()) completed.removeAt(completed.lastIndex)
            is UndoAction.RestoreDrawing -> {
                completed.clear()
                completed.addAll(action.strokes)
            }
        }
    }

    fun clear() {
        cancelStroke()
        if (completed.isEmpty()) return
        // Snapshot only the list of references. Committed strokes and their points are immutable.
        rememberUndo(UndoAction.RestoreDrawing(completed.immutableCopy()))
        completed.clear()
    }

    private fun rememberUndo(action: UndoAction) {
        if (undoHistory.size == MAX_UNDO_STEPS) undoHistory.removeFirst()
        undoHistory.addLast(action)
    }

    private sealed interface UndoAction {
        data object RemoveLastStroke : UndoAction
        data class RestoreDrawing(val strokes: List<Stroke>) : UndoAction
    }

    private data class ActiveStroke(
        val regionId: String,
        val colorArgb: Long,
        val width: Float,
        val tool: Tool,
        val points: ArrayList<Point>,
        val crayonId: String?,
        val gradientStops: List<Long>,
    ) {
        fun snapshot() = Stroke(regionId, colorArgb, width, tool, points.immutableCopy(), crayonId, gradientStops)
    }

    companion object {
        const val MAX_STROKES = 300
        const val MAX_POINTS_PER_STROKE = 1024
        const val MAX_UNDO_STEPS = 100
        const val MIN_BRUSH_WIDTH = 1f
        const val MAX_BRUSH_WIDTH = 200f
        private val crayonIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,79}")

        private fun validWidth(width: Float) = width.isFinite() &&
            width in MIN_BRUSH_WIDTH..MAX_BRUSH_WIDTH

        private fun validColor(argb: Long) = argb in 0xFF000000L..0xFFFFFFFFL

        private fun validCrayon(id: String?, stops: List<Long>): Boolean =
            (id == null || id.matches(crayonIdPattern)) &&
                (stops.isEmpty() || (stops.size in 2..5 && stops.all(::validColor)))

        private fun boundedPoints(points: List<Point>, limit: Int = MAX_POINTS_PER_STROKE): List<Point> {
            if (points.size <= limit) return points.toList()
            return List(limit) { index -> points[(index.toLong() * (points.size - 1) / (limit - 1)).toInt()] }
        }
    }
}

internal fun <T> List<T>.immutableCopy(): List<T> = Collections.unmodifiableList(ArrayList(this))

internal fun LineArt.immutableSnapshot(): LineArt = copy(
    regions = regions.map { region ->
        region.copy(outlineDash = region.outlineDash.immutableCopy(), shape = when (val shape = region.shape) {
            is Shape.Oval -> shape
            is Shape.Polygon -> shape.copy(points = shape.points.immutableCopy())
            is Shape.Vector -> shape.copy(contours = shape.contours.map { it.immutableCopy() }.immutableCopy())
        })
    }.immutableCopy(),
    details = details.map { detail ->
        when (detail) {
            is Detail.Dot -> detail
            is Detail.Line -> detail.copy(points = detail.points.immutableCopy())
            is Detail.Vector -> detail.copy(
                shape = detail.shape.copy(contours = detail.shape.contours.map { it.immutableCopy() }.immutableCopy()),
                strokeDash = detail.strokeDash.immutableCopy(),
            )
        }
    }.immutableCopy(),
    renderOrder = renderOrder.immutableCopy(),
)
