package com.sammy.sakuhincamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sammy.sakuhincamera.Bitmaps
import com.sammy.sakuhincamera.EditorViewModel
import com.sammy.sakuhincamera.core.BrushStroke
import com.sammy.sakuhincamera.core.EdgeSoftness

/** 縁の調整：1本指でなぞって塗る（追加・消去）、2本指で拡大と移動。縁のなめらかさを選ぶ。 */
@Composable
fun RefineScreen(vm: EditorViewModel) {
    val photo = vm.displayPhoto ?: return
    val photoBitmap = remember(photo) { Bitmaps.toBitmap(photo).asImageBitmap() }
    val overlay = remember(vm.maskVersion) { vm.displayMask()?.let { maskOverlay(it).asImageBitmap() } }

    var adding by remember { mutableStateOf(true) }
    var diameterDp by remember { mutableFloatStateOf(40f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val livePoints = remember { mutableStateListOf<Offset>() }
    val density = LocalDensity.current

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopTitle("縁の調整", onBack = { vm.screen = com.sammy.sakuhincamera.Screen.CUTOUT })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val container = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
            val rect = fitRect(container, photo.width, photo.height)
            val diameterPx = with(density) { diameterDp.dp.toPx() }
            Box(
                Modifier.fillMaxSize().pointerInput(rect, adding, diameterPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        // 画面の点 → 拡大前の入れ物の点
                        fun content(p: Offset) = (p - pan) / zoom
                        var drawing = true
                        livePoints.clear()
                        livePoints += content(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                drawing = false
                                livePoints.clear()
                                val z = event.calculateZoom()
                                val c = event.calculateCentroid()
                                val newZoom = (zoom * z).coerceIn(1f, 8f)
                                val applied = newZoom / zoom
                                pan = (pan - c) * applied + c + event.calculatePan()
                                zoom = newZoom
                            } else if (drawing) {
                                livePoints += content(pressed.first().position)
                            }
                            event.changes.forEach { it.consume() }
                        }
                        if (drawing && livePoints.isNotEmpty()) {
                            // 入れ物の点 → 元写真の画素
                            val toFull = vm.displayToFull * photo.width / rect.width
                            val points = livePoints.map { ((it.x - rect.left) * toFull).toFloat() to ((it.y - rect.top) * toFull).toFloat() }
                            val radius = (diameterPx / zoom / 2 * toFull).toFloat()
                            vm.addStroke(BrushStroke(points, radius, adding))
                        }
                        livePoints.clear()
                    }
                }
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = zoom
                        scaleY = zoom
                        translationX = pan.x
                        translationY = pan.y
                    }
                ) {
                    Image(photoBitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    overlay?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    Canvas(Modifier.fillMaxSize()) {
                        if (livePoints.isNotEmpty()) {
                            val path = Path().apply {
                                moveTo(livePoints[0].x, livePoints[0].y)
                                livePoints.drop(1).forEach { lineTo(it.x, it.y) }
                                if (livePoints.size == 1) lineTo(livePoints[0].x + 0.1f, livePoints[0].y)
                            }
                            drawPath(
                                path,
                                if (adding) Color(0x8834C759) else Color(0x88FF3B30),
                                style = Stroke(width = diameterPx / zoom, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
                if (vm.working) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
        }

        Column(Modifier.fillMaxWidth().background(Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(listOf(true, false), adding, { if (it) "追加" else "消去" }, { adding = it }, Modifier.weight(1f))
                PillButton("元に戻す", primary = false, enabled = vm.strokes.isNotEmpty() && !vm.working, onClick = vm::undoStroke)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ブラシ", color = Color.White, modifier = Modifier.width(72.dp))
                Slider(value = diameterDp, onValueChange = { diameterDp = it }, valueRange = 8f..120f, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("縁のなめらかさ", color = Color.White, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                Segmented(EdgeSoftness.entries, vm.softness, { it.label }, vm::changeSoftness, Modifier.weight(1f))
            }
            Text(
                "1本指でなぞって塗る・2本指で拡大と移動。毛羽のある作品は「なめらかさ」を強めにすると自然になります",
                color = Color.Gray,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PillButton("次へ", enabled = !vm.working, onClick = vm::proceedToBackground)
            }
        }
    }
}
