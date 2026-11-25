package com.sammy.sakuhincamera.core

/**
 * 最終合成。式は `出力 = 背景 × (1 − マスク) + 元写真 × マスク` のみ。
 *
 * 整数演算で行い、マスク=255 の画素が元写真と完全一致することを保証する
 * （(元×255 + 127) / 255 = 元）。背景側の調整（明るさ・色温度・ぼかし・影）は、
 * この関数に渡す前の背景レイヤーにだけ適用すること。
 */
object Compositor {
    fun composite(original: RgbImage, mask: AlphaMask, background: RgbImage): RgbImage {
        require(original.width == mask.width && original.height == mask.height) { "マスクの大きさが違う" }
        require(original.width == background.width && original.height == background.height) { "背景の大きさが違う" }
        val src = original.pixels
        val bg = background.pixels
        val m = mask.values
        val out = IntArray(src.size)
        for (i in src.indices) {
            val a = m[i].toInt() and 0xFF
            out[i] = when (a) {
                255 -> src[i] or (0xFF shl 24)
                0 -> bg[i] or (0xFF shl 24)
                else -> {
                    val s = src[i]
                    val b = bg[i]
                    argb(blend(red(b), red(s), a), blend(green(b), green(s), a), blend(blue(b), blue(s), a))
                }
            }
        }
        return RgbImage(original.width, original.height, out)
    }

    private fun blend(background: Int, original: Int, alpha: Int): Int =
        (background * (255 - alpha) + original * alpha + 127) / 255
}
