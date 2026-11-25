package com.sammy.sakuhincamera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.Image
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 12MP を超える写真は縮める（合成のメモリが足りなくなるため）。 */
private const val MAX_PIXELS = 12_200_000

@Composable
fun CameraScreen(onCaptured: (Bitmap) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    var captured by remember { mutableStateOf<Bitmap?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val shot = captured
        when {
            !granted -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("作品を撮影するためにカメラを使用します", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("カメラを許可") }
            }
            shot != null -> CapturedPreview(shot, onRetake = { captured = null }, onNext = { onCaptured(shot) })
            else -> LiveCamera(onCaptured = { captured = it })
        }
    }
}

@Composable
private fun CapturedPreview(bitmap: Bitmap, onRetake: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopTitle("撮影")
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            PillButton("撮り直す", primary = false, onClick = onRetake)
            PillButton("次へ", onClick = onNext)
        }
    }
}

@Composable
private fun LiveCamera(onCaptured: (Bitmap) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(selector)
            .build()
    }
    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var busy by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build()
                )
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            }.onFailure { error = "カメラを開けませんでした：${it.message}" }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }

    // 端末の向きに合わせて写真を回す（真上から撮るときは縦のまま）
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    val tilt = rememberTilt(context)

    Column(Modifier.fillMaxSize()) {
        TopTitle("撮影")
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                AndroidView({ previewView }, Modifier.fillMaxSize())
                Guides(tilt)
                Box(
                    Modifier.fillMaxSize().pointerInput(camera) {
                        detectTapGestures { offset ->
                            val cam = camera ?: return@detectTapGestures
                            val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                            cam.cameraControl.startFocusAndMetering(
                                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).build()
                            )
                            focusPoint = offset
                        }
                    }
                )
                focusPoint?.let { p ->
                    val density = LocalDensity.current
                    val half = with(density) { 36.dp.toPx() }
                    Box(
                        Modifier
                            .offset { IntOffset((p.x - half).roundToInt(), (p.y - half).roundToInt()) }
                            .size(72.dp)
                            .border(2.dp, Color(0xFFFFD54F))
                    )
                    LaunchedEffect(p) { delay(1200); focusPoint = null }
                }
            }
            error?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Box(
                    Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .background(Color.White, CircleShape)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                busy = true
                                imageCapture.takePicture(
                                    ContextCompat.getMainExecutor(context),
                                    object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            val bitmap = image.use { proxy -> uprightBitmap(proxy) }
                                            busy = false
                                            onCaptured(bitmap)
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            busy = false
                                            error = "撮影に失敗しました：${exception.message}"
                                        }
                                    },
                                )
                            }
                        }
                )
            }
        }
    }
}

/** 撮影データを正しい向きの Bitmap にする（回転は画素の並べ替えだけ。12MP を超えるときだけ縮める）。 */
private fun uprightBitmap(proxy: ImageProxy): Bitmap {
    var bitmap = proxy.toBitmap()
    val rotation = proxy.imageInfo.rotationDegrees
    if (rotation != 0) {
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
    }
    val pixels = bitmap.width.toLong() * bitmap.height
    if (pixels > MAX_PIXELS) {
        val f = sqrt(MAX_PIXELS.toDouble() / pixels)
        bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).roundToInt(), (bitmap.height * f).roundToInt(), true)
    }
    return bitmap
}

/** 端末の傾き。roll は画面の左右の傾き（度）、flat は真上・真下を向いているか。 */
private data class Tilt(val roll: Float, val flat: Boolean, val flatLevel: Boolean)

@Composable
private fun rememberTilt(context: Context): Tilt {
    var roll by remember { mutableFloatStateOf(0f) }
    var flat by remember { mutableStateOf(false) }
    var flatLevel by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                val g = sqrt(x * x + y * y + z * z).coerceAtLeast(0.1f)
                flat = abs(z) / g > 0.85f
                flatLevel = abs(x) / g < 0.03f && abs(y) / g < 0.03f
                roll = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager.unregisterListener(listener) }
    }
    return Tilt(roll, flat, flatLevel)
}

/** 中央のガイド枠と水平ガイド。 */
@Composable
private fun Guides(tilt: Tilt) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val frameW = w * 0.72f
        val frameH = h * 0.62f
        val left = (w - frameW) / 2
        val top = (h - frameH) / 2
        val arm = frameW * 0.12f
        val stroke = 3.dp.toPx()
        val white = Color.White.copy(alpha = 0.9f)
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(white, Offset(x, y), Offset(x + arm * dx, y), stroke)
            drawLine(white, Offset(x, y), Offset(x, y + arm * dy), stroke)
        }
        corner(left, top, 1f, 1f)
        corner(left + frameW, top, -1f, 1f)
        corner(left, top + frameH, 1f, -1f)
        corner(left + frameW, top + frameH, -1f, -1f)

        val center = Offset(w / 2, h / 2)
        if (tilt.flat) {
            // 真上から撮るとき：中央の円と十字。水平なら緑
            val color = if (tilt.flatLevel) Color(0xFF4CAF50) else white
            drawCircle(color, radius = 18.dp.toPx(), center = center, style = Stroke(stroke))
            drawLine(color, center - Offset(10.dp.toPx(), 0f), center + Offset(10.dp.toPx(), 0f), stroke)
            drawLine(color, center - Offset(0f, 10.dp.toPx()), center + Offset(0f, 10.dp.toPx()), stroke)
        } else {
            // 縦・横どちらで持っても、近い方の水平からのずれを表示する
            val nearest = (tilt.roll / 90f).roundToInt() * 90f
            val deviation = tilt.roll - nearest
            val level = abs(deviation) < 1.5f
            val color = if (level) Color(0xFF4CAF50) else white
            rotate(-deviation, center) {
                drawLine(color, center - Offset(w * 0.18f, 0f), center + Offset(w * 0.18f, 0f), stroke)
            }
            drawLine(white.copy(alpha = 0.5f), center - Offset(w * 0.06f, 0f), center + Offset(w * 0.06f, 0f), stroke)
        }
    }
}

@Composable
fun TopTitle(title: String, onBack: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
        if (onBack != null) {
            Text(
                "‹ 戻る",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.CenterStart).padding(8.dp).pointerInput(Unit) {
                    detectTapGestures { onBack() }
                },
            )
        }
        Text(title, color = Color.White, fontSize = 17.sp, modifier = Modifier.align(Alignment.Center))
    }
}
