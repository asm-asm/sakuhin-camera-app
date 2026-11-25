package com.sammy.sakuhincamera.core

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 1チャンネルの画素面（0〜255 の Int）と、ぼかし・縮小・拡大。
 * 背景レイヤーと影にだけ使う（作品の画素には使わない）。
 */
object Planes {
    /** ガウスぼかし（箱型ぼかし3回で近似）。大きくぼかすときは縮小してから計算して速くする。 */
    fun gaussianBlur(plane: IntArray, width: Int, height: Int, sigma: Double): IntArray {
        if (sigma < 0.5) return plane.copyOf()
        val k = floor(sigma / 3).toInt().coerceIn(1, 8)
        if (k == 1) return boxBlur3(plane, width, height, sigma)
        val sw = max(1, (width + k - 1) / k)
        val sh = max(1, (height + k - 1) / k)
        val small = downscale(plane, width, height, sw, sh)
        val blurred = boxBlur3(small, sw, sh, sigma / k)
        return upscaleBilinear(blurred, sw, sh, width, height)
    }

    private fun boxBlur3(plane: IntArray, width: Int, height: Int, sigma: Double): IntArray {
        var current = plane.copyOf()
        val tmp = IntArray(plane.size)
        for (size in boxSizes(sigma, 3)) {
            val r = (size - 1) / 2
            if (r <= 0) continue
            boxHorizontal(current, tmp, width, height, r)
            boxVertical(tmp, current, width, height, r)
        }
        return current
    }

    /** ガウスぼかしを n 回の箱型ぼかしで近似するときの箱の大きさ。 */
    private fun boxSizes(sigma: Double, n: Int): IntArray {
        val ideal = sqrt(12 * sigma * sigma / n + 1)
        var wl = floor(ideal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val m = ((12 * sigma * sigma - n * wl * wl - 4 * n * wl - 3 * n) / (-4 * wl - 4)).roundToInt()
        return IntArray(n) { if (it < m) wl else wu }
    }

    /** 端は端の値をのばす。 */
    private fun boxHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
            for (i in -r..r) sum += src[row + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = (sum + div / 2) / div
                sum += src[row + min(x + r + 1, w - 1)] - src[row + max(x - r, 0)]
            }
        }
    }

    private fun boxVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += src[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = (sum + div / 2) / div
                sum += src[min(y + r + 1, h - 1) * w + x] - src[max(y - r, 0) * w + x]
            }
        }
    }

    /** 面積平均で縮小する。 */
    fun downscale(plane: IntArray, w: Int, h: Int, dw: Int, dh: Int): IntArray {
        val sums = LongArray(dw * dh)
        val counts = IntArray(dw * dh)
        for (y in 0 until h) {
            val dy = min(dh - 1, y * dh / h)
            for (x in 0 until w) {
                val di = dy * dw + min(dw - 1, x * dw / w)
                sums[di] += plane[y * w + x].toLong()
                counts[di]++
            }
        }
        return IntArray(dw * dh) { if (counts[it] == 0) 0 else ((sums[it] + counts[it] / 2) / counts[it]).toInt() }
    }

    /** 双一次補間で拡大する（範囲外は端の値）。 */
    fun upscaleBilinear(plane: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        val fx = sw.toFloat() / dw
        val fy = sh.toFloat() / dh
        for (y in 0 until dh) {
            val v = ((y + 0.5f) * fy - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y0 = v.toInt()
            val y1 = min(y0 + 1, sh - 1)
            val ty = v - y0
            for (x in 0 until dw) {
                val u = ((x + 0.5f) * fx - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x0 = u.toInt()
                val x1 = min(x0 + 1, sw - 1)
                val tx = u - x0
                val top = plane[y0 * sw + x0] * (1 - tx) + plane[y0 * sw + x1] * tx
                val bottom = plane[y1 * sw + x0] * (1 - tx) + plane[y1 * sw + x1] * tx
                out[y * dw + x] = (top * (1 - ty) + bottom * ty + 0.5f).toInt()
            }
        }
        return out
    }

    fun split(image: RgbImage): Array<IntArray> {
        val p = image.pixels
        return arrayOf(IntArray(p.size) { red(p[it]) }, IntArray(p.size) { green(p[it]) }, IntArray(p.size) { blue(p[it]) })
    }

    fun merge(width: Int, height: Int, r: IntArray, g: IntArray, b: IntArray): RgbImage =
        RgbImage(width, height, IntArray(width * height) { argb(r[it].coerceIn(0, 255), g[it].coerceIn(0, 255), b[it].coerceIn(0, 255)) })

    fun blurRgb(image: RgbImage, sigma: Double): RgbImage {
        if (sigma < 0.5) return image
        val (r, g, b) = split(image)
        val w = image.width
        val h = image.height
        return merge(w, h, gaussianBlur(r, w, h, sigma), gaussianBlur(g, w, h, sigma), gaussianBlur(b, w, h, sigma))
    }

    /** sRGB の値（0〜255）→ 線形の明るさ（0〜1）。 */
    private val toLinear = FloatArray(256) {
        val c = it / 255.0
        (if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)).toFloat()
    }

    private fun fromLinear(l: Double): Int {
        val c = l.coerceIn(0.0, 1.0)
        val s = if (c <= 0.0031308) c * 12.92 else 1.055 * Math.pow(c, 1 / 2.4) - 0.055
        return (s * 255 + 0.5).toInt().coerceIn(0, 255)
    }

    /**
     * 明るさ（EV）と色温度を、線形の明るさに倍率をかけて調整する。
     * 色温度は 1 で暖かく（赤を強く・青を弱く）、-1 で青みにする。
     */
    fun adjust(image: RgbImage, brightness: Double, temperature: Double): RgbImage {
        if (brightness == 0.0 && temperature == 0.0) return image
        val exposure = Math.pow(2.0, brightness)
        val gains = doubleArrayOf(
            exposure * exp(0.22 * temperature),
            exposure * exp(0.03 * temperature),
            exposure * exp(-0.28 * temperature),
        )
        val luts = Array(3) { c -> IntArray(256) { v -> fromLinear(toLinear[v] * gains[c]) } }
        val p = image.pixels
        return RgbImage(image.width, image.height, IntArray(p.size) {
            val px = p[it]
            argb(luts[0][red(px)], luts[1][green(px)], luts[2][blue(px)])
        })
    }

}
