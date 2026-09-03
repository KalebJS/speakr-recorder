package dev.kalebjs.speakr.recorder

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    val paused by App.paused.observeAsState(false)
    val elapsed by App.elapsed.observeAsState(0L)
    val amplitude by App.amplitude.observeAsState(0)
    val level by App.level.observeAsState(0.0)
    val pendingPath by App.recordingFilePath.observeAsState()
    val pendingCount by App.pendingCount.observeAsState(0)
    val message by App.lastMessage.observeAsState()
    val successFlash by App.successFlash.observeAsState(false)
    val uploading = vm.uploadingPath.value != null
    val tags by App.tags.observeAsState(emptyList())

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            App.lastMessage.postValue(null)
        }
    }

    var drawerOpen by remember { mutableStateOf(false) }
    var submitAfterStop by remember { mutableStateOf<List<Long>?>(null) }

    // Open the submission drawer automatically when a recording finishes.
    LaunchedEffect(recording, pendingPath) {
        if (!recording && pendingPath != null) drawerOpen = true
        // One-tap stop-and-send from the paused state.
        if (!recording && pendingPath != null && submitAfterStop != null) {
            val path = pendingPath
            val ids = submitAfterStop
            submitAfterStop = null
            if (path != null && ids != null) vm.submit(path, ids)
        }
    }

    // Auto-dismiss the success flash.
    LaunchedEffect(successFlash) {
        if (successFlash) {
            kotlinx.coroutines.delay(1600)
            App.successFlash.postValue(false)
        }
    }

    // Live level history for the waveform. Uses true PCM RMS (AudioRecord
    // pipeline) posted by the capture thread — appended on EVERY sample.
    val levels = remember { mutableStateListOf<Float>() }
    DisposableEffect(recording, paused) {
        val observer = androidx.lifecycle.Observer<Double> { lvl ->
            if (recording && !paused) {
                // Perceptual boost: sqrt makes quiet speech visible.
                val norm = sqrt(lvl.coerceIn(0.0, 1.0)).coerceAtLeast(0.06)
                levels.add(norm.toFloat())
                while (levels.size > 48) levels.removeAt(0)
            }
        }
        App.level.observeForever(observer)
        onDispose { App.level.removeObserver(observer) }
    }
    // Clear bars only when the recording is fully consumed (new session).
    LaunchedEffect(recording) {
        if (!recording && pendingPath == null) levels.clear()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            MainLayout(
                recording = recording,
                paused = paused,
                elapsed = elapsed,
                levels = levels,
                pendingPath = pendingPath,
                pendingCount = pendingCount,
                onStartRecording = {
                    when {
                        pendingPath != null -> {
                            // Continue the same session instead of blocking.
                            ContextCompat.startForegroundService(
                                App.context,
                                Intent(App.context, RecorderService::class.java)
                                    .setAction(RecorderService.ACTION_RESUME)
                            )
                        }
                        else -> onStartRecording()
                    }
                },
                onPrimaryButton = {
                    when {
                        recording && !paused -> ContextCompat.startForegroundService(
                            App.context,
                            Intent(App.context, RecorderService::class.java)
                                .setAction(RecorderService.ACTION_PAUSE)
                        )
                        recording && paused -> ContextCompat.startForegroundService(
                            App.context,
                            Intent(App.context, RecorderService::class.java)
                                .setAction(RecorderService.ACTION_RESUME)
                        )
                        pendingPath != null -> ContextCompat.startForegroundService(
                            App.context,
                            Intent(App.context, RecorderService::class.java)
                                .setAction(RecorderService.ACTION_RESUME)
                        )
                        else -> onStartRecording()
                    }
                },
                onStopForSend = {
                    // From paused, "stop to send" finishes the session and the
                    // drawer's Send becomes enabled.
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
                    paused = paused,
                    uploading = uploading,
                    path = pendingPath,
                    tags = tags,
                    elapsed = elapsed,
                    onSubmit = { ids ->
                        pendingPath?.let { p ->
                            if (!recording) {
                                vm.submit(p, ids)
                            } else {
                                // Paused: finalize the session, then auto-send
                                // once the file is closed.
                                submitAfterStop = ids
                                ContextCompat.startForegroundService(
                                    App.context,
                                    Intent(App.context, RecorderService::class.java)
                                        .setAction(RecorderService.ACTION_STOP)
                                )
                            }
                        }
                    },
                    onDiscard = {
                        pendingPath?.let { p ->
                            if (recording) {
                                // Paused: abort the session and drop the file.
                                ContextCompat.startForegroundService(
                                    App.context,
                                    Intent(App.context, RecorderService::class.java)
                                        .setAction(RecorderService.ACTION_ABORT)
                                )
                            } else {
                                vm.discard(p)
                            }
                            drawerOpen = false
                        }
                    },
                    onDismiss = { drawerOpen = false }
                )
            }
        }
    }

    // Non-blocking success overlay: green check pop, auto-fades, no clicks.
    AnimatedVisibility(
        visible = successFlash,
        enter = fadeIn() + scaleIn(initialScale = 0.6f),
        exit = fadeOut()
    ) {
        Box(
            modifier = Modifier.fillMaxSize().alpha(0.92f),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 36.dp, vertical = 24.dp)
            ) {
                Text("✓", fontSize = 44.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sent to Speakr",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun MainLayout(
    recording: Boolean,
    paused: Boolean,
    elapsed: Long,
    levels: List<Float>,
    pendingPath: String?,
    pendingCount: Int,
    onStartRecording: () -> Unit,
    onPrimaryButton: () -> Unit,
    onStopForSend: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
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

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (recording && !paused) {
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
            LiveWaveform(levels = levels, active = recording && !paused)
            Spacer(Modifier.height(48.dp))
            RecordButton(
                recording = recording && !paused,
                paused = paused,
                onClick = onPrimaryButton
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = when {
                    recording && !paused -> "Recording — tap to pause"
                    recording && paused -> "Paused — tap to resume"
                    pendingPath != null -> "Tap ▲ to send, or resume"
                    else -> "Tap to record"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (recording || pendingPath != null) {
            DrawerHandle(onClick = onOpenDrawer, label = if (recording) "Tags & send" else "Review recording")
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DrawerHandle(onClick: () -> Unit, label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("▲", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

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
                val h = (size.height * v).coerceAtLeast(6.dp.toPx())
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
private fun RecordButton(recording: Boolean, paused: Boolean, onClick: () -> Unit) {
    val size by animateFloatAsState(
        targetValue = if (recording && !paused) 84f else 96f,
        animationSpec = tween(250), label = "size"
    )
    val color by animateColorAsState(
        targetValue = when {
            recording && !paused -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(250), label = "color"
    )
    Box(
        modifier = Modifier
            .size(size.dp)
            .semantics {
                contentDescription = when {
                    recording && !paused -> "Pause recording"
                    paused -> "Resume recording"
                    else -> "Start recording"
                }
            }
            .background(color, CircleShape)
            .border(4.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            // Recording: white square = pause glyph
            recording && !paused -> Box(
                Modifier
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.onError, RoundedCornerShape(5.dp))
            )
            // Paused: revert to the original record-style circle; the text
            // underneath ("Paused — tap to resume") carries the state.
            paused || (!recording) -> Box(
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
    paused: Boolean,
    uploading: Boolean,
    path: String?,
    tags: List<Tag>,
    elapsed: Long,
    onSubmit: (List<Long>) -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit
) {
    val selected = remember { mutableStateListOf<Long>() }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (recording && !paused) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                when {
                    recording && !paused -> "Recording… ${formatElapsed(elapsed)}"
                    recording && paused -> "Paused — ${formatElapsed(elapsed)}"
                    else -> "Ready to send"
                },
                style = MaterialTheme.typography.titleMedium
            )
        }

        Text(
            when {
                recording && !paused -> "Recording continues in the background. Pause or stop it to enable sending."
                recording && paused -> "Recording is paused. Sending will finish the session and upload."
                uploading -> "Uploading…"
                else -> "Pick one or more tags (optional), then send to your Speakr."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TagChipRow(
            tags = tags,
            selected = selected,
            onToggle = { tag ->
                if (selected.contains(tag.id)) selected.remove(tag.id) else selected.add(tag.id)
            }
        )

        Button(
            onClick = { onSubmit(selected.toList()) },
            enabled = path != null && !uploading && (!recording || paused),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (uploading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(12.dp))
                Text("Uploading…")
            } else {
                Text(
                    when {
                        recording && !paused -> "Stop recording to send"
                        recording && paused -> "Finish & send to Speakr"
                        else -> "Send to Speakr"
                    }
                )
            }
        }
        OutlinedButton(
            onClick = onDiscard,
            enabled = path != null && !uploading && (!recording || paused),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Discard recording", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Flowing chip layout — wraps naturally, no ragged fixed rows. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TagChipRow(
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
    androidx.compose.foundation.layout.FlowRow {
        tags.forEach { tag ->
            val isSel = selected.contains(tag.id)
            val bg = if (isSel) parseTagColor(tag.color) ?: MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant
            val fg = if (isSel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                modifier = Modifier
                    .padding(end = 8.dp, bottom = 8.dp)
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