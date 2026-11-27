# Video Studio: feature ledger (never drop a feature)

Every feature the owner asked for, by version. **A new version keeps all of them and adds the new
request.** `FeatureGuardTest` checks the key ones on every build. If something here truly has to
change, ask the owner first (with options), then update this file and the test in the same commit.

## Owner's standing rules
- Every update continues the previous one: keep all earlier features, add the new one.
- When in doubt, ask the owner with clickable options; otherwise keep both the old and the new behaviour.
- Same cost: free tiers by default; paid only where the owner chose it (premium script studio, own key).
- Target: YouTube monetization, US audience, 50-60 s faceless educational Shorts.
- Facts only: every number and claim is true and sourced.
- Never add accessibility, SMS or notification-listener permissions (Play Protect blocks the APK).
- Ship as a signed APK in the chat, same signing key (installs over the last version).
- "Make the video" must never fail or hang, and should finish in about 1-3 minutes without lowering quality (1080p, 30/60 fps stay).
- Keep the app light: no heavy SDKs or libraries that change the Kotlin runtime (the 3.2 Claude SDK added ~10 MB and broke renders). Talk to AI services over plain HTTPS.
- Never decode video or large media while rendering; prepare it before. Pictures are capped to the 1080x1920 frame.
- One render at a time; a failed render retries itself once in safe mode before showing an error.

## Script
| Feature | Since | Where |
|---|---|---|
| Gemini research with Google Search, then script | 1.0 | `work/Pipeline.writeScript`, `ai/Prompts.research` |
| Gemini model auto-switch (Flash, Flash-Lite, Pro, Gemma), daily reset retry job | 1.5 / 1.9 | `ai/Gemini.kt` |
| 10 free backup AIs with instant switching (Z.ai, NVIDIA, Groq, Alibaba, Mistral, OpenRouter, SiliconFlow, Cloudflare, Pollinations) | 2.0 / 2.1 | `ai/BackupAi.kt` |
| Built-in writer (no AI): the script step never fails | 2.2 | `ai/BuiltInWriter.kt` |
| Playbook: hook formulas, 5 hook options scored, re-hooks, banned clichés, comment trigger | 2.7 | `ai/Prompts.kt` |
| Quality gate 0-100 with one targeted rewrite | 2.7 | `ai/ScriptCheck.kt` |
| 50-60 s (50/55/60, default 60), 140-160 words, lengthen guarantee (AI, Wikipedia facts, recap) | 2.9 / 3.0 | `Prompts.wordRange`, `Pipeline.ensureLength` |
| Micro-payoffs, mid-thought loop ending, format templates, FACTS ONLY with on-phone number check | 3.0 | `Prompts`, `ScriptCheck` |
| Your own take, emphasis words, titles of 4-6 words, 3-5 hashtags | 1.7 | `Prompts` |
| YouTube rules: advertiser-friendly, honest titles, careful health/finance, disclaimers | 1.8 | `Prompts`, YouTube check |
| Premium script studio: Claude Opus 5.5 (or Sonnet 5.5) live web research, writer, editor and fact-checker | 3.2 | `ai/ClaudeStudio.kt` |

## Visuals and motion
| Feature | Since | Where |
|---|---|---|
| Hook readable on frame 1, then hook intro effects for 5 s (sunburst, flash, glitch, confetti, sticker, stripe wipe) | 1.7 / 2.4 | `ScenePainter.hookBackdrop/hookOverlay` |
| 6 style families (soft light, grid, aurora, liquid glass, paper sketch, kinetic type) x 4 captions x 4 transitions = 96 looks | 1.7-2.3 | `render/Look.kt` |
| 8+ palettes, "Auto" palette rotation | 1.6 / 3.1 | `Palettes` |
| Look rotation: every family in 6 videos, no look repeated in 12 | 3.1 | `Look.pickFresh` |
| Motion library: 115 techniques (20 entrances, 21 transitions, 15 hits, 12 motifs, 11 overlays, 10 cameras, 8 caption styles, 12 data graphics), no back-to-back repeats | 3.1 | `render/MotionPlan.kt` |
| Scenes: hook, stat, list, steps, compare, emoji, question, photo, logo, chart (bars/donut/line), timeline, quote, tier, document, map, outro, kinetic | 1.0-3.1 | `ScenePainter` |
| Visual change every ~2 s, cut-ins, slideshows, seamless loop | 1.7 | `ScenePainter` |
| Safe zones (graphics 300-1150 px, captions clear of the right-side buttons) | 1.4 / 3.0 | `ScenePainter` constants |
| 12 fps handmade option (always on in paper sketch), 60 fps option | 3.0 / 1.4 | Settings |
| Animated emoji (Lottie), pop-out photos (ML Kit), display fonts | 1.9 | `render/*` |

## Pictures, clips and logos
| Feature | Since | Where |
|---|---|---|
| Pictures matched to the script: Pexels/Pixabay video, Wikipedia, Openverse, Commons, NASA, The Met; relevance filter; credits | 1.6 / 2.8 | `ai/MediaFinder.kt`, `ai/Stock.kt` |
| Verified logos only (Simple Icons, Wikidata, confirmed site icon), logo scenes and logo badges | 1.6 / 2.8 | `MediaFinder.logo` |
| Brand spotter: brand names the script forgot still get their logo | 3.3 | `ai/BrandSpotter.kt` |
| Matching picture or clip behind every talking scene (toned into the look) | 3.3 | `ScenePainter.backdrop`, Settings `backdrops` |
| Bigger logo badge; overlays never cover it | 3.3 | `ScenePainter.badge` |

## Voice and sound
| Feature | Since | Where |
|---|---|---|
| Edge TTS (default rate +0%), length-fit re-record within 50-60 s, ending hold | 2.3 / 2.9 | `voice/*`, Pipeline |
| Optional expressive Gemini voice, device TTS fallback | 3.0 | `voice/GeminiTts.kt` |
| Music on the phone by mood, ducking, SFX (pops, hits, risers, whooshes, clicks, paper, drops, heartbeat), -14 LUFS | 1.4-3.0 | `render/AudioMix.kt` |

## Posting
| Feature | Since | Where |
|---|---|---|
| Post to all apps one after another (TikTok, Instagram, YouTube, Facebook, X, Threads, LinkedIn, WhatsApp) | 1.2 | upload |
| Per-app captions and hashtags (5 max on TikTok/Instagram), copied as each app opens | 2.5 | `upload/Captions.kt` |
| YouTube direct upload, made-for-kids and paid-promotion flags, YouTube check, monetization guide | 1.2 / 1.8 | upload, Settings |
| US time-zone scheduling (publishAt, works with the phone offline) | 1.9 | upload |

## Speed
| Feature | Since | Where |
|---|---|---|
| Frame drawing ~2.5x faster (half-size smooth backgrounds, baked vignette and grain, no offscreen layer for opaque scenes) | 3.3 | `ScenePainter` |
| Drawing and encoding run on two threads at once | 3.3 | `render/VideoRenderer.kt` |
| Pictures, clips and logos download in parallel (6 at once) | 3.3 | `work/Pipeline.kt` |
| Clips are decoded once, before rendering and in parallel, into JPEG frames (never during drawing); frame timeout 240 s with the frame number | 3.4 | `SceneMedia.Clip.prepare`, `VideoRenderer` |
| One render at a time across the phone (others wait in line and keep preparing); at most 2 clips decoded at once | 3.5 | `render/RenderGate.kt`, Pipeline |
| Clips: smallest file that is sharp enough (~540 px), frames read in one pass on Android 9+, 3 s ping-pong | 3.5 | `ai/Stock.kt`, `MediaFinder.pixabayVideo`, `SceneMedia.Clip` |
| Clips must match the scene's words (Pixabay tags, Pexels page name), like photos | 3.5 | `Stock.pexelsVideo`, `MediaFinder.pixabayVideo` |
| Claude premium studio over plain HTTPS (SDK removed: app back to ~14 MB, Kotlin runtime back to 1.9) | 3.6 | `ai/ClaudeStudio.kt` |
| Memory-safe renders: largeHeap, pictures capped to the frame (`MediaLoad.fit`), 2 frame buffers, reused canvases | 3.6 | `render/MediaLoad.kt`, `VideoRenderer` |
| Automatic safe-mode retry (fresh painter, one thread, same quality) before any render error | 3.6 | `Pipeline.render` |
| Every build renders a full 60 s video frame by frame on two threads (classic and glass) and checks frame times | 3.6 | `RenderSafetyTest` |
| Total making time shown in the log ("whole video 1 min 52 s") | 3.6 | `Pipeline.render` |
