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

## 2.0: more free AI for scripts

- **Backup AI chain.** Scripts are written by Gemini first, using every free model on the key. When all of them are out, the app moves on to free OpenAI-compatible services, each with its own optional free key: Groq (about 1,000 requests a day), NVIDIA, Mistral, and OpenRouter (free models only). The last resort is the keyless Pollinations endpoint, which can be turned off. A service that hits its daily limit is rested and skipped; an answer that isn't a usable script moves on to the next model.
- **Fix:** a per-minute Gemini limit used to mark a model as used up for the whole day. It now rests the model for 3 minutes only.
- Research (Google Search) is skipped, not failed, when Gemini can't do it; the script is then written from general knowledge.

## 2.1: never-stop script chain, Liquid glass look

- **Ten free AI services, instant switching.** The order is Z.ai GLM Flash (free Chinese models, the most room), NVIDIA, every free Gemini/Gemma model, Groq, Alibaba Qwen (Singapore free quota), Mistral, OpenRouter free models, SiliconFlow, Cloudflare Workers AI, then Pollinations (new gen.pollinations.ai endpoint, key optional). With backups configured, a Gemini per-minute limit no longer waits: it switches at once. Used-up services rest and come back. If all of them fail, the background retry comes after 20 minutes, then after the daily reset. Settings shows a status for each service.
- **Liquid glass look** (Settings → Video look: Mix / Liquid glass / Classic dark), built from a study of popular AI-tutorial Shorts:
  - Pastel mesh light theme with frosted glass cards; problem, myth and data scenes switch to a midnight-violet dark theme with a radial wipe.
  - Blur-in kinetic captions on a glass pill, with gradient-filled punch words.
  - UI micro-events, each with a click sound: a cursor taps "send" on the hook card and clicks list items as they pop; a toggle flips from MYTH to FACT; percent stats fill a glass progress bar; photos play in a floating phone mock-up; the ending has a notification banner and a comment box.

## 2.2: the script step never fails

- **Built-in writer** (`ai/BuiltInWriter.kt`): no AI, no key, no quota. It looks the topic up on Wikipedia (REST search + summary, keyless), falling back to DuckDuckGo instant answers, and builds a full script with the usual beats: hook, promise, facts as stat/photo/list/statement scenes, the creator's take, payoff and comment question, plus titles, hashtags and sources. Offline it writes a grammatical, structured script from the topic alone.
- It runs automatically whenever every free AI fails (quota, bad key, no internet), so writing a script never ends in an error. The Script tab says when the built-in writer was used; tap Rewrite later for an AI version.
- Free research: when Gemini's search research is used up, Wikipedia facts become the research notes for the AI writers.

## 2.3: more pro looks, tier lists, 60-second default

- **Paper sketch look** (whiteboard and stick-figure explainer style): cream ruled paper with a red margin, Permanent Marker lettering (Apache 2.0), yellow highlighter captions with red punch words, hand-drawn stat circles, wavy underlines and wobbly sketched cards.
- **Kinetic type look**: on a dark screen, the spoken words of statement, question and emoji scenes stack up huge, each one slamming in as it is said. Punch words get their own line in colour, and older lines scroll up.
- **Tier list scene** (`"type":"tier"`, items like `S: name`) in every look: S/A/B/C/D rows, with each item popping in along with a sound.
- Settings → Video look: Mix / Liquid glass / Paper sketch / Kinetic type / Classic dark.
- **60 seconds is the default length.** Scripts under 85% of the word target are lengthened once. If the recorded voice is more than about 5 s off the target, it is re-recorded slightly slower or faster (at most ±12 points).

## 2.4: hook intro (first ~5 seconds)

Every look now opens the way top Shorts do:
- Frame 1 stays clean and readable (the feed preview), on a vivid rotating colour sunburst.
- From the second frame: an impact flash, RGB-split glitch and screen shake, with a boom sound.
- A colourful confetti burst and a shockwave ring come out of the hook.
- The hook's key word slams in as a tilted colour sticker when it is spoken, with a second shockwave and shake.
- Three bright diagonal stripes wipe across the cut to the second scene, with a whoosh.
- The colour fades out gradually by 5 s. Colours rotate per video.

## 2.5: captions and hashtags for every app, typed in automatically

- **A caption made for each app** (`upload/Captions.kt`), from the script with no extra AI call:
  - TikTok and Instagram: 5 hashtags at most (their new limit), with #fyp / #reels
  - Facebook: a short version with 3 tags
  - YouTube app: the title plus #shorts within 100 characters
  - X: fits 280 characters; Threads: 1 tag
  - Disclaimers, picture credits and the AI label are included where there is room.
- The Post-to-all card shows every app's caption with a Copy button. Each app's caption is on the clipboard when that app opens.

## 2.6: installable again

- Removed the accessibility "auto-fill captions" helper added in 2.5. Google Play Protect blocks sideloaded APKs that ask for accessibility access, so 2.5 could not be installed. The per-app captions and hashtags stay: each app's caption is copied as it opens.
- Rule for future versions: no accessibility, SMS or notification-listener permissions; Play Protect blocks those in sideloaded apps.

## 2.7: top-class scripts (playbook + quality gate)

- **Script playbook in every AI request:**
  - 10 proven hook formulas; the AI writes 5 hook options with different formulas, scores them and uses the best
  - hook rules: 5-10 words, keyword, a number or named tool, no "Hey guys" or "Did you know"
  - a timed 60 s structure: hook, promise/stakes, setup, a re-hook every ~12 s, escalating concrete beats, payoff with one action for today, comment trigger, loop line
  - language rules: sentences of 4-12 words, "you", at least 3 numbers or names
  - a banned-cliché list (unlock, delve, game-changer...)
- **Quality gate** (`ai/ScriptCheck.kt`): scores every script 0-100 on the phone. Below 80, one targeted rewrite fixes the listed issues, and it is kept only if it scores higher.
- **Hook picker:** the other hook options appear on the Script tab; tap Use to swap.
- **Best free writers first:** Kimi K2 and DeepSeek on NVIDIA and Groq, GLM on Z.ai, DeepSeek/Kimi first on OpenRouter.
- **Built-in writer:** bold-claim hooks from numeric facts, plus a re-hook line.

## 2.8: verified logos, relevant pictures

- **Logos are verified, never guessed** (`MediaFinder.logo`):
  1. Simple Icons only on an exact name match, and the icon's own source site must be the brand's official domain ("Gemini" on gemini.google.com becomes Google Gemini, never the Gemini crypto exchange).
  2. Otherwise the logo from the brand's Wikidata entry (Wikimedia Commons), only if that entry's official website matches the domain. A Wikipedia title from the script picks the exact entry.
  3. Otherwise the site icon of the official domain, only when that domain is confirmed to be the brand's.
  4. Otherwise no logo: the scene shows the brand name in clean type.
- Removed the guesses that caused wrong logos: first word of the brand, start of the domain, and invented "brand.com" domains.
- **The script must give** the exact official brand name and product domain, plus a Wikipedia title for logo scenes, and leave brands out when unsure.
- **Photos must match the scene:** Openverse, Commons and Pixabay results are kept only if their title or tags contain the search words (at least half of them for longer searches), and the closest match wins.

## 2.9: every video is 50-60 seconds

- Length choices are 50 / 55 / 60 s (default 60); older 30/45 s projects are lifted to 50.
- The AI gets a hard word range inside 50-60 s (150-174 words, about 2.9 words per second), with a scene count, and is told that fewer words means a failed script.
- The quality check fails scripts under the range (or over 60 s) and asks for a rewrite.
- **Guarantee:** if a script is still short, the AI is asked once more. Then keyless Wikipedia fact scenes are added before the ending, and finally a short recap scene.
- The voice is re-recorded slower or faster (at most ±12 points) when it lands outside 50-60 s. A video just under 50 s holds its ending (comment question on screen, music playing) for up to 4.5 s.

## 3.0: playbook upgrade (script, motion, voice, sound)

- **Script:**
  - nested loops (a micro-payoff every 5-8 s that opens a deeper question)
  - no verbal sign-off: the last line breaks off mid-thought and runs into the hook; the comment question is on screen only
  - 140-160 words at a clearer pace (2.7 words/s, default voice rate +0%)
  - format templates (what_if, myth_vs_fact, tutorial, ranking, explainer, story)
  - FACTS ONLY rules, plus an on-phone check that flags any number not found in the research or sources (sent back for a rewrite)
  - facts added from Wikipedia carry their source
- **Motion:**
  - "document" scene: a tilted article card with a drop shadow and a marker stroke sweeping across the key words
  - "map" scene: Natural Earth countries (public domain asset); zooms from the world to the country, lights it up, drops a pin
  - transition 0 is now a 3D push with motion-blur trails
  - 12 fps handmade graphics option (always on in Paper sketch)
- **Voice and sound:**
  - optional expressive Gemini voice (8 voices; word timings estimated from the speech and pauses; falls back to the Microsoft voice)
  - new SFX at -18 to -24 dB: UI clicks, paper rustles, a sub-bass drop on the payoff, a heartbeat under the hook
- **Captions** stay clear of the right-side like/comment buttons (centred at x=492, max width 780).

