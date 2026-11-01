#!/usr/bin/env python3
"""
make_shorts.py — turn a long podcast video into N vertical short clips
with burned-in captions and a music bed.

Stages (run in order, or `all`):
  fetch       download the source video (Google Drive public link, or local copy)
  transcribe  word-level transcript via faster-whisper
  select      score candidate moments and write a clip plan (JSON)
  render      cut, reframe to 9:16, burn captions, mix music
  all         transcribe -> select -> render

Why NFD normalisation matters (do not remove):
  libass/harfbuzz in common ffmpeg builds fails to shape the PRECOMPOSED
  Bengali o-kar U+09CB and renders a dotted circle (U+25CC) instead.
  The canonical decomposition U+09C7 + U+09BE shapes correctly.
  Whisper emits NFC text, so every `ো` would be corrupted without this.
  Verified empirically; see nfd_for_ass().
"""
from __future__ import annotations
import argparse, json, os, re, shlex, subprocess, sys, unicodedata
from pathlib import Path

# ---------------------------------------------------------------- utilities

def run(cmd, **kw):
    if isinstance(cmd, str):
        cmd = shlex.split(cmd)
    p = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if p.returncode != 0:
        sys.stderr.write(p.stderr[-4000:] + "\n")
        raise SystemExit(f"command failed ({p.returncode}): {cmd[0]}")
    return p.stdout


def nfd_for_ass(s: str) -> str:
    """Decompose so libass shapes Indic vowel signs correctly. See module docstring."""
    return unicodedata.normalize("NFD", s)


def ass_escape(s: str) -> str:
    return s.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}").replace("\n", r"\N")


def ts(sec: float) -> str:
    sec = max(0.0, sec)
    h, rem = divmod(sec, 3600)
    m, s = divmod(rem, 60)
    return f"{int(h):d}:{int(m):02d}:{s:05.2f}"

# ---------------------------------------------------------------- 1. fetch

def stage_fetch(args):
    out = Path(args.video)
    if out.exists() and out.stat().st_size > 0:
        print(f"[fetch] already present: {out} ({out.stat().st_size:,} bytes)")
        return
    if not args.drive_id:
        raise SystemExit("[fetch] no local file and no --drive-id given")
    out.parent.mkdir(parents=True, exist_ok=True)
    url = ("https://drive.usercontent.google.com/download"
           f"?id={args.drive_id}&export=download&confirm=t")
    print(f"[fetch] downloading {args.drive_id} -> {out}")
    run(["curl", "-sSL", "--fail", "--retry", "3", "--retry-delay", "2",
         "-o", str(out), url])
    print(f"[fetch] done ({out.stat().st_size:,} bytes)")


def probe(path: Path) -> dict:
    j = json.loads(run(["ffprobe", "-v", "error", "-print_format", "json",
                        "-show_format", "-show_streams", str(path)]))
    v = next(s for s in j["streams"] if s["codec_type"] == "video")
    return {"duration": float(j["format"]["duration"]),
            "width": int(v["width"]), "height": int(v["height"])}

# ----------------------------------------------------------- 2. transcribe

def stage_transcribe(args):
    from faster_whisper import WhisperModel
    src, outp = Path(args.video), Path(args.transcript)
    outp.parent.mkdir(parents=True, exist_ok=True)
    print(f"[transcribe] model={args.model} lang={args.lang} device=cpu")
    model = WhisperModel(args.model, device="cpu", compute_type="int8")
    segments, info = model.transcribe(
        str(src), language=args.lang, word_timestamps=True,
        vad_filter=True, vad_parameters={"min_silence_duration_ms": 400},
        beam_size=5,
    )
    segs = []
    for s in segments:
        words = [{"w": w.word.strip(), "s": w.start, "e": w.end,
                  "p": getattr(w, "probability", None)}
                 for w in (s.words or []) if w.word.strip()]
        segs.append({"start": s.start, "end": s.end,
                     "text": s.text.strip(), "words": words,
                     "avg_logprob": s.avg_logprob,
                     "no_speech_prob": s.no_speech_prob})
        print(f"  [{ts(s.start)}] {s.text.strip()[:90]}")
    outp.write_text(json.dumps(
        {"language": info.language, "duration": info.duration, "segments": segs},
        ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[transcribe] {len(segs)} segments -> {outp}")

# -------------------------------------------------------------- 3. select

# Signals drawn from the virality brief: hook strength, standalone sense,
# payoff, numbers/claims, emotional peaks. Language-agnostic cues + loudness.
NUM_RE = re.compile(r"[0-9\u09e6-\u09ef]")

def audio_energy(video: Path, duration: float, step: float = 1.0):
    """Per-second RMS, used as a proxy for emphasis/laughter/energy peaks."""
    raw = run(["ffprobe", "-v", "error", "-f", "lavfi",
               "-i", f"amovie={shlex.quote(str(video))},astats=metadata=1:reset={int(step)}",
               "-show_entries", "frame_tags=lavfi.astats.Overall.RMS_level",
               "-of", "csv=p=0"])
    vals = []
    for line in raw.splitlines():
        line = line.strip().rstrip(",")
        try:
            vals.append(float(line))
        except ValueError:
            pass
    return vals


def score_window(words, lo, hi, energy):
    inside = [w for w in words if w["s"] >= lo and w["e"] <= hi]
    if len(inside) < 8:
        return None
    text = " ".join(w["w"] for w in inside)
    dur = hi - lo
    wps = len(inside) / max(dur, 1e-6)
    score = 0.0
    score += 2.0 * min(len(NUM_RE.findall(text)), 4)      # concrete numbers
    score += 1.5 if wps > 2.2 else 0.0                     # energetic delivery
    score += 2.0 if 12 <= dur <= 22 else -1.0              # completion-friendly
    if energy:
        a, b = int(lo), min(int(hi), len(energy) - 1)
        if b > a:
            seg = energy[a:b]
            score += 1.2 * ((sum(seg) / len(seg)) - (sum(energy) / len(energy))) / 3.0
    first = " ".join(w["w"] for w in inside[:6])
    score += 1.5 if NUM_RE.search(first) else 0.0          # number in the hook
    conf = [w["p"] for w in inside if w.get("p") is not None]
    if conf:
        score += 2.0 * (sum(conf) / len(conf))             # transcript reliability
    return {"start": lo, "end": hi, "score": round(score, 3),
            "text": text, "words": inside,
            "mean_conf": round(sum(conf) / len(conf), 3) if conf else None}


def stage_select(args):
    tr = json.loads(Path(args.transcript).read_text(encoding="utf-8"))
    words = [w for s in tr["segments"] for w in s["words"]]
    if not words:
        raise SystemExit("[select] transcript has no word timings")
    meta = probe(Path(args.video))
    print("[select] computing audio energy…")
    energy = audio_energy(Path(args.video), meta["duration"])

    cands = []
    for target in (14.0, 18.0, 22.0):
        t = words[0]["s"]
        while t < words[-1]["e"] - target:
            c = score_window(words, t, t + target, energy)
            if c:
                cands.append(c)
            t += 2.0
    cands.sort(key=lambda c: -c["score"])

    chosen, used = [], []
    for c in cands:
        if any(not (c["end"] <= a or c["start"] >= b) for a, b in used):
            continue                                        # no overlap
        chosen.append(c)
        used.append((c["start"], c["end"]))
        if len(chosen) >= args.n:
            break
    chosen.sort(key=lambda c: c["start"])
    for i, c in enumerate(chosen, 1):
        c["id"] = i
    Path(args.plan).write_text(json.dumps(chosen, ensure_ascii=False, indent=1),
                               encoding="utf-8")
    print(f"[select] {len(chosen)} clips -> {args.plan}")
    for c in chosen:
        flag = "" if (c["mean_conf"] or 1) >= args.min_conf else "  << LOW CONFIDENCE"
        print(f"  #{c['id']:02d} {ts(c['start'])}-{ts(c['end'])} "
              f"score={c['score']:.2f} conf={c['mean_conf']}{flag}")
        print(f"      {c['text'][:110]}")

# -------------------------------------------------------------- 4. render

ASS_HEAD = """[Script Info]
ScriptType: v4.00+
PlayResX: 1080
PlayResY: 1920
WrapStyle: 2
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding
Style: Base,{font},{size},&H00FFFFFF,&H000000FF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,7,3,2,70,70,{mv},1
Style: Hit,{font},{size},&H0000E5FF,&H000000FF,&H00000000,&H00000000,1,0,0,0,100,100,0,0,1,7,3,2,70,70,{mv},1

[Events]
Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
"""


def group_words(words, max_chars=22, max_dur=1.6):
    """Short caption phrases: 2-4 words, readable at a glance on mute."""
    out, cur = [], []
    for w in words:
        trial = cur + [w]
        text = " ".join(x["w"] for x in trial)
        if cur and (len(text) > max_chars or trial[-1]["e"] - trial[0]["s"] > max_dur):
            out.append(cur)
            cur = [w]
        else:
            cur = trial
    if cur:
        out.append(cur)
    return out


def build_ass(clip, path: Path, font: str, size: int, margin_v: int):
    lines = [ASS_HEAD.format(font=font, size=size, mv=margin_v)]
    t0 = clip["start"]
    for grp in group_words(clip["words"]):
        s, e = grp[0]["s"] - t0, grp[-1]["e"] - t0
        text = " ".join(g["w"] for g in grp)
        style = "Hit" if NUM_RE.search(text) else "Base"
        body = ass_escape(nfd_for_ass(text))          # <-- the shaping fix
        lines.append(f"Dialogue: 0,{ts(s)},{ts(e + 0.08)},{style},,0,0,0,,{body}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def stage_render(args):
    plan = json.loads(Path(args.plan).read_text(encoding="utf-8"))
    outdir = Path(args.outdir); outdir.mkdir(parents=True, exist_ok=True)
    workdir = outdir / "_work"; workdir.mkdir(exist_ok=True)
    music = Path(args.music) if args.music else None

    for clip in plan:
        n = clip["id"]
        dur = clip["end"] - clip["start"]
        assf = workdir / f"clip{n:02d}.ass"
        build_ass(clip, assf, args.font_name, args.font_size, args.margin_v)
        out = outdir / f"clip{n:02d}.mp4"

        # 9:16: blurred fill behind a contained copy — safe for any source framing
        vf = (
            "[0:v]split=2[bg][fg];"
            "[bg]scale=1080:1920:force_original_aspect_ratio=increase,"
            "crop=1080:1920,gblur=sigma=28,eq=brightness=-0.09[bgb];"
            "[fg]scale=1080:1920:force_original_aspect_ratio=decrease[fgs];"
            "[bgb][fgs]overlay=(W-w)/2:(H-h)/2,"
            f"subtitles={shlex.quote(str(assf))}:fontsdir={shlex.quote(args.fontsdir)}[v]"
        )
        cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
               "-ss", f"{clip['start']:.3f}", "-t", f"{dur:.3f}", "-i", args.video]
        if music:
            cmd += ["-stream_loop", "-1", "-i", str(music)]
            # NOTE: a filtergraph label may be consumed only once, so the
            # speech bus is asplit — one copy mixes, one keys the ducking.
            filt = (vf +
                    ";[0:a]loudnorm=I=-16:TP=-1.5:LRA=11,asplit=2[sp1][sp2];"
                    f"[1:a]volume={args.music_db}dB,atrim=0:{dur:.3f},"
                    "afade=t=in:st=0:d=0.4,"
                    f"afade=t=out:st={max(dur-0.6,0):.3f}:d=0.6[mus];"
                    "[mus][sp2]sidechaincompress=threshold=0.05:ratio=9:attack=8:release=320[duck];"
                    "[sp1][duck]amix=inputs=2:duration=first:dropout_transition=0[a]")
            cmd += ["-filter_complex", filt, "-map", "[v]", "-map", "[a]"]
        else:
            cmd += ["-filter_complex", vf + ";[0:a]loudnorm=I=-16:TP=-1.5:LRA=11[a]",
                    "-map", "[v]", "-map", "[a]"]
        cmd += ["-c:v", "libx264", "-preset", "medium", "-crf", "19",
                "-profile:v", "high", "-pix_fmt", "yuv420p",
                "-r", "30", "-g", "60",
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-movflags", "+faststart",
                str(out)]
        print(f"[render] clip {n:02d}  {ts(clip['start'])}  {dur:.1f}s -> {out.name}")
        run(cmd)
    print(f"[render] {len(plan)} clips in {outdir}")

# ---------------------------------------------------------------- cli

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("stage", choices=["fetch", "transcribe", "select", "render", "all"])
    ap.add_argument("--video", default="work/source.mp4")
    ap.add_argument("--drive-id")
    ap.add_argument("--transcript", default="work/transcript.json")
    ap.add_argument("--plan", default="work/plan.json")
    ap.add_argument("--outdir", default="out")
    ap.add_argument("--music")
    ap.add_argument("--music-db", default="-19", help="music bed gain in dB")
    ap.add_argument("-n", type=int, default=10, help="number of clips")
    ap.add_argument("--lang", default="bn")
    ap.add_argument("--model", default="large-v3")
    ap.add_argument("--min-conf", type=float, default=0.60)
    ap.add_argument("--font-name", default="Noto Sans Bengali")
    ap.add_argument("--fontsdir", default="fonts")
    ap.add_argument("--font-size", type=int, default=86)
    ap.add_argument("--margin-v", type=int, default=520)
    a = ap.parse_args()

    if a.stage in ("fetch",):
        stage_fetch(a)
    elif a.stage == "transcribe":
        stage_transcribe(a)
    elif a.stage == "select":
        stage_select(a)
    elif a.stage == "render":
        stage_render(a)
    else:
        stage_fetch(a); stage_transcribe(a); stage_select(a); stage_render(a)


if __name__ == "__main__":
    main()
