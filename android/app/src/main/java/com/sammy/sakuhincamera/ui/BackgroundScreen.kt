package com.sammy.sakuhincamera.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sammy.sakuhincamera.Bitmaps
import com.sammy.sakuhincamera.EditorViewModel
import com.sammy.sakuhincamera.Screen
import com.sammy.sakuhincamera.core.AspectRatio
import com.sammy.sakuhincamera.core.BackgroundAdjustments
import com.sammy.sakuhincamera.core.BackgroundCatalog
import com.sammy.sakuhincamera.core.BackgroundItem
import com.sammy.sakuhincamera.core.Canvas
import com.sammy.sakuhincamera.core.ShadowSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

private enum class Tab(val label: String) { BACKGROUND("背景"), ADJUST("調整"), SHADOW("影") }

@Composable
fun BackgroundScreen(vm: EditorViewModel) {
    var tab by remember { mutableStateOf(Tab.BACKGROUND) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopTitle("背景", onBack = { vm.screen = Screen.REFINE })
        Segmented(AspectRatio.entries, vm.composition.ratio, { it.label }, { vm.composition = vm.composition.copy(ratio = it) },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        CompositionPreview(vm, editable = true, modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp))
        TransformStatus(vm)
        Column(Modifier.fillMaxWidth().background(Panel).padding(vertical = 12.dp)) {
            Segmented(Tab.entries, tab, { it.label }, { tab = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Box(Modifier.fillMaxWidth().height(190.dp).padding(top = 10.dp)) {
                when (tab) {
                    Tab.BACKGROUND -> BackgroundPicker(vm)
                    Tab.ADJUST -> AdjustPanel(vm)
                    Tab.SHADOW -> ShadowPanel(vm)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PillButton("次へ", onClick = { vm.screen = Screen.EXPORT })
            }
        }
    }
}

/** 仕上がりのプレビュー。editable なら指で作品を動かす・拡大縮小・回転できる。 */
@Composable
fun CompositionPreview(vm: EditorViewModel, editable: Boolean, modifier: Modifier = Modifier) {
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var rendering by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { vm.composition to vm.maskVersion }.conflate().collect { (composition, _) ->
            rendering = true
            preview = withContext(Dispatchers.Default) { vm.renderPreview(composition) }
            rendering = false
        }
    }
    val photo = vm.displayPhoto ?: return
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val container = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        val (cw, ch) = Canvas.size(photo.width, photo.height, vm.composition.ratio)
        val rect = fitRect(container, cw, ch)
        val gestures = if (editable) {
            Modifier.pointerInput(rect) {
                detectTransformGestures { _, pan, zoomChange, rotationChange ->
                    // 画面の点 → 元写真の画素
                    val toFull = cw / rect.width * vm.displayToFull
                    val t = vm.rawTransform
                    vm.updateTransform(
                        t.copy(
                            offsetX = t.offsetX + pan.x * toFull,
                            offsetY = t.offsetY + pan.y * toFull,
                            scale = (t.scale * zoomChange).coerceIn(0.2, 4.0),
                            rotation = t.rotation + Math.toRadians(rotationChange.toDouble()),
                        )
                    )
                }
            }
        } else Modifier
        Box(Modifier.fillMaxSize().then(gestures), contentAlignment = Alignment.Center) {
            preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (preview == null && rendering) CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun TransformStatus(vm: EditorViewModel) {
    val unscaled = vm.composition.transform.isUnscaled
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (unscaled) "✓ 作品は撮ったまま（無加工）" else "作品を拡大・回転中（大きさと向きだけ変更）",
            color = if (unscaled) Ok else Color.LightGray,
            fontSize = 12.sp,
        )
        if (vm.composition.transform != com.sammy.sakuhincamera.core.SubjectTransform()) {
            Text("  元に戻す", color = Accent, fontSize = 12.sp, modifier = Modifier.clickable(onClick = vm::resetTransform))
        }
    }
}

@Composable
private fun BackgroundPicker(vm: EditorViewModel) {
    val categories = remember(vm.items) { BackgroundCatalog.categories(vm.items) }
    var category by remember { mutableStateOf<String?>(null) }
    val shown = remember(category, vm.items) { vm.items.filter { category == null || it.category == category } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Chip("すべて", category == null) { category = null }
            categories.forEach { c -> Chip(BackgroundCatalog.categoryNames[c] ?: c, category == c) { category = c } }
        }
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown, key = { it.id }) { item ->
                BackgroundTile(item, item.id == vm.composition.backgroundId) { vm.selectBackground(item) }
            }
        }
    }
}

@Composable
private fun BackgroundTile(item: BackgroundItem, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) { Bitmaps.loadThumbnail(context, item) }
    }
    Column(Modifier.width(72.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(10.dp)
        Box(
            Modifier
                .size(width = 72.dp, height = 96.dp)
                .clip(shape)
                .border(if (selected) 3.dp else 0.dp, if (selected) Accent else Color.Transparent, shape)
                .background(tileBrush(item)),
            contentAlignment = Alignment.Center,
        ) {
            thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (item.kind == BackgroundItem.Kind.BlurredOriginal) Text("ぼかし", color = Color.White, fontSize = 12.sp)
        }
        Text(item.name, color = Color.LightGray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun tileBrush(item: BackgroundItem): Brush = when (val kind = item.kind) {
    is BackgroundItem.Kind.Solid -> Brush.linearGradient(listOf(Color(kind.color), Color(kind.color)))
    is BackgroundItem.Kind.Gradient -> Brush.verticalGradient(listOf(Color(kind.top), Color(kind.bottom)))
    BackgroundItem.Kind.BlurredOriginal -> Brush.verticalGradient(listOf(Color(0xFF8E8E93), Color(0xFF48484A)))
    is BackgroundItem.Kind.Image -> Brush.linearGradient(listOf(PanelLight, PanelLight))
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.width(64.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AdjustPanel(vm: EditorViewModel) {
    val a = vm.composition.adjustments
    fun set(new: BackgroundAdjustments) { vm.composition = vm.composition.copy(adjustments = new) }
    Column {
        LabeledSlider("明るさ", a.brightness.toFloat(), -1f..1f) { set(a.copy(brightness = it.toDouble())) }
        LabeledSlider("色温度", a.temperature.toFloat(), -1f..1f) { set(a.copy(temperature = it.toDouble())) }
        LabeledSlider("ぼかし", a.blur.toFloat(), 0f..1f) { set(a.copy(blur = it.toDouble())) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("調整は背景だけにかかります", color = Color.Gray, fontSize = 12.sp)
            Text("リセット", color = Accent, fontSize = 13.sp, modifier = Modifier.clickable { set(BackgroundAdjustments()) })
        }
    }
}

@Composable
private fun ShadowPanel(vm: EditorViewModel) {
    val s = vm.composition.shadow
    fun set(new: ShadowSettings) { vm.composition = vm.composition.copy(shadow = new) }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("接地影", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = s.enabled, onCheckedChange = { set(s.copy(enabled = it)) })
        }
        LabeledSlider("濃さ", s.opacity.toFloat(), 0f..1f) { set(s.copy(opacity = it.toDouble())) }
        LabeledSlider("ぼかし", s.blur.toFloat(), 0f..1f) { set(s.copy(blur = it.toDouble())) }
        LabeledSlider("ずれ", s.offset.toFloat(), 0f..1f) { set(s.copy(offset = it.toDouble())) }
    }
}
