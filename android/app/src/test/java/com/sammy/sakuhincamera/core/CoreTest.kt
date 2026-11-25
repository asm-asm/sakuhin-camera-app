package com.sammy.sakuhincamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CoreTest {
    @Test
    fun canvasIsSmallestRectOfRatioContainingPhoto() {
        assertEquals(3000 to 4000, Canvas.size(3000, 4000, AspectRatio.ORIGINAL))
        assertEquals(4000 to 4000, Canvas.size(3000, 4000, AspectRatio.SQUARE))
        assertEquals(3200 to 4000, Canvas.size(3000, 4000, AspectRatio.PORTRAIT_4X5))
        assertEquals(3000 to 5333, Canvas.size(3000, 4000, AspectRatio.STORY_9X16))
        assertEquals(4000 to 5000, Canvas.size(4000, 3000, AspectRatio.PORTRAIT_4X5))
    }

    @Test
    fun snapsNearlyUnscaledTransform() {
        val t = SubjectTransform(scale = 1.02, rotation = 0.01).snapped()
        assertTrue(t.isUnscaled)
    }

    @Test
    fun bundledManifestParsesAndEveryImageExists() {
        // テストは android/app で動く。背景は iOS 版と同じ Backgrounds/ を使う
        val dir = File("../../Backgrounds")
        val items = BackgroundCatalog.parse(File(dir, "manifest.json").readText())
        assertEquals(items.size, items.map { it.id }.toSet().size)
        for (item in items) {
            val kind = item.kind
            if (kind is BackgroundItem.Kind.Image) assertTrue(kind.file, File(dir, kind.file).isFile)
        }
        val categories = BackgroundCatalog.categories(items)
        assertTrue(categories.containsAll(listOf("season", "wood", "angle", "plain")))
        for (c in categories) assertTrue(c, BackgroundCatalog.categoryNames.containsKey(c))
        assertTrue(BackgroundCatalog.defaultItem(items)!!.category != "season")
        assertTrue(items.any { it.kind == BackgroundItem.Kind.BlurredOriginal })
    }

    @Test
    fun parsesColors() {
        assertEquals(0xFF336699.toInt(), BackgroundCatalog.parseColor("#336699"))
        assertNull(BackgroundCatalog.parseColor("red"))
    }

    @Test
    fun brushAddsAndErases() {
        val mask = AlphaMask(50, 50)
        Brush.paint(mask, BrushStroke(listOf(10f to 25f, 40f to 25f), 5f, add = true))
        assertEquals(255, mask[25 * 50 + 25])
        assertEquals(0, mask[5 * 50 + 25])
        Brush.paint(mask, BrushStroke(listOf(25f to 25f), 3f, add = false))
        assertEquals(0, mask[25 * 50 + 25])
    }

    @Test
    fun adjustmentsChangeBackgroundOnly() {
        val image = RgbImage(2, 1, intArrayOf(argb(128, 128, 128), argb(128, 128, 128)))
        val warm = Planes.adjust(image, 0.0, 1.0).pixels[0]
        assertTrue(red(warm) > blue(warm))
        val bright = Planes.adjust(image, 1.0, 0.0).pixels[0]
        assertTrue(red(bright) > 128)
        val blurred = Planes.gaussianBlur(IntArray(100) { if (it == 55) 255 else 0 }, 10, 10, 2.0)
        assertTrue(blurred[55] in 1..254)
    }
}
