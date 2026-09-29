package botix.dev.detectorlicenseplateocr.pipeline

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Checks the JSON files actually bundled in assets (Pipeline.md 2.1, 2.4 and 10.3). No device needed. */
class ModelAssetsTest {
    private val assets = File("src/androidMain/assets")

    private fun text(path: String) = File(assets, path).readText()

    @Test
    fun ocrConfigMatchesTheGuide() {
        val config = OcrConfig.parse(text(ModelFiles.OCR_CONFIG))
        assertEquals(128, config.height)
        assertEquals(128, config.width)
        assertEquals(listOf(0, 1, 2, 16), with(config.tokens) { listOf(start, pad, eos, maxLength) })
    }

    @Test
    fun fastOcrConfigMatchesTheModel() {
        val config = FastOcrConfig.parse(text(ModelFiles.FAST_OCR_CONFIG))
        // plate_ocr.tflite: input [1, 64, 128, 3], output [1, 10, 37].
        assertEquals(listOf(64, 128, 10), listOf(config.height, config.width, config.slots))
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_", config.alphabet)
        assertEquals('_', config.padChar)
    }

    @Test
    fun detectorConfigMatchesTheGuide() {
        val config = DetectorConfig.parse(text(ModelFiles.DETECTOR_CONFIG))
        assertEquals(576, config.height)
        assertEquals(576, config.width)
    }

    @Test
    fun vocabularyDecodesTheGuideExamples() {
        val vocabulary = Vocabulary.parse(text(ModelFiles.OCR_VOCABULARY))
        assertEquals(50265, vocabulary.size)
        assertEquals("597*LK*", vocabulary.decode(listOf(39819, 3226, 574, 530, 3226)))
        assertEquals("A", vocabulary.decode(listOf(250)))
        assertEquals(" A", vocabulary.decode(listOf(83)))
    }

    @Test
    fun bundledFilesMatchTheExportedHashes() {
        mapOf(
            ModelFiles.DETECTOR_MODEL to "f4c2675905e42082630ec7b657ac99881741b0c2d4a9b6aabbc61fe26a6d30ab",
            ModelFiles.DETECTOR_CONFIG to "4e55a721a4ca13007d7ee4bbe914958060519f64da7c6e21b8e75732d46b8fb6",
            ModelFiles.OCR_MODEL to "da7499dbdc1855c8bdf48547cfc79912db9cd7db592dae9dfca52aa62403bc6e",
            ModelFiles.OCR_CONFIG to "868f79f992a3f1591ecad72550826bf2660d2417bd266e11ef34da1ecacef60e",
            ModelFiles.OCR_VOCABULARY to "e587ea3e69db8e5d3f2155dea57e50a64d58b146f358c9a06dc19f160fbd272d",
            ModelFiles.FAST_OCR_MODEL to "ac6a129b8f2a5ba379f8b126c96fcd7c69428fdb8c2194101404a324eed72aaf",
        ).forEach { (path, expected) -> assertEquals(expected, sha256(File(assets, path)), path) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
