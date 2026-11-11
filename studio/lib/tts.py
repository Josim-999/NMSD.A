#!/usr/bin/env python3
"""Synthesize per-scene voiceover with Piper, measure durations, build timings.json + vo.wav.

Usage: python3 tts.py <video_dir>
Reads  <video_dir>/script.json
Writes <video_dir>/work/scene_N.wav, <video_dir>/work/vo.wav, <video_dir>/timings.json
"""
import json, os, subprocess, sys, shutil

GAP = 0.35          # seconds of silence after each line
SR = 22050          # piper medium sample rate
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # studio/
VOICES = os.environ.get("PIPER_VOICES", os.path.join(ROOT, ".voices"))

def run(cmd, **kw):
    return subprocess.run(cmd, check=True, capture_output=True, text=True, **kw)

def duration(path):
    out = run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
               "-of", "default=noprint_wrappers=1:nokey=1", path]).stdout.strip()
    return float(out)

def main(vdir):
    script = json.load(open(os.path.join(vdir, "script.json")))
    model = script.get("voice", "en_US-amy-medium")
    fps = script.get("fps", 30)
    work = os.path.join(vdir, "work")
    os.makedirs(work, exist_ok=True)

    # silence clip matching sample rate
    sil = os.path.join(work, "_sil.wav")
    run(["ffmpeg", "-y", "-f", "lavfi", "-i", f"anullsrc=r={SR}:cl=mono",
         "-t", str(GAP), sil])

    scenes, concat_list, t = [], [], 0.0
    for i, sc in enumerate(script["scenes"]):
        raw = os.path.join(work, f"scene_{i}.wav")
        p = subprocess.run(["python3", "-m", "piper", "-m", model,
                            "--data-dir", VOICES, "-f", raw],
                           input=sc["vo"], text=True, capture_output=True)
        if p.returncode != 0 or not os.path.exists(raw):
            sys.exit(f"piper failed on scene {i}: {p.stderr[-500:]}")
        d = duration(raw)
        total = d + GAP
        scenes.append({"id": sc["id"], "start": round(t, 3), "vo": round(d, 3),
                       "total": round(total, 3), "caption": sc["caption"],
                       "accent": sc.get("accent", "#f7c948")})
        concat_list.append(raw); concat_list.append(sil)
        t += total

    # build concatenated voiceover track
    listf = os.path.join(work, "concat.txt")
    with open(listf, "w") as f:
        for c in concat_list:
            f.write(f"file '{os.path.abspath(c)}'\n")
    vo = os.path.join(work, "vo.wav")
    run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", listf,
         "-ar", str(SR), "-ac", "1", vo])

    timings = {"fps": fps, "total": round(t, 3), "title": script.get("title", ""),
               "scenes": scenes}
    json.dump(timings, open(os.path.join(vdir, "timings.json"), "w"), indent=2)
    print(f"OK total={t:.2f}s scenes={len(scenes)} vo={vo}")

if __name__ == "__main__":
    main(sys.argv[1])
