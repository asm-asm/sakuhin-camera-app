package com.sammy.sakuhincamera.core

/**
 * 画素データ（Android に依存しない形）。pixels は ARGB の Int（Bitmap.getPixels と同じ並び）。
 * 作品の画素は撮影した元写真のまま、この配列で持ち回す。
 */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(pixels.size == width * height) { "画素数が幅×高さと合わない" }
    }
}

/** マスク（0=背景、255=作品）。1画素1バイト（符号なしとして扱う）。 */
class AlphaMask(val width: Int, val height: Int, val values: ByteArray = ByteArray(width * height)) {
    init {
        require(values.size == width * height) { "画素数が幅×高さと合わない" }
    }

    operator fun get(index: Int): Int = values[index].toInt() and 0xFF

    operator fun set(index: Int, value: Int) {
        values[index] = value.coerceIn(0, 255).toByte()
    }

    fun copy(): AlphaMask = AlphaMask(width, height, values.copyOf())

    companion object {
        fun quantize(value: Float): Int = (value * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}

internal inline fun red(p: Int) = (p shr 16) and 0xFF
internal inline fun green(p: Int) = (p shr 8) and 0xFF
internal inline fun blue(p: Int) = p and 0xFF
internal inline fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
