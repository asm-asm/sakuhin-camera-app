package com.sammy.sakuhincamera.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sammy.sakuhincamera.EditorViewModel
import com.sammy.sakuhincamera.Exporter
import com.sammy.sakuhincamera.Screen
import com.sammy.sakuhincamera.core.AppText
import com.sammy.sakuhincamera.core.AspectRatio
import com.sammy.sakuhincamera.core.Composition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 書き出し画面。比率を選び、写真に保存・共有・説明文のコピー。 */
@Composable
fun ExportScreen(vm: EditorViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // 同じ仕上がりなら作り直さない
    var rendered by remember { mutableStateOf<Pair<Composition, Bitmap>?>(null) }
    val caption = AppText.caption(vm.selectedItem?.aiGenerated ?: false)

    fun withFullImage(action: (Bitmap) -> Unit) {
        val composition = vm.composition
        val cached = rendered?.takeIf { it.first == composition }?.second
        if (cached != null) { action(cached); return }
        busy = true
        status = "書き出し中…"
        scope.launch {
            val bitmap = runCatching { withContext(Dispatchers.Default) { vm.renderFull(composition) } }.getOrNull()
            busy = false
            if (bitmap == null) {
                status = "書き出しに失敗しました"
            } else {
                rendered = composition to bitmap
                status = null
                action(bitmap)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopTitle("書き出し", onBack = { vm.screen = Screen.BACKGROUND })
        Segmented(AspectRatio.entries, vm.composition.ratio, { it.label }, { vm.composition = vm.composition.copy(ratio = it) },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        CompositionPreview(vm, editable = false, modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp))
        Column(Modifier.fillMaxWidth().background(Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PanelLight).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(caption, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("コピー", color = Accent, fontSize = 14.sp, modifier = Modifier.clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("説明文", caption))
                    status = "説明文をコピーしました"
                })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                PillButton("写真に保存", enabled = !busy) {
                    withFullImage { bitmap ->
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { Exporter.saveToGallery(context, bitmap) }
                            status = if (ok) "写真（ピクチャ › 作品用カメラ）に保存しました" else "保存に失敗しました"
                        }
                    }
                }
                PillButton("共有", primary = false, enabled = !busy) {
                    withFullImage { bitmap -> Exporter.share(context, bitmap) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp), color = Color.White, strokeWidth = 2.dp)
                status?.let { Text(it, color = Color.LightGray, fontSize = 13.sp) }
            }
            Text(
                "新しく撮る",
                color = Accent,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).clickable(onClick = vm::startOver),
            )
        }
    }
}
