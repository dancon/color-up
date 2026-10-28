package com.colorup.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.colorup.core.ColoringRegion
import com.colorup.core.LineArt
import com.colorup.core.Point
import com.colorup.core.Shape
import com.colorup.core.Stroke
import com.colorup.core.Tool
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.util.UUID

/** Writes through ContentResolver into an isolated private file; never opens sharing or storage UI. */
@RunWith(AndroidJUnit4::class)
class ArtworkExporterTest {
    private lateinit var directory: File

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "artwork-export-test-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
    }

    @After fun tearDown() {
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test fun pngExportPreservesTexturedPigmentRegionClippingAndEraserRules() {
        val page = LineArt(
            id = "export-test", title = "导出边界测试", subtitle = "",
            regions = listOf(
                ColoringRegion("left", rectangle(50f, 450f)),
                ColoringRegion("right", rectangle(550f, 950f)),
            ),
            details = emptyList(),
        )
        val strokes = listOf(
            // Both paint paths cross the gap and the other region; their region IDs constrain them.
            stroke("left", Tool.CRAYON, 0xFFFF0000L, 90f, 100f, 900f),
            stroke("right", Tool.CRAYON, 0xFF0000FFL, 90f, 100f, 900f),
            // Erase only part of the red stroke, crossing into the blue region along the way.
            stroke("left", Tool.ERASER, 0xFFFF0000L, 50f, 200f, 800f),
        )
        assertTrue(strokes.flatMap { it.points }.all { it.x in 0f..1000f && it.y in 0f..1000f })
        val target = File(directory, "clipped-artwork.png")
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

        val request = ExportRequest(page, strokes)
        ArtworkExporter.write(resolver, Uri.fromFile(target), request)

        assertTrue("Export must create a nonempty PNG file", target.isFile && target.length() > 8)
        val signature = ByteArray(8)
        DataInputStream(target.inputStream()).use { it.readFully(signature) }
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            signature,
        )
        val decoded = BitmapFactory.decodeFile(target.absolutePath)
        assertNotNull("The exported bytes must decode as a bitmap", decoded)
        val bitmap = decoded!!
        try {
            assertEquals(1080, bitmap.width)
            assertEquals(1200, bitmap.height)
            // A grain can be bare white paper. Patches verify pigment coverage and variation
            // without depending on the noise value of one arbitrarily selected pixel.
            assertCrayonPatch(bitmap, 125, 485, red = true)
            assertCrayonPatch(bitmap, 725, 485, red = false)
            assertWhitePatch(bitmap, 285, 485) // Erased red, entirely inside the eraser width.
            assertWhitePatch(bitmap, 485, 485) // Gap between regions.
            assertWhitePatch(bitmap, 960, 485) // Outside the right region.
            assertWhitePatch(bitmap, 285, 585) // Inside the region, outside either stroke.

            val repeatedTarget = File(directory, "clipped-artwork-repeated.png")
            ArtworkExporter.write(resolver, Uri.fromFile(repeatedTarget), request)
            val repeated = BitmapFactory.decodeFile(repeatedTarget.absolutePath)
            assertNotNull("Repeated export must also decode", repeated)
            val repeatedBitmap = repeated!!
            try {
                assertEquals(bitmap.width, repeatedBitmap.width)
                assertEquals(bitmap.height, repeatedBitmap.height)
                // Compare the paper only: its deterministic texture must not depend on a draw
                // invocation. The date caption below the paper intentionally is not compared.
                assertArrayEquals(
                    "Repeated PNG exports must reproduce identical paper pigment",
                    paperPatch(bitmap, 0, 0, 1000, 1000),
                    paperPatch(repeatedBitmap, 0, 0, 1000, 1000),
                )
            } finally {
                repeatedBitmap.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun gradientPngPreservesColorChangesPaperGrainAndWhiteGlitter() {
        val page = LineArt("gradient-export", "炫彩", "",
            listOf(ColoringRegion("paper", rectangle(50f, 950f))), emptyList())
        val rainbow = stroke("paper", Tool.CRAYON, 0xFFFF0000, 80f, 100f, 900f).copy(
            crayonId = "rainbow-test", gradientStops = listOf(0xFFFF0000, 0xFF0000FF))
        val target = File(directory, "gradient.png")
        ArtworkExporter.write(InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
            Uri.fromFile(target), ExportRequest(page, listOf(rainbow)))
        val bitmap = requireNotNull(BitmapFactory.decodeFile(target.absolutePath))
        try {
            val left = paperPatch(bitmap, 160, 475)
            val right = paperPatch(bitmap, 810, 475)
            assertTrue(left.sumOf { Color.red(it).toLong() } > left.sumOf { Color.blue(it).toLong() } + 40000)
            assertTrue(right.sumOf { Color.blue(it).toLong() } > right.sumOf { Color.red(it).toLong() } + 40000)
            assertTrue("Export retains visible grain", left.map { Color.green(it) }.distinct().size > 20)
            assertTrue("Glitter contains short bright highlights along the stroke", paperPatch(bitmap, 250, 496, 100, 8)
                .count { Color.green(it) > 170 } > 50)
            assertWhitePatch(bitmap, 960, 485)
        } finally { bitmap.recycle() }
    }

    private fun assertCrayonPatch(bitmap: Bitmap, left: Int, top: Int, red: Boolean) {
        val pixels = paperPatch(bitmap, left, top)
        val otherChannel = pixels.map { if (red) Color.green(it) else Color.red(it) }
        pixels.forEach { pixel ->
            val dominant = if (red) Color.red(pixel) else Color.blue(pixel)
            val firstOther = if (red) Color.green(pixel) else Color.red(pixel)
            val secondOther = if (red) Color.blue(pixel) else Color.green(pixel)
            assertEquals("Pigment must remain opaque after compositing over paper", 255, Color.alpha(pixel))
            assertTrue("Pigment must mix the selected color with white paper", dominant >= 250)
            assertTrue("Pigment must preserve the selected hue", kotlin.math.abs(firstOther - secondOther) <= 2)
        }
        assertTrue("The patch must contain substantial colored wax", otherChannel.count { it < 220 } > pixels.size / 4)
        assertTrue("Crayon pigment must vary in brightness rather than form a solid fill",
            otherChannel.maxOrNull()!! - otherChannel.minOrNull()!! > 20)
    }

    private fun assertWhitePatch(bitmap: Bitmap, left: Int, top: Int) {
        assertTrue("Paper patch at ($left, $top) must stay white",
            paperPatch(bitmap, left, top).all { it == Color.WHITE })
    }

    /** The exporter places its 1000 × 1000 drawing paper at bitmap coordinate (40, 40). */
    private fun paperPatch(bitmap: Bitmap, left: Int, top: Int, width: Int = 30, height: Int = 30): IntArray {
        assertTrue("Patch must lie inside the drawing paper",
            left >= 0 && top >= 0 && width > 0 && height > 0 && left + width <= 1000 && top + height <= 1000)
        val x = left + 40
        val y = top + 40
        assertTrue("Every sampled pixel must lie inside the exported bitmap",
            x >= 0 && y >= 0 && x + width <= bitmap.width && y + height <= bitmap.height)
        return IntArray(width * height).also { pixels ->
            bitmap.getPixels(pixels, 0, width, x, y, width, height)
        }
    }

    private fun rectangle(left: Float, right: Float) = Shape.Polygon(
        listOf(Point(left, 100f), Point(right, 100f), Point(right, 900f), Point(left, 900f)),
    )

    private fun stroke(region: String, tool: Tool, color: Long, width: Float, startX: Float, endX: Float) = Stroke(
        regionId = region,
        colorArgb = color,
        width = width,
        tool = tool,
        points = listOf(Point(startX, 500f), Point(endX, 500f)),
    )
}
