package dev.kalebjs.speakr.recorder

import android.app.Application
import android.content.Context
import android.media.MediaRecorder
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
    val lastAttemptAt: Long = 0
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
    val recordingFilePath = MutableLiveData<String?>(null)
    val pendingCount = MutableLiveData(0)
    val lastMessage = MutableLiveData<String?>(null)
    val successFlash = MutableLiveData(false)
    val tags = MutableLiveData<List<Tag>>(emptyList())

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
    }

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(v) = prefs.edit().putString("server_url", v.trimEnd('/')).apply()

    var apiToken: String
        get() = prefs.getString("api_token", "") ?: ""
        set(v) = prefs.edit().putString("api_token", v).apply()

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

    fun onRecordingStarted(path: String) {
        recordingFilePath.postValue(path)
        recording.postValue(true)
    }
}