# Video Studio (Android)

Turns a topic into a finished vertical Short on the phone: Gemini researches and writes the
script, a Microsoft neural voice reads it, the app animates and renders the video, then posts it.

## What's new in 1.1

- **Connected accounts: post everywhere with one tap.** Paste one Upload-Post API key, tap
  *Connect accounts*, log in to each network once (TikTok, Instagram, YouTube, Facebook, X,
  LinkedIn, Threads, Bluesky). After that, *Post everywhere* sends a video to all of them in one
  go and shows each network's link. No Google Cloud project, no developer accounts, no app reviews.
  Upload-Post holds the approved app registrations with each network; its free plan allows
  10 uploads a month.
- **YouTube upload fixed.** YouTube now works through Connected accounts with no Google Cloud
  setup. The old direct route (your own OAuth client) stays under Settings as an advanced option,
  with fixes: it no longer signs you out on a network blip, and Google's errors are explained
  (no test user added, the 7-day sign-in limit in Testing mode, the API not enabled, no channel,
  daily quota).
- Share buttons for X, Threads, LinkedIn and WhatsApp next to TikTok, Instagram, Facebook and the
  YouTube app.

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
- `upload/` `SocialPost` (one-tap posting), `YouTube` (direct upload), `Share` (apps on the phone)
- `work/` background jobs, foreground service and the pipeline steps
- `ui/` Compose screens
