package com.colorup.app

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.colorup.app.ui.drawArtwork
import com.colorup.core.LineArt
import com.colorup.core.Stroke
import java.time.LocalDate

internal data class ExportRequest(val page: LineArt, val strokes: List<Stroke>)

internal object ArtworkExporter {
    fun write(resolver: ContentResolver, uri: Uri, request: ExportRequest) {
        val bitmap = createBitmap(1080, 1200)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(0xFFFFF8EC.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
            canvas.drawRoundRect(40f, 40f, 1040f, 1040f, 24f, 24f, paint)
            canvas.withTranslation(40f, 40f) {
                clipRect(0f, 0f, 1000f, 1000f)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(this), Size(1000f, 1000f)) {
                    drawArtwork(request.page, request.strokes, null)
                }
            }
            paint.color = 0xFF3D3A45.toInt()
            paint.textSize = 30f
            paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            canvas.drawText("涂个彩 · ${request.page.title}", 48f, 1110f, paint)
            paint.textSize = 22f
            paint.typeface = android.graphics.Typeface.DEFAULT
            canvas.drawText("给童年涂个彩。  ${LocalDate.now()}", 48f, 1152f, paint)
            val stream = requireNotNull(resolver.openOutputStream(uri, "w")) { "Cannot open export destination" }
            stream.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }
}
