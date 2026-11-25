package com.sammy.sakuhincamera.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sammy.sakuhincamera.Bitmaps
import com.sammy.sakuhincamera.EditorViewModel
import com.sammy.sakuhincamera.core.SubjectMaps

/** 切り抜き画面：自動で見つけた作品を表示し、複数あるときはタップで選ぶ。 */
@Composable
fun CutoutScreen(vm: EditorViewModel) {
    val photo = vm.displayPhoto ?: return
    val photoBitmap = remember(photo) { Bitmaps.toBitmap(photo).asImageBitmap() }
    val subjects = vm.subjects
    val overlay = remember(subjects, vm.selected) { subjects?.let { selectionOverlay(it, vm.selected).asImageBitmap() } }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopTitle("切り抜き", onBack = vm::retake)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val container = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
            val rect = fitRect(container, photo.width, photo.height)
            Box(
                Modifier.fillMaxSize().pointerInput(rect, subjects) {
                    detectTapGestures { p ->
                        if (rect.contains(p)) vm.toggleSubject((p.x - rect.left) / rect.width, (p.y - rect.top) / rect.height)
                    }
                }
            ) {
                Image(photoBitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                overlay?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                if (vm.segmenting || vm.working) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
        }
        val message = when {
            vm.segmenting -> "作品を探しています…"
            vm.segmentError != null -> vm.segmentError!!
            subjects == null -> ""
            subjects.count == 0 -> "作品が見つかりませんでした。次の画面でブラシで塗って指定できます"
            subjects.count == 1 -> "作品を切り抜きました"
            else -> "作品が ${subjects.count} つ見つかりました。タップで選ぶ・外す（複数可）"
        }
        Text(
            message,
            color = Color.LightGray,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        )
        BottomBar {
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (vm.segmentError != null) PillButton("もう一度", primary = false, onClick = vm::segment)
                PillButton(
                    "次へ",
                    enabled = !vm.segmenting && !vm.working && (subjects != null || vm.segmentError != null),
                    onClick = vm::proceedToRefine,
                )
            }
        }
    }
}

/** 選んでいない部分を暗くし、選べる（まだ選んでいない）作品は白っぽく見せる。 */
private fun selectionOverlay(maps: SubjectMaps, selected: Set<Int>): Bitmap {
    val n = maps.width * maps.height
    val pixels = IntArray(n)
    for (i in 0 until n) {
        var best = -1
        var bestValue = 0.5f
        maps.confidences.forEachIndexed { k, c -> if (c[i] > bestValue) { best = k; bestValue = c[i] } }
        pixels[i] = when {
            best < 0 -> 0x99000000.toInt()
            best in selected -> 0
            else -> 0x66FFFFFF
        }
    }
    return Bitmap.createBitmap(pixels, maps.width, maps.height, Bitmap.Config.ARGB_8888)
}
