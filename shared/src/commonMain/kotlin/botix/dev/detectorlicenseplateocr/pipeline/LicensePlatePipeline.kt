package botix.dev.detectorlicenseplateocr.pipeline

import kotlin.time.TimeSource

/** Reference parameters (Pipeline.md 7), in one place. */
data class PipelineParams(
    val detectionThreshold: Float = 0.4f,
    val cropPaddingX: Double = 0.08,
    val cropPaddingY: Double = 0.15,
    val minOcrWidth: Int = 60,
)

data class PlateReading(
    /** Corners in pixels of the image given to the pipeline. */
    val box: Box,
    /** 0-1, sigmoid of the detector logit. */
    val detectionScore: Float,
    val cropWidth: Int,
    val cropHeight: Int,
    /** cropWidth >= minOcrWidth. */
    val readable: Boolean,
    /** Normalized text (A-Z, 0-9, -); null if not read. Empty means the OCR could not read it. */
    val text: String?,
    val rawText: String?,
    val tokenIds: List<Int>?,
    /** Uncalibrated OCR confidence (see [OcrResult.confidence]); null if not read. */
    val ocrConfidence: Float?,
)

data class PipelineResult(
    val imageWidth: Int,
    val imageHeight: Int,
    /** Sorted by detectionScore, highest first. */
    val plates: List<PlateReading>,
    val detectorMillis: Long,
    val ocrMillis: Long,
)

/**
 * Detector + OCR (Pipeline.md 7). Load once and reuse; [close] releases the detector and [ocr].
 * Not thread safe: call [run] from one thread at a time.
 */
class LicensePlatePipeline(
    private val detectorModel: TfliteModel,
    detectorConfig: DetectorConfig,
    private val ocr: PlateTextReader,
    private val params: PipelineParams = PipelineParams(),
) : AutoCloseable {
    private val detector = PlateDetector(detectorModel, detectorConfig)

    /**
     * Runs the full pipeline on [image] (already upright, RGB). Only the [maxPlatesToRead] most confident readable
     * plates go through the OCR; the others are returned with their box and score but no text.
     */
    fun run(image: RgbImage, maxPlatesToRead: Int = Int.MAX_VALUE): PipelineResult {
        val detectStart = TimeSource.Monotonic.markNow()
        val detections = detector.detect(image, params.detectionThreshold)
        val detectorMillis = detectStart.elapsedNow().inWholeMilliseconds

        val ocrStart = TimeSource.Monotonic.markNow()
        var read = 0
        val plates = detections.map { detection ->
            val rect = plateCropRect(detection.box, image.width, image.height, params)
            val readable = rect.width >= params.minOcrWidth && rect.height > 0
            val result = if (readable && read < maxPlatesToRead) {
                read++
                ocr.read(image.crop(rect))
            } else {
                null
            }
            PlateReading(
                box = detection.box,
                detectionScore = detection.score,
                cropWidth = rect.width,
                cropHeight = rect.height,
                readable = readable,
                text = result?.text,
                rawText = result?.rawText,
                tokenIds = result?.tokenIds,
                ocrConfidence = result?.confidence,
            )
        }
        return PipelineResult(
            imageWidth = image.width,
            imageHeight = image.height,
            plates = plates,
            detectorMillis = detectorMillis,
            ocrMillis = ocrStart.elapsedNow().inWholeMilliseconds,
        )
    }

    /** OCR only, on an image that is already a plate crop. */
    fun readPlate(crop: RgbImage): OcrResult = ocr.read(crop)

    override fun close() {
        detectorModel.close()
        ocr.close()
    }
}
