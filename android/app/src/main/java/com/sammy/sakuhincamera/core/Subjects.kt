package com.sammy.sakuhincamera.core

import kotlin.math.min

/**
 * 切り抜きで見つかった被写体（作品）ごとの信頼度マップ（縮小した解像度）。
 * タップでの選択と、選んだ被写体を合わせたマスク作りに使う。
 */
class SubjectMaps(val width: Int, val height: Int, val confidences: List<FloatArray>) {
    val count: Int get() = confidences.size

    /** 正規化座標（0〜1）にある被写体の番号。なければ -1。 */
    fun subjectAt(nx: Float, ny: Float): Int {
        val x = (nx * width).toInt().coerceIn(0, width - 1)
        val y = (ny * height).toInt().coerceIn(0, height - 1)
        var best = -1
        var bestValue = 0.5f
        confidences.forEachIndexed { k, c ->
            val v = c[y * width + x]
            if (v > bestValue) { best = k; bestValue = v }
        }
        return best
    }

    /**
     * 選んだ被写体を合わせたマスクを、元写真の大きさで作る。
     * 信頼度 0.9 以上は 255（作品の画素をそのまま使う）、0.1 以下は 0 にし、その間だけを縁としてなだらかにつなぐ。
     */
    fun combinedMask(selected: Set<Int>, fullWidth: Int, fullHeight: Int): AlphaMask {
        val low = FloatArray(width * height)
        for (k in selected) {
            val c = confidences.getOrNull(k) ?: continue
            for (i in low.indices) if (c[i] > low[i]) low[i] = c[i]
        }
        val out = ByteArray(fullWidth * fullHeight)
        val fx = width.toFloat() / fullWidth
        val fy = height.toFloat() / fullHeight
        for (y in 0 until fullHeight) {
            val v = ((y + 0.5f) * fy - 0.5f).coerceIn(0f, (height - 1).toFloat())
            val y0 = v.toInt()
            val y1 = min(y0 + 1, height - 1)
            val ty = v - y0
            for (x in 0 until fullWidth) {
                val u = ((x + 0.5f) * fx - 0.5f).coerceIn(0f, (width - 1).toFloat())
                val x0 = u.toInt()
                val x1 = min(x0 + 1, width - 1)
                val tx = u - x0
                val top = low[y0 * width + x0] * (1 - tx) + low[y0 * width + x1] * tx
                val bottom = low[y1 * width + x0] * (1 - tx) + low[y1 * width + x1] * tx
                val c = top * (1 - ty) + bottom * ty
                val remapped = ((c - 0.1f) / 0.8f).coerceIn(0f, 1f)
                out[y * fullWidth + x] = AlphaMask.quantize(remapped).toByte()
            }
        }
        return AlphaMask(fullWidth, fullHeight, out)
    }
}
