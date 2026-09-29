package botix.dev.detectorlicenseplateocr.pipeline

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

data class Detection(val box: Box, val score: Float)

/** RF-DETR plate detector (Pipeline.md 4). */
internal class PlateDetector(private val model: TfliteModel, private val config: DetectorConfig) {
    private val signature = model.signatures.singleOrNull() ?: SIGNATURE
    private val inputName: String
    private val logitsName: String
    private val boxesName: String
    private val candidates: Int
    private val input = FloatArray(3 * config.height * config.width)
    private val logits: FloatArray
    private val boxes: FloatArray

    init {
        model.requireSignature(signature, ModelFiles.DETECTOR_MODEL)
        inputName = model.inputNames(signature).singleOrNull()
            ?: error("${ModelFiles.DETECTOR_MODEL}: expected 1 input, found ${model.inputNames(signature)}")
        val inputShape = model.inputShape(signature, inputName)
        require(inputShape.contentEquals(intArrayOf(1, 3, config.height, config.width))) {
            "${ModelFiles.DETECTOR_MODEL}: input is ${inputShape.contentToString()}, " +
                "${ModelFiles.DETECTOR_CONFIG} says [1, 3, ${config.height}, ${config.width}]"
        }
        // Told apart by shape, not name: last dimension 1 = logits, 4 = boxes.
        val outputs = model.outputNames(signature).associateWith { model.outputShape(signature, it) }
        logitsName = outputs.entries.singleOrNull { it.value.size == 3 && it.value[2] == 1 }?.key
            ?: error("${ModelFiles.DETECTOR_MODEL}: no [1, N, 1] logits output in ${outputs.describe()}")
        boxesName = outputs.entries.singleOrNull { it.value.size == 3 && it.value[2] == 4 }?.key
            ?: error("${ModelFiles.DETECTOR_MODEL}: no [1, N, 4] boxes output in ${outputs.describe()}")
        candidates = outputs.getValue(logitsName)[1]
        require(outputs.getValue(boxesName)[1] == candidates) { "Logits and boxes disagree on the candidate count" }
        logits = FloatArray(candidates)
        boxes = FloatArray(candidates * 4)
    }

    fun detect(image: RgbImage, threshold: Float): List<Detection> {
        image.toNchw(config.width, config.height, config.normalization, input)
        model.run(signature, mapOf(inputName to input), mapOf(logitsName to logits, boxesName to boxes))
        return decodeDetections(logits, boxes, candidates, image.width, image.height, threshold)
    }

    companion object {
        const val SIGNATURE = "serving_default"
    }
}

/** Sigmoid, corners in pixels of the original image, threshold, sorted by score (Pipeline.md 4.4). No NMS. */
internal fun decodeDetections(
    logits: FloatArray,
    boxes: FloatArray,
    candidates: Int,
    imageWidth: Int,
    imageHeight: Int,
    threshold: Float,
): List<Detection> {
    val found = ArrayList<Detection>()
    for (i in 0 until candidates) {
        val score = 1f / (1f + exp(-logits[i]))
        if (score <= threshold) continue
        val cx = boxes[i * 4]
        val cy = boxes[i * 4 + 1]
        val w = boxes[i * 4 + 2]
        val h = boxes[i * 4 + 3]
        found += Detection(
            Box(
                x0 = (cx - w / 2) * imageWidth,
                y0 = (cy - h / 2) * imageHeight,
                x1 = (cx + w / 2) * imageWidth,
                y1 = (cy + h / 2) * imageHeight,
            ),
            score,
        )
    }
    return found.sortedByDescending { it.score }
}

/**
 * Crop with margin around a plate, clamped to the image (Pipeline.md 4.5). Computed in double precision with
 * truncation on the top-left, like the reference `int(x0 - pad_x)`.
 */
internal fun plateCropRect(box: Box, imageWidth: Int, imageHeight: Int, params: PipelineParams): PixelRect {
    val x0 = box.x0.toDouble()
    val y0 = box.y0.toDouble()
    val x1 = box.x1.toDouble()
    val y1 = box.y1.toDouble()
    val padX = (x1 - x0) * params.cropPaddingX
    val padY = (y1 - y0) * params.cropPaddingY
    return PixelRect(
        left = max(0, (x0 - padX).toInt()),
        top = max(0, (y0 - padY).toInt()),
        right = min(imageWidth, ceil(x1 + padX).toInt()),
        bottom = min(imageHeight, ceil(y1 + padY).toInt()),
    )
}

private fun Map<String, IntArray>.describe() = entries.joinToString { "${it.key}=${it.value.contentToString()}" }
