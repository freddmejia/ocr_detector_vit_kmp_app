package botix.dev.detectorlicenseplateocr.pipeline

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The app's pipeline (RF-DETR fp16 + plate_ocr.tflite) on the reference images through TensorFlow Lite C, the
 * counterpart of Android's `FastPlateOcrReferenceTest`. Every reading is printed so the two platforms can be compared.
 */
class IosReferencePipelineTest {
    private lateinit var pipeline: LicensePlatePipeline

    @BeforeTest
    fun load() {
        pipeline = createLicensePlatePipeline(testDir("MODELS_DIR"))
    }

    @AfterTest
    fun close() = pipeline.close()

    @Test
    fun readsTheVisiblePlates() {
        val failures = GROUND_TRUTH.mapNotNull { (image, expected) ->
            val plate = pipeline.run(loadReferenceImage(image), maxPlatesToRead = 1).plates.firstOrNull()
                ?: return@mapNotNull "$image: no plate detected"
            println("$image: '${plate.text}' (expected $expected) det=${plate.detectionScore} ocr=${plate.ocrConfidence}")
            if (plate.text != expected) "$image: '${plate.text}', expected '$expected'" else null
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun runsOnEveryReferenceImage() {
        val images = referenceImages()
        assertTrue(images.size >= GROUND_TRUTH.size, "Reference images missing: $images")
        for (image in images) {
            val rgb = loadReferenceImage(image)
            val result = pipeline.run(rgb)
            println("$image ${rgb.width}x${rgb.height}: detector ${result.detectorMillis} ms, OCR ${result.ocrMillis} ms")
            for (plate in result.plates) {
                println(
                    "  score=${plate.detectionScore.fmt()} box=[${plate.box.x0.fmt(0)}, ${plate.box.y0.fmt(0)}, " +
                        "${plate.box.x1.fmt(0)}, ${plate.box.y1.fmt(0)}] crop=${plate.cropWidth}x${plate.cropHeight} " +
                        "text=${plate.text} ocr=${plate.ocrConfidence?.fmt()}",
                )
                assertTrue(plate.detectionScore > PipelineParams().detectionThreshold)
                assertEquals(plate.readable, plate.text != null, "$image: every readable plate goes through the OCR")
                plate.ocrConfidence?.let { assertTrue(it in 0f..1f) }
            }
            assertEquals(result.plates.sortedByDescending { it.detectionScore }, result.plates)
        }
    }

    @Test
    fun readsAPlateCropDirectly() {
        // The OCR must also work on an image that is already a plate crop (Pipeline.md 1).
        val image = loadReferenceImage("images.jpeg")
        val plate = pipeline.run(image).plates.first()
        val crop = image.crop(plateCropRect(plate.box, image.width, image.height, PipelineParams()))
        assertEquals(plate.text, pipeline.readPlate(crop).text)
    }

    private fun Float.fmt(decimals: Int = 3): String {
        if (decimals == 0) return kotlin.math.round(this).toInt().toString()
        var factor = 1f
        repeat(decimals) { factor *= 10f }
        return (kotlin.math.round(this * factor) / factor).toString()
    }

    private companion object {
        /** Same table as Android's `FastPlateOcrReferenceTest`: the plate text, checked by eye. */
        val GROUND_TRUTH = mapOf(
            "gettyimages-1645361278-612x612.jpg" to "NZQ7G26",
            "images (1).jpeg" to "CVL65718",
            "images (2).jpeg" to "LJ733PS",
            "images.jpeg" to "GRF4721",
        )
    }
}
