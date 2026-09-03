package dev.kalebjs.speakr.recorder

import android.content.Intent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.livedata.observeAsState
import kotlin.math.min

@Composable
fun RecordScreen(vm: MainViewModel, onOpenSettings: () -> Unit, onStartRecording: () -> Unit) {
    val recording by App.recording.observeAsState(false)
    val elapsed by App.elapsed.observeAsState(0L)
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

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            val path: String? = pendingPath
            when {
                path != null -> TagPickScreen(
                    path = path,
                    tags = tags,
                    onSubmit = { ids -> vm.submit(path, ids) },
                    onDiscard = { vm.discard(path) },
                    onRetake = {
                        // user wants to record again without sending
                        vm.discard(path)
                    }
                )
                else -> IdleOrRecording(
                    recording = recording,
                    elapsed = elapsed,
                    pendingCount = pendingCount,
                    tags = tags,
                    onStartRecording = onStartRecording,
                    onStopRecording = {
                        ContextCompat.startForegroundService(
                            App.context,
                            Intent(App.context, RecorderService::class.java)
                                .setAction(RecorderService.ACTION_STOP)
                        )
                    },
                    onOpenSettings = onOpenSettings
                )
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
            )
        }
    }
}

@Composable
private fun IdleOrRecording(
    recording: Boolean,
    elapsed: Long,
    pendingCount: Int,
    tags: List<Tag>,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onOpenSettings: () -> Unit
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

        // Center: timer + button
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = formatElapsed(elapsed),
                style = MaterialTheme.typography.displayMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
            Spacer(Modifier.height(48.dp))
            RecordButton(
                recording = recording,
                onClick = { if (recording) onStopRecording() else onStartRecording() }
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = if (recording) "Tap to stop" else "Tap to record",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Bottom: tag legend (informational)
        if (tags.isNotEmpty()) {
            Text(
                "${tags.size} tags from your Speakr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Spacer(Modifier.height(8.dp))
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

/** Pulsing outer ring while recording. */
@Composable
fun PulsingRing(recording: Boolean) {
    if (!recording) return
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse"
    )
    Box(
        Modifier
            .size((110 * pulse).dp)
            .background(Color.Transparent, CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape)
    )
}

@Composable
private fun TagPickScreen(
    path: String,
    tags: List<Tag>,
    onSubmit: (List<Long>) -> Unit,
    onDiscard: () -> Unit,
    onRetake: () -> Unit
) {
    val selected = remember { mutableStateListOf<Long>() }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Ready to send", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pick one or more tags (optional), then send to your Speakr.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRowOfTags(
            tags = tags,
            selected = selected,
            onToggle = { tag ->
                if (selected.contains(tag.id)) selected.remove(tag.id) else selected.add(tag.id)
            }
        )

        Spacer(Modifier.weight(1f))

        Button(
            onClick = { onSubmit(selected.toList()) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Send to Speakr")
        }
        OutlinedButton(
            onClick = onDiscard,
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Discard recording", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun FlowRowOfTags(
    tags: List<Tag>,
    selected: MutableList<Long>,
    onToggle: (Tag) -> Unit
) {
    if (tags.isEmpty()) {
        Text(
            "No tags available — they will sync when your server is reachable. You can still send without a tag.",
            style = MaterialTheme.typography.bodySmall
        )
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
        Color(
            android.graphics.Color.parseColor(hex)
        )
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