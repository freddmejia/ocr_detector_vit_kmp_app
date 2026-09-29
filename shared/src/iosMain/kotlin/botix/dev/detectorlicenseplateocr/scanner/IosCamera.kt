@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package botix.dev.detectorlicenseplateocr.scanner

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.reinterpret
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePosition
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDevicePositionFront
import platform.AVFoundation.AVCaptureDeviceRotationCoordinator
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInDualCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInDualWideCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInTripleCamera
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureExposureModeContinuousAutoExposure
import platform.AVFoundation.AVCaptureFocusModeContinuousAutoFocus
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoDataOutput
import platform.AVFoundation.AVCaptureVideoDataOutputSampleBufferDelegateProtocol
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.AVFoundation.displayVideoZoomFactorMultiplier
import platform.AVFoundation.exposureMode
import platform.AVFoundation.exposurePointOfInterest
import platform.AVFoundation.focusMode
import platform.AVFoundation.focusPointOfInterest
import platform.AVFoundation.hasTorch
import platform.AVFoundation.isExposureModeSupported
import platform.AVFoundation.isExposurePointOfInterestSupported
import platform.AVFoundation.isFocusModeSupported
import platform.AVFoundation.isFocusPointOfInterestSupported
import platform.AVFoundation.maxAvailableVideoZoomFactor
import platform.AVFoundation.minAvailableVideoZoomFactor
import platform.AVFoundation.position
import platform.AVFoundation.setExposureMode
import platform.AVFoundation.setExposurePointOfInterest
import platform.AVFoundation.setFocusMode
import platform.AVFoundation.setFocusPointOfInterest
import platform.AVFoundation.setTorchMode
import platform.AVFoundation.setVideoZoomFactor
import platform.AVFoundation.videoZoomFactor
import platform.CoreFoundation.CFRetain
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSSelectorFromString
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIGestureRecognizerStateBegan
import platform.UIKit.UIGestureRecognizerStateChanged
import platform.UIKit.UIPinchGestureRecognizer
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t
import kotlin.concurrent.Volatile

/** A locked `32BGRA` camera frame, valid only during [IosCamera]'s frame callback. */
internal class CameraFrame(
    val base: CPointer<UByteVar>,
    val bytesPerRow: Int,
    val width: Int,
    val height: Int,
    /** Clockwise degrees (0, 90, 180, 270) that show the buffer the way the preview does. */
    val rotation: Int,
    val lens: CameraLens,
)

/** What the controls need to know about the active camera. Zoom ratios are as shown to the user (1x = wide). */
internal data class CameraInfo(
    val lens: CameraLens,
    val canSwitchLens: Boolean,
    val hasTorch: Boolean,
    val torchOn: Boolean,
    val zoom: ZoomInfo,
)

/**
 * AVFoundation capture for the scanner: the iOS counterpart of the Android `LifecycleCameraController`.
 *
 * Session changes run on a private serial queue (`startRunning` blocks). Frames arrive on [analysisQueue], where
 * [onFrame] runs synchronously; `alwaysDiscardsLateVideoFrames` drops the frames that arrive meanwhile, so each
 * reading uses the newest frame (Android's KEEP_ONLY_LATEST). Frames are 1920x1080 BGRA in the sensor's orientation.
 */
internal class IosCamera(
    private val analysisQueue: dispatch_queue_t,
    private val onFrame: (CameraFrame) -> Unit,
    /** Called on the main thread whenever [info] changes. */
    private val onInfo: (CameraInfo?) -> Unit,
) {
    private val sessionQueue = dispatch_queue_create("plate-camera-session", null)
    private val session = AVCaptureSession()
    private val output = AVCaptureVideoDataOutput()
    private val frameDelegate = FrameDelegate()
    private val previewLayer = AVCaptureVideoPreviewLayer(session = session).apply {
        videoGravity = AVLayerVideoGravityResizeAspectFill
    }
    private var input: AVCaptureDeviceInput? = null
    @Volatile private var device: AVCaptureDevice? = null
    private var rotationCoordinator: AVCaptureDeviceRotationCoordinator? = null
    private var configured = false

    /** Read on the analysis queue, written on the main thread when the layout or the camera changes. */
    @Volatile private var frameRotation = 90
    @Volatile private var lens = CameraLens.BACK

    /** The preview, created once and reused by the Compose `UIKitView`. */
    val previewView: UIView = CameraPreviewView(previewLayer, ::updateRotation, ::onPinch, ::onTap)

    var info: CameraInfo? = null
        private set

    private var pinchStartRatio = 1f

    /** Configures the session with the back camera (or the front one if there is no back camera) and starts it. */
    fun start() {
        dispatch_async(sessionQueue) {
            if (!configured) {
                configured = true
                configure(if (findDevice(AVCaptureDevicePositionBack) != null) CameraLens.BACK else CameraLens.FRONT)
            }
            if (input != null && !session.running) session.startRunning()
        }
    }

    fun stop() {
        dispatch_async(sessionQueue) {
            if (session.running) session.stopRunning()
            // The torch goes off with the session.
            onMain { info = info?.copy(torchOn = false); onInfo(info) }
        }
    }

    fun switchLens() {
        val target = if (lens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        dispatch_async(sessionQueue) { configure(target) }
    }

    /** [ratio] as shown to the user; the device's own zoom factor is `ratio / displayVideoZoomFactorMultiplier`. */
    fun setZoom(ratio: Float) {
        val device = device ?: return
        val zoom = info?.zoom ?: return
        val clamped = ratio.coerceIn(zoom.min, zoom.max)
        configureDevice(device) { setVideoZoomFactor(clamped / device.displayVideoZoomFactorMultiplier) }
        info = info?.copy(zoom = zoom.copy(ratio = clamped))
        onInfo(info)
    }

    fun setTorch(on: Boolean) {
        val device = device ?: return
        if (!device.hasTorch) return
        configureDevice(device) { setTorchMode(if (on) AVCaptureTorchModeOn else AVCaptureTorchModeOff) }
        info = info?.copy(torchOn = on)
        onInfo(info)
    }

    /** Runs on the session queue. */
    private fun configure(target: CameraLens) {
        val position = if (target == CameraLens.BACK) AVCaptureDevicePositionBack else AVCaptureDevicePositionFront
        val newDevice = findDevice(position) ?: run {
            onMain { onInfo(null) }
            return
        }
        val newInput = AVCaptureDeviceInput.deviceInputWithDevice(newDevice, null) ?: run {
            onMain { onInfo(null) }
            return
        }
        session.beginConfiguration()
        input?.let(session::removeInput)
        if (session.canSetSessionPreset(AVCaptureSessionPreset1920x1080)) {
            session.sessionPreset = AVCaptureSessionPreset1920x1080
        }
        if (session.canAddInput(newInput)) session.addInput(newInput)
        if (output !in session.outputs && session.canAddOutput(output)) {
            output.videoSettings = mapOf<Any?, Any?>(pixelFormatKey() to kCVPixelFormatType_32BGRA)
            output.alwaysDiscardsLateVideoFrames = true
            output.setSampleBufferDelegate(frameDelegate, analysisQueue)
            session.addOutput(output)
        }
        session.commitConfiguration()
        input = newInput
        device = newDevice

        // Continuous focus and exposure; on virtual devices, start at the wide lens (1x) rather than the ultra wide.
        val multiplier = newDevice.displayVideoZoomFactorMultiplier
        val minRatio = (newDevice.minAvailableVideoZoomFactor * multiplier).toFloat()
        val maxRatio = (newDevice.maxAvailableVideoZoomFactor * multiplier).toFloat().coerceAtMost(MAX_ZOOM_RATIO)
        val startRatio = 1f.coerceIn(minRatio, maxRatio)
        configureDevice(newDevice) {
            if (isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus)) setFocusMode(AVCaptureFocusModeContinuousAutoFocus)
            if (isExposureModeSupported(AVCaptureExposureModeContinuousAutoExposure)) {
                setExposureMode(AVCaptureExposureModeContinuousAutoExposure)
            }
            setVideoZoomFactor(startRatio / multiplier)
        }
        val canSwitch = findDevice(AVCaptureDevicePositionBack) != null && findDevice(AVCaptureDevicePositionFront) != null
        onMain {
            lens = target
            rotationCoordinator = AVCaptureDeviceRotationCoordinator(newDevice, previewLayer)
            updateRotation()
            info = CameraInfo(target, canSwitch, newDevice.hasTorch, torchOn = false, ZoomInfo(startRatio, minRatio, maxRatio))
            onInfo(info)
        }
    }

    /**
     * Main thread: the preview is rotated for the current interface orientation, and frames are cropped with the
     * same angle so the guide covers what the user sees. Runs on every layout (rotation included) and camera change.
     */
    private fun updateRotation() {
        val angle = rotationCoordinator?.videoRotationAngleForHorizonLevelPreview ?: return
        previewLayer.connection?.let { if (it.isVideoRotationAngleSupported(angle)) it.videoRotationAngle = angle }
        frameRotation = rightAngle(angle)
    }

    private fun onPinch(recognizer: UIPinchGestureRecognizer) {
        val zoom = info?.zoom ?: return
        when (recognizer.state) {
            UIGestureRecognizerStateBegan -> pinchStartRatio = zoom.ratio
            UIGestureRecognizerStateChanged -> setZoom(pinchStartRatio * recognizer.scale.toFloat())
            else -> Unit
        }
    }

    /** Focuses and meters on the tapped point, then keeps adjusting continuously from there. */
    private fun onTap(recognizer: UITapGestureRecognizer) {
        val device = device ?: return
        val point = previewLayer.captureDevicePointOfInterestForPoint(recognizer.locationInView(previewView))
        configureDevice(device) {
            if (isFocusPointOfInterestSupported()) {
                setFocusPointOfInterest(point)
                setFocusMode(AVCaptureFocusModeContinuousAutoFocus)
            }
            if (isExposurePointOfInterestSupported()) {
                setExposurePointOfInterest(point)
                setExposureMode(AVCaptureExposureModeContinuousAutoExposure)
            }
        }
    }

    private inner class FrameDelegate : NSObject(), AVCaptureVideoDataOutputSampleBufferDelegateProtocol {
        @ObjCSignatureOverride
        override fun captureOutput(
            output: AVCaptureOutput,
            didOutputSampleBuffer: CMSampleBufferRef?,
            fromConnection: AVCaptureConnection,
        ) {
            val pixelBuffer = CMSampleBufferGetImageBuffer(didOutputSampleBuffer) ?: return
            CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
            try {
                val base = CVPixelBufferGetBaseAddress(pixelBuffer) ?: return
                onFrame(
                    CameraFrame(
                        base = base.reinterpret(),
                        bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer).toInt(),
                        width = CVPixelBufferGetWidth(pixelBuffer).toInt(),
                        height = CVPixelBufferGetHeight(pixelBuffer).toInt(),
                        rotation = frameRotation,
                        lens = lens,
                    ),
                )
            } finally {
                CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly)
            }
        }
    }

    private companion object {
        /** Digital zoom beyond this only magnifies noise; the zoom presets stop at 10x too. */
        const val MAX_ZOOM_RATIO = 10f

        /** Back: the virtual multi-lens devices first, so close plates can switch lenses; front: the only one. */
        val DEVICE_TYPES = listOf(
            AVCaptureDeviceTypeBuiltInTripleCamera,
            AVCaptureDeviceTypeBuiltInDualWideCamera,
            AVCaptureDeviceTypeBuiltInDualCamera,
            AVCaptureDeviceTypeBuiltInWideAngleCamera,
        )

        fun findDevice(position: AVCaptureDevicePosition): AVCaptureDevice? = DEVICE_TYPES.firstNotNullOfOrNull {
            AVCaptureDevice.defaultDeviceWithDeviceType(it, AVMediaTypeVideo, position)
        }

        fun configureDevice(device: AVCaptureDevice, block: AVCaptureDevice.() -> Unit) {
            if (!device.lockForConfiguration(null)) return
            try {
                device.block()
            } finally {
                device.unlockForConfiguration()
            }
        }

        /** `kCVPixelBufferPixelFormatTypeKey` as the NSString the settings dictionary needs. */
        fun pixelFormatKey(): Any? = CFBridgingRelease(CFRetain(kCVPixelBufferPixelFormatTypeKey))

        fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)
    }
}

/** Hosts the preview layer, keeps it the size of the view, and turns pinches and taps into zoom and focus. */
private class CameraPreviewView(
    private val previewLayer: AVCaptureVideoPreviewLayer,
    private val onLayout: () -> Unit,
    onPinch: (UIPinchGestureRecognizer) -> Unit,
    onTap: (UITapGestureRecognizer) -> Unit,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    // Gesture recognizers do not retain their target.
    private val gestureTarget = GestureTarget(onPinch, onTap)

    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(previewLayer)
        addGestureRecognizer(UIPinchGestureRecognizer(gestureTarget, NSSelectorFromString("onPinch:")))
        addGestureRecognizer(UITapGestureRecognizer(gestureTarget, NSSelectorFromString("onTap:")))
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        previewLayer.frame = bounds
        CATransaction.commit()
        onLayout()
    }
}

private class GestureTarget(
    private val pinch: (UIPinchGestureRecognizer) -> Unit,
    private val tap: (UITapGestureRecognizer) -> Unit,
) : NSObject() {
    @ObjCAction
    fun onPinch(recognizer: UIPinchGestureRecognizer) = pinch(recognizer)

    @ObjCAction
    fun onTap(recognizer: UITapGestureRecognizer) = tap(recognizer)
}
