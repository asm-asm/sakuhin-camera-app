package com.sammy.sakuhincamera

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.sammy.sakuhincamera.ui.BackgroundScreen
import com.sammy.sakuhincamera.ui.CameraScreen
import com.sammy.sakuhincamera.ui.CutoutScreen
import com.sammy.sakuhincamera.ui.ExportScreen
import com.sammy.sakuhincamera.ui.RefineScreen

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                    BackHandler(enabled = vm.screen != Screen.CAMERA) {
                        vm.screen = when (vm.screen) {
                            Screen.EXPORT -> Screen.BACKGROUND
                            Screen.BACKGROUND -> Screen.REFINE
                            Screen.REFINE -> Screen.CUTOUT
                            else -> { vm.retake(); Screen.CAMERA }
                        }
                    }
                    when (vm.screen) {
                        Screen.CAMERA -> CameraScreen(onCaptured = vm::onCaptured)
                        Screen.CUTOUT -> CutoutScreen(vm)
                        Screen.REFINE -> RefineScreen(vm)
                        Screen.BACKGROUND -> BackgroundScreen(vm)
                        Screen.EXPORT -> ExportScreen(vm)
                    }
                }
            }
        }
    }
}
