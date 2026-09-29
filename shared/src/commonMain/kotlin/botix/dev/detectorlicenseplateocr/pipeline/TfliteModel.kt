package botix.dev.detectorlicenseplateocr.pipeline

/**
 * The only thing the pipeline needs from a TFLite/LiteRT runtime: running a signature by name (Pipeline.md 9).
 * Not thread safe: callers must serialize calls to one instance.
 */
interface TfliteModel : AutoCloseable {
    val signatures: List<String>

    fun inputNames(signature: String): List<String>

    fun outputNames(signature: String): List<String>

    fun inputShape(signature: String, name: String): IntArray

    fun outputShape(signature: String, name: String): IntArray

    /**
     * Runs [signature]. Each input is a FloatArray or IntArray holding the whole tensor, flattened. Each output is
     * written into the given FloatArray, which must hold the whole tensor. Reuse the arrays between calls.
     */
    fun run(signature: String, inputs: Map<String, Any>, outputs: Map<String, FloatArray>)
}

internal fun IntArray.elementCount(): Int = fold(1) { acc, d -> acc * d }

internal fun TfliteModel.requireSignature(signature: String, file: String) {
    require(signature in signatures) { "$file has no signature '$signature' (found $signatures)" }
}
