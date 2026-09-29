package botix.dev.detectorlicenseplateocr.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview

private object NoActions : ScannerActions {
    override fun toggleScanning() = Unit
    override fun switchLens() = Unit
    override fun setZoom(ratio: Float) = Unit
    override fun toggleTorch() = Unit
    override fun clearHistory() = Unit
}

@Composable
private fun FakeCamera(modifier: Modifier) {
    Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF37474F), Color(0xFF263238), Color(0xFF455A64)))))
}

@Preview
@Composable
private fun ScannerReadingPreview() {
    ScannerTheme {
        ScannerScreen(
            state = ScannerUiState(
                modelStatus = ModelStatus.Ready,
                isScanning = true,
                canSwitchLens = true,
                hasTorch = true,
                zoom = ZoomInfo(ratio = 1.6f, min = 0.6f, max = 8f),
                lastReading = ScanReading(
                    outcome = ReadingOutcome.READ,
                    text = "CUL718",
                    detectionScore = 0.897f,
                    ocrConfidence = 0.74f,
                    plateBoxes = listOf(NormalizedRect(0.22f, 0.3f, 0.78f, 0.7f)),
                    totalMillis = 1840,
                    sequence = 1,
                ),
                history = listOf(PlateHit("CUL718", 0.74f, 0.9f, 3), PlateHit("GRF474", 0.41f, 0.88f, 1)),
            ),
            actions = NoActions,
            preview = { FakeCamera(it) },
        )
    }
}

@Preview
@Composable
private fun ScannerIdlePreview() {
    ScannerTheme {
        ScannerScreen(
            state = ScannerUiState(modelStatus = ModelStatus.Ready, canSwitchLens = true, zoom = ZoomInfo(1f, 1f, 10f)),
            actions = NoActions,
            preview = { FakeCamera(it) },
        )
    }
}

@Preview
@Composable
private fun ScannerLoadingPreview() {
    ScannerTheme {
        ScannerScreen(state = ScannerUiState(), actions = NoActions, preview = { FakeCamera(it) })
    }
}
