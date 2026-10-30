"""Cut the avatar clips into one presenter track that follows the narration timeline.

Each scene gets a piece of footage; when a scene is longer than the remaining
footage the clip plays back and forth so the motion never jumps.
"""
import json, subprocess, sys

proj, up = sys.argv[1], sys.argv[2]
SRC = {  # clip: (file, crop filter giving a 1080x1070 panel)
    "A": (f"{up}/b1f8a7a2-gemini_generated_video_995cc971.mp4", "crop=727:720:270:0,scale=1080:1070"),
    "B": (f"{up}/07d2755d-gemini_generated_video_ab125e67.mp4", "crop=727:720:277:0,scale=1080:1070"),
    "C": (f"{up}/c6a1340a-409467119_1791228536894718.mp4", "crop=1080:1070:0:200"),  # mouth and chin stay above the burned-in captions
}
# Per scene: list of (clip, from, to); to < from plays in reverse.
PLAN = [
    [("C", 0.0, 4.5), ("C", 4.5, 0.0)],                  # intro (after 4.5s the chin meets the old captions)
    [("A", 0.2, 10.0)],                                   # 2027 agents: office + tablet
    [("C", 0.0, 4.5), ("C", 4.5, 0.0), ("C", 0.0, 4.5)],  # 2028 creation
    [("B", 0.3, 10.0)],                                   # 2029 learning: podium
    [("A", 10.0, 0.3)],                                   # 2030 robots & science
    [("B", 5.15, 10.0), ("B", 10.0, 5.15), ("B", 5.15, 10.0)],  # 2031 close-up at the podium
]
tl = json.load(open(f"{proj}/build/timeline.json"))
inputs, chains, labels = [], [], []
for scene, pieces in zip(tl["scenes"], PLAN):
    need = scene["end"] - scene["start"]
    for clip, a, b in pieces:
        if need <= 1e-3: break
        take = min(abs(b - a), need)
        lo, hi = (a, a + take) if b > a else (a - take, a)
        f, crop = SRC[clip]
        inputs += ["-i", f]
        i = len(inputs) // 2 - 1
        rev = ",reverse" if b < a else ""
        chains.append(f"[{i}:v]trim={lo:.4f}:{hi:.4f},setpts=PTS-STARTPTS,{crop},fps=24{rev},setsar=1[p{i}]")
        labels.append(f"[p{i}]")
        need -= take
    assert need <= 1e-3, f"not enough footage for scene {scene['year']}"
graph = ";".join(chains) + f";{''.join(labels)}concat=n={len(labels)}:v=1:a=0[v]"
subprocess.run(["ffmpeg", "-y", "-v", "error", *inputs, "-filter_complex", graph, "-map", "[v]",
                "-c:v", "libx264", "-crf", "14", "-preset", "medium", "-pix_fmt", "yuv420p",
                f"{proj}/build/presenter_raw.mp4"], check=True)
print("presenter track written")
