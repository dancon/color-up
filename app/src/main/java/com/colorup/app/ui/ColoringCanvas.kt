package com.colorup.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.PathParser
import com.colorup.core.ArtLayer
import com.colorup.core.ArtStrokeCap
import com.colorup.core.ArtStrokeJoin
import com.colorup.core.Detail
import com.colorup.core.FillRule
import com.colorup.core.LineArt
import com.colorup.core.Point
import com.colorup.core.Shape
import com.colorup.core.Stroke
import com.colorup.core.Tool
import kotlin.math.min

private val Outline = Color(0xFF3D3A45)

private data class PaperTransform(val scale: Float, val left: Float, val top: Float) {
    fun toPaper(offset: Offset) = Point((offset.x - left) / scale, (offset.y - top) / scale)

    companion object {
        fun fit(width: Float, height: Float): PaperTransform {
            val side = min(width, height)
            return PaperTransform(side / 1000f, (width - side) / 2f, (height - side) / 2f)
        }
    }
}

private data class PreparedArtwork(val regions: Map<String, Path>, val details: Map<Int, Path>)

private fun prepareArtwork(page: LineArt) = PreparedArtwork(
    regions = page.regions.associate { it.id to it.shape.toPath() },
    details = page.details.mapIndexedNotNull { index, detail ->
        when (detail) {
            is Detail.Vector -> index to detail.shape.toPath()
            is Detail.Line -> index to detail.points.toPolyline()
            is Detail.Dot -> null
        }
    }.toMap(),
)

/** Draws the exact same 1000 × 1000 artwork for the live canvas, thumbnails and PNG exports. */
fun DrawScope.drawArtwork(page: LineArt, strokes: List<Stroke>, activeStroke: Stroke? = null) {
    drawPreparedArtwork(page, strokes, activeStroke, prepareArtwork(page))
}

private fun DrawScope.drawPreparedArtwork(
    page: LineArt,
    strokes: List<Stroke>,
    activeStroke: Stroke?,
    prepared: PreparedArtwork,
) {
    if (page.inkAsset != null) drawRect(Color.White, size = Size(1000f, 1000f))
    val allStrokes = if (activeStroke == null) strokes else strokes + activeStroke
    val byRegion = allStrokes.groupBy { it.regionId }
    val regions = page.regions.associateBy { it.id }
    val order = page.renderOrder.ifEmpty {
        page.regions.map { ArtLayer.RegionLayer(it.id) } + page.details.indices.map { ArtLayer.DetailLayer(it) }
    }
    order.forEach { layer ->
        when (layer) {
            is ArtLayer.RegionLayer -> {
                val region = regions[layer.regionId] ?: return@forEach
                val path = prepared.regions.getValue(region.id)
                drawPath(path, Color.White)
                clipPath(path) { byRegion[region.id].orEmpty().forEach { drawColoringStroke(it) } }
                if (region.outlineWidth > 0f) {
                    drawPath(path, Color(region.outlineArgb.toInt()), style = DrawStroke(
                        width = region.outlineWidth, cap = StrokeCap.Round, join = region.outlineJoin.composeJoin(),
                        pathEffect = region.outlineDash.dashEffect(),
                    ))
                }
            }
            is ArtLayer.DetailLayer -> {
                val index = layer.detailIndex
                when (val detail = page.details.getOrNull(index) ?: return@forEach) {
                    is Detail.Dot -> drawCircle(Outline, detail.radius, detail.center.offset())
                    is Detail.Line -> {
                        if (detail.points.size == 1) drawCircle(Outline, detail.width / 2f, detail.points.first().offset())
                        else if (detail.points.isNotEmpty()) drawPath(prepared.details.getValue(index), Outline, style = DrawStroke(
                            detail.width, cap = StrokeCap.Round, join = StrokeJoin.Round,
                        ))
                    }
                    is Detail.Vector -> {
                        val path = prepared.details.getValue(index)
                        detail.fillArgb?.let { drawPath(path, Color(it.toInt()), alpha = detail.opacity) }
                        detail.strokeArgb?.let {
                            if (detail.strokeWidth > 0f) drawPath(path, Color(it.toInt()), alpha = detail.opacity, style = DrawStroke(
                                detail.strokeWidth, cap = detail.strokeCap.composeCap(), join = detail.strokeJoin.composeJoin(),
                                pathEffect = detail.strokeDash.dashEffect(),
                            ))
                        }
                    }
                }
            }
        }
    }
    drawIllustratedInk(page)
}

@Composable
fun ColoringCanvas(
    page: LineArt,
    strokes: List<Stroke>,
    activeStroke: Stroke?,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    onStrokeStart: (Point) -> Unit = {},
    onStrokeMove: (Point) -> Unit = {},
    onStrokeEnd: () -> Unit = {},
    onStrokeCancel: () -> Unit = {},
) {
    val prepared = remember(page) { prepareArtwork(page) }
    val start by rememberUpdatedState(onStrokeStart)
    val move by rememberUpdatedState(onStrokeMove)
    val end by rememberUpdatedState(onStrokeEnd)
    val cancel by rememberUpdatedState(onStrokeCancel)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val input = if (interactive) {
        Modifier.testTag("coloring_canvas")
            .semantics { contentDescription = "${page.title}涂色画板，按住一个区域开始涂色，抬手结束" }
            .onSizeChanged { canvasSize = it }
            .pointerInput(page.id, canvasSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val transform = PaperTransform.fit(size.width.toFloat(), size.height.toFloat())
                    if (transform.scale <= 0f) return@awaitEachGesture
                    val first = transform.toPaper(down.position)
                    if (first.x !in 0f..1000f || first.y !in 0f..1000f) return@awaitEachGesture
                    down.consume()
                    var finished = false
                    try {
                        start(first)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val primary = event.changes.firstOrNull { it.id == down.id }
                            if (primary == null || primary.isConsumed) break
                            move(transform.toPaper(primary.position))
                            primary.consume()
                            if (!primary.pressed) {
                                end()
                                finished = true
                                break
                            }
                        }
                    } finally {
                        if (!finished) cancel()
                    }
                }
            }
    } else Modifier
    Canvas(modifier.then(input)) {
        val viewport = PaperTransform.fit(size.width, size.height)
        if (viewport.scale <= 0f) return@Canvas
        translate(viewport.left, viewport.top) {
            scale(viewport.scale, viewport.scale, pivot = Offset.Zero) {
                drawPreparedArtwork(page, strokes, activeStroke, prepared)
            }
        }
    }
}

internal fun DrawScope.drawColoringStroke(stroke: Stroke) {
    if (stroke.points.isEmpty()) return
    if (stroke.tool == Tool.CRAYON) {
        drawCrayonStroke(stroke)
    } else if (stroke.points.size == 1) {
        drawCircle(Color.White, stroke.width / 2f, stroke.points.first().offset())
    } else {
        drawPath(stroke.points.toPolyline(), Color.White, style = DrawStroke(stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun Point.offset() = Offset(x, y)
private fun List<Point>.toPolyline() = Path().apply {
    firstOrNull()?.let { moveTo(it.x, it.y) }
    drop(1).forEach { lineTo(it.x, it.y) }
}

internal fun Shape.toPath(): Path = when (this) {
    is Shape.Oval -> Path().apply { addOval(Rect(left, top, right, bottom)) }
    is Shape.Polygon -> points.toPolyline().apply { close() }
    is Shape.Vector -> requireNotNull(PathParser.createPathFromPathData(pathData)).asComposePath().apply {
        transform(Matrix().apply { scale(this@toPath.scale, this@toPath.scale) })
        fillType = if (fillRule == FillRule.EVEN_ODD) PathFillType.EvenOdd else PathFillType.NonZero
    }
}

private fun ArtStrokeCap.composeCap() = when (this) {
    ArtStrokeCap.BUTT -> StrokeCap.Butt
    ArtStrokeCap.ROUND -> StrokeCap.Round
    ArtStrokeCap.SQUARE -> StrokeCap.Square
}
private fun ArtStrokeJoin.composeJoin() = when (this) {
    ArtStrokeJoin.MITER -> StrokeJoin.Miter
    ArtStrokeJoin.ROUND -> StrokeJoin.Round
    ArtStrokeJoin.BEVEL -> StrokeJoin.Bevel
}

private fun List<Float>.dashEffect(): PathEffect? =
    if (size >= 2 && all { it.isFinite() && it > 0f }) PathEffect.dashPathEffect(toFloatArray()) else null
