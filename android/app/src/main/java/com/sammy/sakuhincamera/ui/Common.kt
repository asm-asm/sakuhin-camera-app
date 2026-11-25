package com.sammy.sakuhincamera.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sammy.sakuhincamera.core.AlphaMask
import kotlin.math.min

val Accent = Color(0xFF0A84FF)
val Panel = Color(0xFF1C1C1E)
val PanelLight = Color(0xFF2C2C2E)
val Ok = Color(0xFF34C759)

@Composable
fun PillButton(text: String, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (!enabled) PanelLight else if (primary) Accent else PanelLight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(text, color = if (enabled) Color.White else Color.Gray, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** 横に並ぶ切り替えボタン（セグメント）。 */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(10.dp)).background(PanelLight).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Color(0xFF636366) else Color.Transparent)
                    .clickable { onSelect(option) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(option), color = Color.White, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) Accent else PanelLight)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
fun BottomBar(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { content() }
}

/** 入れ物の中に、縦横比を保って収めたときの位置。 */
fun fitRect(container: Size, imageWidth: Int, imageHeight: Int): Rect {
    val scale = min(container.width / imageWidth, container.height / imageHeight)
    val w = imageWidth * scale
    val h = imageHeight * scale
    val left = (container.width - w) / 2
    val top = (container.height - h) / 2
    return Rect(left, top, left + w, top + h)
}

/** マスクの外側（背景になるところ）を暗くする重ね絵。 */
fun maskOverlay(mask: AlphaMask): Bitmap {
    val pixels = IntArray(mask.width * mask.height) {
        val outside = 255 - mask[it]
        (outside * 150 / 255 shl 24)
    }
    return Bitmap.createBitmap(pixels, mask.width, mask.height, Bitmap.Config.ARGB_8888)
}
