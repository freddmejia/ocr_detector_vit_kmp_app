package botix.dev.detectorlicenseplateocr.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * plate_ocr.tflite (fast-plate-ocr fine-tune) on the reference images. The detector is the same as in
 * [ReferencePipelineTest]; only the OCR changes. Logs every reading next to TrOCR's (`adb logcat -s FastPlateOcr`).
 */
@RunWith(AndroidJUnit4::class)
class FastPlateOcrReferenceTest {
    @Test
    fun readsTheVisiblePlates() {
        val failures = GROUND_TRUTH.mapNotNull { (image, expected) ->
            val plate = pipeline.run(loadImage(image), maxPlatesToRead = 1).plates.firstOrNull()
                ?: return@mapNotNull "$image: no plate detected"
            Log.i(TAG, "$image: '${plate.text}' (expected $expected) ocr=${plate.ocrConfidence}")
            if (plate.text != expected) "$image: '${plate.text}', expected '$expected'" else null
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun comparesWithTrocr() {
        val images = InstrumentationRegistry.getInstrumentation().context.assets.list("reference").orEmpty()
            .filter { it.substringAfterLast('.') in setOf("jpg", "jpeg", "png", "webp") }
        var fastHits = 0
        var trocrHits = 0
        for (image in images) {
            val rgb = loadImage(image)
            val result = pipeline.run(rgb)
            for (plate in result.plates.filter { it.readable }) {
                val crop = rgb.crop(plateCropRect(plate.box, rgb.width, rgb.height, PipelineParams()))
                val trocr = trocrReader.read(crop)
                val expected = GROUND_TRUTH[image].takeIf { plate === result.plates.first() }
                if (expected != null && plate.text == expected) fastHits++
                if (expected != null && trocr.text == expected) trocrHits++
                Log.i(TAG, "%s crop=%dx%d fast='%s' (%.2f) trocr='%s' (%.2f)%s".format(
                    image, plate.cropWidth, plate.cropHeight, plate.text, plate.ocrConfidence, trocr.text,
                    trocr.confidence, expected?.let { " expected='$it'" }.orEmpty(),
                ))
                assertTrue(plate.ocrConfidence!! in 0f..1f)
            }
            Log.i(TAG, "$image: OCR ${result.ocrMillis} ms (fast-plate-ocr, ${result.plates.count { it.readable }} crops)")
        }
        Log.i(TAG, "Exact readings on ${GROUND_TRUTH.size} visible plates: fast-plate-ocr $fastHits, TrOCR $trocrHits")
    }

    private fun loadImage(image: String): RgbImage {
        val bitmap = InstrumentationRegistry.getInstrumentation().context.assets.open("reference/$image").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } ?: error("Cannot decode $image")
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) pixels[i] = pixels[i] and 0xFFFFFF
        return RgbImage(bitmap.width, bitmap.height, pixels)
    }

    companion object {
        private const val TAG = "FastPlateOcr"

        /** What the plate says in each image with one clearly readable plate (checked by eye, spaces and dashes removed). */
        private val GROUND_TRUTH = mapOf(
            "gettyimages-1645361278-612x612.jpg" to "NZQ7G26",
            "images (1).jpeg" to "CVL65718",
            "images (2).jpeg" to "LJ733PS",
            "images.jpeg" to "GRF4721",
        )

        private lateinit var pipeline: LicensePlatePipeline
        private lateinit var trocrReader: PlateTextReader

        @JvmStatic
        @BeforeClass
        fun load() {
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            pipeline = createLicensePlatePipeline(assets, ocrEngine = OcrEngine.FAST_PLATE_OCR)
            trocrReader = createPlateTextReader(assets, OcrEngine.TROCR)
        }

        @JvmStatic
        @AfterClass
        fun close() {
            pipeline.close()
            trocrReader.close()
        }
    }
}
