package botix.dev.detectorlicenseplateocr

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import botix.dev.detectorlicenseplateocr.scanner.CameraPermissionScreen
import botix.dev.detectorlicenseplateocr.scanner.ScannerScreen
import botix.dev.detectorlicenseplateocr.scanner.ScannerViewModel

@Composable
actual fun ScannerRoute() {
    val context = LocalContext.current
    var granted by rememberSaveable { mutableStateOf(context.hasCameraPermission()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }
    // Picks up a permission granted from the system Settings screen.
    LifecycleResumeEffect(Unit) {
        granted = context.hasCameraPermission()
        onPauseOrDispose { }
    }

    if (!granted) {
        val activity = context.findActivity()
        val permanentlyDenied = asked && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        CameraPermissionScreen(permanentlyDenied) {
            if (permanentlyDenied) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            } else {
                launcher.launch(Manifest.permission.CAMERA)
            }
        }
        return
    }

    val viewModel = viewModel { ScannerViewModel(context.applicationContext as Application) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    DisposableEffect(state.isScanning) {
        view.keepScreenOn = state.isScanning
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(lifecycleOwner) {
        viewModel.controller.bindToLifecycle(lifecycleOwner)
        onDispose { viewModel.controller.unbind() }
    }

    ScannerScreen(state, viewModel) { modifier ->
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    controller = viewModel.controller
                }
            },
            modifier = modifier.onSizeChanged { viewModel.onViewportChanged(it.width, it.height) },
            onRelease = { it.controller = null },
        )
    }
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
