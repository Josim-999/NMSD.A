#!/usr/bin/env bash
# One-command build for a video: TTS -> scene -> render -> assemble.
# Usage: studio/lib/build.sh <video_dir> [output_mp4]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
VDIR="$(cd "$1" && pwd)"
OUT="${2:-$HERE/../output/$(basename "$VDIR").mp4}"
mkdir -p "$(dirname "$OUT")"

echo "[1/4] voiceover..."; python3 -I "$HERE/tts.py" "$VDIR"
echo "[2/4] scene...";     node "$HERE/gen_scene.js" "$VDIR"
echo "[3/4] render...";    node "$HERE/render.js" "$VDIR"
echo "[4/4] assemble...";  bash "$HERE/assemble.sh" "$VDIR" "$OUT"
echo "FINAL: $OUT"
