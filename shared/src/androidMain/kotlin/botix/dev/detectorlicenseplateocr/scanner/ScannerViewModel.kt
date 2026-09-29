package botix.dev.detectorlicenseplateocr.scanner

import android.app.Application
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.TorchState
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Observer
import botix.dev.detectorlicenseplateocr.pipeline.LicensePlatePipeline
import botix.dev.detectorlicenseplateocr.pipeline.PipelineResult
import botix.dev.detectorlicenseplateocr.pipeline.createLicensePlatePipeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the camera controller and the pipeline. Model loading and every inference run on one analysis thread, which
 * serializes access to the interpreters. With KEEP_ONLY_LATEST, frames that arrive while a frame is being read are
 * dropped, so each reading uses the newest frame.
 */
class ScannerViewModel(application: Application) : AndroidViewModel(application), ScannerActions {
    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    private val analysisExecutor = Executors.newSingleThreadExecutor { Thread(it, "plate-analysis") }
    private val mainExecutor = ContextCompat.getMainExecutor(application)
    private val scanning = AtomicBoolean(false)
    private val sequence = AtomicLong(0)

    @Volatile private var pipeline: LicensePlatePipeline? = null
    @Volatile private var viewSize: Size? = null

    val controller = LifecycleCameraController(application).apply {
        setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
        // High enough that a plate filling part of the guide keeps well over the OCR's 60 px minimum.
        imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        setImageAnalysisAnalyzer(analysisExecutor, ::analyze)
    }

    private val zoomObserver = Observer<ZoomState> { zoom ->
        _state.update {
            it.copy(
                zoom = ZoomInfo(zoom.zoomRatio, zoom.minZoomRatio, zoom.maxZoomRatio),
                hasTorch = controller.cameraInfo?.hasFlashUnit() == true,
            )
        }
    }
    private val torchObserver = Observer<Int> { torch -> _state.update { it.copy(torchOn = torch == TorchState.ON) } }

    init {
        analysisExecutor.execute(::loadPipeline)
        controller.initializationFuture.addListener(::onCameraInitialized, mainExecutor)
        controller.zoomState.observeForever(zoomObserver)
        controller.torchState.observeForever(torchObserver)
    }

    private fun loadPipeline() {
        try {
            val started = SystemClock.elapsedRealtime()
            pipeline = createLicensePlatePipeline(getApplication<Application>().assets)
            Log.i(TAG, "Models loaded in ${SystemClock.elapsedRealtime() - started} ms")
            _state.update { it.copy(modelStatus = ModelStatus.Ready) }
        } catch (e: Throwable) {
            Log.e(TAG, "Could not load the models", e)
            _state.update { it.copy(modelStatus = ModelStatus.Failed(e.message ?: e.toString())) }
        }
    }

    private fun onCameraInitialized() {
        runCatching {
            val hasBack = controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            val hasFront = controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            if (!hasBack && hasFront) {
                controller.cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                _state.update { it.copy(lens = CameraLens.FRONT) }
            }
            _state.update { it.copy(canSwitchLens = hasBack && hasFront) }
        }.onFailure { Log.w(TAG, "Camera initialization failed", it) }
    }

    /** Size of the preview in pixels; the guide rectangle is derived from it. */
    fun onViewportChanged(width: Int, height: Int) {
        if (width > 0 && height > 0) viewSize = Size(width, height)
    }

    private fun analyze(image: ImageProxy) {
        val pipeline = pipeline
        val size = viewSize
        if (!scanning.get() || pipeline == null || size == null) {
            image.close()
            return
        }
        val started = SystemClock.elapsedRealtime()
        val lens = _state.value.lens
        val region = ScanRegion.inView(size.width.toFloat(), size.height.toFloat())
        val roi = try {
            FrameCropper.cropRegion(image, size.width, size.height, region)
        } catch (e: Exception) {
            Log.e(TAG, "Could not convert the frame", e)
            null
        } finally {
            image.close()
        }
        if (roi == null) return

        _state.update { it.copy(isProcessing = true) }
        try {
            val result = pipeline.run(roi, maxPlatesToRead = 1)
            if (scanning.get()) publish(result, lens, SystemClock.elapsedRealtime() - started)
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline failed", e)
            _state.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            _state.update { it.copy(isProcessing = false) }
        }
    }

    private fun publish(result: PipelineResult, lens: CameraLens, totalMillis: Long) {
        // The plate that went through the OCR if any, else the most confident one.
        val plate = result.plates.firstOrNull { it.text != null } ?: result.plates.firstOrNull()
        val outcome = when {
            plate == null -> ReadingOutcome.NO_PLATE
            !plate.readable -> ReadingOutcome.TOO_SMALL
            plate.text.isNullOrEmpty() -> ReadingOutcome.NO_TEXT
            else -> ReadingOutcome.READ
        }
        val w = result.imageWidth.toFloat()
        val h = result.imageHeight.toFloat()
        val boxes = result.plates.map {
            val rect = NormalizedRect(
                (it.box.x0 / w).coerceIn(0f, 1f),
                (it.box.y0 / h).coerceIn(0f, 1f),
                (it.box.x1 / w).coerceIn(0f, 1f),
                (it.box.y1 / h).coerceIn(0f, 1f),
            )
            // The front preview is mirrored; the analyzed frame is not.
            if (lens == CameraLens.FRONT) rect.mirroredHorizontally() else rect
        }
        val reading = ScanReading(
            outcome = outcome,
            text = plate?.text,
            detectionScore = plate?.detectionScore,
            ocrConfidence = plate?.ocrConfidence,
            plateBoxes = boxes,
            totalMillis = totalMillis,
            sequence = sequence.incrementAndGet(),
        )
        Log.d(
            TAG,
            "outcome=$outcome text=${plate?.text} raw=${plate?.rawText} det=${plate?.detectionScore} " +
                "ocr=${plate?.ocrConfidence} roi=${result.imageWidth}x${result.imageHeight} " +
                "crop=${plate?.cropWidth}x${plate?.cropHeight} detector=${result.detectorMillis}ms ocr=${result.ocrMillis}ms",
        )
        _state.update { current ->
            current.copy(
                lastReading = reading,
                error = null,
                history = if (outcome == ReadingOutcome.READ) current.history.withHit(reading) else current.history,
            )
        }
    }

    private fun List<PlateHit>.withHit(reading: ScanReading): List<PlateHit> {
        val text = reading.text ?: return this
        val existing = firstOrNull { it.text == text }
        val hit = PlateHit(
            text = text,
            bestOcrConfidence = maxOf(existing?.bestOcrConfidence ?: 0f, reading.ocrConfidence ?: 0f),
            bestDetectionScore = maxOf(existing?.bestDetectionScore ?: 0f, reading.detectionScore ?: 0f),
            count = (existing?.count ?: 0) + 1,
        )
        return (listOf(hit) + filter { it.text != text }).take(MAX_HISTORY)
    }

    override fun toggleScanning() {
        if (_state.value.modelStatus != ModelStatus.Ready) return
        val start = !scanning.get()
        scanning.set(start)
        _state.update { it.copy(isScanning = start, lastReading = null, isProcessing = false, error = null) }
    }

    override fun switchLens() {
        val lens = if (_state.value.lens == CameraLens.BACK) CameraLens.FRONT else CameraLens.BACK
        controller.cameraSelector =
            if (lens == CameraLens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        _state.update { it.copy(lens = lens, lastReading = null, torchOn = false) }
    }

    override fun setZoom(ratio: Float) {
        controller.setZoomRatio(ratio)
    }

    override fun toggleTorch() {
        controller.enableTorch(!_state.value.torchOn)
    }

    override fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
    }

    override fun onCleared() {
        scanning.set(false)
        controller.zoomState.removeObserver(zoomObserver)
        controller.torchState.removeObserver(torchObserver)
        controller.clearImageAnalysisAnalyzer()
        // Closed on the analysis thread so it never races an inference in progress.
        analysisExecutor.execute {
            pipeline?.close()
            pipeline = null
        }
        analysisExecutor.shutdown()
    }

    private companion object {
        const val TAG = "PlateScanner"
        const val MAX_HISTORY = 8
    }
}
