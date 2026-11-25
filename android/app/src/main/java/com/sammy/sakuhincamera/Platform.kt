package com.sammy.sakuhincamera

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.sammy.sakuhincamera.core.AlphaMask
import com.sammy.sakuhincamera.core.BackgroundItem
import com.sammy.sakuhincamera.core.RgbImage
import com.sammy.sakuhincamera.core.SubjectMaps
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Bitmap と画素データの行き来。 */
object Bitmaps {
    fun toImage(bitmap: Bitmap): RgbImage {
        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return RgbImage(source.width, source.height, pixels)
    }

    fun toBitmap(image: RgbImage): Bitmap =
        Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)

    /** 表示用に縮小する（長辺 maxSide 以下）。 */
    fun scaled(image: RgbImage, maxSide: Int): RgbImage {
        val f = min(1.0, maxSide.toDouble() / max(image.width, image.height))
        if (f >= 1.0) return image
        val w = max(1, (image.width * f).roundToInt())
        val h = max(1, (image.height * f).roundToInt())
        return toImage(Bitmap.createScaledBitmap(toBitmap(image), w, h, true))
    }

    /** マスクを表示用に縮小する（面積平均）。 */
    fun scaledMask(mask: AlphaMask, w: Int, h: Int): AlphaMask {
        val plane = IntArray(mask.width * mask.height) { mask[it] }
        val small = com.sammy.sakuhincamera.core.Planes.downscale(plane, mask.width, mask.height, w, h)
        return AlphaMask(w, h, ByteArray(w * h) { small[it].toByte() })
    }

    fun loadAsset(context: Context, file: String, maxSide: Int = 4096): RgbImage? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open(file).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.assets.open(file).use { BitmapFactory.decodeStream(it, null, options) }?.let(::toImage)
    }.getOrNull()

    fun loadThumbnail(context: Context, item: BackgroundItem): Bitmap? {
        val kind = item.kind as? BackgroundItem.Kind.Image ?: return null
        return runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
            context.assets.open(kind.file).use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }
}

/** 端末内（ML Kit）で作品を切り抜く。写真は外部に送らない。 */
object Segmenter {
    /** 切り抜きは縮小した写真で行い、縁は元の解像度でなめらかにする。 */
    private const val WORK_SIDE = 1280

    suspend fun segment(photo: RgbImage): SubjectMaps {
        val f = min(1.0, WORK_SIDE.toDouble() / max(photo.width, photo.height))
        val w = max(1, (photo.width * f).roundToInt())
        val h = max(1, (photo.height * f).roundToInt())
        val small = Bitmap.createScaledBitmap(Bitmaps.toBitmap(photo), w, h, true)
        val options = SubjectSegmenterOptions.Builder()
            .enableMultipleSubjects(
                SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build()
            )
            .build()
        val client = SubjectSegmentation.getClient(options)
        try {
            val result = client.process(InputImage.fromBitmap(small, 0)).await()
            val maps = result.subjects.mapNotNull { subject ->
                val buffer = subject.confidenceMask ?: return@mapNotNull null
                buffer.rewind()
                val full = FloatArray(w * h)
                for (y in 0 until subject.height) {
                    val fy = subject.startY + y
                    for (x in 0 until subject.width) {
                        val value = buffer.get()
                        val fx = subject.startX + x
                        if (fx in 0 until w && fy in 0 until h) full[fy * w + fx] = value
                    }
                }
                full
            }
            return SubjectMaps(w, h, maps)
        } finally {
            client.close()
        }
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/** 写真に保存・共有。 */
object Exporter {
    private const val QUALITY = 95

    /** 写真（ピクチャ/作品用カメラ）に保存する。Android 10 以降は権限なしで追加できる。 */
    fun saveToGallery(context: Context, bitmap: Bitmap): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "sakuhin_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/作品用カメラ")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) } ?: false
        }.getOrDefault(false)
        if (!ok) {
            resolver.delete(uri, null, null)
            return false
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return true
    }

    fun share(context: Context, bitmap: Bitmap) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "sakuhin_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "共有").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
