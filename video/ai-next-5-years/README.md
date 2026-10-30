# The Next 5 Years of AI — 49s vertical video

`ai-next-5-years.mp4` — 1080×1920, 24fps, H.264 + AAC, −14.5 LUFS, captions burned in.

Edit `script.json` to change the words, then rebuild:

```bash
python tts.py . /path/to/kokoro-models   # narration + build/timeline.json (needs kokoro-onnx)
node render.js . build/visual.mp4        # visuals (needs playwright + chromium)
./mix.sh                                 # music bed, ducking, loudness, grain, final mux
```

To use your own voice instead of the AI narrator, record each line of `script.json`
and replace `build/voice.wav` with your recording before running `mix.sh`
(the line timings in `build/timeline.json` must match your recording).
