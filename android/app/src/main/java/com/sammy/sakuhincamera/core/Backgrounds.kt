package com.sammy.sakuhincamera.core

import org.json.JSONArray

/** 同梱背景（Backgrounds/manifest.json の1件）。iOS 版と同じファイルを読む。 */
data class BackgroundItem(
    val id: String,
    val name: String,
    val category: String,
    val aiGenerated: Boolean,
    val kind: Kind,
) {
    sealed interface Kind {
        data class Image(val file: String) : Kind
        data class Solid(val color: Int) : Kind
        data class Gradient(val top: Int, val bottom: Int) : Kind
        data object BlurredOriginal : Kind
    }
}

object BackgroundCatalog {
    val categoryNames = mapOf(
        "season" to "季節の背景",
        "wood" to "木",
        "angle" to "ななめ",
        "fabric" to "布",
        "cafe" to "カフェ",
        "home" to "おうち",
        "plant" to "植物・屋外",
        "stone" to "石・紙",
        "outdoor" to "屋外",
        "plain" to "単色・グラデーション",
    )

    /** manifest.json を読む。読めない項目（色の書き方が違うなど）は飛ばす。自分用なので有料の印は使わない。 */
    fun parse(json: String): List<BackgroundItem> {
        val array = JSONArray(json)
        val items = mutableListOf<BackgroundItem>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val kind: BackgroundItem.Kind? = when {
                o.has("file") -> BackgroundItem.Kind.Image(o.getString("file"))
                o.has("color") -> parseColor(o.getString("color"))?.let { BackgroundItem.Kind.Solid(it) }
                o.has("gradient") -> {
                    val g = o.getJSONArray("gradient")
                    val top = parseColor(g.optString(0))
                    val bottom = parseColor(g.optString(1))
                    if (top != null && bottom != null) BackgroundItem.Kind.Gradient(top, bottom) else null
                }
                o.optBoolean("blurOriginal", false) -> BackgroundItem.Kind.BlurredOriginal
                else -> null
            }
            if (kind != null) {
                items += BackgroundItem(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    category = o.getString("category"),
                    aiGenerated = o.optBoolean("aiGenerated", false),
                    kind = kind,
                )
            }
        }
        return items
    }

    /** manifest に出てくる順のカテゴリ。 */
    fun categories(items: List<BackgroundItem>): List<String> = items.map { it.category }.distinct()

    /** 最初に選ぶ背景（季節ものは選ばない）。 */
    fun defaultItem(items: List<BackgroundItem>): BackgroundItem? =
        items.firstOrNull { it.category != "season" } ?: items.firstOrNull()

    /** "#RRGGBB" → 0xFFRRGGBB。 */
    fun parseColor(text: String?): Int? {
        if (text == null || !Regex("^#[0-9A-Fa-f]{6}$").matches(text)) return null
        return (0xFF shl 24) or text.substring(1).toInt(16)
    }
}
