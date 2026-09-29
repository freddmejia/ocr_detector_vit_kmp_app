package botix.dev.detectorlicenseplateocr.scanner

import kotlin.math.min

/** Rectangle as fractions (0-1) of a parent's width and height. */
data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun mirroredHorizontally() = NormalizedRect(1f - right, top, 1f - left, bottom)
}

/**
 * The guide rectangle, as a fraction of the camera preview. The overlay draws it and the analyzer crops the frame
 * with it, so both must use this one function.
 */
object ScanRegion {
    private const val MAX_WIDTH_FRACTION = 0.86f
    private const val MAX_HEIGHT_FRACTION = 0.42f
    private const val CENTER_Y = 0.46f

    /** Width / height. Wide enough for a 2:1 Colombian plate and a 4.7:1 Spanish one with some margin. */
    const val ASPECT_RATIO = 2f

    fun inView(viewWidth: Float, viewHeight: Float): NormalizedRect {
        val width = min(viewWidth * MAX_WIDTH_FRACTION, viewHeight * MAX_HEIGHT_FRACTION * ASPECT_RATIO)
        val height = width / ASPECT_RATIO
        val left = (viewWidth - width) / 2f
        val top = viewHeight * CENTER_Y - height / 2f
        return NormalizedRect(left / viewWidth, top / viewHeight, (left + width) / viewWidth, (top + height) / viewHeight)
    }
}

enum class CameraLens { BACK, FRONT }

sealed interface ModelStatus {
    data object Loading : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

enum class ReadingOutcome {
    /** The detector found nothing above the threshold inside the guide. */
    NO_PLATE,

    /** A plate was found but its crop is narrower than the OCR minimum: move closer or zoom in. */
    TOO_SMALL,

    /** The OCR ran but returned no usable characters. */
    NO_TEXT,
    READ,
}

data class ScanReading(
    val outcome: ReadingOutcome,
    val text: String? = null,
    val detectionScore: Float? = null,
    val ocrConfidence: Float? = null,
    /** Every detected plate, as fractions of the guide rectangle, already mirrored for the front camera preview. */
    val plateBoxes: List<NormalizedRect> = emptyList(),
    val totalMillis: Long = 0,
    /** Increases with every reading, so the UI can react to repeated identical readings. */
    val sequence: Long = 0,
)

/** A distinct plate text read during the session. */
data class PlateHit(val text: String, val bestOcrConfidence: Float, val bestDetectionScore: Float, val count: Int)

data class ZoomInfo(val ratio: Float = 1f, val min: Float = 1f, val max: Float = 1f)

data class ScannerUiState(
    val modelStatus: ModelStatus = ModelStatus.Loading,
    val isScanning: Boolean = false,
    val isProcessing: Boolean = false,
    val lens: CameraLens = CameraLens.BACK,
    val canSwitchLens: Boolean = false,
    val hasTorch: Boolean = false,
    val torchOn: Boolean = false,
    val zoom: ZoomInfo = ZoomInfo(),
    val lastReading: ScanReading? = null,
    val history: List<PlateHit> = emptyList(),
    val error: String? = null,
)

interface ScannerActions {
    fun toggleScanning()
    fun switchLens()
    fun setZoom(ratio: Float)
    fun toggleTorch()
    fun clearHistory()
}
