# Video Studio (Android)

Turns a topic into a finished vertical Short on the phone: Gemini researches and writes the
script, a Microsoft neural voice reads it, the app animates and renders the video, then posts it.

## What's new in 1.2

- **Post to all, with nothing to set up.** One button opens TikTok, Instagram, YouTube and
  Facebook (plus X, Threads, LinkedIn or WhatsApp if ticked) one after another. Each app opens
  with the video attached and the caption on the clipboard (the title, for YouTube). Post there,
  come back, and the next app opens after a 3-second countdown, with Skip, Stop and "open it again".
  No API keys, no accounts to connect, no backend: you are already signed in to those apps.
- The video is saved once to Movies/Video Studio and shared from the gallery, which every app accepts.
- Removed the Upload-Post connection (it needed an account and an API key).
- YouTube direct upload with your own Google Cloud client moved to Settings → Advanced and only
  shows on the video screen once you have signed in there.

## Build

Needs JDK 17 and the Android SDK (platform 35).

```
cd android
./gradlew :app:testDebugUnitTest   # unit, Robolectric and screen tests
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

Release signing reads `android/keystore.properties` (not committed):

```
store.file=/path/to/videostudio-release.jks
store.password=...
key.alias=videostudio
key.password=...
```

Without it, the release APK is signed with the debug key. GitHub Actions
(`.github/workflows/android.yml`) builds the APK on every push and can sign it with
repository secrets.

If Maven Central rate-limits downloads on your machine, run Gradle with `CENTRAL_MIRROR=1` to
also use Google's mirror of it.

## Layout

- `ai/` Gemini client and the research/script prompts
- `voice/` Microsoft Edge neural voice (with word timings) and the phone's own voice as fallback
- `render/` timeline, scene painter (all scene types, captions) and the H.264/AAC encoder
- `upload/` `PostAll` (the Post to all queue), `Share` (opening each app), `YouTube` (optional direct upload)
- `work/` background jobs, foreground service and the pipeline steps
- `ui/` Compose screens
