@file:OptIn(ExperimentalForeignApi::class)

package botix.dev.detectorlicenseplateocr.scanner

import androidx.lifecycle.ViewModel
import botix.dev.detectorlicenseplateocr.pipeline.LicensePlatePipeline
import botix.dev.detectorlicenseplateocr.pipeline.PipelineResult
import botix.dev.detectorlicenseplateocr.pipeline.createLicensePlatePipeline
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import platform.Foundation.NSBundle
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * Owns the camera and the pipeline: the iOS counterpart of Android's `ScannerViewModel`. Model loading and every
 * inference run on one serial analysis queue (the one the camera delivers frames on), which serializes access to
 * the interpreters.
 */
class IosScannerViewModel : ViewModel(), ScannerActions {
    private val _state = MutableStateFlow(ScannerUiState())
    val state: StateFlow<ScannerUiState> = _state.asStateFlow()

    private val analysisQueue = dispatch_queue_create("plate-analysis", null)

    @Volatile private var scanning = false
    @Volatile private var pipeline: LicensePlatePipeline? = null
    @Volatile private var viewSize: Pair<Int, Int>? = null
    private var sequence = 0L

    internal val camera = IosCamera(analysisQueue, ::analyze, ::onCameraInfo)

    init {
        dispatch_async(analysisQueue, ::loadPipeline)
    }

    private fun loadPipeline() {
        try {
            val started = TimeSource.Monotonic.markNow()
            val modelsDir = NSBundle.mainBundle.resourcePath + "/" + MODELS_FOLDER
            pipeline = createLicensePlatePipeline(modelsDir)
            println("$TAG: models loaded in ${started.elapsedNow().inWholeMilliseconds} ms")
            _state.update { it.copy(modelStatus = ModelStatus.Ready) }
        } catch (e: Throwable) {
            println("$TAG: could not load the models: $e")
            _state.update { it.copy(modelStatus = ModelStatus.Failed(e.message ?: e.toString())) }
        }
    }

    private fun onCameraInfo(info: CameraInfo?) {
        _state.update {
            if (info == null) {
                it.copy(error = "No camera available", canSwitchLens = false, hasTorch = false)
            } else {
                it.copy(
                    lens = info.lens,
                    canSwitchLens = info.canSwitchLens,
                    hasTorch = info.hasTorch,
                    torchOn = info.torchOn,
                    zoom = info.zoom,
                )
            }
        }
    }

    /** Size of the preview in pixels; the guide rectangle is derived from it. */
    fun onViewportChanged(width: Int, height: Int) {
        if (width > 0 && height > 0) viewSize = width to height
    }

    /** Analysis queue, while the frame is locked. */
    private fun analyze(frame: CameraFrame) {
        val pipeline = pipeline
        val size = viewSize
        if (!scanning || pipeline == null || size == null) return
        val started = TimeSource.Monotonic.markNow()
        val (viewWidth, viewHeight) = size
        val region = ScanRegion.inView(viewWidth.toFloat(), viewHeight.toFloat())
        val roi = try {
            IosFrameCropper.cropBgra(
                frame.base, frame.bytesPerRow, frame.width, frame.height, frame.rotation, viewWidth, viewHeight, region,
            )
        } catch (e: Exception) {
            println("$TAG: could not convert the frame: $e")
            null
        } ?: return

        _state.update { it.copy(isProcessing = true) }
        try {
            val result = pipeline.run(roi, maxPlatesToRead = 1)
            if (scanning) publish(result, frame.lens, started.elapsedNow().inWholeMilliseconds)
        } catch (e: Exception) {
            println("$TAG: pipeline failed: $e")
            _state.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            _state.update { it.copy(isProcessing = false) }
        }
    }

    private fun publish(result: PipelineResult, lens: CameraLens, totalMillis: Long) {
        val reading = result.toScanReading(lens, totalMillis, ++sequence)
        _state.update { current ->
            current.copy(
                lastReading = reading,
                error = null,
                history = if (reading.outcome == ReadingOutcome.READ) current.history.withHit(reading) else current.history,
            )
        }
    }

    override fun toggleScanning() {
        if (_state.value.modelStatus != ModelStatus.Ready) return
        val start = !scanning
        scanning = start
        _state.update { it.copy(isScanning = start, lastReading = null, isProcessing = false, error = null) }
    }

    override fun switchLens() {
        camera.switchLens()
        _state.update { it.copy(lastReading = null, torchOn = false) }
    }

    override fun setZoom(ratio: Float) = camera.setZoom(ratio)

    override fun toggleTorch() = camera.setTorch(!_state.value.torchOn)

    override fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
    }

    override fun onCleared() {
        scanning = false
        camera.stop()
        // Closed on the analysis queue so it never races an inference in progress.
        dispatch_async(analysisQueue) {
            pipeline?.close()
            pipeline = null
        }
    }

    private companion object {
        const val TAG = "PlateScanner"

        /** Bundle folder the `Copy Model Files` build phase fills from `androidMain/assets`. */
        const val MODELS_FOLDER = "models"
    }
}
