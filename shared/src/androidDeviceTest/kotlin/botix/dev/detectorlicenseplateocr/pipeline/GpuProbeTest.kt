package botix.dev.detectorlicenseplateocr.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

// TEMPORARY diagnostic: detector on LiteRT's built-in GPU accelerator.
@RunWith(AndroidJUnit4::class)
class GpuProbeTest {
    @Test
    fun probe() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val args = InstrumentationRegistry.getArguments()
        val accelerator = Accelerator.valueOf(args.getString("acc", "GPU"))
        val file = args.getString("detector")
        val options = CompiledModel.Options(accelerator)
        var t = System.currentTimeMillis()
        val model = if (file != null) CompiledModel.create(File(context.filesDir, file).absolutePath, options, null)
        else CompiledModel.create(context.assets, ModelFiles.DETECTOR_MODEL, options, null)
        Log.i("GpuProbe", "created $accelerator ${file ?: "asset"} in ${System.currentTimeMillis() - t} ms")
        val cfg = DetectorConfig.parse(context.assets.open(ModelFiles.DETECTOR_CONFIG).bufferedReader().readText())
        val image = context.assets.open("reference/images (1).jpeg").use { android.graphics.BitmapFactory.decodeStream(it) }
        val pixels = IntArray(image.width * image.height).also { image.getPixels(it, 0, image.width, 0, 0, image.width, image.height) }
        val rgb = RgbImage(image.width, image.height, pixels.map { it and 0xFFFFFF }.toIntArray())
        val input = rgb.toNchw(cfg.width, cfg.height, cfg.normalization)
        val inputs = model.createInputBuffers()
        val outputs = model.createOutputBuffers()
        repeat(4) {
            t = System.currentTimeMillis()
            inputs[0].writeFloat(input)
            model.run(inputs, outputs)
            val a = outputs[0].readFloat()
            val b = outputs[1].readFloat()
            val (logits, boxes) = if (a.size < b.size) a to b else b to a
            val found = decodeDetections(logits, boxes, logits.size, rgb.width, rgb.height, 0.4f)
            Log.i("GpuProbe", "run $it: ${System.currentTimeMillis() - t} ms, ${found.size} plates, best ${found.firstOrNull()}")
        }
        model.close()
    }
}
