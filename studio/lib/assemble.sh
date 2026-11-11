#!/usr/bin/env bash
# Assemble final 9:16 mp4 from rendered frames + voiceover + a generated music bed.
# Usage: assemble.sh <video_dir> <output_mp4>
set -euo pipefail
VDIR="$1"; OUT="$2"
WORK="$VDIR/work"
FRAMES="$WORK/frames"
VO="$WORK/vo.wav"
FPS="$(python3 -c "import json;print(json.load(open('$VDIR/timings.json'))['fps'])")"

# duration from the voiceover track
D="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$VO")"

echo "  building music bed (${D}s)..."
# mellow 3-note pad (A3 / C#4 / E4) + slow tremolo, kept low so the voice leads
ffmpeg -y -f lavfi -i "sine=frequency=220:duration=$D" \
             -f lavfi -i "sine=frequency=277:duration=$D" \
             -f lavfi -i "sine=frequency=330:duration=$D" \
  -filter_complex "[0][1][2]amix=inputs=3:normalize=1,tremolo=f=0.18:d=0.5,highpass=f=120,lowpass=f=2600,volume=0.10[mus]" \
  -map "[mus]" "$WORK/music.wav" -loglevel error

echo "  encoding frames -> video..."
ffmpeg -y -framerate "$FPS" -i "$FRAMES/%04d.png" \
  -c:v libx264 -pix_fmt yuv420p -crf 18 -preset medium "$WORK/silent.mp4" -loglevel error

echo "  mixing audio + muxing..."
ffmpeg -y -i "$WORK/silent.mp4" -i "$VO" -i "$WORK/music.wav" \
  -filter_complex "[1:a]volume=1.0[v];[2:a]volume=1.0[m];[v][m]amix=inputs=2:duration=first:dropout_transition=0,volume=1.6,alimiter=limit=0.95[a]" \
  -map 0:v -map "[a]" -c:v copy -c:a aac -b:a 192k -shortest "$OUT" -loglevel error

echo "  done -> $OUT"
ffprobe -v error -show_entries format=duration:stream=codec_type,width,height -of default=noprint_wrappers=1 "$OUT"
