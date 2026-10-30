#!/usr/bin/env bash
# Builds the music bed, mixes it under the narration and muxes the final video.
set -euo pipefail
cd "$(dirname "$0")"
D=$(python3 -c "import json;print(json.load(open('build/timeline.json'))['duration'])")
# Soft low impacts at the start of every year scene.
BOOMS=$(python3 -c "
import json;s=json.load(open('build/timeline.json'))['scenes'][1:]
print('+'.join(f'0.55*sin(2*PI*48*(t-{x[\"start\"]:.3f}))*exp(-3.5*(t-{x[\"start\"]:.3f}))*gte(t,{x[\"start\"]:.3f})' for x in s))")
PAD="0.06*sin(2*PI*110*t)*(0.6+0.4*sin(2*PI*0.07*t))+0.045*sin(2*PI*164.81*t)*(0.6+0.4*sin(2*PI*0.11*t+1))+0.035*sin(2*PI*220*t)*(0.5+0.5*sin(2*PI*0.05*t+2))+0.025*sin(2*PI*261.63*t)*(0.5+0.5*sin(2*PI*0.09*t+3))+0.05*sin(2*PI*55*t)"
ffmpeg -y -v error -f lavfi -i "aevalsrc='$PAD':s=48000:d=$D" -f lavfi -i "aevalsrc='$BOOMS':s=48000:d=$D" \
  -filter_complex "[0]lowpass=f=900,aecho=0.8:0.7:60|120:0.35|0.25,afade=t=in:d=1.5,afade=t=out:st=$(python3 -c "print($D-2.5)"):d=2.5[pad];[1]lowpass=f=160[b];[pad][b]amix=inputs=2:normalize=0,pan=stereo|c0=c0|c1=c0[m]" \
  -map "[m]" build/music.wav
ffmpeg -y -v error -i build/voice.wav -i build/music.wav -filter_complex \
  "[0]aresample=48000,highpass=f=80,equalizer=f=3500:t=q:w=1:g=3,acompressor=threshold=-20dB:ratio=3:attack=5:release=120,pan=stereo|c0=c0|c1=c0,asplit[v][sc];[1][sc]sidechaincompress=threshold=0.03:ratio=6:attack=20:release=400[duck];[v][duck]amix=inputs=2:normalize=0,loudnorm=I=-14:TP=-1.0:LRA=9[a]" \
  -map "[a]" -ar 48000 build/mix.wav
ffmpeg -y -v error -i build/visual.mp4 -i build/mix.wav \
  -vf "noise=alls=3:allf=t,vignette=PI/5" -c:v libx264 -preset slow -crf 21 -maxrate 6M -bufsize 12M -profile:v high -pix_fmt yuv420p \
  -c:a aac -b:a 192k -movflags +faststart -shortest ai-next-5-years-avatar-DRAFT.mp4
