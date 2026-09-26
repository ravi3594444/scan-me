#!/usr/bin/env bash
# Rebuilds derived media from the committed sources.
# Wall footage: square crop, re-encoded all-intra (-g 1) as VP9, because Playwright's Chromium has no H.264 decoder.
set -euo pipefail
cd "$(dirname "$0")/../assets/video"
ffmpeg -v error -y -i wall-src.mp4 -vf "crop=2160:2160:0:600,scale=1800:1800:flags=lanczos" \
  -c:v libvpx-vp9 -g 1 -keyint_min 1 -crf 20 -b:v 0 -deadline good -cpu-used 4 -row-mt 1 -pix_fmt yuv420p -an wall-intra.webm
echo "wrote assets/video/wall-intra.webm"
