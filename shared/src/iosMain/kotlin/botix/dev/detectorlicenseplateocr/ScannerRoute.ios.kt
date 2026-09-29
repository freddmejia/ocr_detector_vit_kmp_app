package botix.dev.detectorlicenseplateocr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.UIKitView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import botix.dev.detectorlicenseplateocr.scanner.CameraPermissionScreen
import botix.dev.detectorlicenseplateocr.scanner.IosScannerViewModel
import botix.dev.detectorlicenseplateocr.scanner.ScannerScreen
import platform.AVFoundation.AVAuthorizationStatus
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

@Composable
actual fun ScannerRoute() {
    var status by remember { mutableStateOf(cameraAuthorization()) }
    // Asks on first launch; picks up a permission changed in Settings when the app comes back.
    LifecycleResumeEffect(Unit) {
        status = cameraAuthorization()
        if (status == AVAuthorizationStatusNotDetermined) {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) {
                dispatch_async(dispatch_get_main_queue()) { status = cameraAuthorization() }
            }
        }
        onPauseOrDispose { }
    }

    if (status != AVAuthorizationStatusAuthorized) {
        // iOS asks only once: after a denial the only way back is the Settings app.
        val denied = status != AVAuthorizationStatusNotDetermined
        CameraPermissionScreen(permanentlyDenied = denied) {
            if (denied) {
                NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
                    UIApplication.sharedApplication.openURL(it, options = emptyMap<Any?, Any>(), completionHandler = null)
                }
            } else {
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) {
                    dispatch_async(dispatch_get_main_queue()) { status = cameraAuthorization() }
                }
            }
        }
        return
    }

    val viewModel = viewModel { IosScannerViewModel() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(state.isScanning) {
        UIApplication.sharedApplication.idleTimerDisabled = state.isScanning
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
    // The camera runs only while the screen is visible and the app is in the foreground.
    LifecycleResumeEffect(viewModel) {
        viewModel.camera.start()
        onPauseOrDispose { viewModel.camera.stop() }
    }

    ScannerScreen(state, viewModel) { modifier ->
        UIKitView(
            factory = { viewModel.camera.previewView },
            modifier = modifier.onSizeChanged { viewModel.onViewportChanged(it.width, it.height) },
        )
    }
}

private fun cameraAuthorization(): AVAuthorizationStatus = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
