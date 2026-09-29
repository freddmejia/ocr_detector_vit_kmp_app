package botix.dev.detectorlicenseplateocr.scanner

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The scanner screen. Platform-free: the camera preview comes in through [preview], which must fill the modifier
 * it is given so the guide rectangle lines up with the frames the analyzer crops.
 */
@Composable
fun ScannerScreen(
    state: ScannerUiState,
    actions: ScannerActions,
    preview: @Composable (Modifier) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val reading = state.lastReading
    LaunchedEffect(reading?.sequence) {
        if (reading?.outcome == ReadingOutcome.READ) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val region = ScanRegion.inView(maxWidth.value, maxHeight.value)
        val regionTop = maxHeight * region.top
        val regionBottom = maxHeight * region.bottom

        preview(Modifier.fillMaxSize())
        ScanOverlay(state, Modifier.fillMaxSize())

        ConfidenceHeader(
            state,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            text = if (state.isScanning) "Keep the plate inside the frame" else "Place the plate inside the frame",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = regionTop - 32.dp),
        )
        ResultPanel(
            state,
            Modifier.align(Alignment.TopCenter).offset(y = regionBottom + 20.dp).padding(horizontal = 24.dp),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HistoryStrip(state.history, onClear = actions::clearHistory)
            ZoomSelector(state.zoom, onZoom = actions::setZoom)
            ControlBar(state, actions)
        }
        ModelStatusOverlay(state.modelStatus, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun ConfidenceHeader(state: ScannerUiState, modifier: Modifier = Modifier) {
    val reading = state.lastReading?.takeIf { state.isScanning }
    val hasPlate = reading?.detectionScore != null
    Surface(
        modifier = modifier.widthIn(max = 480.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = ScannerColors.Glass,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state)
                Spacer(Modifier.width(8.dp))
                Text(
                    statusText(state),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (state.isProcessing) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = ScannerColors.Accent, strokeWidth = 2.dp)
                } else if (reading != null && reading.totalMillis > 0) {
                    Text(reading.totalMillis.asSeconds(), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ConfidenceMeter("Detection", reading?.detectionScore.takeIf { hasPlate }, Modifier.weight(1f))
                ConfidenceMeter("Reading", reading?.ocrConfidence, Modifier.weight(1f))
            }
            state.error?.let {
                Text(it, color = ScannerColors.Danger, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun statusText(state: ScannerUiState): String = when {
    state.modelStatus is ModelStatus.Loading -> "Loading models…"
    state.modelStatus is ModelStatus.Failed -> "Models unavailable"
    !state.isScanning -> "Ready to scan"
    else -> when (state.lastReading?.outcome) {
        null -> "Looking for a plate…"
        ReadingOutcome.READ -> "Plate read"
        ReadingOutcome.NO_PLATE -> "No plate in the frame"
        ReadingOutcome.TOO_SMALL -> "Plate too far"
        ReadingOutcome.NO_TEXT -> "Plate found, text unclear"
    }
}

@Composable
private fun StatusDot(state: ScannerUiState) {
    val pulse = rememberInfiniteTransition(label = "statusPulse")
    val alpha by pulse.animateFloat(1f, 0.3f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "statusAlpha")
    val color = when {
        state.modelStatus is ModelStatus.Failed -> ScannerColors.Danger
        state.isScanning -> ScannerColors.Accent
        else -> Color.White.copy(alpha = 0.6f)
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (state.isScanning) color.copy(alpha = alpha) else color))
}

@Composable
private fun ConfidenceMeter(label: String, value: Float?, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(value ?: 0f, tween(400), label = "meter")
    val color by animateColorAsState(ScannerColors.forConfidence(value), label = "meterColor")
    Column(modifier.semantics { contentDescription = "$label confidence ${value?.asPercent() ?: "not available"}" }) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(label, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(
                value?.asPercent() ?: "—",
                color = color,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = color,
            trackColor = Color.White.copy(alpha = 0.15f),
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun ResultPanel(state: ScannerUiState, modifier: Modifier = Modifier) {
    val reading = state.lastReading?.takeIf { state.isScanning }
    // Keyed on what is shown, so the same plate read twice does not re-animate.
    val key = when {
        state.modelStatus !is ModelStatus.Ready -> "none"
        reading == null -> if (state.isScanning) "searching" else "idle"
        else -> "${reading.outcome}:${reading.text}"
    }
    AnimatedContent(
        targetState = key,
        modifier = modifier,
        transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.92f)) togetherWith fadeOut(tween(120)) },
        contentAlignment = Alignment.TopCenter,
        label = "result",
    ) { shown ->
        when {
            shown == "none" -> Spacer(Modifier.height(1.dp))
            shown == "idle" -> Hint("Tap Start to begin real-time scanning", "Pinch or use the buttons below to zoom")
            shown == "searching" -> Hint("Looking for a plate…", "Hold the phone steady")
            reading?.outcome == ReadingOutcome.READ -> PlateCard(reading)
            reading?.outcome == ReadingOutcome.TOO_SMALL -> Hint("Plate too far away", "Move closer or zoom in")
            reading?.outcome == ReadingOutcome.NO_TEXT -> Hint("Couldn't read the characters", "Avoid glare and hold steady")
            else -> Hint("No plate in the frame", "Center the plate inside the rectangle")
        }
    }
}

@Composable
private fun PlateCard(reading: ScanReading) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ScannerColors.PlateBackground)
                .border(3.dp, ScannerColors.PlateInk, RoundedCornerShape(10.dp))
                .padding(horizontal = 22.dp, vertical = 6.dp)
                .semantics { contentDescription = "Plate ${reading.text}" },
        ) {
            Text(
                reading.text.orEmpty(),
                color = ScannerColors.PlateInk,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 36.sp,
                letterSpacing = 4.sp,
            )
        }
    }
}

@Composable
private fun Hint(title: String, subtitle: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = ScannerColors.Glass) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            Text(subtitle, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HistoryStrip(history: List<PlateHit>, onClear: () -> Unit) {
    AnimatedVisibility(
        visible = history.isNotEmpty(),
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            items(history, key = { it.text }) { hit ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ScannerColors.Glass,
                    border = BorderStroke(1.dp, ScannerColors.forConfidence(hit.bestOcrConfidence).copy(alpha = 0.7f)),
                    modifier = Modifier.animateItem(),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(hit.text, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        if (hit.count > 1) {
                            Text(" ×${hit.count}", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            item(key = "clear") {
                RoundIconButton(ScannerIcons.Delete, "Clear history", onClear, size = 34.dp)
            }
        }
    }
}

@Composable
private fun ZoomSelector(zoom: ZoomInfo, onZoom: (Float) -> Unit) {
    val presets = listOf(zoom.min, 1f, 2f, 5f, 10f)
        .filter { it >= zoom.min - 0.01f && it <= zoom.max + 0.01f }
        .distinctBy { (it * 10).toInt() }
        .sorted()
    if (presets.size < 2) return
    // The active preset is the largest one at or below the current ratio; it shows the exact ratio, like the
    // system camera does while pinching.
    val active = presets.lastOrNull { it <= zoom.ratio + 0.05f } ?: presets.first()
    Surface(shape = CircleShape, color = ScannerColors.Glass) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            presets.forEach { preset ->
                val selected = preset == active
                val background by animateColorAsState(if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent, label = "zoomBg")
                Box(
                    Modifier
                        .size(if (selected) 44.dp else 36.dp)
                        .align(Alignment.CenterVertically)
                        .clip(CircleShape)
                        .background(background)
                        .clickable(role = Role.Button, onClickLabel = "Zoom ${preset.asZoom()}") { onZoom(preset) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (selected) zoom.ratio.asZoom() else preset.asZoom(),
                        color = if (selected) ScannerColors.Warning else Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlBar(state: ScannerUiState, actions: ScannerActions) {
    val ready = state.modelStatus is ModelStatus.Ready
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 36.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (state.hasTorch) {
                RoundIconButton(
                    icon = if (state.torchOn) ScannerIcons.FlashOn else ScannerIcons.FlashOff,
                    description = if (state.torchOn) "Turn flashlight off" else "Turn flashlight on",
                    onClick = actions::toggleTorch,
                    tint = if (state.torchOn) ScannerColors.Warning else Color.White,
                )
            }
        }
        ScanButton(scanning = state.isScanning, enabled = ready, loading = state.modelStatus is ModelStatus.Loading, onClick = actions::toggleScanning)
        val rotation by animateFloatAsState(if (state.lens == CameraLens.FRONT) 180f else 0f, tween(400), label = "lensRotation")
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            RoundIconButton(
                icon = ScannerIcons.SwitchCamera,
                description = if (state.lens == CameraLens.BACK) "Switch to front camera" else "Switch to back camera",
                onClick = actions::switchLens,
                enabled = state.canSwitchLens,
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

@Composable
private fun ScanButton(scanning: Boolean, enabled: Boolean, loading: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(
        when {
            !enabled -> Color.White.copy(alpha = 0.25f)
            scanning -> ScannerColors.Danger
            else -> ScannerColors.Accent
        },
        label = "scanFill",
    )
    val pulse = rememberInfiniteTransition(label = "scanPulse")
    val ring by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scanRing")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
            if (scanning) {
                Box(Modifier.size(80.dp).scale(ring).clip(CircleShape).background(ScannerColors.Danger.copy(alpha = 0.25f)))
            }
            Box(
                Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(7.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .semantics { contentDescription = if (scanning) "Stop scanning" else "Start scanning" },
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
                } else {
                    AnimatedContent(scanning, label = "scanIcon") { isScanning ->
                        Icon(
                            if (isScanning) ScannerIcons.Stop else ScannerIcons.Play,
                            contentDescription = null,
                            tint = if (isScanning) Color.White else Color.Black,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
            }
        }
        Text(
            if (scanning) "Stop" else "Start",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.White,
    size: androidx.compose.ui.unit.Dp = 52.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(ScannerColors.Glass)
            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
private fun ModelStatusOverlay(status: ModelStatus, modifier: Modifier = Modifier) {
    AnimatedVisibility(status !is ModelStatus.Ready, modifier = modifier, exit = fadeOut()) {
        Surface(shape = RoundedCornerShape(20.dp), color = Color(0xE6121212), modifier = Modifier.padding(32.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (status) {
                    is ModelStatus.Failed -> {
                        Text("Couldn't load the models", color = ScannerColors.Danger, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(status.message, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
                    else -> {
                        CircularProgressIndicator(color = ScannerColors.Accent)
                        Spacer(Modifier.height(16.dp))
                        Text("Loading models…", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text("Detector and OCR, about 127 MB", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Shown when the camera cannot be used yet. */
@Composable
fun CameraPermissionScreen(permanentlyDenied: Boolean, onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(88.dp).clip(CircleShape).background(ScannerColors.Accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(ScannerIcons.Camera, contentDescription = null, tint = ScannerColors.Accent, modifier = Modifier.size(44.dp))
            }
            Text("Camera access needed", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text(
                if (permanentlyDenied) "Camera access is turned off. Enable it in Settings to scan plates."
                else "The camera is used to find and read license plates in real time. Images never leave your device.",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Surface(
                shape = CircleShape,
                color = ScannerColors.Accent,
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onRequest),
            ) {
                Text(
                    if (permanentlyDenied) "Open Settings" else "Allow camera",
                    color = Color.Black,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp),
                )
            }
        }
    }
}
