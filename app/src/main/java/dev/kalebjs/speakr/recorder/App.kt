package dev.kalebjs.speakr.recorder

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Tag(val id: Long, val name: String, val color: String? = null)

@Serializable
data class PendingUpload(
    val path: String,
    val tagIds: List<Long>,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0,
    /** Wait for an unmetered network before this upload is allowed to run. */
    val wifiOnly: Boolean = false,
    /** Parked for user review (standalone stop) — never auto-uploaded. */
    val holdForReview: Boolean = false
)

/** Process-wide singleton state bus between UI, service, and queue. */
object App {
    lateinit var context: Context
        private set

    val recording = MutableLiveData(false)
    val paused = MutableLiveData(false)
    private val _elapsed = MutableLiveData(0L)
    val elapsed: LiveData<Long> = _elapsed
    private val _amplitude = MutableLiveData(0)
    val amplitude: LiveData<Int> = _amplitude
    private val _level = MutableLiveData(0.0)
    val level: LiveData<Double> = _level
    private val _transcript = MutableLiveData("")
    val transcript: LiveData<String> = _transcript
    val recordingFilePath = MutableLiveData<String?>(null)
    val pendingCount = MutableLiveData(0)
    val lastMessage = MutableLiveData<String?>(null)
    val successFlash = MutableLiveData(false)
    /** Failsafe alert: shown once when an upload permanently fails. */
    val uploadFailure = MutableLiveData<String?>(null)
    val tags = MutableLiveData<List<Tag>>(emptyList())
    /** Non-null while the "mobile data" send prompt is on screen. */
    val sendPrompt = MutableLiveData<String?>(null)

    private val json = Json { ignoreUnknownKeys = true }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, "speakr_settings", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun init(app: Application) {
        context = app
        loadTagsCache()
        refreshPendingCount()
        NetworkMonitor.watchUnmetered(app) {
            wifiWaiting = false
            UploadWorker.kick()
        }
    }

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(v) = prefs.edit().putString("server_url", v.trimEnd('/')).apply()

    var apiToken: String
        get() = prefs.getString("api_token", "") ?: ""
        set(v) = prefs.edit().putString("api_token", v).apply()

    /** GitHub self-update auto-check (default ON). */
    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", true)
        set(v) = prefs.edit().putBoolean("auto_update", v).apply()

    val isConfigured: Boolean get() = serverUrl.isNotEmpty() && apiToken.isNotEmpty()

    private val queueFile: File
        get() = File(context.filesDir, "upload_queue.json")

    @Synchronized
    fun loadQueue(): MutableList<PendingUpload> {
        if (!queueFile.exists()) return mutableListOf()
        return try {
            json.decodeFromString<List<PendingUpload>>(queueFile.readText()).toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun saveQueue(q: List<PendingUpload>) {
        queueFile.writeText(json.encodeToString(q))
        pendingCount.postValue(q.size)
    }

    fun refreshPendingCount() = saveQueue(loadQueue())

    @Synchronized
    fun enqueueUpload(p: PendingUpload) {
        val q = loadQueue()
        q.add(p)
        saveQueue(q)
    }

    @Synchronized
    fun updateQueueEntry(updated: PendingUpload) {
        val q = loadQueue()
        val idx = q.indexOfFirst { it.path == updated.path }
        if (idx >= 0) {
            q[idx] = updated
            saveQueue(q)
        }
    }

    @Synchronized
    fun removeQueued(path: String) {
        val q = loadQueue()
        q.removeAll { it.path == path }
        saveQueue(q)
    }

    /** Queue only if this exact file isn't already queued (idempotent finalize). */
    @Synchronized
    fun enqueueIfAbsent(p: PendingUpload) {
        val q = loadQueue()
        if (q.none { it.path == p.path }) {
            q.add(p)
            saveQueue(q)
        }
    }

    // ------------------------------------------------------------------
    // Send arbitration: exactly one sender may own a path at a time.
    // Prevents the recorder finalize's UploadWorker.kick() racing the
    // drawer's inline submit() and uploading the file twice.
    // ------------------------------------------------------------------

    /** Paths with an upload attempt in flight (inline submit or worker). */
    private val inFlight = mutableSetOf<String>()

    @Synchronized
    fun claimSend(path: String): Boolean =
        if (path in inFlight) false else { inFlight.add(path); true }

    @Synchronized
    fun releaseSend(path: String) {
        inFlight.remove(path)
    }

    /**
     * Armed by the drawer just before ACTION_STOP when the user chose
     * "Finish & send": the finalize path must skip its own kick so the
     * drawer's submit remains the only sender.
     */
    @Volatile
    var sendArmed: Boolean = false

    /**
     * True while at least one queued recording is parked for Wi-Fi.
     * Cleared when the network callback (or a manual send) drains it.
     */
    @Volatile
    var wifiWaiting: Boolean = false

    private val tagsCacheFile: File
        get() = File(context.filesDir, "tags_cache.json")

    @Synchronized
    fun loadTagsCache(): List<Tag> {
        val cached = if (tagsCacheFile.exists()) {
            try {
                json.decodeFromString<List<Tag>>(tagsCacheFile.readText())
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()
        if (tags.value == null || tags.value!!.isEmpty()) tags.postValue(cached)
        return cached
    }

    @Synchronized
    fun saveTagsCache(tags: List<Tag>) {
        tagsCacheFile.writeText(json.encodeToString(tags))
        this.tags.postValue(tags)
    }

    fun toast(msg: String) = lastMessage.postValue(msg)

    fun onTick(seconds: Long) = _elapsed.postValue(seconds)

    fun resetClock() {
        _elapsed.postValue(0)
    }

    fun onAmplitude(peak: Int) = _amplitude.postValue(peak)

    fun onLevel(rms: Double) = _level.postValue(rms)

    /** Rolling live-transcription text (finals + current partial) from the service. */
    fun onTranscription(text: String) = _transcript.postValue(text)

    /**
     * Failsafe: copy a recording into the public Downloads folder (MediaStore,
     * no storage permission needed on API 29+). Returns the display name.
     */
    fun saveRecordingToDownloads(path: String): String? {
        return try {
            val src = File(path)
            if (!src.exists()) return null
            val resolver = context.contentResolver
            var name = src.name
            // Avoid silent collisions: if the name already exists in Downloads,
            // prefix with a timestamp instead of letting the system rename it.
            val existing = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(name),
                null
            )?.use { it.count > 0 } == true
            if (existing) {
                val dot = name.lastIndexOf('.')
                name = if (dot > 0) {
                    "speakr-${System.currentTimeMillis()}" + name.substring(dot)
                } else {
                    "speakr-${System.currentTimeMillis()}.m4a"
                }
            }
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            name
        } catch (_: Exception) {
            null
        }
    }

    fun onRecordingStarted(path: String) {
        recordingFilePath.postValue(path)
        recording.postValue(true)
    }
}