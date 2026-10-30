#!/usr/bin/env bash
# One-time setup: Python 3.10 env + models. Everything downloads from PyPI or GitHub releases (no HuggingFace needed).
set -e
HERE="$(cd "$(dirname "$0")/.." && pwd)"
command -v uv >/dev/null || pip install -q uv
uv python install 3.10
uv venv -p 3.10 "$HERE/.venv"
VIRTUAL_ENV="$HERE/.venv" uv pip install "torch==2.1.2" "numpy==1.23.5" "setuptools<70" dlib-bin \
  "opencv-python-headless==4.8.1.78" "librosa==0.9.2" allosaurus
mkdir -p "$HERE/models"
curl -sL -o "$HERE/models/shape_predictor_68_face_landmarks.dat" \
  https://github.com/OpenTalker/video-retalking/releases/download/v0.0.1/shape_predictor_68_face_landmarks.dat
"$HERE/.venv/bin/python" -m allosaurus.bin.download_model -m uni2005
echo "ready: $HERE/.venv/bin/python $HERE/scripts/lipsync.py --help"
