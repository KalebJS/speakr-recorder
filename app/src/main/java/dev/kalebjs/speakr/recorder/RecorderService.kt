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
import androidx.core.app.NotificationCompat
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
 */
class RecorderService : Service() {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var timer: Timer? = null
    private var elapsedSeconds = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startAsForeground()
                startRecording()
                return START_STICKY
            }
        }
    }

    private fun startAsForeground() {
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
                .setContentTitle("Recording…")
                .setContentText("Tap to open Speakr Recorder")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(openApp)
                .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
                .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

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

        App.onRecordingStarted(file.absolutePath)
        timer = Timer().apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    elapsedSeconds++
                    App.onTick(elapsedSeconds)
                    // True capture level (0..32767); doubles as "still alive" proof.
                    try {
                        App.onAmplitude(recorder?.maxAmplitude ?: 0)
                    } catch (_: Exception) {
                    }
                }
            }, 1000, 1000)
        }
    }

    private fun stopRecording() {
        timer?.cancel()
        timer = null
        val r = recorder
        val file = outputFile
        recorder = null
        outputFile = null
        if (r != null && file != null) {
            try {
                r.stop()
                if (file.length() > 0) {
                    App.enqueueUpload(
                        PendingUpload(
                            path = file.absolutePath,
                            tagIds = emptyList(),
                            createdAt = System.currentTimeMillis()
                        )
                    )
                    App.toast("Saved — pick a tag to send")
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

    override fun onDestroy() {
        timer?.cancel()
        recorder?.release()
        recorder = null
        App.recording.postValue(false)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "dev.kalebjs.speakr.recorder.STOP"
    }
}