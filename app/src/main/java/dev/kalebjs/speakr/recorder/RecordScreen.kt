package dev.kalebjs.speakr.recorder

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.livedata.observeAsState
import kotlin.math.min
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(vm: MainViewModel, onOpenSettings: () -> Unit, onStartRecording: () -> Unit) {
    val recording by App.recording.observeAsState(false)
    val elapsed by App.elapsed.observeAsState(0L)
    val amplitude by App.amplitude.observeAsState(0)
    val pendingPath by App.recordingFilePath.observeAsState()
    val pendingCount by App.pendingCount.observeAsState(0)
    val message by App.lastMessage.observeAsState()
    val tags by App.tags.observeAsState(emptyList())

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            App.lastMessage.postValue(null)
        }
    }

    var drawerOpen by remember { mutableStateOf(false) }

    // Open the submission drawer automatically when a recording finishes.
    LaunchedEffect(recording, pendingPath) {
        if (!recording && pendingPath != null) drawerOpen = true
    }

    // Live level history for the waveform (one bar per second, latest on the right).
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(amplitude, recording) {
        if (recording) {
            val norm = sqrt((amplitude / 32767f).coerceIn(0f, 1f)).coerceAtLeast(0.04f)
            levels.add(norm)
            if (levels.size > 48) levels.removeAt(0)
        } else {
            levels.clear()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            MainLayout(
                recording = recording,
                elapsed = elapsed,
                levels = levels,
                pendingPath = pendingPath,
                pendingCount = pendingCount,
                tags = tags,
                onStartRecording = {
                    if (pendingPath != null) {
                        // A finished recording is still awaiting a decision.
                        App.toast("Send or discard the previous recording first")
                        drawerOpen = true
                    } else {
                        onStartRecording()
                    }
                },
                onStopRecording = {
                    ContextCompat.startForegroundService(
                        App.context,
                        Intent(App.context, RecorderService::class.java)
                            .setAction(RecorderService.ACTION_STOP)
                    )
                },
                onOpenSettings = onOpenSettings,
                onOpenDrawer = { drawerOpen = true }
            )

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
            )
        }

        if (drawerOpen) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { drawerOpen = false },
                sheetState = sheetState
            ) {
                SubmissionDrawer(
                    recording = recording,
                    path = pendingPath,
                    tags = tags,
                    elapsed = elapsed,
                    onSubmit = { ids ->
                        pendingPath?.let { p ->
                            vm.submit(p, ids)
                            drawerOpen = false
                        }
                    },
                    onDiscard = {
                        pendingPath?.let { p ->
                            vm.discard(p)
                            drawerOpen = false
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun MainLayout(
    recording: Boolean,
    elapsed: Long,
    levels: List<Float>,
    pendingPath: String?,
    pendingCount: Int,
    tags: List<Tag>,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (pendingCount > 0) {
                AssistChip(
                    onClick = { UploadWorker.kick() },
                    label = { Text("$pendingCount pending") }
                )
            } else {
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onOpenSettings) {
                Text("⚙", fontSize = 22.sp)
            }
        }

        // Center: timer + waveform + button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (recording) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = formatElapsed(elapsed),
                    style = MaterialTheme.typography.displayMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(32.dp))
            LiveWaveform(levels = levels, active = recording)
            Spacer(Modifier.height(48.dp))
            RecordButton(
                recording = recording,
                onClick = { if (recording) onStopRecording() else onStartRecording() }
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = when {
                    recording -> "Recording — tap to stop"
                    pendingPath != null -> "Review in the drawer below"
                    else -> "Tap to record"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Bottom: drawer handle while recording (or a finished review pending)
        if (recording || pendingPath != null) {
            DrawerHandle(onClick = onOpenDrawer)
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Minimal pill handle with an up arrow that opens the submission drawer. */
@Composable
private fun DrawerHandle(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("▲", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (App.recording.value == true) "Tags & send" else "Review recording",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** Real amplitude history rendered as slim rounded bars; idle = flat dotted line. */
@Composable
private fun LiveWaveform(levels: List<Float>, active: Boolean) {
    val barColor = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        val n = 48
        val gap = 3.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        if (!active && levels.isEmpty()) {
            // idle placeholder: faint dots along the center line
            val cy = size.height / 2
            repeat(n) { i ->
                val x = i * (barW + gap)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, cy - 1.dp.toPx()),
                    size = Size(barW, 2.dp.toPx()),
                    cornerRadius = CornerRadius(2.dp.toPx())
                )
            }
        } else {
            val start = (n - levels.size).coerceAtLeast(0)
            levels.forEachIndexed { i, v ->
                val slot = start + i
                val x = slot * (barW + gap)
                val h = (size.height * v).coerceAtLeast(4.dp.toPx())
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, (size.height - h) / 2),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2)
                )
            }
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val size by animateFloatAsState(
        targetValue = if (recording) 84f else 96f,
        animationSpec = tween(250), label = "size"
    )
    val color by animateColorAsState(
        targetValue = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        animationSpec = tween(250), label = "color"
    )
    Box(
        modifier = Modifier
            .size(size.dp)
            .semantics { contentDescription = if (recording) "Stop recording" else "Start recording" }
            .background(color, CircleShape)
            .border(4.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (recording) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.onError, RoundedCornerShape(6.dp))
            )
        } else {
            Box(
                Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.onPrimary, CircleShape)
            )
        }
    }
}

@Composable
private fun SubmissionDrawer(
    recording: Boolean,
    path: String?,
    tags: List<Tag>,
    elapsed: Long,
    onSubmit: (List<Long>) -> Unit,
    onDiscard: () -> Unit
) {
    val selected = remember { mutableStateListOf<Long>() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (recording) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                if (recording) "Recording… ${formatElapsed(elapsed)}" else "Ready to send",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Text(
            if (recording) "Recording continues in the background. Stop it to enable sending."
            else "Pick one or more tags (optional), then send to your Speakr.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TagChipGrid(
            tags = tags,
            selected = selected,
            onToggle = { tag ->
                if (selected.contains(tag.id)) selected.remove(tag.id) else selected.add(tag.id)
            }
        )

        Button(
            onClick = { onSubmit(selected.toList()) },
            enabled = !recording && path != null,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (recording) "Stop recording to send" else "Send to Speakr")
        }
        OutlinedButton(
            onClick = onDiscard,
            enabled = !recording && path != null,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Discard recording", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun TagChipGrid(
    tags: List<Tag>,
    selected: MutableList<Long>,
    onToggle: (Tag) -> Unit
) {
    if (tags.isEmpty()) {
        Text(
            "No tags available — they sync when your server is reachable. You can still send without a tag.",
            style = MaterialTheme.typography.bodySmall
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.chunked(3).forEach { rowTags ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTags.forEach { tag ->
                    val isSel = selected.contains(tag.id)
                    val bg = if (isSel) parseTagColor(tag.color) ?: MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                    val fg = if (isSel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(bg)
                            .clickable { onToggle(tag) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(tag.name, color = fg, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

private fun parseTagColor(hex: String?): Color? {
    if (hex == null || !hex.startsWith("#") || hex.length < 7) return null
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        null
    }
}

private fun formatElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}