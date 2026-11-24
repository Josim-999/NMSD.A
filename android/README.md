# Video Studio (Android)

Turns a topic into a finished vertical Short on the phone: Gemini researches and writes the
script, a Microsoft neural voice reads it, the app animates and renders the video, then posts it.

## What's new in 1.4: better video at the same Gemini cost

Everything below runs on the phone; Gemini is called exactly as before.

- **Sharper master file:** 12 Mbps at 1080p 30 fps (16 Mbps at 60 fps), H.264 High profile,
  variable bitrate (Android 12+ keeps a minimum quality), BT.709 colour tags. Falls back step by
  step on phones whose encoder refuses a setting.
- **Optional 60 fps** (Settings → Voice and video) for silkier motion.
- **Platform safe zones:** graphics stay in 300-1150 px and captions around 1330 px on the
  1080x1920 frame, clear of TikTok, Reels and Shorts buttons and captions.
- **Motion:** zoom-push transitions between scenes, a slow camera push-in, spring pops on list
  items, captions and buttons, soft text shadows, vignette and fine film grain.
- **Real footage:** with a Pexels key, photo scenes play portrait stock video clips full-screen
  (free Pexels video API), falling back to a photo, then an emoji. Gemini is asked for 2-3
  such scenes when footage is available.
- **Sound:** voice high-pass and compression; music generated on the phone (no licences), about
  16 dB under the voice while speaking and 8 dB in pauses; a whoosh on each scene change;
  loudness about -14 LUFS with a -1 dBFS peak limit; AAC 160 kbps at 48 kHz.

## What's new in 1.3

- Ready for updates: every future version installs over this one and keeps your videos and
  settings (see Updates below). Settings → About shows the installed version and build.

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
./gradlew :app:testDebugUnitTest   # unit, Robolectric, screen and update tests
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

If Maven Central rate-limits downloads on your machine, run Gradle with `CENTRAL_MIRROR=1` to
also use Google's mirror of it.

## Updates

An update installs over the app on the phone only if all three hold:

1. **Same signing key.** Android refuses an update signed with another key. The release key's
   certificate SHA-256 is `2b18af11…d6c14` (`releaseCertSha256` in `app/build.gradle.kts`).
   GitHub Actions reads the key from three repository secrets: `RELEASE_KEYSTORE_BASE64`,
   `RELEASE_KEYSTORE_PASSWORD` and `RELEASE_KEY_ALIAS` (`videostudio`). With
   `REQUIRE_RELEASE_KEY=1`, which the workflow sets, the build stops if the key is missing or
   different, and the workflow checks the finished APK's certificate again. Locally the key is
   read from `android/keystore.properties`, which is never committed:
   `store.file`, `store.password`, `key.alias`, `key.password`.
   Never commit the key: this repository is public.
2. **Higher version code.** `versionCode` comes from the build time (minutes since
   2025-01-01), so a later build is always higher. Builds before that: 1.0 = 1, 1.1 = 2, 1.2 = 3.
   Raise `versionName` by hand for each release.
3. **Readable data.** `Migrations` runs at every start-up. Never rename or remove keys in
   `project.json` or `Settings`; add new ones with defaults. When data has to change shape, bump
   `Migrations.CURRENT` and add a step. `UpdateCompatTest` loads data saved by 1.0 and 1.1.

The application id `com.videostudio.app` must never change either.

## Layout

- `ai/` Gemini client and the research/script prompts
- `voice/` Microsoft Edge neural voice (with word timings) and the phone's own voice as fallback
- `render/` timeline, scene painter (all scene types, captions, transitions), stock media, soundtrack mixer (`AudioMix`) and the H.264/AAC encoder
- `upload/` `PostAll` (the Post to all queue), `Share` (opening each app), `YouTube` (optional direct upload)
- `work/` background jobs, foreground service and the pipeline steps
- `ui/` Compose screens
