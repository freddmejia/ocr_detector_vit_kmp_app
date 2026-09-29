package botix.dev.detectorlicenseplateocr.pipeline

import android.content.res.AssetManager
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * [TfliteModel] on LiteRT's `Interpreter` (shipped inside `com.google.ai.edge.litert:litert`), which runs signatures
 * by name and exposes their tensor names and shapes. Input and output buffers are allocated once per tensor and
 * reused: the OCR decoder alone returns ~3.2 MB per step.
 */
class AndroidTfliteModel private constructor(private val interpreter: Interpreter) : TfliteModel {
    private val buffers = HashMap<String, ByteBuffer>()

    override val signatures: List<String> = interpreter.signatureKeys.toList()

    override fun inputNames(signature: String) = interpreter.getSignatureInputs(signature).toList()

    override fun outputNames(signature: String) = interpreter.getSignatureOutputs(signature).toList()

    override fun inputShape(signature: String, name: String): IntArray =
        interpreter.getInputTensorFromSignature(name, signature).shape()

    override fun outputShape(signature: String, name: String): IntArray =
        interpreter.getOutputTensorFromSignature(name, signature).shape()

    override fun run(signature: String, inputs: Map<String, Any>, outputs: Map<String, FloatArray>) {
        val inputBuffers = inputs.mapValues { (name, value) ->
            when (value) {
                is FloatArray -> buffer("$signature/in/$name", value.size).also { it.asFloatBuffer().put(value) }
                is IntArray -> buffer("$signature/in/$name", value.size).also { it.asIntBuffer().put(value) }
                else -> throw IllegalArgumentException("Input '$name' must be a FloatArray or IntArray")
            }
        }
        val outputBuffers = outputs.mapValues { (name, value) -> buffer("$signature/out/$name", value.size) }
        interpreter.runSignature(inputBuffers, outputBuffers, signature)
        for ((name, target) in outputs) {
            val buffer = outputBuffers.getValue(name)
            buffer.rewind()
            buffer.asFloatBuffer().get(target)
        }
    }

    private fun buffer(key: String, elements: Int): ByteBuffer {
        val existing = buffers[key]
        val buffer = if (existing != null && existing.capacity() == elements * 4) existing
        else ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder()).also { buffers[key] = it }
        buffer.rewind()
        return buffer
    }

    override fun close() = interpreter.close()

    companion object {
        /** Memory-maps a model file, e.g. one downloaded to private storage on first use (Pipeline.md 2.3). */
        fun fromFile(file: java.io.File, threads: Int, useXnnpack: Boolean = true): AndroidTfliteModel {
            val model = FileInputStream(file).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
            val options = Interpreter.Options().setNumThreads(threads).setUseXNNPACK(useXnnpack)
            return AndroidTfliteModel(Interpreter(model, options))
        }

        /**
         * Memory-maps [path] straight from the APK (no copy), which requires the asset to be stored uncompressed
         * (`noCompress += "tflite"` in the app module).
         */
        fun fromAsset(assets: AssetManager, path: String, threads: Int, useXnnpack: Boolean = true): AndroidTfliteModel {
            val model = try {
                assets.openFd(path).use { fd ->
                    FileInputStream(fd.fileDescriptor).channel.use {
                        it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                    }
                }
            } catch (e: FileNotFoundException) {
                throw IllegalStateException("Model asset '$path' is missing or compressed", e)
            }
            val options = Interpreter.Options().setNumThreads(threads).setUseXNNPACK(useXnnpack)
            return AndroidTfliteModel(Interpreter(model, options))
        }
    }
}

/**
 * Loads the detector, the [ocrEngine] OCR and their JSON files from `androidMain/assets` and validates them against
 * each other.
 */
fun createLicensePlatePipeline(
    assets: AssetManager,
    threads: Int = defaultThreadCount(),
    params: PipelineParams = PipelineParams(),
    ocrEngine: OcrEngine = OcrEngine.FAST_PLATE_OCR,
): LicensePlatePipeline {
    val detectorConfig = DetectorConfig.parse(assets.text(ModelFiles.DETECTOR_CONFIG))
    val detector = AndroidTfliteModel.fromAsset(assets, ModelFiles.DETECTOR_MODEL, threads)
    val ocr = try {
        createPlateTextReader(assets, ocrEngine, threads)
    } catch (e: Throwable) {
        detector.close()
        throw e
    }
    return try {
        LicensePlatePipeline(detector, detectorConfig, ocr, params)
    } catch (e: Throwable) {
        detector.close()
        ocr.close()
        throw e
    }
}

/** Loads only the [engine] OCR, e.g. to read crops that are already plates or to compare engines. */
fun createPlateTextReader(
    assets: AssetManager,
    engine: OcrEngine,
    threads: Int = defaultThreadCount(),
): PlateTextReader = when (engine) {
    OcrEngine.TROCR -> {
        val config = OcrConfig.parse(assets.text(ModelFiles.OCR_CONFIG))
        val vocabulary = Vocabulary.parse(assets.text(ModelFiles.OCR_VOCABULARY))
        // Without XNNPACK: it repacks the int8 weights of both signatures into its own buffers (~2.9 GB of RAM measured
        // on an emulator, killed by the low-memory killer). The built-in hybrid kernels read the int8 weights straight
        // from the memory-mapped file.
        val model = AndroidTfliteModel.fromAsset(assets, ModelFiles.OCR_MODEL, threads, useXnnpack = false)
        try {
            PlateOcr(model, config, vocabulary)
        } catch (e: Throwable) {
            model.close()
            throw e
        }
    }
    OcrEngine.FAST_PLATE_OCR -> {
        val config = FastOcrConfig.parse(assets.text(ModelFiles.FAST_OCR_CONFIG))
        // 1.6 MB model: XNNPACK's copy of the weights is negligible.
        val model = AndroidTfliteModel.fromAsset(assets, ModelFiles.FAST_OCR_MODEL, threads)
        try {
            FastPlateOcr(model, config)
        } catch (e: Throwable) {
            model.close()
            throw e
        }
    }
}

private fun AssetManager.text(path: String) = open(path).bufferedReader().use { it.readText() }

/** Up to 4 threads: beyond the big cores, extra threads land on little cores and slow inference down. */
fun defaultThreadCount(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
