package botix.dev.detectorlicenseplateocr.pipeline

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OcrTest {
    private val configJson = """
        {
          "entrada": {"alto": 4, "ancho": 6, "formato": "NCHW float32 RGB", "reescalado": 0.00392156862745098,
                      "media": [0.5, 0.5, 0.5], "desviacion": [0.5, 0.5, 0.5]},
          "tokens": {"start": 0, "pad": 1, "eos": 2, "max_length": 16},
          "firmas": {}
        }
    """.trimIndent()

    // Vocabulary with the ids used in Pipeline.md 10.3; the rest are filler so the size is 5000.
    private val vocabulary = Vocabulary.parse(
        buildString {
            append("{")
            val known = mapOf(0 to "<s>", 1 to "<pad>", 2 to "</s>", 83 to " A", 250 to "A", 530 to "K", 574 to "L", 3226 to "*", 4999 to "597")
            append((0 until 5000).joinToString(",") { "\"$it\": \"${known[it] ?: "t$it"}\"" })
            append("}")
        },
    )

    @Test
    fun parsesConfig() {
        val config = OcrConfig.parse(configJson)
        assertEquals(4, config.height)
        assertEquals(6, config.width)
        assertEquals(16, config.tokens.maxLength)
        assertEquals(2, config.tokens.eos)
        assertEquals(0.5f, config.normalization.mean[1])
    }

    @Test
    fun parsesDetectorConfig() {
        val config = DetectorConfig.parse(
            """{"do_normalize": true, "image_mean": [0.485, 0.456, 0.406], "image_std": [0.229, 0.224, 0.225],
               "rescale_factor": 0.00392156862745098, "size": {"height": 576, "width": 576}}""",
        )
        assertEquals(576, config.height)
        assertEquals(0.225f, config.normalization.std[2])
    }

    @Test
    fun decodesTokensLikeTheReference() {
        assertEquals("597*LK*", vocabulary.decode(listOf(4999, 3226, 574, 530, 3226)))
        assertEquals("A", vocabulary.decode(listOf(250)))
        assertEquals(" A", vocabulary.decode(listOf(83)))
        assertEquals("A", vocabulary.decode(listOf(0, 83, 1, 2), skip = setOf(0, 1, 2)).trim())
    }

    @Test
    fun normalizesPlateText() {
        assertEquals("597LK", normalizePlateText("597*lk*"))
        assertEquals("AB-12", normalizePlateText(" ab-12 ñ"))
    }

    @Test
    fun greedyLoopFeedsPaddedIdsAndReadsRowT() {
        val config = OcrConfig.parse(configJson)
        // Emits 4999, 3226, 574, 530, 3226, then eos.
        val model = FakeOcrModel(config, vocabulary.size, listOf(4999, 3226, 574, 530, 3226, 2))
        val ocr = PlateOcr(model, config, vocabulary)
        val result = ocr.read(RgbImage(10, 3, IntArray(30) { 0x808080 }))

        assertEquals(listOf(4999, 3226, 574, 530, 3226), result.tokenIds)
        assertEquals("597*LK*", result.rawText)
        assertEquals("597LK", result.text)
        assertEquals(1, model.encoderRuns)
        assertEquals(6, model.decoderIds.size)
        // Step t sees start, the t tokens generated so far, then pad.
        assertContentEquals(intArrayOf(0) + IntArray(15) { 1 }, model.decoderIds[0])
        assertContentEquals(intArrayOf(0, 4999, 3226) + IntArray(13) { 1 }, model.decoderIds[2])
        assertTrue(result.confidence > 0.9f && result.confidence <= 1f)
    }

    @Test
    fun emptyReadingWhenFirstTokenIsEos() {
        val config = OcrConfig.parse(configJson)
        val result = PlateOcr(FakeOcrModel(config, vocabulary.size, listOf(2)), config, vocabulary)
            .read(RgbImage(6, 4, IntArray(24)))
        assertEquals("", result.text)
        assertTrue(result.tokenIds.isEmpty())
    }

    @Test
    fun stopsAfterMaxLengthMinusOneSteps() {
        val config = OcrConfig.parse(configJson)
        val model = FakeOcrModel(config, vocabulary.size, List(40) { 250 })
        val result = PlateOcr(model, config, vocabulary).read(RgbImage(6, 4, IntArray(24)))
        assertEquals(15, result.tokenIds.size)
        assertEquals(15, model.decoderIds.size)
    }

    @Test
    fun rejectsConfigFromAnotherExport() {
        val config = OcrConfig.parse(configJson.replace("\"alto\": 4", "\"alto\": 8"))
        val model = FakeOcrModel(OcrConfig.parse(configJson), vocabulary.size, listOf(2))
        assertFailsWith<IllegalArgumentException> { PlateOcr(model, config, vocabulary) }
    }

    @Test
    fun argmaxTakesFirstIndexOnTiesAndGivesSoftmaxProbability() {
        val step = argmaxWithProbability(floatArrayOf(9f, 1f, 3f, 3f, 0f), offset = 1, count = 4)
        assertEquals(1, step.index)
        val expected = 1.0 / (kotlin.math.exp(-2.0) + 1 + 1 + kotlin.math.exp(-3.0))
        assertTrue(abs(step.probability - expected.toFloat()) < 1e-6f)
    }
}

/**
 * Stand-in for the OCR model: `encoder` returns zeros; each `decoder` call puts a high logit on the next scripted token
 * in row t, where t is the number of non-pad ids after start. Other rows get noise to catch reading the wrong row.
 */
private class FakeOcrModel(config: OcrConfig, private val vocab: Int, private val script: List<Int>) : TfliteModel {
    private val maxLength = config.tokens.maxLength
    private val pixelShape = intArrayOf(1, 3, config.height, config.width)
    private val featureShape = intArrayOf(1, 7, 768)
    var encoderRuns = 0
    val decoderIds = mutableListOf<IntArray>()

    override val signatures = listOf("encoder", "decoder")
    override fun inputNames(signature: String) = if (signature == "encoder") listOf("args_0") else listOf("args_0", "args_1")
    override fun outputNames(signature: String) = listOf("output_0")
    override fun inputShape(signature: String, name: String) = when {
        signature == "encoder" -> pixelShape
        name == "args_0" -> intArrayOf(1, maxLength)
        else -> featureShape
    }
    override fun outputShape(signature: String, name: String) =
        if (signature == "encoder") featureShape else intArrayOf(1, maxLength, vocab)

    override fun run(signature: String, inputs: Map<String, Any>, outputs: Map<String, FloatArray>) {
        val out = outputs.getValue("output_0")
        if (signature == "encoder") {
            encoderRuns++
            out.fill(0f)
            return
        }
        val ids = (inputs.getValue("args_0") as IntArray).copyOf()
        decoderIds += ids
        val t = ids.drop(1).takeWhile { it != 1 }.size
        for (i in out.indices) out[i] = (i % 13).toFloat() * 0.01f
        for (row in 0 until maxLength) if (row != t) out[row * vocab + (row * 7 + 3) % vocab] = 50f
        out[t * vocab + script[decoderIds.size - 1]] = 20f
    }

    override fun close() = Unit
}
