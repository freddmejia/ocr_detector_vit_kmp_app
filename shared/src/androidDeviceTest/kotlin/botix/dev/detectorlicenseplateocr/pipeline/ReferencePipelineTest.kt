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
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pipeline.md 10.4: the full pipeline on the reference images must reproduce `lecturas_tflite.csv` from
 * `Pipeline_TFLite.ipynb` (int8 OCR, threshold 0.4). Tolerances: same plate count, confidence +/-0.02, boxes +/-3 px,
 * identical text. A plate within +/-0.02 of the threshold may appear or not.
 */
@RunWith(AndroidJUnit4::class)
class ReferencePipelineTest {
    private class Expected(val image: String, val score: Float, val box: List<Float>, val crop: String, val text: String)

    @Test
    fun reproducesTheReferenceReadings() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val expected = assets.open("reference/lecturas_tflite.csv").bufferedReader().readLines().drop(1).map(::parseRow)
        val failures = mutableListOf<String>()

        for ((image, rows) in expected.groupBy { it.image }) {
            val bitmap = assets.open("reference/$image").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            } ?: error("Cannot decode $image")
            val rgb = bitmap.toRgb()
            val result = pipeline.run(rgb)
            Log.i(TAG, "$image ${rgb.width}x${rgb.height}: detector ${result.detectorMillis} ms, OCR ${result.ocrMillis} ms")
            result.plates.forEach {
                Log.i(TAG, "  score=%.3f box=[%.0f, %.0f, %.0f, %.0f] crop=%dx%d text=%s ocr=%s".format(
                    it.detectionScore, it.box.x0, it.box.y0, it.box.x1, it.box.y1, it.cropWidth, it.cropHeight, it.text, it.ocrConfidence,
                ))
            }

            val borderline = { score: Float -> abs(score - 0.4f) <= 0.02f }
            val actual = result.plates.filterNot { borderline(it.detectionScore) }
            val wanted = rows.filterNot { borderline(it.score) }
            if (actual.size != wanted.size) {
                failures += "$image: ${actual.size} plates, expected ${wanted.size}"
                continue
            }
            for ((plate, row) in actual.zip(wanted)) {
                val box = listOf(plate.box.x0, plate.box.y0, plate.box.x1, plate.box.y1)
                if (abs(plate.detectionScore - row.score) > 0.02f) failures += "$image: score ${plate.detectionScore} vs ${row.score}"
                if (box.zip(row.box).any { (a, b) -> abs(a - b) > 3f }) failures += "$image: box $box vs ${row.box}"
                val text = if (plate.readable) plate.text.orEmpty() else ""
                if (text != row.text) failures += "$image: text '$text' vs '${row.text}'"
                val readable = row.crop.substringBefore('x').toInt() >= PipelineParams().minOcrWidth
                if (plate.readable != readable) failures += "$image: readable ${plate.readable} vs $readable"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun readsAPlateCropDirectly() {
        // The OCR must also work on an image that is already a plate crop (Pipeline.md 1).
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val image = assets.open("reference/images (1).jpeg").use { BitmapFactory.decodeStream(it) }.toRgb()
        val plate = pipeline.run(image).plates.first()
        val crop = image.crop(plateCropRect(plate.box, image.width, image.height, PipelineParams()))
        val result = pipeline.readPlate(crop)
        assertEquals("CUL718", result.text)
        assertTrue(result.confidence in 0f..1f)
    }

    private fun parseRow(line: String): Expected {
        // imagen,confianza,"[x0, y0, x1, y1]",recorte,texto
        val box = line.substringAfter('"').substringBefore('"')
        val before = line.substringBefore(",\"").split(',')
        val after = line.substringAfterLast('"').removePrefix(",").split(',')
        return Expected(
            image = before[0],
            score = before[1].toFloat(),
            box = box.trim('[', ']').split(',').map { it.trim().toFloat() },
            crop = after[0],
            text = after.getOrElse(1) { "" },
        )
    }

    private fun Bitmap.toRgb(): RgbImage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) pixels[i] = pixels[i] and 0xFFFFFF
        return RgbImage(width, height, pixels)
    }

    companion object {
        private const val TAG = "ReferencePipelineTest"
        private lateinit var pipeline: LicensePlatePipeline

        @JvmStatic
        @BeforeClass
        fun load() {
            pipeline = createLicensePlatePipeline(
                InstrumentationRegistry.getInstrumentation().context.assets,
                ocrEngine = OcrEngine.TROCR,
            )
        }

        @JvmStatic
        @AfterClass
        fun close() = pipeline.close()
    }
}
