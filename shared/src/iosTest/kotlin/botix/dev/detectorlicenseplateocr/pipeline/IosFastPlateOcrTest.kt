@file:OptIn(ExperimentalForeignApi::class)

package botix.dev.detectorlicenseplateocr.pipeline

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IosPort.md task 4: the retrained fast-plate-ocr model and its `plate_config.yaml` load and run through TensorFlow
 * Lite C, without a camera. iOS loads the float32 copy of the model (ModelFiles.FAST_OCR_MODEL_FLOAT).
 */
class IosFastPlateOcrTest {
    private val modelsDir = testDir("MODELS_DIR")

    private fun config() = FastOcrConfig.parse(
        NSString.stringWithContentsOfFile("$modelsDir/${ModelFiles.FAST_OCR_CONFIG}", NSUTF8StringEncoding, null)
            ?: error("Cannot read ${ModelFiles.FAST_OCR_CONFIG}"),
    )

    @Test
    fun parsesTheExportedPlateConfig() {
        val config = config()
        assertEquals(64, config.height)
        assertEquals(128, config.width)
        assertEquals(10, config.slots)
        assertEquals(37, config.alphabet.length)
        assertEquals('_', config.padChar)
    }

    @Test
    fun modelHasOneSignatureWithTheConfigShapes() {
        IosTfliteModel.fromFile("$modelsDir/${ModelFiles.FAST_OCR_MODEL_FLOAT}", threads = 1).use { model ->
            assertEquals(1, model.signatures.size)
            val signature = model.signatures.single()
            assertContentEquals(intArrayOf(1, 64, 128, 3), model.inputShape(signature, model.inputNames(signature).single()))
            assertContentEquals(intArrayOf(1, 10, 37), model.outputShape(signature, model.outputNames(signature).single()))
        }
    }

    @Test
    fun readsAnyImage() {
        val model = IosTfliteModel.fromFile("$modelsDir/${ModelFiles.FAST_OCR_MODEL_FLOAT}", threads = 1)
        FastPlateOcr(model, config()).use { ocr ->
            val image = RgbImage(200, 100, IntArray(200 * 100) { if (it % 7 == 0) 0x000000 else 0xFFFFFF })
            val result = ocr.read(image)
            assertTrue(result.confidence in 0f..1f, "confidence ${result.confidence}")
            assertEquals(10, result.tokenIds.size)
        }
    }

    @Test
    fun createsTheAppPipeline() {
        createLicensePlatePipeline(modelsDir).close()
    }
}
