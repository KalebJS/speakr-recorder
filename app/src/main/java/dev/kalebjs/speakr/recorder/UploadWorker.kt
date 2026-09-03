package dev.kalebjs.speakr.recorder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drains the upload queue in the background. Failed uploads stay queued and
 * are retried with capped exponential backoff whenever the app or a finished
 * recording kicks the worker.
 */
object UploadWorker {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running = false

    fun kick() {
        if (running) return
        running = true
        scope.launch {
            try {
                drain()
            } finally {
                running = false
            }
        }
    }

    private suspend fun drain() {
        if (!App.isConfigured) return
        val api = SpeakrApi(App.serverUrl, App.apiToken)
        val queue = App.loadQueue()
        val now = System.currentTimeMillis()

        for (item in queue) {
            val file = File(item.path)
            if (!file.exists()) {
                App.removeQueued(item.path)
                continue
            }
            // Backoff: 1m, 5m, 25m, then every 30m, up to 10 attempts
            if (item.attempts > 0) {
                val backoffMs = minOf(60_000L * (1L shl minOf(item.attempts, 5)), 30L * 60_000L)
                if (now - item.lastAttemptAt < backoffMs) continue
            }
            try {
                api.upload(file, item.tagIds)
                App.removeQueued(item.path)
                file.delete()
                App.toast("Sent to Speakr")
            } catch (e: Exception) {
                val updated = item.copy(
                    attempts = item.attempts + 1,
                    lastAttemptAt = now
                )
                if (updated.attempts >= 10) {
                    App.toast("Upload failed 10x — kept on device")
                } else {
                    App.updateQueueEntry(updated)
                }
                // Server unreachable: stop hammering, retry on next kick
                if (e.message?.contains("Server error 5", ignoreCase = true) == true ||
                    e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("Unable to resolve", ignoreCase = true) == true ||
                    e.message?.contains("Failed to connect", ignoreCase = true) == true
                ) break
            }
        }
    }
}