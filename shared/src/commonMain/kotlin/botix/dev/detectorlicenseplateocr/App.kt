package botix.dev.detectorlicenseplateocr

import androidx.compose.runtime.Composable
import botix.dev.detectorlicenseplateocr.scanner.ScannerTheme

@Composable
fun App() {
    ScannerTheme {
        ScannerRoute()
    }
}

/** Platform entry to the scanner: camera, permission and model loading around the shared ScannerScreen. */
@Composable
expect fun ScannerRoute()
