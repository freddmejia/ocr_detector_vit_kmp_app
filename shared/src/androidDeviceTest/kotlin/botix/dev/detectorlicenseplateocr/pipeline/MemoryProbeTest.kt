package botix.dev.detectorlicenseplateocr.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// TEMPORARY diagnostic: memory and speed of an OCR model file placed in the test app's files dir.
@RunWith(AndroidJUnit4::class)
class MemoryProbeTest {
    private fun mem(stage: String) {
        val status = File("/proc/self/status").readLines().filter { it.startsWith("RssAnon") || it.startsWith("RssFile") }
        Log.i("MemoryProbe", "$stage: ${status.joinToString { it.replace(Regex("\\s+"), " ") }}")
    }

    @Test
    fun probe() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val args = InstrumentationRegistry.getArguments()
        val file = File(context.filesDir, args.getString("model", "ocr_dyn.tflite"))
        val xnn = args.getString("xnn", "true").toBoolean()
        val threads = args.getString("threads", "4").toInt()
        mem("start ${file.name} xnn=$xnn threads=$threads")
        var t = System.currentTimeMillis()
        val detector = PlateDetector(
            args.getString("detector")?.let { AndroidTfliteModel.fromFile(File(context.filesDir, it), threads, true) }
                ?: AndroidTfliteModel.fromAsset(context.assets, ModelFiles.DETECTOR_MODEL, threads, true),
            DetectorConfig.parse(context.assets.open(ModelFiles.DETECTOR_CONFIG).bufferedReader().readText()),
        )
        mem("detector loaded (${System.currentTimeMillis() - t} ms)")
        t = System.currentTimeMillis()
        val model = AndroidTfliteModel.fromFile(file, threads, xnn)
        val ocr = PlateOcr(
            model,
            OcrConfig.parse(context.assets.open(ModelFiles.OCR_CONFIG).bufferedReader().readText()),
            Vocabulary.parse(context.assets.open(ModelFiles.OCR_VOCABULARY).bufferedReader().readText()),
        )
        mem("loaded (${System.currentTimeMillis() - t} ms)")
        val image = context.assets.open("reference/images (1).jpeg").use { android.graphics.BitmapFactory.decodeStream(it) }
        val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
        val crop = RgbImage(image.width, image.height, pixels.map { it and 0xFFFFFF }.toIntArray()).crop(PixelRect(229, 304, 412, 369))
        val full = RgbImage(image.width, image.height, pixels.map { it and 0xFFFFFF }.toIntArray())
        repeat(3) {
            t = System.currentTimeMillis()
            val found = detector.detect(full, 0.4f)
            mem("detect ${found.size} best=${found.firstOrNull()?.score} (${System.currentTimeMillis() - t} ms)")
        }
        repeat(3) {
            t = System.currentTimeMillis()
            val result = ocr.read(crop)
            mem("read ${result.text} conf=${result.confidence} (${System.currentTimeMillis() - t} ms)")
        }
        model.close()
    }
}
