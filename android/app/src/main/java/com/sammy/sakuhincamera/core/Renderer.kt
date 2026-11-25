package com.sammy.sakuhincamera.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 背景レイヤーの元になるもの。 */
sealed interface BackgroundSource {
    /** 画像（デコード済み。大きさは何でもよく、キャンバスいっぱいに敷いてはみ出しは切り取る）。 */
    class Image(val image: RgbImage) : BackgroundSource
    class Solid(val color: Int) : BackgroundSource
    class Gradient(val top: Int, val bottom: Int) : BackgroundSource
    /** 元写真を背景としてぼかす。 */
    data object BlurredOriginal : BackgroundSource
    data object None : BackgroundSource
}

/**
 * 最終画像の生成。
 *
 * 1. 作品（元写真＋マスク）をキャンバスに配置する。原寸・回転なしなら画素をそのまま写す
 * 2. 背景レイヤーを作る：背景素材 → ぼかし・明るさ・色温度 → 接地影
 * 3. `Compositor` で `背景 × (1 − マスク) + 元写真 × マスク` を合成する
 *
 * 調整・影はすべて背景レイヤーで行い、作品の画素には触れない。
 */
object Renderer {
    class Placed(val subject: RgbImage, val mask: AlphaMask)

    fun render(original: RgbImage, mask: AlphaMask, background: BackgroundSource, composition: Composition): RgbImage {
        val (cw, ch) = Canvas.size(original.width, original.height, composition.ratio)
        val placed = placeSubject(original, mask, cw, ch, composition.transform)
        val layer = backgroundLayer(background, original, cw, ch, composition.adjustments, composition.shadow, placed.mask)
        return Compositor.composite(placed.subject, placed.mask, layer)
    }

    /** 背景レイヤー（素材 → ぼかし・明るさ・色温度 → 接地影）。作品は含まない。 */
    fun backgroundLayer(
        background: BackgroundSource, original: RgbImage, cw: Int, ch: Int,
        adjustments: BackgroundAdjustments, shadow: ShadowSettings, canvasMask: AlphaMask?,
    ): RgbImage {
        val shortSide = min(cw, ch).toDouble()
        var layer = when (background) {
            is BackgroundSource.Image -> aspectFill(background.image, cw, ch)
            is BackgroundSource.Solid -> RgbImage(cw, ch, IntArray(cw * ch) { background.color or (0xFF shl 24) })
            is BackgroundSource.Gradient -> gradient(background.top, background.bottom, cw, ch)
            BackgroundSource.BlurredOriginal -> Planes.blurRgb(aspectFill(original, cw, ch), 0.04 * shortSide)
            BackgroundSource.None -> RgbImage(cw, ch, IntArray(cw * ch) { -1 })
        }
        if (adjustments.blur > 0) layer = Planes.blurRgb(layer, adjustments.blur * 0.03 * shortSide)
        layer = Planes.adjust(layer, adjustments.brightness, adjustments.temperature)
        if (canvasMask != null && shadow.enabled && shadow.opacity > 0) layer = applyShadow(layer, canvasMask, shadow)
        return layer
    }

    /** 作品のマスクを下にずらしてぼかし、背景を暗くする。 */
    fun applyShadow(background: RgbImage, mask: AlphaMask, shadow: ShadowSettings): RgbImage {
        val w = background.width
        val h = background.height
        val shortSide = min(w, h).toDouble()
        val dy = shadow.offsetPixels(shortSide).roundToInt()
        val shifted = IntArray(w * h)
        for (y in 0 until h) {
            val sy = y - dy
            if (sy !in 0 until h) continue
            for (x in 0 until w) shifted[y * w + x] = mask[sy * w + x]
        }
        val blurred = Planes.gaussianBlur(shifted, w, h, shadow.blurPixels(shortSide))
        val p = background.pixels
        val out = IntArray(p.size)
        val opacity = shadow.opacity
        for (i in p.indices) {
            val keep = 1.0 - blurred[i] / 255.0 * opacity
            val px = p[i]
            out[i] = argb((red(px) * keep + 0.5).toInt(), (green(px) * keep + 0.5).toInt(), (blue(px) * keep + 0.5).toInt())
        }
        return RgbImage(w, h, out)
    }

    // ---- 作品の配置 ----

    fun placeSubject(original: RgbImage, mask: AlphaMask, cw: Int, ch: Int, transform: SubjectTransform): Placed {
        val centerX = cw / 2.0 + transform.offsetX
        val centerY = ch / 2.0 + transform.offsetY
        if (transform.isUnscaled) {
            // 整数画素だけずらしてそのまま写す。作品の画素は元写真と完全に一致する
            val tx = (centerX - original.width / 2.0).roundToInt()
            val ty = (centerY - original.height / 2.0).roundToInt()
            return copyPlacing(original, mask, cw, ch, tx, ty)
        }
        return resamplePlacing(original, mask, cw, ch, centerX, centerY, transform)
    }

    private fun copyPlacing(original: RgbImage, mask: AlphaMask, cw: Int, ch: Int, tx: Int, ty: Int): Placed {
        if (tx == 0 && ty == 0 && cw == original.width && ch == original.height) return Placed(original, mask)
        val subject = IntArray(cw * ch)
        val values = ByteArray(cw * ch)
        val w = original.width
        val xStart = max(0, -tx)
        val xEnd = min(w, cw - tx)
        if (xStart < xEnd) {
            for (sy in 0 until original.height) {
                val dy = sy + ty
                if (dy !in 0 until ch) continue
                val count = xEnd - xStart
                val src = sy * w + xStart
                val dst = dy * cw + xStart + tx
                System.arraycopy(original.pixels, src, subject, dst, count)
                System.arraycopy(mask.values, src, values, dst, count)
            }
        }
        return Placed(RgbImage(cw, ch, subject), AlphaMask(cw, ch, values))
    }

    /** 拡大縮小・回転するときは、キャンバスの各画素から元写真を逆にたどって双一次補間する。 */
    private fun resamplePlacing(
        original: RgbImage, mask: AlphaMask, cw: Int, ch: Int,
        centerX: Double, centerY: Double, t: SubjectTransform,
    ): Placed {
        val subject = IntArray(cw * ch)
        val values = ByteArray(cw * ch)
        val w = original.width
        val h = original.height
        val c = cos(-t.rotation) / t.scale
        val s = sin(-t.rotation) / t.scale
        val src = original.pixels
        for (y in 0 until ch) {
            val dy = y + 0.5 - centerY
            for (x in 0 until cw) {
                val dx = x + 0.5 - centerX
                val u = dx * c - dy * s + w / 2.0 - 0.5
                val v = dx * s + dy * c + h / 2.0 - 0.5
                if (u < -0.5 || v < -0.5 || u > w - 0.5 || v > h - 0.5) continue
                val cu = u.coerceIn(0.0, (w - 1).toDouble())
                val cv = v.coerceIn(0.0, (h - 1).toDouble())
                val x0 = cu.toInt()
                val y0 = cv.toInt()
                val x1 = min(x0 + 1, w - 1)
                val y1 = min(y0 + 1, h - 1)
                val fx = cu - x0
                val fy = cv - y0
                val i00 = y0 * w + x0
                val i01 = y0 * w + x1
                val i10 = y1 * w + x0
                val i11 = y1 * w + x1
                fun mix(a: Int, b: Int, cc: Int, d: Int): Int {
                    val top = a * (1 - fx) + b * fx
                    val bottom = cc * (1 - fx) + d * fx
                    return (top * (1 - fy) + bottom * fy + 0.5).toInt().coerceIn(0, 255)
                }
                val o = y * cw + x
                subject[o] = argb(
                    mix(red(src[i00]), red(src[i01]), red(src[i10]), red(src[i11])),
                    mix(green(src[i00]), green(src[i01]), green(src[i10]), green(src[i11])),
                    mix(blue(src[i00]), blue(src[i01]), blue(src[i10]), blue(src[i11])),
                )
                values[o] = mix(mask[i00], mask[i01], mask[i10], mask[i11]).toByte()
            }
        }
        return Placed(RgbImage(cw, ch, subject), AlphaMask(cw, ch, values))
    }

    // ---- ヘルパー ----

    /** キャンバスいっぱいに敷く（はみ出しは切り取る）。双一次補間。 */
    fun aspectFill(source: RgbImage, cw: Int, ch: Int): RgbImage {
        val scale = max(cw.toDouble() / source.width, ch.toDouble() / source.height)
        val ox = (source.width * scale - cw) / 2
        val oy = (source.height * scale - ch) / 2
        val w = source.width
        val h = source.height
        val src = source.pixels
        val out = IntArray(cw * ch)
        for (y in 0 until ch) {
            val v = ((y + 0.5 + oy) / scale - 0.5).coerceIn(0.0, (h - 1).toDouble())
            val y0 = v.toInt()
            val y1 = min(y0 + 1, h - 1)
            val fy = v - y0
            for (x in 0 until cw) {
                val u = ((x + 0.5 + ox) / scale - 0.5).coerceIn(0.0, (w - 1).toDouble())
                val x0 = u.toInt()
                val x1 = min(x0 + 1, w - 1)
                val fx = u - x0
                val a = src[y0 * w + x0]
                val b = src[y0 * w + x1]
                val c = src[y1 * w + x0]
                val d = src[y1 * w + x1]
                fun mix(pa: Int, pb: Int, pc: Int, pd: Int): Int =
                    ((pa * (1 - fx) + pb * fx) * (1 - fy) + (pc * (1 - fx) + pd * fx) * fy + 0.5).toInt().coerceIn(0, 255)
                out[y * cw + x] = argb(
                    mix(red(a), red(b), red(c), red(d)),
                    mix(green(a), green(b), green(c), green(d)),
                    mix(blue(a), blue(b), blue(c), blue(d)),
                )
            }
        }
        return RgbImage(cw, ch, out)
    }

    private fun gradient(top: Int, bottom: Int, cw: Int, ch: Int): RgbImage {
        val out = IntArray(cw * ch)
        for (y in 0 until ch) {
            val t = if (ch == 1) 0.0 else y.toDouble() / (ch - 1)
            val color = argb(
                (red(top) + (red(bottom) - red(top)) * t + 0.5).toInt(),
                (green(top) + (green(bottom) - green(top)) * t + 0.5).toInt(),
                (blue(top) + (blue(bottom) - blue(top)) * t + 0.5).toInt(),
            )
            out.fill(color, y * cw, (y + 1) * cw)
        }
        return RgbImage(cw, ch, out)
    }
}
