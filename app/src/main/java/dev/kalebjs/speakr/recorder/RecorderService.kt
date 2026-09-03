package dev.kalebjs.speakr.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.IBinder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import kotlin.math.sqrt

/**
 * Foreground capture via AudioRecord -> MediaCodec(AAC-LC) -> MediaMuxer(MP4).
 * Screen-off safe, pause/resume as one continuous file, and true PCM level
 * data for the waveform (MediaRecorder.maxAmplitude is unreliable here).
 * 48 kHz mono, 128 kbps AAC-LC — diarization-friendly.
 */
class RecorderService : Service() {

    private enum class State { IDLE, RECORDING, PAUSED, RELEASING }

    @Volatile private var state = State.IDLE

    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var outputFile: File? = null
    private var captureThread: Thread? = null
    private var tickTimer: Timer? = null
    private var elapsedSeconds = 0L
    private var totalSamples = 0L

    private val sampleRate = 48_000
    private var finalize = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> pauseCapture()
            ACTION_RESUME -> resumeCapture()
            ACTION_ABORT -> {
                if (state != State.IDLE) {
                    finalize = false
                    abortFlag = true
                    state = State.RELEASING
                } else stopSelf()
            }
            ACTION_STOP -> {
                if (state != State.IDLE) {
                    finalize = true
                    abortFlag = false
                    state = State.RELEASING
                } else stopSelf()
            }
            else -> {
                if (state == State.IDLE) {
                    startForegroundWith("Recording…")
                    startCapture()
                }
            }
        }
        return START_STICKY
    }

    private fun notifyForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification =
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Speakr Recorder")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(openApp)
                .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
                .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startForegroundWith(text: String) = notifyForeground(text)

    private fun startCapture() {
        val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "speakr-$stamp.m4a")
        outputFile = file
        totalSamples = 0L

        try {
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4
            )

            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            encoder = MediaCodec.createEncoderByType("audio/mp4a-latm").apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            audioRecord?.startRecording()
            state = State.RECORDING
            App.paused.postValue(false)
            App.onRecordingStarted(file.absolutePath)
            startTickTimer()
            captureThread = Thread { captureLoop() }.apply { start() }
        } catch (e: Exception) {
            cleanupMedia(keepFile = false)
            App.toast("Could not start recorder (mic busy?)")
            state = State.IDLE
            stopSelf()
        }
    }

    private fun pauseCapture() {
        if (state != State.RECORDING) return
        try { audioRecord?.stop() } catch (_: Exception) {}
        state = State.PAUSED
        App.paused.postValue(true)
        notifyForeground("Paused — tap ▲ to review")
    }

    private fun resumeCapture() {
        if (state != State.PAUSED) return
        try { audioRecord?.startRecording() } catch (_: Exception) {}
        state = State.RECORDING
        App.paused.postValue(false)
        notifyForeground("Recording…")
    }

    /** Capture loop: PCM in -> encoder -> muxer. Handles pause (no reads) and EOS. */
    private fun captureLoop() {
        val pcm = ShortArray(2048) // ~43 ms chunks at 48 kHz
        val bufInfo = MediaCodec.BufferInfo()
        var eosSent = false
        var eosDrained = false

        try {
            while (true) {
                val recording = state == State.RECORDING
                val releasing = state == State.RELEASING
                if (state == State.IDLE) break

                if (recording) {
                    val enc = encoder
                    val rec = audioRecord
                    if (enc != null && rec != null) {
                        val inIdx = enc.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val read = rec.read(pcm, 0, pcm.size)
                            if (read > 0) {
                                val inBuf = enc.getInputBuffer(inIdx)!!
                                inBuf.clear()
                                inBuf.asShortBuffer().put(pcm, 0, read)
                                val pts = totalSamples * 1_000_000L / sampleRate
                                enc.queueInputBuffer(inIdx, 0, read * 2, pts, 0)
                                totalSamples += read
                                // True PCM RMS level (0..1) for the waveform.
                                var sum = 0.0
                                for (i in 0 until read) {
                                    val v = pcm[i].toDouble()
                                    sum += v * v
                                }
                                val rms = sqrt(sum / read) / 32768.0
                                App.onLevel(rms.coerceIn(0.0, 1.0))
                            } else if (read < 0) {
                                Thread.sleep(5)
                            }
                        }
                    }
                } else if (state == State.RELEASING && !eosSent) {
                    val enc = encoder
                    if (enc != null) {
                        val inIdx = enc.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            enc.queueInputBuffer(inIdx, 0, 0, totalSamples * 1_000_000L / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        }
                    }
                }

                // Drain encoder output (also drives muxer start + EOS detection).
                val enc = encoder
                if (enc != null) {
                    while (true) {
                        val outIdx = enc.dequeueOutputBuffer(bufInfo, if (eosSent) 10_000 else 0)
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                muxer?.let { m ->
                                    trackIndex = m.addTrack(enc.outputFormat)
                                    m.start()
                                }
                            }
                            outIdx >= 0 -> {
                                val outBuf = enc.getOutputBuffer(outIdx)
                                if (outBuf != null && bufInfo.size > 0 &&
                                    bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                                ) {
                                    muxer?.let { m ->
                                        if (trackIndex >= 0) {
                                            outBuf.position(bufInfo.offset)
                                            outBuf.limit(bufInfo.offset + bufInfo.size)
                                            m.writeSampleData(trackIndex, outBuf, bufInfo)
                                        }
                                    }
                                }
                                enc.releaseOutputBuffer(outIdx, false)
                                if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    eosDrained = true
                                }
                            }
                            else -> break
                        }
                        if (eosDrained) break
                    }
                }

                if (eosDrained) break
                if (state == State.PAUSED) Thread.sleep(40)
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
        } finally {
            val keep = state != State.RELEASING || outputFile?.let { it.length() > 0 } == true
            val aborted = state == State.RELEASING && abortFlag
            cleanupMedia(keepFile = !aborted)
            state = State.IDLE
            App.recording.postValue(false)
            App.paused.postValue(false)
            if (aborted) {
                App.recordingFilePath.postValue(null)
            } else {
                outputFile?.let { f ->
                    if (f.length() > 0) {
                        App.enqueueIfAbsent(
                            PendingUpload(
                                path = f.absolutePath,
                                tagIds = emptyList(),
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        App.toast("Saved — review and send")
                    } else {
                        f.delete()
                        App.toast("Recording failed — nothing saved")
                    }
                }
                UploadWorker.kick()
            }
            stopTickTimer()
            stopSelf()
        }
    }

    @Volatile private var abortFlag = false

    private fun cleanupMedia(keepFile: Boolean) {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        encoder = null
        try { if (trackIndex >= 0) muxer?.stop() } catch (_: Exception) {}
        try { muxer?.release() } catch (_: Exception) {}
        muxer = null
        trackIndex = -1
        if (!keepFile) {
            outputFile?.delete()
        }
    }

    private fun startTickTimer() {
        tickTimer?.cancel()
        tickTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    if (state == State.RECORDING) {
                        elapsedSeconds++
                        App.onTick(elapsedSeconds)
                    }
                }
            }, 1000, 1000)
        }
    }

    private fun stopTickTimer() {
        tickTimer?.cancel()
        tickTimer = null
    }

    override fun onDestroy() {
        if (state != State.IDLE) {
            state = State.RELEASING
            captureThread?.interrupt()
        }
        captureThread = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "dev.kalebjs.speakr.recorder.STOP"
        const val ACTION_PAUSE = "dev.kalebjs.speakr.recorder.PAUSE"
        const val ACTION_RESUME = "dev.kalebjs.speakr.recorder.RESUME"
        const val ACTION_ABORT = "dev.kalebjs.speakr.recorder.ABORT"
    }
}