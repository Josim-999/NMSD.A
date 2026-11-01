# make_shorts — long podcast → 10 vertical shorts

Cuts a long talking-head video into N short vertical clips with burned-in
captions and a ducked music bed. Built for Bengali; works for any language
faster-whisper supports.

## Why this exists as a script

The cloud session this was built in has a strict network allowlist: Google
Drive and huggingface.co are unreachable, so the source video and the Whisper
model weights could not be downloaded there. Everything that does not need the
network was built and verified; run this anywhere with normal internet.

## The Bengali shaping bug (important)

Common ffmpeg/libass builds fail to shape the **precomposed** Bengali o-kar
`U+09CB` and render a dotted circle `◌` instead. Its canonical decomposition
`U+09C7 + U+09BE` shapes correctly.

Whisper emits NFC (precomposed) text, so without a fix **every `ো` in every
caption is corrupted** — and `ো` is extremely common.

Fix: `nfd_for_ass()` applies `unicodedata.normalize("NFD", ...)` before writing
the ASS file. Verified across the full vowel-sign series, conjuncts
(`ব্যবসা`, `কমার্স`, `স্ট্র্যাটেজি`), nukta forms (`ড় ঢ় য়`), hard conjuncts
(`ক্ষ জ্ঞ ঙ্গ ন্ত্র`) and Bengali numerals. **Do not remove it.**

## Requirements

- `ffmpeg` built with `--enable-libass --enable-libharfbuzz --enable-libfribidi`
  (harfbuzz is required for Indic shaping; check with `ffmpeg -buildconf`)
- `pip install faster-whisper`
- A Bengali font in `fonts/`, e.g. Noto Sans Bengali:
  `curl -sSL -o fonts/NotoSansBengali-VF.ttf \
    "https://raw.githubusercontent.com/google/fonts/main/ofl/notosansbengali/NotoSansBengali%5Bwdth,wght%5D.ttf"`

## Usage

```bash
# everything at once
python3 make_shorts.py all \
  --drive-id <GOOGLE_DRIVE_FILE_ID> \
  --video work/source.mp4 \
  --music work/music.m4a \
  --lang bn -n 10 \
  --fontsdir fonts --outdir out
```

Stages run individually — `fetch`, `transcribe`, `select`, `render`.
**Review `work/plan.json` after `select`** before rendering: it lists each clip's
timecode, score, transcript text and mean confidence. Lines below `--min-conf`
(default 0.60) are flagged `<< LOW CONFIDENCE` — fix those by hand rather than
burning a wrong word into a hook.

The Drive file must be shared "Anyone with the link → Viewer" for `fetch`.

## Selection logic

Scores sliding windows (14/18/22s) on signals from the virality brief:
concrete numbers, numbers inside the first few words (hook strength), delivery
pace, duration in the completion-friendly band, audio energy above the episode
mean (emphasis/laughter), and transcript confidence. Picks the top
non-overlapping N.

This is a **shortlist generator, not an editor.** It finds strong candidates;
a human should confirm each clip starts on the strongest sentence and ends on
a payoff.

## Output

`out/clipNN.mp4` — 1080×1920, H.264 high, 30fps, AAC 192k, `+faststart`,
speech normalised to −16 LUFS with the music bed sidechain-ducked beneath it.

## Tuning

| flag | default | notes |
|---|---|---|
| `--font-size` | 86 | caption size at 1080×1920 |
| `--margin-v` | 520 | vertical caption position |
| `--music-db` | -19 | music bed gain |
| `--model` | large-v3 | `medium` is much faster, weaker on Bengali |
| `-n` | 10 | number of clips |
