package dev.kalebjs.speakr.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

/**
 * Foreground service that captures mic audio (screen-off safe).
 * AAC LC / 48 kHz / 128 kbps mono in an MPEG-4 container — good quality,
 * small files, and mono keeps Speakr's diarization clean.
 * Supports pause/resume (single continuous file) and amplitude reporting.
 */
class RecorderService : Service() {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var tickTimer: Timer? = null
    private var ampTimer: Timer? = null
    private var elapsedSeconds = 0L
    private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                if (recorder != null && !paused) {
                    try {
                        recorder?.pause()
                        paused = true
                        App.paused.postValue(true)
                        stopTimers()
                        notifyForeground("Paused — tap ▲ to review")
                    } catch (_: Exception) {
                    }
                }
            }
            ACTION_RESUME -> {
                if (recorder != null && paused) {
                    try {
                        recorder?.resume()
                        paused = false
                        App.paused.postValue(false)
                        startTimers()
                        notifyForeground("Recording…")
                    } catch (_: Exception) {
                    }
                }
            }
            ACTION_ABORT -> {
                abortRecording()
                stopSelf()
            }
            ACTION_STOP -> {
                finalizeRecording()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (recorder == null) {
                    startAsForeground("Recording…")
                    startRecording()
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    private fun makeNotification(text: String): Notification {
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
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Speakr Recorder")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun notifyForeground(text: String) {
        val notification = makeNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startAsForeground(text: String) = notifyForeground(text)

    private fun startRecording() {
        val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "speakr-$stamp.m4a")
        outputFile = file

        try {
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(48_000)
                setAudioEncodingBitRate(128_000)
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            file.delete()
            App.toast("Could not start recorder (mic busy?)")
            stopSelf()
            return
        }

        paused = false
        App.paused.postValue(false)
        App.onRecordingStarted(file.absolutePath)
        startTimers()
    }

    private fun startTimers() {
        stopTimers()
        tickTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    elapsedSeconds++
                    App.onTick(elapsedSeconds)
                }
            }, 1000, 1000)
        }
        ampTimer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    // True capture level (0..32767) sampled fast enough to feel alive.
                    try {
                        App.onAmplitude(recorder?.maxAmplitude ?: 0)
                    } catch (_: Exception) {
                    }
                }
            }, 0, 120)
        }
    }

    private fun stopTimers() {
        tickTimer?.cancel(); tickTimer = null
        ampTimer?.cancel(); ampTimer = null
    }

    /** Final stop: closes the file and queues it for upload. */
    private fun finalizeRecording() {
        stopTimers()
        val r = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        paused = false
        App.paused.postValue(false)
        if (r != null && file != null) {
            try {
                r.stop()
                if (file.length() > 0) {
                    App.enqueueIfAbsent(
                        PendingUpload(
                            path = file.absolutePath,
                            tagIds = emptyList(),
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
            } catch (_: Exception) {
                file.delete()
                App.toast("Recording failed — nothing saved")
            } finally {
                r.release()
            }
        }
        App.recording.postValue(false)
        UploadWorker.kick()
    }

    /** Abort: close and delete, no upload. */
    private fun abortRecording() {
        stopTimers()
        val r = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        paused = false
        App.paused.postValue(false)
        if (r != null) {
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        file?.delete()
        App.recording.postValue(false)
        App.recordingFilePath.postValue(null)
    }

    override fun onDestroy() {
        stopTimers()
        if (recorder != null) {
            try { recorder?.stop() } catch (_: Exception) {}
            recorder?.release()
            recorder = null
        }
        App.recording.postValue(false)
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