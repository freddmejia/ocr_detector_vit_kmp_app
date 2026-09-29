package botix.dev.detectorlicenseplateocr.pipeline

import kotlin.math.exp
import kotlin.math.min

class OcrResult(
    /** TrOCR: generated token ids. fast-plate-ocr: the alphabet index chosen for every slot, pad slots included. */
    val tokenIds: List<Int>,
    /** Concatenated tokens (without pad characters), trimmed, not normalized. */
    val rawText: String,
    /** A-Z, 0-9 and '-' only. Empty when the model read nothing (first token eos, or only pad slots). */
    val text: String,
    /**
     * Lowest softmax probability among the chosen tokens (including eos or pad slots). Not calibrated
     * (Pipeline.md 6.5): a relative indication only, and not comparable between [OcrEngine]s.
     */
    val confidence: Float,
)

/** Reads the text of one plate crop. Not thread safe. [close] releases the model. */
interface PlateTextReader : AutoCloseable {
    fun read(crop: RgbImage): OcrResult
}

/** TrOCR plate reader: one `encoder` run, then greedy `decoder` steps (Pipeline.md 6). */
internal class PlateOcr(
    private val model: TfliteModel,
    private val config: OcrConfig,
    private val vocabulary: Vocabulary,
) : PlateTextReader {
    private val tokens = config.tokens
    private val encoderInput: String
    private val encoderOutput: String
    private val decoderIdsInput: String
    private val decoderFeaturesInput: String
    private val decoderOutput: String
    private val vocabSize: Int
    private val pixels = FloatArray(3 * config.height * config.width)
    private val features: FloatArray
    private val ids = IntArray(tokens.maxLength)
    private val logits: FloatArray
    private val specialTokens = setOf(tokens.start, tokens.pad, tokens.eos)

    init {
        val file = ModelFiles.OCR_MODEL
        model.requireSignature(ENCODER, file)
        model.requireSignature(DECODER, file)

        encoderInput = model.inputNames(ENCODER).singleOrNull() ?: error("$file: encoder must have 1 input")
        encoderOutput = model.outputNames(ENCODER).singleOrNull() ?: error("$file: encoder must have 1 output")
        val pixelShape = model.inputShape(ENCODER, encoderInput)
        require(pixelShape.contentEquals(intArrayOf(1, 3, config.height, config.width))) {
            "$file: encoder input is ${pixelShape.contentToString()}, ${ModelFiles.OCR_CONFIG} says " +
                "[1, 3, ${config.height}, ${config.width}]. Were the model and its JSON exported together?"
        }
        val featureShape = model.outputShape(ENCODER, encoderOutput)
        features = FloatArray(featureShape.elementCount())

        // The decoder's int32 [1, max_length] input is the token ids; the other one takes the encoder features.
        val decoderInputs = model.inputNames(DECODER)
        require(decoderInputs.size == 2) { "$file: decoder must have 2 inputs, found $decoderInputs" }
        decoderIdsInput = decoderInputs.singleOrNull {
            model.inputShape(DECODER, it).contentEquals(intArrayOf(1, tokens.maxLength))
        } ?: error("$file: no decoder input of shape [1, ${tokens.maxLength}] (max_length in ${ModelFiles.OCR_CONFIG})")
        decoderFeaturesInput = decoderInputs.single { it != decoderIdsInput }
        require(model.inputShape(DECODER, decoderFeaturesInput).contentEquals(featureShape)) {
            "$file: decoder features input does not match the encoder output ${featureShape.contentToString()}"
        }

        decoderOutput = model.outputNames(DECODER).singleOrNull() ?: error("$file: decoder must have 1 output")
        val logitShape = model.outputShape(DECODER, decoderOutput)
        require(logitShape.size == 3 && logitShape[0] == 1 && logitShape[1] == tokens.maxLength) {
            "$file: decoder output is ${logitShape.contentToString()}, expected [1, ${tokens.maxLength}, V]"
        }
        vocabSize = logitShape[2]
        require(vocabSize == vocabulary.size) {
            "${ModelFiles.OCR_VOCABULARY} has ${vocabulary.size} tokens but the decoder outputs $vocabSize"
        }
        logits = FloatArray(logitShape.elementCount())
    }

    override fun read(crop: RgbImage): OcrResult {
        crop.toNchw(config.width, config.height, config.normalization, pixels)
        model.run(ENCODER, mapOf(encoderInput to pixels), mapOf(encoderOutput to features))

        ids.fill(tokens.pad)
        ids[0] = tokens.start
        val generated = ArrayList<Int>()
        var confidence = 1f
        val decoderInputs = mapOf(decoderIdsInput to ids, decoderFeaturesInput to features)
        val decoderOutputs = mapOf(decoderOutput to logits)
        for (t in 0 until tokens.maxLength - 1) {
            model.run(DECODER, decoderInputs, decoderOutputs)
            // Only row t matters; padded positions do not affect it (causal decoder).
            val step = argmaxWithProbability(logits, t * vocabSize, vocabSize)
            confidence = min(confidence, step.probability)
            if (step.index == tokens.eos) break
            ids[t + 1] = step.index
            generated += step.index
        }
        val raw = vocabulary.decode(generated, specialTokens).trim()
        return OcrResult(generated, raw, normalizePlateText(raw), confidence)
    }

    override fun close() = model.close()

    companion object {
        const val ENCODER = "encoder"
        const val DECODER = "decoder"
    }
}

internal class ArgmaxStep(val index: Int, val probability: Float)

/** Strict argmax over `values[offset until offset + count]` (first index wins ties), plus its softmax probability. */
internal fun argmaxWithProbability(values: FloatArray, offset: Int, count: Int): ArgmaxStep {
    var best = 0
    var max = values[offset]
    for (i in 1 until count) {
        val v = values[offset + i]
        if (v > max) {
            max = v
            best = i
        }
    }
    var sum = 0.0
    for (i in 0 until count) sum += exp((values[offset + i] - max).toDouble())
    return ArgmaxStep(best, (1.0 / sum).toFloat())
}
