package botix.dev.detectorlicenseplateocr.pipeline

import kotlin.math.min

/**
 * fast-plate-ocr reader: one run of the model's only signature. Input NHWC float32 `[1, img_height, img_width, 3]`
 * with raw 0-255 RGB values (the 1/255 rescaling is inside the model); output `[1, max_plate_slots, alphabet]`,
 * already softmaxed. Each slot is read by argmax; pad slots are dropped from the text.
 *
 * The resize is Pillow's bilinear ([resizeBilinear]), while fast-plate-ocr trains with OpenCV `INTER_LINEAR`, which
 * does not antialias: crops much larger than the input size look slightly smoother here than in training.
 */
internal class FastPlateOcr(
    private val model: TfliteModel,
    private val config: FastOcrConfig,
) : PlateTextReader {
    private val signature: String
    private val input: String
    private val output: String
    private val classes = config.alphabet.length
    private val padIndex = config.alphabet.indexOf(config.padChar)
    private val pixels = FloatArray(config.height * config.width * 3)
    private val probabilities = FloatArray(config.slots * classes)

    init {
        val file = ModelFiles.FAST_OCR_MODEL
        signature = model.signatures.singleOrNull() ?: error("$file: expected 1 signature, found ${model.signatures}")
        input = model.inputNames(signature).singleOrNull() ?: error("$file: expected 1 input")
        output = model.outputNames(signature).singleOrNull() ?: error("$file: expected 1 output")
        val inputShape = model.inputShape(signature, input)
        require(inputShape.contentEquals(intArrayOf(1, config.height, config.width, 3))) {
            "$file: input is ${inputShape.contentToString()}, ${ModelFiles.FAST_OCR_CONFIG} says " +
                "[1, ${config.height}, ${config.width}, 3]"
        }
        val outputShape = model.outputShape(signature, output)
        require(outputShape.contentEquals(intArrayOf(1, config.slots, classes))) {
            "$file: output is ${outputShape.contentToString()}, ${ModelFiles.FAST_OCR_CONFIG} says " +
                "[1, ${config.slots}, $classes] (max_plate_slots, alphabet length)"
        }
    }

    override fun read(crop: RgbImage): OcrResult {
        crop.toNhwc(config.width, config.height, pixels)
        model.run(signature, mapOf(input to pixels), mapOf(output to probabilities))
        val ids = ArrayList<Int>(config.slots)
        var confidence = 1f
        for (slot in 0 until config.slots) {
            val offset = slot * classes
            var best = 0
            for (i in 1 until classes) if (probabilities[offset + i] > probabilities[offset + best]) best = i
            ids += best
            confidence = min(confidence, probabilities[offset + best])
        }
        val raw = buildString { for (id in ids) if (id != padIndex) append(config.alphabet[id]) }
        return OcrResult(ids, raw, normalizePlateText(raw), confidence)
    }

    override fun close() = model.close()
}
