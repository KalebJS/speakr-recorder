package dev.kalebjs.speakr.recorder

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel : ViewModel() {
    var testing = mutableStateOf(false)
    var testResult = mutableStateOf<String?>(null)
    var testError = mutableStateOf<String?>(null)
    var tagsLoading = mutableStateOf(false)
    var uploadingPath = mutableStateOf<String?>(null)
    /** Live "on mobile data" flag, observed by the send prompt. */
    var onMetered = mutableStateOf(false)

    fun refreshMetered() {
        onMetered.value = NetworkMonitor.isMetered(App.context)
    }

    /** Manual send from the queue screen: prompt on metered, else upload. */
    fun sendFromQueue(path: String) {
        if (File(path).exists()) {
            submit(path, App.loadQueue().firstOrNull { it.path == path }?.tagIds ?: emptyList())
        } else {
            App.removeQueued(path)
        }
    }

    fun testConnection(url: String, token: String) {
        testing.value = true
        testError.value = null
        testResult.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = SpeakrApi(url.trimEnd('/'), token.trim()).me()
                withContext(Dispatchers.Main) {
                    testResult.value = "Connected as $name"
                    testing.value = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    testError.value = e.message ?: "Connection failed"
                    testing.value = false
                }
            }
        }
    }

    fun refreshTags() {
        if (!App.isConfigured) return
        tagsLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val t = SpeakrApi(App.serverUrl, App.apiToken).tags()
                App.saveTagsCache(t)
            } catch (_: Exception) {
                // keep cached tags
            } finally {
                withContext(Dispatchers.Main) { tagsLoading.value = false }
            }
        }
    }

    fun submit(path: String, tagIds: List<Long>, forceNow: Boolean = false) {
        if (uploadingPath.value != null) return
        // Any submit settles the drawer's armed send: the finalize path has
        // either already run (paused flow) or never will.
        App.sendArmed = false
        // Metered-network gate: on mobile data, ask before spending it.
        if (!forceNow && NetworkMonitor.isMetered(App.context)) {
            pendingSend = PendingSend(path, tagIds)
            App.sendPrompt.postValue(path)
            return
        }
        doSubmit(path, tagIds)
    }

    private var pendingSend: PendingSend? = null

    private data class PendingSend(val path: String, val tagIds: List<Long>)

    /** Prompt dismissed without choosing: the recording stays reviewable. */
    fun cancelPrompt() {
        pendingSend = null
        App.sendPrompt.postValue(null)
    }

    /** User chose "Upload now" on mobile data. */
    fun confirmSendNow() {
        val p = pendingSend ?: return
        pendingSend = null
        App.sendPrompt.postValue(null)
        doSubmit(p.path, p.tagIds)
    }

    /** User chose "Wait for Wi-Fi": re-queue with the wifiOnly flag. */
    fun waitForWifi() {
        val p = pendingSend ?: return
        pendingSend = null
        App.sendPrompt.postValue(null)
        App.wifiWaiting = true
        viewModelScope.launch(Dispatchers.IO) {
            val entry = App.loadQueue().firstOrNull { it.path == p.path }
            if (entry != null) {
                App.updateQueueEntry(entry.copy(tagIds = p.tagIds, wifiOnly = true, holdForReview = false))
            } else {
                App.enqueueUpload(
                    PendingUpload(path = p.path, tagIds = p.tagIds, wifiOnly = true, createdAt = System.currentTimeMillis())
                )
            }
            App.toast("Waiting for Wi-Fi")
            App.recordingFilePath.postValue(null)
        }
    }

    private fun doSubmit(path: String, tagIds: List<Long>) {
        if (uploadingPath.value != null) return
        // One sender at a time: the worker may already own this file.
        if (!App.claimSend(path)) {
            App.toast("Already sending…")
            return
        }
        uploadingPath.value = path
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Attach tags to the queued entry (or create one), then send inline
                // for immediate feedback. Queue + backoff still covers retries.
                val entry = App.loadQueue().firstOrNull { it.path == path }
                if (entry != null) {
                    App.updateQueueEntry(entry.copy(tagIds = tagIds, wifiOnly = false, holdForReview = false))
                } else {
                    App.enqueueIfAbsent(
                        PendingUpload(path = path, tagIds = tagIds, createdAt = System.currentTimeMillis())
                    )
                }
                try {
                    if (!App.isConfigured) throw SpeakrException("Not configured")
                    SpeakrApi(App.serverUrl, App.apiToken).upload(File(path), tagIds)
                    App.removeQueued(path)
                    File(path).delete()
                    App.successFlash.postValue(true)
                } catch (e: Exception) {
                    // Failsafe: copy the recording to public Downloads and
                    // alert the user; the retry queue keeps trying anyway.
                    val savedName = App.saveRecordingToDownloads(path)
                    val retryMsg = if (savedName != null) {
                        "Upload failed — a copy is saved in Downloads as \"$savedName\". It will still retry automatically."
                    } else {
                        "Upload failed — will retry automatically"
                    }
                    withContext(Dispatchers.Main) {
                        App.uploadFailure.postValue(retryMsg)
                    }
                    UploadWorker.kick()
                }
            } finally {
                App.releaseSend(path)
                uploadingPath.value = null
                App.recordingFilePath.postValue(null)
            }
        }
    }

    fun discard(path: String) {
        File(path).delete()
        App.removeQueued(path)
        App.recordingFilePath.postValue(null)
    }
}

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val micOk = grants[Manifest.permission.RECORD_AUDIO] == true
            val notifOk = !needsPostNotification() ||
                grants[Manifest.permission.POST_NOTIFICATIONS] == true
            if (micOk && notifOk) startRecordingService()
            else App.toast("Microphone permission is required")
        }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(application)
        setContent {
            SpeakrTheme {
                Root()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshMetered()
        UploadWorker.kick()
        vm.refreshTags()
    }

    private fun startRecordingService() {
        ContextCompat.startForegroundService(
            this, Intent(this, RecorderService::class.java)
        )
    }

    @Composable
    fun Root() {
        val configured = App.isConfigured
        var showSettings by remember { mutableStateOf(!configured) }

        if (showSettings) {
            SetupScreen(
                vm = vm,
                onDone = { showSettings = false },
                onCancel = { if (configured) showSettings = false }
            )
        } else {
            RecordScreen(
                vm = vm,
                onOpenSettings = { showSettings = true },
                onStartRecording = { requestPermissionsAndRecord() }
            )
        }
    }

    private fun requestPermissionsAndRecord() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (needsPostNotification()) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}

fun needsPostNotification(): Boolean = Build.VERSION.SDK_INT >= 33