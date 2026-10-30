"""Generate narration per line with Kokoro and write a timeline for the renderer."""
import json, sys, numpy as np, soundfile as sf
from kokoro_onnx import Kokoro

proj, models = sys.argv[1], sys.argv[2]
cfg = json.load(open(f"{proj}/script.json"))
k = Kokoro(f"{models}/kokoro-v1.0.onnx", f"{models}/voices-v1.0.bin")
SR, LEAD, LINE_GAP, SCENE_GAP, TAIL = 24000, 0.6, 0.35, 0.7, 1.6

audio, t, timeline = [np.zeros(int(LEAD * SR))], LEAD, []
for si, sc in enumerate(cfg["scenes"]):
    scene = {"year": sc["year"], "label": sc["label"], "start": t - (LEAD if si == 0 else SCENE_GAP / 2), "lines": []}
    for li, line in enumerate(sc["lines"]):
        speak = line.replace("2027.", "Twenty twenty-seven.").replace("2028.", "Twenty twenty-eight.") \
            .replace("2029.", "Twenty twenty-nine.").replace("2030.", "Twenty thirty.").replace("2031", "twenty thirty-one")
        samples, sr = k.create(speak, voice=cfg["voice"], speed=1.0, lang="en-us")
        assert sr == SR
        dur = len(samples) / SR
        scene["lines"].append({"text": line, "start": t, "end": t + dur})
        audio.append(samples); t += dur
        gap = LINE_GAP if li < len(sc["lines"]) - 1 else SCENE_GAP
        audio.append(np.zeros(int(gap * SR))); t += gap
    timeline.append(scene)
audio.append(np.zeros(int(TAIL * SR))); t += TAIL
for a, b in zip(timeline, timeline[1:]):
    a["end"] = b["start"]
timeline[-1]["end"] = t
sf.write(f"{proj}/build/voice.wav", np.concatenate(audio), SR)
json.dump({"duration": t, "scenes": timeline}, open(f"{proj}/build/timeline.json", "w"), indent=1)
print(f"duration {t:.2f}s")
