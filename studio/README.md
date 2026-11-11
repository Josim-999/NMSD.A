# NMSD.A Studio — free animated-short pipeline

A **100% free** automation pipeline for colorful 9:16 animated finance shorts.
No paid APIs, no credits. Everything runs locally with Chromium + ffmpeg + Piper.

## The reusable cast — "The Money Crew"

Defined once in [`lib/characters.js`](lib/characters.js), reused in **every** video:

| Character | Role |
|-----------|------|
| **PENNY** (gold coin) | the host / narrator |
| **VAULT** (bank safe) | assets · stored wealth |
| **BUCK** (dollar bill) | income · cash flow |
| **CARDY** (credit card) | debt · liabilities (the trap) |
| **PIGGY** (piggy bank) | saving · compounding |

Because characters are code, they are pixel-identical across all videos.
Render the lineup poster: `node lib/gen_lineup.js` → `assets/cast_lineup.png`.

## Make a new video

1. Create `videos/<slug>/script.json` (copy an existing one). Each scene has:
   `vo` (spoken line), `caption` (big on-screen words), `accent` (colour).
2. Build it with one command:

   ```bash
   studio/lib/build.sh studio/videos/<slug>
   # -> studio/output/<slug>.mp4
   ```

That runs the four stages:

| Stage | Script | Does |
|-------|--------|------|
| 1 voiceover | `lib/tts.py` | Piper neural TTS per scene → `vo.wav` + `timings.json` |
| 2 scene | `lib/gen_scene.js` | builds `scene.html` and choreographs the cast |
| 3 render | `lib/render.js` | Chromium captures every frame (deterministic `seek(t)`) |
| 4 assemble | `lib/assemble.sh` | music bed + frames → final `9:16` MP4 |

## The full content workflow

1. **Idea** → you give a topic.
2. **Research** → scan YouTube/web for titles & angles (free WebSearch).
3. **Title** → pick the strongest, psychology-aware headline.
4. **Script** → hook + retention-beat script into `script.json`.
5. **Animate** → `build.sh` renders the short with the cast.
6. **Report** → review & revise.

## Requirements (already installed in this environment)

- `ffmpeg`, Node 22 + Playwright/Chromium, Python 3 + `piper-tts`
- Piper voice in `studio/.voices/` (gitignored; download once via
  `python3 -m piper.download_voices en_US-amy-medium --data-dir studio/.voices`)

## First video

`videos/why-rich-stay-rich/` — *"Why The Rich Stay Rich (And You Don't)"* · 59s.
