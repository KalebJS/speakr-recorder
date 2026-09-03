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

    fun submit(path: String, tagIds: List<Long>) {
        if (uploadingPath.value != null) return
        uploadingPath.value = path
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Attach tags to the queued entry (or create one), then send inline
                // for immediate feedback. Queue + backoff still covers retries.
                val entry = App.loadQueue().firstOrNull { it.path == path }
                if (entry != null) {
                    App.updateQueueEntry(entry.copy(tagIds = tagIds))
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
                    App.toast("Upload failed — will retry automatically")
                    UploadWorker.kick()
                }
            } finally {
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