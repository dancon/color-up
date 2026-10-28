package com.colorup.app.ui

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntSize
import com.colorup.core.LineArt

/** The original illustrated PNG is the visible art; derived contours only constrain pigment. */
internal object ArtworkInk {
    private lateinit var assets: AssetManager
    private val images = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
    }

    @Synchronized fun initialize(manager: AssetManager) { assets = manager }

    @Synchronized fun image(asset: String, thumbnail: Boolean): ImageBitmap {
        val key = "$asset:${if (thumbnail) 2 else 1}"
        images.get(key)?.let { return it }
        check(::assets.isInitialized) { "Artwork assets must be initialized by the application" }
        val options = BitmapFactory.Options().apply {
            inSampleSize = if (thumbnail) 2 else 1
            inScaled = false
        }
        val bitmap = assets.open(asset).use { requireNotNull(BitmapFactory.decodeStream(it, null, options)) }
        return bitmap.asImageBitmap().also { images.put(key, it) }
    }
}

internal fun DrawScope.drawIllustratedInk(page: LineArt) {
    val asset = page.inkAsset ?: return
    val image = ArtworkInk.image(asset, thumbnail = size.minDimension < 600f)
    // White paper multiplies to the existing pigment; black ink remains above every color.
    drawImage(image, dstSize = IntSize(1000, 1000), filterQuality = FilterQuality.High,
        blendMode = BlendMode.Multiply)
}
