package botix.dev.detectorlicenseplateocr.scanner

import botix.dev.detectorlicenseplateocr.pipeline.PipelineResult

/** Distinct plates kept in the session history. */
const val MAX_HISTORY = 8

/**
 * What the UI shows for one pipeline run on the guide crop. [lens] is the camera the frame came from: the front
 * preview is mirrored and the analyzed frame is not, so its boxes are mirrored to line up with what the user sees.
 */
fun PipelineResult.toScanReading(lens: CameraLens, totalMillis: Long, sequence: Long): ScanReading {
    // The plate that went through the OCR if any, else the most confident one.
    val plate = plates.firstOrNull { it.text != null } ?: plates.firstOrNull()
    val outcome = when {
        plate == null -> ReadingOutcome.NO_PLATE
        !plate.readable -> ReadingOutcome.TOO_SMALL
        plate.text.isNullOrEmpty() -> ReadingOutcome.NO_TEXT
        else -> ReadingOutcome.READ
    }
    val w = imageWidth.toFloat()
    val h = imageHeight.toFloat()
    val boxes = plates.map {
        val rect = NormalizedRect(
            (it.box.x0 / w).coerceIn(0f, 1f),
            (it.box.y0 / h).coerceIn(0f, 1f),
            (it.box.x1 / w).coerceIn(0f, 1f),
            (it.box.y1 / h).coerceIn(0f, 1f),
        )
        if (lens == CameraLens.FRONT) rect.mirroredHorizontally() else rect
    }
    return ScanReading(
        outcome = outcome,
        text = plate?.text,
        detectionScore = plate?.detectionScore,
        ocrConfidence = plate?.ocrConfidence,
        plateBoxes = boxes,
        totalMillis = totalMillis,
        sequence = sequence,
    )
}

/** Moves [reading]'s plate to the front of the history, merging it with an earlier hit of the same text. */
fun List<PlateHit>.withHit(reading: ScanReading): List<PlateHit> {
    val text = reading.text ?: return this
    val existing = firstOrNull { it.text == text }
    val hit = PlateHit(
        text = text,
        bestOcrConfidence = maxOf(existing?.bestOcrConfidence ?: 0f, reading.ocrConfidence ?: 0f),
        bestDetectionScore = maxOf(existing?.bestDetectionScore ?: 0f, reading.detectionScore ?: 0f),
        count = (existing?.count ?: 0) + 1,
    )
    return (listOf(hit) + filter { it.text != text }).take(MAX_HISTORY)
}
