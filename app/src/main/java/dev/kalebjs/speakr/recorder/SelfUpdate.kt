package dev.kalebjs.speakr.recorder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Result of the release check: non-null when a different release is published. */
data class UpdateInfo(val tag: String, val apkUrl: String)

/** Phases shown on the download overlay; DONE/FAILED end the flow. */
enum class UpdatePhase { DOWNLOADING, INSTALLING, DONE, FAILED }

/**
 * GitHub Releases self-updater. Compares the latest release tag against the
 * installed versionName (simple string inequality), streams the APK asset to
 * cache, and commits a PackageInstaller full-install session. Everything
 * fails soft: network errors, rate limits, and malformed payloads are logged
 * and swallowed so the app never crashes or spams the user.
 */
object SelfUpdate {

    private const val TAG = "SelfUpdate"
    private const val RELEASES_URL =
        "https://api.github.com/repos/KalebJS/speakr-recorder/releases/latest"
    private const val APK_ASSET = "app-debug.apk"
    private const val INSTALL_ACTION = "dev.kalebjs.speakr.recorder.INSTALL_STATUS"
    private const val SESSION_APK_NAME = "speakr_update.apk"

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Latest release different from the installed version, or null. */
    private val _update = MutableLiveData<UpdateInfo?>(null)
    val update: LiveData<UpdateInfo?> = _update

    /** Download/install progress; DONE and FAILED clear the overlay. */
    private val _phase = MutableLiveData(UpdatePhase.DONE)
    val phase: LiveData<UpdatePhase> = _phase

    /** 0..100 progress during DOWNLOADING. */
    private val _progress = MutableLiveData(0)
    val progress: LiveData<Int> = _progress

    /** True when the install was refused for unknown sources — show the explainer. */
    private val _needsUnknownSource = MutableLiveData(false)
    val needsUnknownSource: LiveData<Boolean> = _needsUnknownSource

    /** Set for one recomposition after the installer reports success. */
    private val _installSuccess = MutableLiveData(false)
    val installSuccess: LiveData<Boolean> = _installSuccess

    private var statusReceiver: BroadcastReceiver? = null

    // ------------------------------------------------------------------
    // Checking
    // ------------------------------------------------------------------

    /** Fetch releases/latest on a worker thread; result lands on [update]. */
    fun check() {
        Thread {
            try {
                val req = Request.Builder()
                    .url(RELEASES_URL)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        !resp.isSuccessful -> {
                            Log.i(TAG, "check skipped: HTTP ${resp.code}")
                            return@Thread
                        }
                        else -> {
                            val body = resp.body?.string() ?: return@Thread
                            val info = parseRelease(body)
                            if (info != null && info.tag != dismissedTag && isUpdateAvailable(info.tag)) {
                                _update.postValue(info)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "check skipped: ${e.message}")
            }
        }.start()
    }

    /** Simple and robust: any tag != 'v' + current versionName means update. */
    private fun isUpdateAvailable(tag: String): Boolean =
        tag != "v" + BuildConfig.VERSION_NAME

    private fun parseRelease(body: String): UpdateInfo? = try {
        val obj = json.parseToJsonElement(body).jsonObject
        val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return null
        val assets = obj["assets"]?.jsonArray ?: return null
        var url: String? = null
        for (a in assets) {
            val asset = a.jsonObject
            val name = asset["name"]?.jsonPrimitive?.content
            if (name == APK_ASSET) {
                url = asset["browser_download_url"]?.jsonPrimitive?.content
                break
            }
        }
        if (url != null) UpdateInfo(tag, url) else null
    } catch (e: Exception) {
        Log.i(TAG, "malformed release payload skipped: ${e.message}")
        null
    }

    /** Tag the user dismissed — not re-shown until the process restarts. */
    @Volatile
    private var dismissedTag: String? = null

    /** User tapped the banner X — hide for this session. */
    fun dismiss() {
        dismissedTag = _update.value?.tag
        _update.postValue(null)
    }

    /** Clear the unknown-source explainer (dialog dismissed). */
    fun clearUnknownSourceFlag() {
        _needsUnknownSource.postValue(false)
    }

    /** Clear the one-shot install-success flag after it was shown. */
    fun clearInstallSuccess() {
        _installSuccess.postValue(false)
    }

    // ------------------------------------------------------------------
    // Download + install
    // ------------------------------------------------------------------

    /**
     * Streams the APK asset into the app cache, then commits a PackageInstaller
     * session. The system confirm dialog opens via the commit IntentSender.
     */
    fun downloadAndInstall() {
        val info = _update.value ?: return
        Thread {
            try {
                val apk = download(info.apkUrl)
                if (apk == null) {
                    Log.i(TAG, "download failed")
                    _phase.postValue(UpdatePhase.DONE)
                    return@Thread
                }
                _phase.postValue(UpdatePhase.INSTALLING)
                install(apk)
            } catch (e: SecurityException) {
                // Unknown-sources restriction: this app needs "Install unknown
                // apps" enabled before the system will show the confirm dialog.
                Log.i(TAG, "install blocked: ${e.message}")
                _phase.postValue(UpdatePhase.DONE)
                _needsUnknownSource.postValue(true)
            } catch (e: Exception) {
                Log.i(TAG, "install skipped: ${e.message}")
                _phase.postValue(UpdatePhase.DONE)
            }
        }.start()
    }

    /** OkHttp streaming download with progress posted to [progress]. */
    private fun download(url: String): File? = try {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val src = resp.body ?: return null
            val total = src.contentLength()
            val out = File(App.context.cacheDir, SESSION_APK_NAME)
            var read = 0L
            src.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            _progress.postValue(((read * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
            }
            out
        }
    } catch (e: Exception) {
        Log.i(TAG, "download skipped: ${e.message}")
        null
    }

    private fun install(apk: File) {
        val ctx = App.context
        val installer = ctx.packageManager.packageInstaller
        val sessionId = installer.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        )
        val session = installer.openSession(sessionId)
        try {
            session.use { s ->
                apk.inputStream().use { input ->
                    val out = s.openWrite(SESSION_APK_NAME, 0, apk.length())
                    try {
                        input.copyTo(out)
                        s.fsync(out)
                    } finally {
                        out.close()
                    }
                }
                val intent = Intent(INSTALL_ACTION).setPackage(ctx.packageName)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                val pi = PendingIntent.getBroadcast(
                    ctx, sessionId, intent, flags
                )
                // Commit with the IntentSender: unknown-source blocks surface
                // here, the confirm dialog opens otherwise.
                s.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            try {
                installer.abandonSession(sessionId)
            } catch (_: Exception) {
            }
            throw e
        } finally {
            apk.delete()
        }
    }

    /** PackageInstaller status callback: success → user confirmed and it installed. */
    fun handleInstallStatus(extraStatus: Int, message: String?) {
        when (extraStatus) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "install success")
                _phase.postValue(UpdatePhase.DONE)
                _update.postValue(null)
                _installSuccess.postValue(true)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The launcher Intent inside extras opens the confirm dialog.
                Log.i(TAG, "install pending user action")
                _phase.postValue(UpdatePhase.DONE)
            }
            else -> {
                Log.i(TAG, "install failed: ${extraStatus} ${message ?: ""}")
                _phase.postValue(UpdatePhase.DONE)
            }
        }
    }

    /**
     * Registers the broadcast receiver that receives PackageInstaller commit
     * results. Call from MainActivity.onCreate; auto-unregisters on destroy.
     */
    fun registerStatusReceiver(activity: android.app.Activity) {
        if (statusReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
                )
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                // Unstash the confirm-dialog launcher intent when present.
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && confirm != null) {
                    _phase.postValue(UpdatePhase.DONE)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        Log.i(TAG, "confirm dialog skipped: ${e.message}")
                    }
                } else {
                    handleInstallStatus(status, msg)
                }
            }
        }
        val filter = IntentFilter(INSTALL_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }
        statusReceiver = receiver
    }

    fun unregisterStatusReceiver(activity: android.app.Activity) {
        statusReceiver?.let {
            try {
                activity.unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        statusReceiver = null
    }
}