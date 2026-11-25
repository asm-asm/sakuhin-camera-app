package com.sammy.sakuhincamera

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.sammy.sakuhincamera.core.AlphaMask
import com.sammy.sakuhincamera.core.RgbImage
import com.sammy.sakuhincamera.core.SubjectMaps
import com.sammy.sakuhincamera.ui.BackgroundScreen
import com.sammy.sakuhincamera.ui.CutoutScreen
import com.sammy.sakuhincamera.ui.ExportScreen
import com.sammy.sakuhincamera.ui.RefineScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 画面の見た目を PC 上で画像に書き出す（実機・エミュレーターなしで確認するため）。
 * 写真はストア画像と同じデモ写真。マスクは ML Kit の代わりに色で大まかに作る（見た目の確認用）。
 * 出力：android/app/build/screenshots フォルダの PNG
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val out = File("build/screenshots").apply { mkdirs() }

    private fun prepared(): EditorViewModel {
        val vm = EditorViewModel(ApplicationProvider.getApplicationContext<Application>())
        val bitmap = BitmapFactory.decodeFile(File("../../ScreenshotAssets/demo_photo.jpg").path)
        val image = Bitmaps.toImage(bitmap)
        val mask = roughMask(image)
        val confidence = FloatArray(image.width / 4 * (image.height / 4)) { i ->
            val x = i % (image.width / 4) * 4
            val y = i / (image.width / 4) * 4
            mask[y * image.width + x] / 255f
        }
        vm.loadPrepared(image, SubjectMaps(image.width / 4, image.height / 4, listOf(confidence)), mask)
        return vm
    }

    /** 色の濃い部分・暗い部分を作品とみなす（デモ写真の靴下用の大まかな切り抜き）。 */
    private fun roughMask(image: RgbImage): AlphaMask {
        val mask = AlphaMask(image.width, image.height)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val inCenter = x > image.width * 0.12 && x < image.width * 0.9 && y > image.height * 0.18 && y < image.height * 0.78
            if (!inCenter) continue
            val p = image.pixels[y * image.width + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            if (hi - lo > 45 || hi < 90) mask[y * image.width + x] = 255
        }
        return mask
    }

    private fun shoot(name: String, vm: EditorViewModel, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
            }
        }
        // プレビューの合成はバックグラウンドで行うので、終わるまで待つ
        repeat(8) {
            compose.waitForIdle()
            Thread.sleep(500)
        }
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun cutout() {
        val vm = prepared()
        shoot("1_cutout", vm) { CutoutScreen(vm) }
    }

    @Test
    fun refine() {
        val vm = prepared()
        shoot("2_refine", vm) { RefineScreen(vm) }
    }

    @Test
    fun background() {
        val vm = prepared()
        vm.items.firstOrNull { it.id == "angle_03" }?.let(vm::selectBackground)
        shoot("3_background", vm) { BackgroundScreen(vm) }
    }

    @Test
    fun export() {
        val vm = prepared()
        vm.items.firstOrNull { it.id == "yarn_01" }?.let(vm::selectBackground)
        vm.composition = vm.composition.copy(ratio = com.sammy.sakuhincamera.core.AspectRatio.PORTRAIT_4X5)
        shoot("4_export", vm) { ExportScreen(vm) }
    }
}
