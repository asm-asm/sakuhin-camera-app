package com.sammy.sakuhincamera

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sammy.sakuhincamera.core.AlphaMask
import com.sammy.sakuhincamera.core.BackgroundCatalog
import com.sammy.sakuhincamera.core.BackgroundItem
import com.sammy.sakuhincamera.core.BackgroundSource
import com.sammy.sakuhincamera.core.Brush
import com.sammy.sakuhincamera.core.BrushStroke
import com.sammy.sakuhincamera.core.Composition
import com.sammy.sakuhincamera.core.EdgeRefiner
import com.sammy.sakuhincamera.core.EdgeSoftness
import com.sammy.sakuhincamera.core.Renderer
import com.sammy.sakuhincamera.core.RgbImage
import com.sammy.sakuhincamera.core.SubjectMaps
import com.sammy.sakuhincamera.core.SubjectTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

enum class Screen { CAMERA, CUTOUT, REFINE, BACKGROUND, EXPORT }

/** 撮影から書き出しまでの 1 枚分の編集状態。元写真の画素（photo）は最後まで変えない。 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

    var screen by mutableStateOf(Screen.CAMERA)

    /** 撮影した元写真（向きは正しく直したもの）。 */
    var photo: RgbImage? = null
        private set
    /** 画面表示用の縮小した元写真。 */
    var displayPhoto by mutableStateOf<RgbImage?>(null)
        private set

    // ---- 切り抜き ----
    var subjects by mutableStateOf<SubjectMaps?>(null)
        private set
    var selected by mutableStateOf(setOf<Int>())
    var segmenting by mutableStateOf(false)
        private set
    var segmentError by mutableStateOf<String?>(null)
        private set

    // ---- 縁の調整 ----
    private var baseMask: AlphaMask? = null
    private val featherCache = mutableMapOf<EdgeSoftness, AlphaMask>()
    var softness by mutableStateOf(EdgeSoftness.MEDIUM)
        private set
    val strokes = mutableStateListOf<BrushStroke>()
    /** 縁の調整を終えた最終マスク（元写真と同じ大きさ）。 */
    var mask: AlphaMask? = null
        private set
    var maskVersion by mutableIntStateOf(0)
        private set
    var working by mutableStateOf(false)
        private set

    // ---- 背景・書き出し ----
    val items: List<BackgroundItem> = runCatching {
        BackgroundCatalog.parse(context.assets.open("manifest.json").bufferedReader().readText())
    }.getOrDefault(emptyList())
    /** 背景・調整・影・配置・比率。背景の選択は撮り直しても引き継ぐ。 */
    var composition by mutableStateOf(Composition(backgroundId = BackgroundCatalog.defaultItem(items)?.id))
    /** 指で動かしている途中の配置（スナップ前）。 */
    var rawTransform by mutableStateOf(SubjectTransform())
        private set
    private val backgroundCache = mutableMapOf<String, RgbImage>()

    val selectedItem: BackgroundItem? get() = items.firstOrNull { it.id == composition.backgroundId }

    fun onCaptured(bitmap: Bitmap) {
        val image = Bitmaps.toImage(bitmap)
        photo = image
        displayPhoto = Bitmaps.scaled(image, DISPLAY_SIDE)
        subjects = null
        selected = emptySet()
        baseMask = null
        featherCache.clear()
        strokes.clear()
        mask = null
        rawTransform = SubjectTransform()
        composition = composition.copy(transform = SubjectTransform())
        screen = Screen.CUTOUT
        segment()
    }

    /** 画面の見た目の確認用（テスト）：切り抜き済みの状態を直接用意する。 */
    internal fun loadPrepared(image: RgbImage, maps: SubjectMaps, finalMask: AlphaMask) {
        photo = image
        displayPhoto = Bitmaps.scaled(image, DISPLAY_SIDE)
        subjects = maps
        selected = (0 until maps.count).toSet()
        baseMask = finalMask
        featherCache.clear()
        strokes.clear()
        mask = finalMask
        maskVersion++
    }

    fun retake() {
        photo = null
        displayPhoto = null
        subjects = null
        mask = null
        screen = Screen.CAMERA
    }

    fun segment() {
        val image = photo ?: return
        segmenting = true
        segmentError = null
        viewModelScope.launch {
            try {
                val maps = Segmenter.segment(image)
                subjects = maps
                selected = (0 until maps.count).toSet()
            } catch (e: Exception) {
                segmentError = if (e.message?.contains("download", ignoreCase = true) == true) {
                    "切り抜きの準備中です（初回は端末にモデルを取り込みます）。少し待ってから「もう一度」を押してください"
                } else {
                    "切り抜きに失敗しました：${e.message}"
                }
            } finally {
                segmenting = false
            }
        }
    }

    fun toggleSubject(nx: Float, ny: Float) {
        val k = subjects?.subjectAt(nx, ny) ?: return
        if (k < 0) return
        selected = if (k in selected) selected - k else selected + k
    }

    /** 選んだ被写体からマスクを作り、縁の調整へ。見つからなかったときは空のマスクから塗って指定する。 */
    fun proceedToRefine() {
        val image = photo ?: return
        working = true
        viewModelScope.launch {
            baseMask = withContext(Dispatchers.Default) {
                subjects?.combinedMask(selected, image.width, image.height) ?: AlphaMask(image.width, image.height)
            }
            featherCache.clear()
            strokes.clear()
            rebuildMask()
            screen = Screen.REFINE
        }
    }

    fun changeSoftness(value: EdgeSoftness) {
        if (value == softness) return
        softness = value
        working = true
        viewModelScope.launch { rebuildMask() }
    }

    fun addStroke(stroke: BrushStroke) {
        strokes += stroke
        val current = mask ?: return
        viewModelScope.launch {
            withContext(Dispatchers.Default) { Brush.paint(current, stroke) }
            maskVersion++
        }
    }

    fun undoStroke() {
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.lastIndex)
        working = true
        viewModelScope.launch { rebuildMask() }
    }

    /** なめらかにしたマスク（縁の強さごとに覚えておく）にブラシを塗り直す。 */
    private suspend fun rebuildMask() {
        val image = photo ?: return
        val base = baseMask ?: return
        val s = softness
        val strokesNow = strokes.toList()
        mask = withContext(Dispatchers.Default) {
            val feathered = featherCache.getOrPut(s) {
                EdgeRefiner.feather(base, image, s.radius(max(image.width, image.height)))
            }
            feathered.copy().also { m -> strokesNow.forEach { Brush.paint(m, it) } }
        }
        maskVersion++
        working = false
    }

    fun proceedToBackground() {
        screen = Screen.BACKGROUND
    }

    fun selectBackground(item: BackgroundItem) {
        composition = composition.copy(backgroundId = item.id)
    }

    fun updateTransform(transform: SubjectTransform) {
        rawTransform = transform
        composition = composition.copy(transform = transform.snapped())
    }

    fun resetTransform() = updateTransform(SubjectTransform())

    /** 背景の元（画像はデコードして覚えておく）。 */
    fun backgroundSource(maxSide: Int): BackgroundSource {
        val item = selectedItem ?: return BackgroundSource.None
        return when (val kind = item.kind) {
            is BackgroundItem.Kind.Image -> {
                val key = "${kind.file}@$maxSide"
                val image = backgroundCache.getOrPut(key) {
                    if (backgroundCache.size > 6) backgroundCache.clear()
                    Bitmaps.loadAsset(context, kind.file, maxSide) ?: RgbImage(1, 1, intArrayOf(-0x0d0d0e))
                }
                BackgroundSource.Image(image)
            }
            is BackgroundItem.Kind.Solid -> BackgroundSource.Solid(kind.color)
            is BackgroundItem.Kind.Gradient -> BackgroundSource.Gradient(kind.top, kind.bottom)
            BackgroundItem.Kind.BlurredOriginal -> BackgroundSource.BlurredOriginal
        }
    }

    /** 画面用の仕上がり（縮小した写真とマスクで、書き出しと同じ処理をかける）。 */
    fun renderPreview(composition: Composition): Bitmap? {
        val small = displayPhoto ?: return null
        val full = photo ?: return null
        val m = mask ?: return null
        val smallMask = Bitmaps.scaledMask(m, small.width, small.height)
        val f = small.width.toDouble() / full.width
        val t = composition.transform
        val scaled = composition.copy(transform = t.copy(offsetX = t.offsetX * f, offsetY = t.offsetY * f))
        val out = Renderer.render(small, smallMask, backgroundSource(DISPLAY_SIDE), scaled)
        return Bitmaps.toBitmap(out)
    }

    /** 書き出し用（元の解像度）。 */
    fun renderFull(composition: Composition): Bitmap? {
        val full = photo ?: return null
        val m = mask ?: return null
        val out = Renderer.render(full, m, backgroundSource(4096), composition)
        return Bitmaps.toBitmap(out)
    }

    /** 表示用のマスク（縮小）。 */
    fun displayMask(): AlphaMask? {
        val small = displayPhoto ?: return null
        val m = mask ?: return null
        return Bitmaps.scaledMask(m, small.width, small.height)
    }

    /** 画面上の写真の 1 画素 → 元写真の画素数。 */
    val displayToFull: Double
        get() {
            val small = displayPhoto ?: return 1.0
            val full = photo ?: return 1.0
            return full.width.toDouble() / small.width
        }

    fun startOver() {
        retake()
    }

    companion object {
        const val DISPLAY_SIDE = 1200
    }
}
