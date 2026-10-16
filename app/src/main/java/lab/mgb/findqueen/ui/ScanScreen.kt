package lab.mgb.findqueen.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import lab.mgb.findqueen.analysis.BeeVerdict.Outcome
import lab.mgb.findqueen.analysis.BeeVerdict
import lab.mgb.findqueen.analysis.BeepGate
import lab.mgb.findqueen.analysis.FrameStats
import lab.mgb.findqueen.camera.BeeAnalyzer
import lab.mgb.findqueen.camera.CameraPreview
import lab.mgb.findqueen.camera.LiveResult
import lab.mgb.findqueen.debug.DebugLog
import lab.mgb.findqueen.debug.DebugSession
import lab.mgb.findqueen.detection.BeeChecker
import java.util.Locale

private val Warn = Color(0xFFFFE082)
private val BeeGreen = Color(0xFF2E7D32)
private val NoBeeRed = Color(0xFFC62828)

private const val BEEP_VOLUME = 90 // 0..100
private const val BEEP_MS = 200

/**
 * How the latest live result is marked: a border colour around the stream, and a badge
 * only when bees are found (then the app moves on to scanning the frame). No / unsure
 * results show no badge, just the border.
 */
private data class Marker(val color: Color, val badge: String?)

private fun LiveResult.marker(hasModel: Boolean): Marker {
    val bees = bees
    return when {
        report.stats.problem != null -> Marker(Color.Gray, null)
        !hasModel -> Marker(Warn, "No bee model")
        bees == null -> Marker(Color.Gray, null)
        else -> when (bees.verdict.outcome) {
            Outcome.BEES -> Marker(BeeGreen, "SCANNING…")
            Outcome.NO_BEES -> Marker(NoBeeRed, null)
            Outcome.UNSURE -> Marker(Warn, null)
        }
    }
}

/** Small bee-percentage line just above the Start/Stop button. */
private fun LiveResult.statusLine(): String {
    val problem = report.stats.problem
    val bees = bees
    return when {
        problem != null -> "Frame $problem"
        bees == null -> "No bee check"
        else -> "Bees ${(bees.verdict.beeScore * 100).toInt()}%"
    }
}

/**
 * Live scan. The camera is off until Start. After Start the camera streams and every frame
 * the phone can keep up with goes through the frame check and the bee check; a border around
 * the stream shows the latest result (green bees / red no bees / amber unsure / grey unusable
 * frame) and "SCANNING…" appears when bees are found. Stop turns the camera off again. A short
 * beep plays when bees are found. (The screen stays on while the app is open: MainActivity.)
 * Queen analysis comes later (see CLAUDE.md).
 */
@Composable
fun ScanScreen() {
    val context = LocalContext.current
    val beeChecker = remember { BeeChecker.create(context) }
    DisposableEffect(beeChecker) { onDispose { beeChecker?.close() } }

    var running by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<LiveResult?>(null) }
    var debugSession by remember { mutableStateOf<DebugSession?>(null) }

    // ToneGenerator can fail on some devices; then live mode just runs without sound.
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME) }.getOrNull() }
    DisposableEffect(tone) { onDispose { tone?.release() } }
    val beepGate = remember { BeepGate() }

    val analyzer = remember(beeChecker) {
        val main = ContextCompat.getMainExecutor(context)
        BeeAnalyzer(beeChecker) { result ->
            main.execute {
                if (!running) return@execute
                latest = result
                if (beepGate.onResult(result.bees?.verdict?.outcome, SystemClock.uptimeMillis())) {
                    tone?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
                    debugSession?.event("beep")
                }
            }
        }
    }

    // Every Start..Stop run is logged to logcat (tag FindQueen) and the app's debug folder;
    // debug/see-queen-apk/collect_logs.sh (not in git) picks both up over adb.
    fun startDebugSession() {
        debugSession = DebugLog.startSession(
            context,
            mapOf(
                "bee_model" to if (beeChecker != null) BeeChecker.MODEL_FILE else "missing",
                "crop_fraction" to BeeChecker.CROP_FRACTION,
                "bees_above" to BeeVerdict.BEES_ABOVE,
                "no_bees_below" to BeeVerdict.NO_BEES_BELOW,
                "dark_limit" to FrameStats.DARK_LIMIT,
                "bright_limit" to FrameStats.BRIGHT_LIMIT,
                "blur_limit" to FrameStats.BLUR_LIMIT,
            ),
        )
        analyzer.debugSession = debugSession
    }

    fun stopDebugSession() {
        analyzer.debugSession = null
        debugSession?.close()
        debugSession = null
    }
    DisposableEffect(Unit) { onDispose { stopDebugSession() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (running) {
            // Only composed while running: leaving composition unbinds the camera.
            CameraPreview(analyzer = analyzer, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                "Press Start, then point the camera at a hive frame.",
                color = Color.LightGray,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }

        val result = latest
        if (running && result != null) {
            val marker = result.marker(hasModel = beeChecker != null)
            Box(Modifier.fillMaxSize().border(12.dp, marker.color))
            marker.badge?.let { badge ->
                Text(
                    badge,
                    color = if (marker.color == Warn) Color.Black else Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .safeDrawingPadding()
                        .padding(top = 16.dp)
                        .background(marker.color, RoundedCornerShape(12.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (running && result != null) Panel { LiveStats(result) }
            if (running) {
                Text(
                    result?.statusLine() ?: "Starting camera…",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Button(
                onClick = {
                    running = !running
                    latest = null
                    beepGate.reset()
                    if (running) startDebugSession() else stopDebugSession()
                },
                colors = if (running) {
                    ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Text(if (running) "Stop" else "Start (debug)", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { content() }
}

@Composable
private fun LiveStats(result: LiveResult) {
    val stats = result.report.stats
    val beeMs = result.bees?.inferenceMs?.let { "$it ms" } ?: "–"
    Line("Frame ${result.report.width} × ${result.report.height}, %.1f checks/s".format(Locale.US, result.fps))
    Line("Brightness %.0f, sharpness %.0f".format(Locale.US, stats.brightness, stats.sharpness))
    Line("Frame check ${result.report.analysisMs} ms, bee check $beeMs")
    val queen = if (result.bees?.verdict?.outcome == Outcome.BEES) "not built yet" else "skipped"
    Line("Queen analysis: $queen")
}

@Composable
private fun Line(text: String) {
    Text(text, color = Color.White, style = MaterialTheme.typography.bodySmall)
}
