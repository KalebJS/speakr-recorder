# Speakr Recorder

A minimal Android voice-recorder companion for your self-hosted [Speakr](https://github.com/murtaza-nasir/speakr) instance.

One button. Record. Pick a tag. It lands in your Speakr, transcribed and summarized like any other recording.

## Features

- **One-tap recording** — big record button, live elapsed timer, nothing else
- **Background recording** — a foreground service keeps capture running with the screen off or while you use other apps (persistent notification with a Stop action)
- **Clean capture** — AAC-LC, 48 kHz, 128 kbps mono in MPEG-4: high voice quality, small files, and mono keeps Speakr's speaker diarization accurate
- **Live transcription captions** — while recording, the newest few words appear on-screen as a fading live caption, recognized entirely on-device (nothing leaves the phone)
- **Pre-existing tags** — your Speakr tags are fetched and shown as color-coded chips; attach one (or several) before sending
- **Offline queue** — recordings are saved locally first. If your server is unreachable, uploads retry automatically with backoff; a "pending" chip shows the queue count
- **Private by design** — your server URL and API token are stored in EncryptedSharedPreferences on-device and sent nowhere but your own instance
- **Bring your own server** — set your Speakr URL and API token on first launch (Speakr → Settings → API Tokens)

## Not supported: phone-call recording

Android 10+ blocks third-party apps from capturing call audio (both sides of a
call are off-limits to normal apps — only the default dialer or system apps can
do it, and Play-policy workarounds are banned). This app records the
microphone, not calls.

## Building

```
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17+, Android SDK (platform 36, build-tools 36).

## Using

1. Install the APK and open **Speakr Recorder**
2. Enter your Speakr server URL (e.g. `https://speakr.example.com`) and an API token (Speakr → Settings → API Tokens), then **Test connection**
3. Tap the big button to record; tap again to stop
4. Pick tags (optional) → **Send to Speakr**

Recordings you send appear in your Speakr web UI like any other upload.

## License

MIT — see [LICENSE](LICENSE).