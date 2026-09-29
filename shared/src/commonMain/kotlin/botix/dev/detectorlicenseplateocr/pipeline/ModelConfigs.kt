package botix.dev.detectorlicenseplateocr.pipeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Asset paths of the model files (Pipeline.md 2.2). */
object ModelFiles {
    const val DETECTOR_MODEL = "detector/rf_detr_license_plates_fp16.tflite"
    const val DETECTOR_CONFIG = "detector/preprocessor_config.json"
    const val OCR_MODEL = "ocr/trocr_placas_int8.tflite"
    const val OCR_CONFIG = "ocr/config_tflite.json"
    const val OCR_VOCABULARY = "ocr/vocabulario.json"

    /** fast-plate-ocr fine-tune, the default OCR (see [OcrEngine]). The TrOCR files above are for comparison tests. */
    const val FAST_OCR_MODEL = "ocr/plate_ocr.tflite"
    const val FAST_OCR_CONFIG = "ocr/plate_ocr_config.json"
}

/** Which OCR model reads the plate crops. */
enum class OcrEngine {
    /** `trocr_placas_int8.tflite`: TrOCR encoder + greedy decoder (Pipeline.md 6). */
    TROCR,

    /** `plate_ocr.tflite`: fast-plate-ocr, one run returns every character slot. */
    FAST_PLATE_OCR,
}

/** Detector preprocessing, read from `preprocessor_config.json`. */
class DetectorConfig(val height: Int, val width: Int, val normalization: Normalization) {
    companion object {
        fun parse(json: String): DetectorConfig {
            val root = Json.parseToJsonElement(json).jsonObject
            val size = root.getObject("size")
            return DetectorConfig(
                height = size.getInt("height"),
                width = size.getInt("width"),
                normalization = Normalization(
                    rescale = root.getFloat("rescale_factor"),
                    mean = root.getFloats("image_mean"),
                    std = root.getFloats("image_std"),
                ),
            )
        }
    }
}

class OcrTokens(val start: Int, val pad: Int, val eos: Int, val maxLength: Int)

/** OCR preprocessing and special tokens, read from `config_tflite.json`. Never hardcode these (Pipeline.md 2.4). */
class OcrConfig(val height: Int, val width: Int, val normalization: Normalization, val tokens: OcrTokens) {
    companion object {
        fun parse(json: String): OcrConfig {
            val root = Json.parseToJsonElement(json).jsonObject
            val input = root.getObject("entrada")
            val tokens = root.getObject("tokens")
            return OcrConfig(
                height = input.getInt("alto"),
                width = input.getInt("ancho"),
                normalization = Normalization(
                    rescale = input.getFloat("reescalado"),
                    mean = input.getFloats("media"),
                    std = input.getFloats("desviacion"),
                ),
                tokens = OcrTokens(
                    start = tokens.getInt("start"),
                    pad = tokens.getInt("pad"),
                    eos = tokens.getInt("eos"),
                    maxLength = tokens.getInt("max_length"),
                ),
            )
        }
    }
}

/**
 * fast-plate-ocr settings, read from `plate_ocr_config.json`: the fields of the `plate_config.yaml` used to train the
 * model. Output slot `i` holds a probability per [alphabet] character; [padChar] fills the slots after the plate.
 */
class FastOcrConfig(val height: Int, val width: Int, val slots: Int, val alphabet: String, val padChar: Char) {
    init {
        require(padChar in alphabet) { "pad_char '$padChar' is not in the alphabet" }
    }

    companion object {
        fun parse(json: String): FastOcrConfig {
            val root = Json.parseToJsonElement(json).jsonObject
            val colorMode = root.getString("image_color_mode")
            require(colorMode == "rgb") { "image_color_mode '$colorMode' is not supported, only 'rgb'" }
            require(!root.getBoolean("keep_aspect_ratio")) { "keep_aspect_ratio: true is not supported" }
            val padChar = root.getString("pad_char")
            require(padChar.length == 1) { "pad_char must be one character, got '$padChar'" }
            return FastOcrConfig(
                height = root.getInt("img_height"),
                width = root.getInt("img_width"),
                slots = root.getInt("max_plate_slots"),
                alphabet = root.getString("alphabet"),
                padChar = padChar[0],
            )
        }
    }
}

/** Token id to text, read from `vocabulario.json` (keys are the ids as strings). */
class Vocabulary(private val tokens: Array<String?>) {
    val size: Int get() = tokens.size

    fun token(id: Int): String? = tokens.getOrNull(id)

    /** Concatenates the tokens in order, skipping [skip] (start, pad, eos) and unknown ids. Not trimmed. */
    fun decode(ids: List<Int>, skip: Set<Int> = emptySet()): String = buildString {
        for (id in ids) if (id !in skip) token(id)?.let(::append)
    }

    companion object {
        fun parse(json: String): Vocabulary {
            val root = Json.parseToJsonElement(json).jsonObject
            val entries = root.entries.map { (key, value) ->
                (key.toIntOrNull() ?: error("Vocabulary key '$key' is not a token id")) to value.jsonPrimitive.content
            }
            val tokens = arrayOfNulls<String>((entries.maxOfOrNull { it.first } ?: -1) + 1)
            for ((id, text) in entries) tokens[id] = text
            return Vocabulary(tokens)
        }
    }
}

/** Plate text to show or compare: uppercase, keeping only A-Z, 0-9 and '-' (Pipeline.md 6.4). */
fun normalizePlateText(raw: String): String =
    raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '-' }

private fun JsonObject.field(name: String) = this[name] ?: throw IllegalArgumentException("Missing field '$name'")
private fun JsonObject.getObject(name: String) = field(name).jsonObject
private fun JsonObject.getInt(name: String) = field(name).jsonPrimitive.int
private fun JsonObject.getFloat(name: String) = field(name).jsonPrimitive.float
private fun JsonObject.getString(name: String) = field(name).jsonPrimitive.content
private fun JsonObject.getBoolean(name: String) = field(name).jsonPrimitive.boolean
private fun JsonObject.getFloats(name: String) = field(name).jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
