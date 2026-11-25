package com.sammy.sakuhincamera.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 最重要ルールの確認：マスク=255 の画素は、背景の調整・影・比率・位置に関係なく元写真と完全一致する。
 */
class InvarianceTest {
    private val random = Random(42)

    private fun randomPhoto(w: Int, h: Int) = RgbImage(w, h, IntArray(w * h) {
        argb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
    })

    /** 中央に円形の作品（内側 255・縁はなだらか）があるマスク。 */
    private fun circleMask(w: Int, h: Int): AlphaMask {
        val mask = AlphaMask(w, h)
        val cx = w / 2.0
        val cy = h / 2.0
        val r = minOf(w, h) * 0.3
        for (y in 0 until h) for (x in 0 until w) {
            val d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            mask[y * w + x] = ((r + 3 - d) / 6 * 255).toInt().coerceIn(0, 255)
        }
        return mask
    }

    @Test
    fun compositorKeepsSubjectPixelsExactly() {
        val photo = randomPhoto(64, 48)
        val background = randomPhoto(64, 48)
        val mask = circleMask(64, 48)
        val out = Compositor.composite(photo, mask, background)
        var checked = 0
        for (i in photo.pixels.indices) {
            when (mask[i]) {
                255 -> { assertEquals(photo.pixels[i] or (0xFF shl 24), out.pixels[i]); checked++ }
                0 -> assertEquals(background.pixels[i] or (0xFF shl 24), out.pixels[i])
            }
        }
        assertTrue(checked > 100)
    }

    @Test
    fun fullRenderKeepsSubjectPixelsWithAdjustmentsShadowRatioAndOffset() {
        val w = 120
        val h = 90
        val photo = randomPhoto(w, h)
        val mask = circleMask(w, h)
        val backgrounds = listOf(
            BackgroundSource.Image(randomPhoto(50, 70)),
            BackgroundSource.Gradient(argb(250, 200, 180), argb(20, 40, 200)),
            BackgroundSource.Solid(argb(10, 200, 30)),
            BackgroundSource.BlurredOriginal,
        )
        for (background in backgrounds) for (ratio in AspectRatio.entries) {
            val composition = Composition(
                adjustments = BackgroundAdjustments(brightness = 0.7, temperature = -0.6, blur = 0.5),
                shadow = ShadowSettings(opacity = 0.9, blur = 0.8, offset = 1.0),
                transform = SubjectTransform(offsetX = 7.4, offsetY = -5.0),
                ratio = ratio,
            )
            val out = Renderer.render(photo, mask, background, composition)
            val (cw, ch) = Canvas.size(w, h, ratio)
            assertEquals(cw, out.width)
            assertEquals(ch, out.height)
            val tx = Math.round(cw / 2.0 + 7.4 - w / 2.0).toInt()
            val ty = Math.round(ch / 2.0 - 5.0 - h / 2.0).toInt()
            var checked = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (mask[y * w + x] != 255) continue
                val cx = x + tx
                val cy = y + ty
                if (cx !in 0 until cw || cy !in 0 until ch) continue
                assertEquals("$background $ratio ($x,$y)", photo.pixels[y * w + x] or (0xFF shl 24), out.pixels[cy * cw + cx])
                checked++
            }
            assertTrue(checked > 500)
        }
    }

    @Test
    fun edgeRefinerDoesNotChangeInteriorOrFarBackground() {
        val w = 200
        val h = 160
        val photo = randomPhoto(w, h)
        val mask = circleMask(w, h)
        val refined = EdgeRefiner.feather(mask, photo, EdgeSoftness.STRONG.radius(4032))
        val cx = w / 2
        val cy = h / 2
        for (y in cy - 10..cy + 10) for (x in cx - 10..cx + 10) assertEquals(255, refined[y * w + x])
        assertEquals(0, refined[0])
        assertEquals(0, refined[w * h - 1])
    }

    @Test
    fun subjectMaskIsFullyOpaqueWhereConfident() {
        val maps = SubjectMaps(10, 10, listOf(FloatArray(100) { if (it % 10 < 5) 0.97f else 0.02f }))
        val mask = maps.combinedMask(setOf(0), 40, 40)
        assertEquals(255, mask[5 * 40 + 2])
        assertEquals(0, mask[5 * 40 + 38])
        assertEquals(-1, SubjectMaps(10, 10, emptyList()).subjectAt(0.5f, 0.5f))
        assertEquals(0, maps.subjectAt(0.1f, 0.5f))
    }
}
