@file:OptIn(ExperimentalForeignApi::class)

package botix.dev.detectorlicenseplateocr.pipeline

import cnames.structs.TfLiteSignatureRunner
import cnames.structs.TfLiteTensor
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import tensorflow.lite.c.TfLiteInterpreterCreate
import tensorflow.lite.c.TfLiteInterpreterDelete
import tensorflow.lite.c.TfLiteInterpreterGetSignatureCount
import tensorflow.lite.c.TfLiteInterpreterGetSignatureKey
import tensorflow.lite.c.TfLiteInterpreterGetSignatureRunner
import tensorflow.lite.c.TfLiteInterpreterOptionsCreate
import tensorflow.lite.c.TfLiteInterpreterOptionsDelete
import tensorflow.lite.c.TfLiteInterpreterOptionsSetNumThreads
import tensorflow.lite.c.TfLiteModelCreateFromFile
import tensorflow.lite.c.TfLiteModelDelete
import tensorflow.lite.c.TfLiteSignatureRunnerAllocateTensors
import tensorflow.lite.c.TfLiteSignatureRunnerDelete
import tensorflow.lite.c.TfLiteSignatureRunnerGetInputCount
import tensorflow.lite.c.TfLiteSignatureRunnerGetInputName
import tensorflow.lite.c.TfLiteSignatureRunnerGetInputTensor
import tensorflow.lite.c.TfLiteSignatureRunnerGetOutputCount
import tensorflow.lite.c.TfLiteSignatureRunnerGetOutputName
import tensorflow.lite.c.TfLiteSignatureRunnerGetOutputTensor
import tensorflow.lite.c.TfLiteSignatureRunnerInvoke
import tensorflow.lite.c.TfLiteTensorByteSize
import tensorflow.lite.c.TfLiteTensorCopyFromBuffer
import tensorflow.lite.c.TfLiteTensorCopyToBuffer
import tensorflow.lite.c.TfLiteTensorDim
import tensorflow.lite.c.TfLiteTensorNumDims
import tensorflow.lite.c.TfLiteTensorType
import tensorflow.lite.c.kTfLiteFloat32
import tensorflow.lite.c.kTfLiteInt32
import tensorflow.lite.c.kTfLiteOk

/**
 * [TfliteModel] on the TensorFlow Lite C signature-runner API (`c_api.h` 2.17), the iOS counterpart of
 * `AndroidTfliteModel`. `TfLiteModelCreateFromFile` memory-maps the file. One runner per signature, created and
 * allocated once; inputs and outputs are copied straight between the caller's arrays and the tensors, so nothing is
 * allocated per call.
 */
class IosTfliteModel private constructor(
    private val file: String,
    private val model: CPointer<cnames.structs.TfLiteModel>,
    private val options: CPointer<cnames.structs.TfLiteInterpreterOptions>,
    private val interpreter: CPointer<cnames.structs.TfLiteInterpreter>,
) : TfliteModel {
    private class Runner(
        val pointer: CPointer<TfLiteSignatureRunner>,
        val inputs: List<String>,
        val outputs: List<String>,
    )

    private val runners: Map<String, Runner>

    override val signatures: List<String>

    init {
        signatures = (0 until TfLiteInterpreterGetSignatureCount(interpreter)).map {
            TfLiteInterpreterGetSignatureKey(interpreter, it)?.toKString() ?: error("$file: signature $it has no key")
        }
        val created = LinkedHashMap<String, Runner>()
        try {
            for (key in signatures) {
                val runner = TfLiteInterpreterGetSignatureRunner(interpreter, key)
                    ?: error("$file: cannot create a runner for signature '$key'")
                created[key] = Runner(
                    runner,
                    inputs = (0 until TfLiteSignatureRunnerGetInputCount(runner).toInt()).map {
                        TfLiteSignatureRunnerGetInputName(runner, it)!!.toKString()
                    },
                    outputs = (0 until TfLiteSignatureRunnerGetOutputCount(runner).toInt()).map {
                        TfLiteSignatureRunnerGetOutputName(runner, it)!!.toKString()
                    },
                )
                check(TfLiteSignatureRunnerAllocateTensors(runner) == kTfLiteOk) {
                    "$file: cannot allocate the tensors of signature '$key'"
                }
            }
        } catch (e: Throwable) {
            created.values.forEach { TfLiteSignatureRunnerDelete(it.pointer) }
            throw e
        }
        runners = created
    }

    private fun runner(signature: String) =
        runners[signature] ?: throw IllegalArgumentException("$file has no signature '$signature' (found $signatures)")

    override fun inputNames(signature: String) = runner(signature).inputs

    override fun outputNames(signature: String) = runner(signature).outputs

    override fun inputShape(signature: String, name: String): IntArray = inputTensor(runner(signature), name).shape()

    override fun outputShape(signature: String, name: String): IntArray = outputTensor(runner(signature), name).shape()

    override fun run(signature: String, inputs: Map<String, Any>, outputs: Map<String, FloatArray>) {
        val runner = runner(signature)
        for ((name, value) in inputs) {
            val tensor = inputTensor(runner, name)
            val status = when (value) {
                is FloatArray -> {
                    tensor.requireType(kTfLiteFloat32, name, "FloatArray")
                    tensor.requireBytes(value.size * Float.SIZE_BYTES, name)
                    value.usePinned { TfLiteTensorCopyFromBuffer(tensor, it.addressOf(0), TfLiteTensorByteSize(tensor)) }
                }
                is IntArray -> {
                    tensor.requireType(kTfLiteInt32, name, "IntArray")
                    tensor.requireBytes(value.size * Int.SIZE_BYTES, name)
                    value.usePinned { TfLiteTensorCopyFromBuffer(tensor, it.addressOf(0), TfLiteTensorByteSize(tensor)) }
                }
                else -> throw IllegalArgumentException("Input '$name' must be a FloatArray or IntArray")
            }
            check(status == kTfLiteOk) { "$file: cannot write input '$name' of '$signature'" }
        }
        check(TfLiteSignatureRunnerInvoke(runner.pointer) == kTfLiteOk) { "$file: signature '$signature' failed" }
        for ((name, target) in outputs) {
            val tensor = outputTensor(runner, name)
            tensor.requireType(kTfLiteFloat32, name, "FloatArray")
            tensor.requireBytes(target.size * Float.SIZE_BYTES, name)
            val status = target.usePinned {
                TfLiteTensorCopyToBuffer(tensor, it.addressOf(0), TfLiteTensorByteSize(tensor))
            }
            check(status == kTfLiteOk) { "$file: cannot read output '$name' of '$signature'" }
        }
    }

    private fun inputTensor(runner: Runner, name: String): CPointer<TfLiteTensor> =
        TfLiteSignatureRunnerGetInputTensor(runner.pointer, name) ?: throw IllegalArgumentException("$file: no input '$name'")

    private fun outputTensor(runner: Runner, name: String): CPointer<TfLiteTensor> =
        TfLiteSignatureRunnerGetOutputTensor(runner.pointer, name) ?: throw IllegalArgumentException("$file: no output '$name'")

    private fun CPointer<TfLiteTensor>.shape() = IntArray(TfLiteTensorNumDims(this)) { TfLiteTensorDim(this, it) }

    private fun CPointer<TfLiteTensor>.requireType(type: UInt, name: String, kotlinType: String) {
        require(TfLiteTensorType(this) == type) { "$file: tensor '$name' is not a $kotlinType tensor" }
    }

    private fun CPointer<TfLiteTensor>.requireBytes(bytes: Int, name: String) {
        val expected = TfLiteTensorByteSize(this).toLong()
        require(bytes.toLong() == expected) { "$file: tensor '$name' holds $expected bytes, got an array of $bytes" }
    }

    /** Deletion order: runners, interpreter, options, model. */
    override fun close() {
        runners.values.forEach { TfLiteSignatureRunnerDelete(it.pointer) }
        TfLiteInterpreterDelete(interpreter)
        TfLiteInterpreterOptionsDelete(options)
        TfLiteModelDelete(model)
    }

    companion object {
        fun fromFile(path: String, threads: Int): IosTfliteModel {
            require(NSFileManager.defaultManager.fileExistsAtPath(path)) { "Model file '$path' is missing" }
            val model = TfLiteModelCreateFromFile(path) ?: error("Cannot load the model '$path'")
            val options = TfLiteInterpreterOptionsCreate()
            if (options == null) {
                TfLiteModelDelete(model)
                error("Cannot create interpreter options")
            }
            TfLiteInterpreterOptionsSetNumThreads(options, threads)
            val interpreter = TfLiteInterpreterCreate(model, options)
            if (interpreter == null) {
                TfLiteInterpreterOptionsDelete(options)
                TfLiteModelDelete(model)
                error("Cannot create an interpreter for '$path'")
            }
            return try {
                IosTfliteModel(path.substringAfterLast('/'), model, options, interpreter)
            } catch (e: Throwable) {
                TfLiteInterpreterDelete(interpreter)
                TfLiteInterpreterOptionsDelete(options)
                TfLiteModelDelete(model)
                throw e
            }
        }
    }
}

/**
 * Loads the detector, the [ocrEngine] OCR and their JSON files from [modelsDir] (the app bundle's `models` folder,
 * a copy of `androidMain/assets`) and validates them against each other.
 */
fun createLicensePlatePipeline(
    modelsDir: String,
    threads: Int = defaultThreadCount(),
    params: PipelineParams = PipelineParams(),
    ocrEngine: OcrEngine = OcrEngine.FAST_PLATE_OCR,
): LicensePlatePipeline {
    val detectorConfig = DetectorConfig.parse(readText("$modelsDir/${ModelFiles.DETECTOR_CONFIG}"))
    val detector = IosTfliteModel.fromFile("$modelsDir/${ModelFiles.DETECTOR_MODEL}", threads)
    val ocr = try {
        createPlateTextReader(modelsDir, ocrEngine, threads)
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
    modelsDir: String,
    engine: OcrEngine,
    threads: Int = defaultThreadCount(),
): PlateTextReader = when (engine) {
    OcrEngine.TROCR -> {
        val config = OcrConfig.parse(readText("$modelsDir/${ModelFiles.OCR_CONFIG}"))
        val vocabulary = Vocabulary.parse(readText("$modelsDir/${ModelFiles.OCR_VOCABULARY}"))
        val model = IosTfliteModel.fromFile("$modelsDir/${ModelFiles.OCR_MODEL}", threads)
        try {
            PlateOcr(model, config, vocabulary)
        } catch (e: Throwable) {
            model.close()
            throw e
        }
    }
    OcrEngine.FAST_PLATE_OCR -> {
        val config = FastOcrConfig.parse(readText("$modelsDir/${ModelFiles.FAST_OCR_CONFIG}"))
        // The float32 copy: TFLite C 2.17 cannot run the hybrid layers of plate_ocr.tflite (see ModelFiles).
        val model = IosTfliteModel.fromFile("$modelsDir/${ModelFiles.FAST_OCR_MODEL_FLOAT}", threads)
        try {
            FastPlateOcr(model, config)
        } catch (e: Throwable) {
            model.close()
            throw e
        }
    }
}

private fun readText(path: String): String =
    NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: error("Cannot read '$path'")

/**
 * Up to 2 threads: iPhones have 2 performance cores, and XNNPACK splits work evenly, so extra threads on the
 * efficiency cores make every inference wait for the slowest one.
 */
fun defaultThreadCount(): Int = NSProcessInfo.processInfo.activeProcessorCount.toInt().coerceIn(1, 2)
