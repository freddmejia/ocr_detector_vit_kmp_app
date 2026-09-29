package botix.dev.detectorlicenseplateocr.scanner

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

internal object ScannerColors {
    val Accent = Color(0xFF00E676)
    val Warning = Color(0xFFFFC400)
    val Danger = Color(0xFFFF5252)
    val PlateBox = Color(0xFF2979FF)
    val Scrim = Color(0x99000000)
    val Glass = Color(0x8C000000)
    val PlateBackground = Color(0xFFFAFAFA)
    val PlateInk = Color(0xFF111111)

    /** Green from 80 %, amber from 50 %, red below. */
    fun forConfidence(value: Float?): Color = when {
        value == null -> Color.White.copy(alpha = 0.4f)
        value >= 0.8f -> Accent
        value >= 0.5f -> Warning
        else -> Danger
    }
}

@Composable
fun ScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ScannerColors.Accent,
            onPrimary = Color.Black,
            secondary = ScannerColors.Warning,
            error = ScannerColors.Danger,
            background = Color.Black,
            surface = Color(0xFF121212),
        ),
        content = content,
    )
}

internal fun Float.asPercent(): String = "${(this * 100).roundToInt()}%"

internal fun Float.asZoom(): String {
    val tenths = (this * 10).roundToInt()
    return if (tenths % 10 == 0) "${tenths / 10}×" else "${tenths / 10}.${tenths % 10}×"
}

internal fun Long.asSeconds(): String {
    val tenths = (this + 50) / 100
    return "${tenths / 10}.${tenths % 10} s"
}
