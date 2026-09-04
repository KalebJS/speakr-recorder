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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import dev.kalebjs.speakr.recorder.SelfUpdate
import java.io.File
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
    val transcript by App.transcript.observeAsState("")
    val pendingPath by App.recordingFilePath.observeAsState()
    val pendingCount by App.pendingCount.observeAsState(0)
    val message by App.lastMessage.observeAsState()
    val successFlash by App.successFlash.observeAsState(false)
    val uploadFailure by App.uploadFailure.observeAsState()
    val sendPrompt by App.sendPrompt.observeAsState()
    val uploading = vm.uploadingPath.value != null
    val onMetered = vm.onMetered.value
    val tags by App.tags.observeAsState(emptyList())
    // Self-update state: banner info, download/install phase + progress,
    // unknown-source explainer, one-shot success toast.
    val updateInfo by SelfUpdate.update.observeAsState()
    val updatePhase by SelfUpdate.phase.observeAsState(UpdatePhase.DONE)
    val updateProgress by SelfUpdate.progress.observeAsState(0)
    val needsUnknownSource by SelfUpdate.needsUnknownSource.observeAsState(false)
    val installSuccess by SelfUpdate.installSuccess.observeAsState(false)

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            App.lastMessage.postValue(null)
        }
    }

    var drawerOpen by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
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

    // Re-open the drawer for a send that was parked ("Wait for Wi-Fi") so
    // the recording is never silently lost — it's still the review target.
    LaunchedEffect(pendingPath, pendingCount) {
        if (pendingPath == null && queueHasWifiOnly()) drawerOpen = false
    }

    // The moment the network becomes unmetered, drain anything parked for Wi-Fi.
    LaunchedEffect(onMetered) {
        if (!onMetered && queueHasWifiOnly()) UploadWorker.kick()
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
    // Clear bars + clock when the recording is fully consumed (sent/discarded).
    LaunchedEffect(recording, pendingPath, successFlash) {
        if (!recording && pendingPath == null) {
            levels.clear()
            App.onTick(0)
            App.onLevel(0.0)
            App.onAmplitude(0)
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            MainLayout(
                recording = recording,
                paused = paused,
                elapsed = elapsed,
                levels = levels,
                transcript = transcript,
                pendingPath = pendingPath,
                pendingCount = pendingCount,
                updateInfo = updateInfo,
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
                onOpenDrawer = { drawerOpen = true },
                onOpenQueue = { queueOpen = true }
            )

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
            )
        }

        if (queueOpen) {
            QueueScreen(
                vm = vm,
                onMetered = onMetered,
                onBack = { queueOpen = false }
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
                                App.sendArmed = true
                                vm.submit(p, ids)
                            } else {
                                // Paused: finalize the session, then auto-send
                                // once the file is closed.
                                App.sendArmed = true
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

    // Uploading overlay: dim + spinner card, non-clickable (blocks input).
    if (uploading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.55f)
                .background(MaterialTheme.colorScheme.scrim)
                .clickable(enabled = false, onClick = {}),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
                    .padding(horizontal = 40.dp, vertical = 28.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(44.dp),
                    strokeWidth = 4.dp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Uploading to Speakr…",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }

    // Metered-network prompt: upload now on mobile data, or hold for Wi-Fi.
    sendPrompt?.let { path ->
        AlertDialog(
            onDismissRequest = { vm.cancelPrompt() },
            title = { Text("On mobile data") },
            text = { Text("Uploading now will use your phone's data connection. Wait for Wi-Fi instead? The recording stays queued and sends automatically.") },
            confirmButton = {
                TextButton(onClick = { vm.confirmSendNow() }) {
                    Text("Upload now")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.waitForWifi() }) {
                    Text("Wait for Wi-Fi")
                }
            }
        )
    }

    // Failsafe alert when an upload fails permanently (or first failure).
    uploadFailure?.let { msg ->
        AlertDialog(
            onDismissRequest = { App.uploadFailure.postValue(null) },
            title = { Text("Upload failed") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { App.uploadFailure.postValue(null) }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    App.uploadFailure.postValue(null)
                    UploadWorker.kick()
                }) {
                    Text("Retry now")
                }
            }
        )
    }

    // Self-update: download/install progress overlay (same style as the
    // upload overlay), unknown-source explainer dialog, success flash.
    if (updatePhase == UpdatePhase.DOWNLOADING || updatePhase == UpdatePhase.INSTALLING) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.55f)
                .background(MaterialTheme.colorScheme.scrim)
                .clickable(enabled = false, onClick = {}),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
                    .padding(horizontal = 40.dp, vertical = 28.dp)
            ) {
                CircularProgressIndicator(
                    progress = { updateProgress / 100f },
                    modifier = Modifier.size(44.dp),
                    strokeWidth = 4.dp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    when (updatePhase) {
                        UpdatePhase.DOWNLOADING -> "Downloading update… $updateProgress%"
                        else -> "Installing update…"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }

    // Android blocked the install: this sideloaded app needs the per-app
    // "install unknown apps" grant before the system confirm dialog appears.
    if (needsUnknownSource) {
        AlertDialog(
            onDismissRequest = { SelfUpdate.clearUnknownSourceFlag() },
            title = { Text("Allow the update to install") },
            text = {
                Text(
                    "Android blocks apps from installing other apps by default. " +
                        "To allow it:\n\n" +
                        "Settings → Apps → Speakr Recorder → Install unknown apps → " +
                        "Allow from this source.\n\n" +
                        "Then tap Update again — the download is kept and the " +
                        "install confirm dialog will appear."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SelfUpdate.clearUnknownSourceFlag()
                    runCatching {
                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .setData(android.net.Uri.parse("package:${App.context.packageName}"))
                        App.context.startActivity(intent)
                    }
                }) {
                    Text("Open settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { SelfUpdate.clearUnknownSourceFlag() }) {
                    Text("Not now")
                }
            }
        )
    }

    // One-shot install-success confirmation.
    LaunchedEffect(installSuccess) {
        if (installSuccess) {
            App.toast("Update installed")
            SelfUpdate.clearInstallSuccess()
        }
    }
}

/** True when any queued entry is parked for Wi-Fi. */
private fun queueHasWifiOnly(): Boolean =
    App.loadQueue().any { it.wifiOnly }

@Composable
private fun QueueScreen(vm: MainViewModel, onMetered: Boolean, onBack: () -> Unit) {
    val pendingCount by App.pendingCount.observeAsState(0)
    val message by App.lastMessage.observeAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            App.lastMessage.postValue(null)
        }
    }
    // Reload on every count change; send-now/delete both change it.
    val items = remember(pendingCount, onMetered) { App.loadQueue().toList() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("← Back") }
                Spacer(Modifier.weight(1f))
                Text(
                    if (onMetered) "On mobile data" else "On Wi-Fi",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Upload queue",
                style = MaterialTheme.typography.headlineSmall
            )
            if (items.isEmpty()) {
                Spacer(Modifier.height(32.dp))
                Text(
                    "Nothing queued — recordings appear here until they're sent.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                items.forEach { item ->
                    QueueItemCard(
                        item = item,
                        onMetered = onMetered,
                        onSendNow = { vm.sendFromQueue(item.path) },
                        onDelete = { vm.discard(item.path) }
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueItemCard(
    item: PendingUpload,
    onMetered: Boolean,
    onSendNow: () -> Unit,
    onDelete: () -> Unit
) {
    val file = remember(item.path) { File(item.path) }
    val sizeMb = if (file.exists()) file.length() / (1024.0 * 1024.0) else 0.0
    val status = when {
        item.wifiOnly && onMetered -> "Waiting for Wi-Fi"
        item.attempts > 0 -> "Failed ${item.attempts}× — will retry"
        item.holdForReview -> "Ready to review"
        else -> "Queued"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp)
    ) {
        Text(
            file.name,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "$status · %.1f MB".format(sizeMb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSendNow) {
                Text("Send now")
            }
            OutlinedButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
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
    transcript: String,
    pendingPath: String?,
    pendingCount: Int,
    updateInfo: UpdateInfo?,
    onStartRecording: () -> Unit,
    onPrimaryButton: () -> Unit,
    onStopForSend: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenQueue: () -> Unit
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
                    onClick = onOpenQueue,
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
            LiveCaption(
                transcript = transcript,
                visible = recording && !paused
            )
            UpdateBannerCard(
                update = updateInfo,
                onUpdate = {
                    App.toast("Downloading update…")
                    SelfUpdate.downloadAndInstall()
                },
                onDismiss = { SelfUpdate.dismiss() }
            )
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
                    pendingCount > 0 -> "$pendingCount queued — tap to review"
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

/** Words shown in the live caption; older words are clipped off. */
private const val LIVE_CAPTION_WORDS = 14

/**
 * Live transcription caption between the waveform and the record button: the
 * newest ~14 words of the rolling transcript, newest at full opacity and older
 * words progressively faded (1.0 → 0.55 → 0.3 → 0.16), like a live caption.
 * Collapses to nothing when idle or when transcription is unavailable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveCaption(transcript: String, visible: Boolean) {
    val words = remember(transcript) {
        transcript.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
    AnimatedVisibility(
        visible = visible && words.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val start = (words.size - LIVE_CAPTION_WORDS).coerceAtLeast(0)
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            val style = MaterialTheme.typography.bodyLarge
            for (i in start until words.size) {
                val age = words.size - 1 - i // 0 = newest word
                val alpha = when {
                    age == 0 -> 1f
                    age == 1 -> 0.55f
                    age == 2 -> 0.3f
                    else -> 0.16f
                }
                Text(
                    text = words[i],
                    style = style,
                    color = color,
                    modifier = Modifier
                        .alpha(alpha)
                        .padding(horizontal = 3.dp)
                )
            }
        }
    }
}

/**
 * Subtle Material 3 banner card for an available GitHub release: tag label,
 * Update button, and an X to dismiss for the current session. Rendered
 * between the live caption and the record button.
 */
@Composable
private fun UpdateBannerCard(
    update: UpdateInfo?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = update != null,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Update available — ${update?.tag?.removePrefix("v") ?: ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onUpdate) {
                Text("Update")
            }
            TextButton(onClick = onDismiss) {
                Text("✕")
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