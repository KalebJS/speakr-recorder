package dev.kalebjs.speakr.recorder

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.IOException
import java.io.OutputStream

/**
 * Live on-device transcription for the recorder.
 *
 * Sinks the shared AudioRecord PCM stream into a ParcelFileDescriptor pipe whose
 * read end is handed to a SpeechRecognizer via RecognizerIntent.EXTRA_AUDIO_SOURCE
 * (48 kHz mono PCM16), and posts rolling partial+final text to [App.transcript].
 *
 * Recognizer choice: createOnDeviceSpeechRecognizer when available (API 31+),
 * otherwise createSpeechRecognizer + EXTRA_PREFER_OFFLINE, otherwise none.
 *
 * Failure contract: transcription must NEVER take the recording down. If no
 * recognizer is available, a session start throws, or sessions keep failing
 * (5 consecutive failures), this degrades silently to no-transcription while
 * capture continues.
 *
 * Lifecycle mirrors the asr-probe: live-streaming sessions end on their own
 * (final result, timeout, busy service), and a fresh session — always with a
 * NEW pipe, since a pipe has exactly one reader — is started automatically.
 * ERROR_RECOGNIZER_BUSY additionally destroys + recreates the recognizer.
 * All recognizer calls happen on the main looper (callbacks arrive there too).
 */
class LiveTranscriber(private val context: Context, private val sampleRate: Int) {

    private val main = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false

    /** Read end handed to the active session; closed before the next session. */
    private var activeReadEnd: ParcelFileDescriptor? = null

    private val pipeLock = Object()
    private var pipeOut: OutputStream? = null

    /** Finals from earlier sessions of this recording, space-joined. */
    private val accumulated = StringBuilder()
    private var failStreak = 0
    private var lastPartialPostMs = 0L

    @Volatile private var running = false
    @Volatile private var capturing = false

    /** False = recognition unavailable or given up on; the UI just shows nothing. */
    @Volatile var available = true
        private set

    /** Starts the first recognition session. Call from the main thread. */
    fun start() {
        running = true
        capturing = true
        accumulated.setLength(0)
        failStreak = 0
        try {
            recognizer = createRecognizer()
            if (recognizer == null) {
                available = false
                return
            }
            recognizer?.setRecognitionListener(listener)
            startSession("start")
        } catch (_: Exception) {
            available = false
        }
    }

    /**
     * Tee target for the capture thread: converts PCM16 samples to little-endian
     * bytes and writes them into the active pipe. Never throws, never blocks on a
     * dead pipe beyond OS buffering — if the read end is gone the stream is closed
     * and audio is dropped until the next session opens a fresh pipe.
     */
    fun writePcm(samples: ShortArray, sampleCount: Int) {
        if (!available || sampleCount <= 0) return
        val out = synchronized(pipeLock) { pipeOut } ?: return
        val n = sampleCount.coerceAtMost(samples.size)
        val bytes = ByteArray(n * 2)
        var b = 0
        for (i in 0 until n) {
            val v = samples[i].toInt()
            bytes[b++] = (v and 0xFF).toByte()
            bytes[b++] = (v shr 8).toByte()
        }
        try {
            out.write(bytes)
        } catch (_: IOException) {
            synchronized(pipeLock) {
                try { pipeOut?.close() } catch (_: IOException) {}
                pipeOut = null
            }
        }
    }

    /** Ends the current session and stops feeding audio. Main thread. */
    fun pause() {
        capturing = false
        closePipeOut()
        main.post {
            try { recognizer?.stopListening() } catch (_: Exception) {}
        }
    }

    /** Starts a fresh session with a fresh pipe. Main thread. */
    fun resume() {
        capturing = true
        if (!available) return
        failStreak = 0
        restartSession("resume")
    }

    /** Tears the recognizer down. Idempotent, safe from any thread. */
    fun stop() {
        running = false
        capturing = false
        closeActiveReadEnd()
        closePipeOut()
        main.post {
            val rec = recognizer
            recognizer = null
            try { rec?.stopListening() } catch (_: Exception) {}
            try { rec?.destroy() } catch (_: Exception) {}
        }
    }

    // --- internals: main thread unless noted ---

    private fun createRecognizer(): SpeechRecognizer? = when {
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { usingOnDevice = true }
        SpeechRecognizer.isRecognitionAvailable(context) ->
            SpeechRecognizer.createSpeechRecognizer(context).also { usingOnDevice = false }
        else -> null
    }

    private fun startSession(reason: String) {
        val rec = recognizer ?: return
        closeActiveReadEnd()
        val readEnd = openFreshPipe()
        activeReadEnd = readEnd
        try {
            rec.startListening(buildAudioIntent(readEnd))
        } catch (_: Exception) {
            // No reader will consume this pipe; drop it so writes never block.
            closeActiveReadEnd()
            closePipeOut()
            failStreak++
            if (failStreak >= MAX_CONSECUTIVE_FAILURES) available = false
        }
    }

    private fun restartSession(reason: String) {
        main.post {
            if (!running || !capturing || !available) return@post
            startSession(reason)
        }
    }

    private fun recreateRecognizer() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = createRecognizer()
        if (recognizer == null) {
            available = false
            return
        }
        recognizer?.setRecognitionListener(listener)
    }

    private fun buildAudioIntent(readEnd: ParcelFileDescriptor): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            if (!usingOnDevice) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

    /** accumulated + current partial, posted to the state bus. */
    private fun postTranscript(partial: String) {
        val sb = StringBuilder(accumulated)
        if (sb.isNotEmpty() && partial.isNotEmpty()) sb.append(' ')
        sb.append(partial)
        App.onTranscription(sb.toString())
    }

    /** Closes the previous write end (EOF for the old session) and opens a new pipe. */
    private fun openFreshPipe(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        synchronized(pipeLock) {
            try { pipeOut?.close() } catch (_: IOException) {}
            pipeOut = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        }
        return pipe[0]
    }

    private fun closeActiveReadEnd() {
        try { activeReadEnd?.close() } catch (_: Exception) {}
        activeReadEnd = null
    }

    private fun closePipeOut() {
        synchronized(pipeLock) {
            try { pipeOut?.close() } catch (_: IOException) {}
            pipeOut = null
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            // Session actually started; consecutive-failure streak resets.
            failStreak = 0
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!running) return
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (partial.isEmpty()) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastPartialPostMs < PARTIAL_MIN_INTERVAL_MS) return
            lastPartialPostMs = now
            postTranscript(partial)
        }

        override fun onResults(results: Bundle?) {
            if (!running) return
            val best = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (best.isNotEmpty()) {
                if (accumulated.isNotEmpty()) accumulated.append(' ')
                accumulated.append(best)
            }
            failStreak = 0
            postTranscript("")
            restartSession("result")
        }

        override fun onError(error: Int) {
            if (!running) return
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                recreateRecognizer()
            }
            failStreak++
            if (failStreak >= MAX_CONSECUTIVE_FAILURES) {
                // Degrade gracefully: keep recording, drop the live captions.
                available = false
                closeActiveReadEnd()
                closePipeOut()
                return
            }
            restartSession("error $error")
        }
    }

    companion object {
        /** Consecutive failed sessions before transcription is switched off. */
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val PARTIAL_MIN_INTERVAL_MS = 120L
    }
}