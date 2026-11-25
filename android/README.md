# Video Studio (Android)

Turns a topic into a finished vertical Short on the phone: Gemini researches and writes the
script, a Microsoft neural voice reads it, the app animates and renders the video, then posts it.

## What's new in 1.7: edited like a pro team (same Gemini cost)

Built from research on what Shorts, Reels and TikTok reward: few swipe-aways in the first
2-3 seconds, completion, rewatches, a visual change every ~2 seconds, and channels that do not
look mass-produced (YouTube will not monetize templated, repetitive content).

- **Hook readable on frame 1** (the feed preview), then it comes alive.
- **A different look for every video** (`Look`): 4 caption styles (chip, colour-pop, boxed,
  glow) x 4 transitions (zoom-push, slide-up, whip-pan, iris) x 3 backgrounds (soft light, grid,
  aurora). Each new video differs from the previous one in all three; "Shuffle look" per video.
- **Your take:** an optional sentence of your own opinion on New video; the script always carries
  one reasoned take or insight (originality protects monetization).
- **Emphasis words:** Gemini marks the key word of each line; it gets a zoom punch, its own
  caption colour and an impact hit.
- **A visual change every ~2 s:** cut-ins inside long scenes; long photo scenes become 2-3 photo
  slideshows (Openverse searches are cached, so this spends no extra quota).
- **Seamless loop:** the last 0.45 s fade into the first frame.
- **Sound design:** pops when items appear, hits on emphasis words and numbers, a riser into the
  final scene; music follows the script's mood (energetic, calm, dramatic, playful) with
  plucked arpeggios for upbeat moods.
- **New scenes:** animated bar charts, timelines, and quote cards with the speaker's photo.
- **Packaging:** titles of 4-6 words with the keyword first, 3-5 hashtags, keyword spoken in the
  first line.
- Fixed: lines of multi-line text could bleed into each other (visible with lower-case text).

## What's new in 1.6: real pictures and logos, no keys needed

- **Pictures matched to the script, free and keyless** (`MediaFinder`): Pexels when a key is set,
  then the Wikipedia image of the named person, place or thing, then Openverse (Creative Commons
  photos from Flickr, Wikimedia and others), then Wikimedia Commons. Prefers large portrait images;
  never repeats a picture in one video. Credits go to `credits.txt` in the project folder.
- **Logos of every brand the video mentions:** Simple Icons vector logos in the brand's own
  colour (3,000+ brands), else Google's 256 px site icon. A new **logo** scene (brand tile with
  glow, light sweep, spring pop, pulsing rings) and a logo badge on any scene naming a brand.
- Gemini (same single call) now marks brands, websites and Wikipedia topics per scene.
- No more emoji fallback when no picture is found: the brand's logo, else clean typography.
- New colour styles: Noir Gold, Aurora, Crimson.
- Wikimedia and Openverse requests identify the app (User-Agent), as their API rules ask.

## What's new in 1.5

- **Gemini quota (HTTP 429) no longer stops a video.** A per-minute limit waits the time Gemini
  asks for and retries. A daily limit (or a model with no free quota) moves to the next model:
  the research model, the script model, then gemini-2.5-flash, gemini-2.5-flash-lite and
  gemini-2.0-flash; models out of quota are skipped until the midnight-Pacific reset. If research
  has no quota anywhere, the script is written without it. Only when every model is out does the
  app stop, with a plain message.
- **Google sign-in no longer hangs after "Continue".** Android froze the app while the browser
  was in front, so its loopback sign-in server never answered. A short foreground service
  (`KeepAliveService`, with a notification) keeps it awake during sign-in, and there is a
  Cancel button.

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

## 1.8: YouTube rules and monetization

- **YouTube check** on the Video tab, before posting. It checks: audience (made for kids), swearing, clickbait titles, sources, sensitive topics (health, money, news, elections), picture licences, original music, AI disclosure, paid promotion, and whether the video is too close to an earlier one.
- **Made for kids** and **Paid promotion** switches, per video. Direct uploads send both to YouTube (`selfDeclaredMadeForKids`, `paidProductPlacementDetails`).
- **The script writer follows YouTube's rules**: advertiser-friendly language, honest titles, careful health and finance claims, real quotes only. It also flags sensitive topics and child-directed videos.
- **Pictures**: only licences that allow commercial use *and* changes (Openverse `commercial,modification`). Credits go into the YouTube description, and a short version goes into captions.
- **Disclaimers** are added automatically: "not medical/financial advice" and "Includes paid promotion".
- **Settings → Get monetized**: what the YouTube Partner Program asks for.

## 1.9: never stuck, US scheduling, free motion graphics

- **Auto-switch AI models.** The app lists every model the Gemini key can use and tries them in order: newest Gemini Flash, Flash-Lite, Pro, then **Gemma**, which has a much larger free daily quota on the same key. JSON mode and Google Search are only sent to models that support them. If every free model is used up, a background job (JobScheduler, survives restarts) writes the script again after the midnight-Pacific reset and sends a notification. Free tier only, so the cost stays the same.
- **Schedule for US viewers.** Pick a zone (ET, CT, MT, PT), a day and a time; the best US Shorts times are preset. "Schedule on YouTube" (direct upload) sends the video private with `publishAt`, and YouTube makes it public on its own servers, so the phone can be offline at that time. With no connection when you tap, it uploads by itself once online. "Schedule in the YouTube app" opens YouTube with the video and the title copied, and shows the matching time on your phone for Visibility → Schedule. Note: API uploads from unaudited Google Cloud projects stay private until YouTube's free API audit passes.
- **Animated emoji**: Google Noto Animated Emoji (Lottie, CC BY 4.0), cached on the phone.
- **Pop-out photos**: ML Kit subject segmentation on the phone; the headline sits behind the subject.
- **Display fonts** per caption style: Poppins ExtraBold, Anton, Archivo Black, Bebas Neue (OFL, licences in assets/fonts).
- **More free pictures**: NASA Image Library (public domain) and The Met (CC0), chosen through the script's `library` hint. Optional Pixabay key for HD photos and stock video (Pexels has paused new keys).

