package com.sammy.sakuhincamera.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/** 書き出しの縦横比。 */
enum class AspectRatio(val label: String, val value: Double?) {
    ORIGINAL("元の比率", null),
    SQUARE("1:1", 1.0),
    PORTRAIT_4X5("4:5", 4.0 / 5.0),
    STORY_9X16("9:16", 9.0 / 16.0),
}

/** 背景レイヤーだけにかける調整。作品には一切かけない。 */
data class BackgroundAdjustments(
    /** 露出（EV）。-1〜1 */
    val brightness: Double = 0.0,
    /** 色温度。-1（青み）〜1（暖かみ） */
    val temperature: Double = 0.0,
    /** ぼかし。0〜1 */
    val blur: Double = 0.0,
)

/** 接地影。作品のマスクを下にずらしてぼかし、背景レイヤーに落とす。 */
data class ShadowSettings(
    val enabled: Boolean = true,
    /** 濃さ。0〜1 */
    val opacity: Double = 0.35,
    /** ぼかし量。0〜1 */
    val blur: Double = 0.4,
    /** 下方向へのずれ量。0〜1 */
    val offset: Double = 0.25,
) {
    fun offsetPixels(shortSide: Double) = offset * 0.04 * shortSide
    fun blurPixels(shortSide: Double) = (0.004 + blur * 0.03) * shortSide
}

/** 作品の配置。位置はキャンバス中央からのずれ（元写真の画素単位）、回転はラジアン。 */
data class SubjectTransform(
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val scale: Double = 1.0,
    val rotation: Double = 0.0,
) {
    /** 拡大縮小・回転なし。このときは画素をそのまま写す（リサンプリングしない）。 */
    val isUnscaled: Boolean get() = scale == 1.0 && rotation == 0.0

    /** 原寸・回転なしに近ければぴったりに寄せる（作品を無加工のまま置きやすくする）。 */
    fun snapped(): SubjectTransform {
        var s = scale
        var r = rotation
        if (abs(s - 1) < 0.03) s = 1.0
        if (abs(r) < 2 * PI / 180) r = 0.0
        return copy(scale = s.coerceIn(0.2, 4.0), rotation = r)
    }
}

data class Composition(
    val backgroundId: String? = null,
    val adjustments: BackgroundAdjustments = BackgroundAdjustments(),
    val shadow: ShadowSettings = ShadowSettings(),
    val transform: SubjectTransform = SubjectTransform(),
    val ratio: AspectRatio = AspectRatio.ORIGINAL,
)

object Canvas {
    /** 元写真がすべて収まる、指定比率の最小のキャンバス（元写真の画素単位）。 */
    fun size(imageWidth: Int, imageHeight: Int, ratio: AspectRatio): Pair<Int, Int> {
        val r = ratio.value ?: return imageWidth to imageHeight
        val imageRatio = imageWidth.toDouble() / imageHeight
        return if (imageRatio > r) {
            imageWidth to (imageWidth / r).roundToInt()
        } else {
            (imageHeight * r).roundToInt() to imageHeight
        }
    }
}

object AppText {
    const val CAPTION_BASE = "作品は無加工・背景は差し替え"

    fun caption(aiGeneratedBackground: Boolean) =
        if (aiGeneratedBackground) "$CAPTION_BASE（AI生成背景）" else CAPTION_BASE
}
