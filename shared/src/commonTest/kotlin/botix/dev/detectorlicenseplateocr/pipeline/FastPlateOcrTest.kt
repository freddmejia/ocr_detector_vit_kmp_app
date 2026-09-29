package botix.dev.detectorlicenseplateocr.pipeline

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FastPlateOcrTest {
    private val configJson = """
        {"max_plate_slots": 4, "alphabet": "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_", "pad_char": "_",
         "img_height": 2, "img_width": 3, "image_color_mode": "rgb", "keep_aspect_ratio": false}
    """.trimIndent()
    private val config = FastOcrConfig.parse(configJson)

    @Test
    fun parsesConfig() {
        assertEquals(2, config.height)
        assertEquals(3, config.width)
        assertEquals(4, config.slots)
        assertEquals(36, config.alphabet.indexOf(config.padChar))
    }

    @Test
    fun rejectsUnsupportedPreprocessing() {
        assertFailsWith<IllegalArgumentException> {
            FastOcrConfig.parse(configJson.replace("\"rgb\"", "\"grayscale\""))
        }
        assertFailsWith<IllegalArgumentException> {
            FastOcrConfig.parse(configJson.replace("\"keep_aspect_ratio\": false", "\"keep_aspect_ratio\": true"))
        }
    }

    @Test
    fun feedsRawRgbAsNhwc() {
        val model = FakeFastOcrModel(config, listOf('A', '_', '_', '_'))
        val image = RgbImage(3, 2, IntArray(6) { 0x102030 + it })
        FastPlateOcr(model, config).read(image)
        assertContentEquals(floatArrayOf(16f, 32f, 48f, 16f, 32f, 49f), model.lastInput!!.copyOfRange(0, 6))
    }

    @Test
    fun readsEverySlotAndDropsPadding() {
        val model = FakeFastOcrModel(config, listOf('A', 'B', '1', '_'))
        val result = FastPlateOcr(model, config).read(RgbImage(3, 2, IntArray(6)))
        assertEquals("AB1", result.rawText)
        assertEquals("AB1", result.text)
        assertEquals(listOf(10, 11, 1, 36), result.tokenIds)
        assertEquals(0.9f, result.confidence)
    }

    @Test
    fun emptyReadingWhenEverySlotIsPadding() {
        val result = FastPlateOcr(FakeFastOcrModel(config, List(4) { '_' }), config).read(RgbImage(3, 2, IntArray(6)))
        assertEquals("", result.text)
    }

    @Test
    fun rejectsConfigFromAnotherModel() {
        val model = FakeFastOcrModel(config, List(4) { '_' })
        val wrongSlots = FastOcrConfig.parse(configJson.replace("\"max_plate_slots\": 4", "\"max_plate_slots\": 9"))
        assertFailsWith<IllegalArgumentException> { FastPlateOcr(model, wrongSlots) }
    }
}

/** Stand-in for plate_ocr.tflite: puts probability 0.9 on each scripted character, the rest spread evenly. */
private class FakeFastOcrModel(private val config: FastOcrConfig, private val script: List<Char>) : TfliteModel {
    var lastInput: FloatArray? = null

    override val signatures = listOf("serving_default")
    override fun inputNames(signature: String) = listOf("input_layer")
    override fun outputNames(signature: String) = listOf("plate")
    override fun inputShape(signature: String, name: String) = intArrayOf(1, config.height, config.width, 3)
    override fun outputShape(signature: String, name: String) = intArrayOf(1, script.size, config.alphabet.length)

    override fun run(signature: String, inputs: Map<String, Any>, outputs: Map<String, FloatArray>) {
        lastInput = (inputs.getValue("input_layer") as FloatArray).copyOf()
        val out = outputs.getValue("plate")
        val classes = config.alphabet.length
        out.fill(0.1f / (classes - 1))
        script.forEachIndexed { slot, c -> out[slot * classes + config.alphabet.indexOf(c)] = 0.9f }
    }

    override fun close() = Unit
}
