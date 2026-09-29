package botix.dev.detectorlicenseplateocr.scanner

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Material Symbols paths (24x24), inlined to avoid depending on the discontinued icons artifact. */
internal object ScannerIcons {
    val Play = icon("Play", "M8,5v14l11,-7z")
    val Stop = icon("Stop", "M6,6h12v12H6z")
    val FlashOn = icon("FlashOn", "M7,2v11h3v9l7,-12h-4l4,-8z")
    val FlashOff = icon(
        "FlashOff",
        "M3.27,3L2,4.27l5,5V13h3v9l3.58,-6.14L17.73,20L19,18.73L3.27,3zM17,10h-4l4,-8H7v2.18l8.46,8.46L17,10z",
    )
    val SwitchCamera = icon(
        "SwitchCamera",
        "M16,7h-1l-1,-1h-4L9,7H8C6.9,7 6,7.9 6,9v6c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V9C18,7.9 17.1,7 16,7z" +
            "M12,14c-1.1,0 -2,-0.9 -2,-2c0,-1.1 0.9,-2 2,-2s2,0.9 2,2C14,13.1 13.1,14 12,14z" +
            "M8.57,0.51l4.48,4.48V2.04c4.72,0.47 8.48,4.23 8.95,8.95h2C23.34,3.02 15.49,-1.59 8.57,0.51z" +
            "M10.95,21.96C6.23,21.49 2.47,17.73 2,13.01H0c0.66,7.97 8.51,12.58 15.43,10.48l-4.48,-4.48V21.96z",
    )
    val Delete = icon("Delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    val Camera = icon(
        "Camera",
        "M12,15.2A3.2,3.2 0,1 1,12 8.8a3.2,3.2 0,0 1,0 6.4z" +
            "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9z",
    )

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
            .build()
}
