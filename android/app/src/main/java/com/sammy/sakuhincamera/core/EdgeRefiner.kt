package com.sammy.sakuhincamera.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 縁の強さ（エッジ保持フェザーの半径）。 */
enum class EdgeSoftness(val label: String, private val base: Double) {
    NONE("なし", 0.0),
    WEAK("弱", 4.0),
    MEDIUM("中", 8.0),
    STRONG("強", 16.0);

    /** 12MP（長辺 4032px）の写真での半径。写真の大きさに比例させる。 */
    fun radius(longSide: Int): Int = if (base == 0.0) 0 else max(1, (base * longSide / 4032).roundToInt())
}

/**
 * マスクの縁を元写真の明るさに沿ってなめらかにする（モヘアなどの毛羽対策）。
 *
 * ガイデッドフィルタ（元写真の輝度をガイドにする）を縮小画像で計算し、
 * 縁の近く（マスクが 0 と 255 の境目付近）だけに適用する。縁から離れた作品の内側・背景は変えない。
 */
object EdgeRefiner {
    fun feather(mask: AlphaMask, guide: RgbImage, radius: Int, epsilon: Float = 5e-4f): AlphaMask {
        require(mask.width == guide.width && mask.height == guide.height)
        if (radius <= 0) return mask
        val width = mask.width
        val height = mask.height
        val step = if (radius >= 4) 4 else 1
        val lowRadius = max(1, radius / step)
        val lw = (width + step - 1) / step
        val lh = (height + step - 1) / step
        val n = lw * lh

        // 縮小（面積平均）
        val lowGuide = FloatArray(n)
        val lowMask = FloatArray(n)
        val counts = FloatArray(n)
        val pixels = guide.pixels
        for (y in 0 until height) {
            val ly = y / step
            for (x in 0 until width) {
                val li = ly * lw + x / step
                val i = y * width + x
                lowGuide[li] += luminance(pixels[i])
                lowMask[li] += mask[i] / 255f
                counts[li] += 1f
            }
        }
        for (i in 0 until n) {
            lowGuide[i] /= counts[i]
            lowMask[i] /= counts[i]
        }

        // ガイデッドフィルタの係数
        val meanI = boxMean(lowGuide, lw, lh, lowRadius)
        val meanP = boxMean(lowMask, lw, lh, lowRadius)
        val corrII = boxMean(FloatArray(n) { lowGuide[it] * lowGuide[it] }, lw, lh, lowRadius)
        val corrIP = boxMean(FloatArray(n) { lowGuide[it] * lowMask[it] }, lw, lh, lowRadius)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val variance = corrII[i] - meanI[i] * meanI[i]
            val covariance = corrIP[i] - meanI[i] * meanP[i]
            a[i] = covariance / (variance + epsilon)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = boxMean(a, lw, lh, lowRadius)
        val meanB = boxMean(b, lw, lh, lowRadius)

        // 元の解像度で、縁の近くだけ置き換える
        val output = mask.values.copyOf()
        for (y in 0 until height) {
            val v = (y + 0.5f) / step - 0.5f
            for (x in 0 until width) {
                val u = (x + 0.5f) / step - 0.5f
                val band = sample(meanP, lw, lh, u, v)
                if (band <= 0.002f || band >= 0.998f) continue
                val i = y * width + x
                val q = sample(meanA, lw, lh, u, v) * luminance(pixels[i]) + sample(meanB, lw, lh, u, v)
                output[i] = AlphaMask.quantize(q).toByte()
            }
        }
        return AlphaMask(width, height, output)
    }

    private fun luminance(p: Int): Float = (0.299f * red(p) + 0.587f * green(p) + 0.114f * blue(p)) / 255f

    /** 双一次補間（範囲外は端の値）。 */
    private fun sample(values: FloatArray, w: Int, h: Int, u: Float, v: Float): Float {
        val cu = u.coerceIn(0f, (w - 1).toFloat())
        val cv = v.coerceIn(0f, (h - 1).toFloat())
        val x0 = cu.toInt()
        val y0 = cv.toInt()
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val fx = cu - x0
        val fy = cv - y0
        val top = values[y0 * w + x0] * (1 - fx) + values[y0 * w + x1] * fx
        val bottom = values[y1 * w + x0] * (1 - fx) + values[y1 * w + x1] * fx
        return top * (1 - fy) + bottom * fy
    }

    /** 半径 r の箱型平均（端は窓に入る画素だけで平均する）。 */
    fun boxMean(input: FloatArray, width: Int, height: Int, r: Int): FloatArray {
        val horizontal = FloatArray(input.size)
        for (y in 0 until height) {
            val row = y * width
            var sum = 0f
            var count = 0f
            for (x in 0 until min(r, width)) {
                sum += input[row + x]; count += 1f
            }
            for (x in 0 until width) {
                val add = x + r
                if (add < width) { sum += input[row + add]; count += 1f }
                val remove = x - r - 1
                if (remove >= 0) { sum -= input[row + remove]; count -= 1f }
                horizontal[row + x] = sum / count
            }
        }
        val output = FloatArray(input.size)
        for (x in 0 until width) {
            var sum = 0f
            var count = 0f
            for (y in 0 until min(r, height)) {
                sum += horizontal[y * width + x]; count += 1f
            }
            for (y in 0 until height) {
                val add = y + r
                if (add < height) { sum += horizontal[add * width + x]; count += 1f }
                val remove = y - r - 1
                if (remove >= 0) { sum -= horizontal[remove * width + x]; count -= 1f }
                output[y * width + x] = sum / count
            }
        }
        return output
    }
}

/** ブラシの1ストローク（マスクの画素座標）。add なら作品に追加、そうでなければ消去。 */
data class BrushStroke(val points: List<Pair<Float, Float>>, val radius: Float, val add: Boolean)

object Brush {
    /** ストロークをマスクに塗る（マスクを直接書き換える）。 */
    fun paint(mask: AlphaMask, stroke: BrushStroke) {
        val pts = stroke.points
        if (pts.isEmpty()) return
        if (pts.size == 1) {
            segment(mask, pts[0], pts[0], stroke.radius, stroke.add)
        } else {
            for (k in 1 until pts.size) segment(mask, pts[k - 1], pts[k], stroke.radius, stroke.add)
        }
    }

    private fun segment(mask: AlphaMask, start: Pair<Float, Float>, end: Pair<Float, Float>, radius: Float, add: Boolean) {
        val target = if (add) 255 else 0
        val r = max(0.5f, radius)
        val dx = end.first - start.first
        val dy = end.second - start.second
        val distance = sqrt(dx * dx + dy * dy)
        val steps = max(1, (distance / max(1f, r / 3)).toInt())
        val r2 = r * r
        val w = mask.width
        val h = mask.height
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val cx = start.first + dx * t
            val cy = start.second + dy * t
            val minX = max(0, (cx - r).toInt())
            val maxX = min(w - 1, (cx + r).toInt() + 1)
            val minY = max(0, (cy - r).toInt())
            val maxY = min(h - 1, (cy + r).toInt() + 1)
            for (y in minY..maxY) {
                val ddy = y + 0.5f - cy
                for (x in minX..maxX) {
                    val ddx = x + 0.5f - cx
                    if (ddx * ddx + ddy * ddy <= r2) mask[y * w + x] = target
                }
            }
        }
    }
}
