---
name: phoneme-lipsync
description: Re-time a real talking-head clip so the person's lips match a new voice track (e.g. a cloned voiceover), on CPU with no GPU or HuggingFace access. Use when asked to lip-sync, dub, or make a face video match new audio, and GPU models (LatentSync, MuseTalk, VideoReTalking) are unavailable.
---

# Phoneme-aware lip sync (CPU)

Sample-based ("Video Rewrite") lip sync: every output mouth is a **real frame** of the person, so nothing is
painted by a generative model. Frames are chosen by Viterbi over three costs:

1. **Sound** – MFCC similarity (±2-frame context, CMVN so a cloned voice matches the real one).
2. **Mouth shape** – phonemes from [allosaurus](https://github.com/xinjli/allosaurus) mapped to visemes, with a
   **hard lip-closure rule on m/b/p** (the JALI rule) and aperture ranges for open / round / spread vowels.
3. **Continuity** – free to keep playing real footage, penalised jumps scaled by mouth-shape distance.

The mouth patch (nose-to-below-chin) is aligned on eyes + nose, colour-matched on its feathered edge, and blended
onto a ping-pong of the clip. The mouth leads the audio by 1 frame (40 ms), as measured on real speech.

## Setup (once)
```bash
bash .claude/skills/phoneme-lipsync/scripts/setup.sh
```

## Run
```bash
P=.claude/skills/phoneme-lipsync/.venv/bin/python
ffmpeg -i talking.mp4 -vf fps=25 -an face25.mp4          # 25 fps, no text over the lower face
ffmpeg -i talking.mp4 -ac 1 -ar 16000 face16k.wav          # the clip's own speech
$P .claude/skills/phoneme-lipsync/scripts/lipsync.py --face face25.mp4 --face-audio face16k.wav \
   --voice new_voice.wav --out synced.mp4 [--clean-frames N]
```
`--clean-frames N` restricts both mouth library and base to the first N frames (use it when burned-in captions
or hands cover the chin later in the clip).

## What it prints (always check it)
- `[reference]` the real clip: timing correlation and m/b/p closure. Expect a clear peak near +40 ms and
  roughly 85–100 % closure (inpainted or occluded chins read lower); far below that, the landmarks or phones
  are unreliable for this clip.
- `[result]` the same metrics on the output. Good results so far: timing corr ≈ 0.69 at +40 ms,
  m/b/p closure 100 %, f/v ≈ 83 %.

## Limits
- Mouth shapes can only be as varied as the source clip: 10–60 s of frontal, evenly lit speech is ideal.
- Output keeps the source framing (one camera setup); cut between crops of it for variety.
- The landmark-based closure metric can misread heavily graded or re-framed frames; inspect flagged frames by eye.
- Not literally 100 %: it measures and enforces timing and lip closure, not every viseme.
