#!/usr/bin/env bash
# Rebuilds derived media from the committed sources.
# Wall footage: square crop, re-encoded all-intra (-g 1) as VP9, because Playwright's Chromium has no H.264 decoder.
set -euo pipefail
cd "$(dirname "$0")/../assets/video"
# the 30 fps source is motion-interpolated to 60 fps so the shadow moves on every film frame
ffmpeg -v error -y -i wall-src.mp4 -vf "crop=2160:2160:0:600,scale=1800:1800:flags=lanczos,minterpolate=fps=60:mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1" \
  -c:v libvpx-vp9 -g 1 -keyint_min 1 -crf 20 -b:v 0 -deadline good -cpu-used 4 -row-mt 1 -pix_fmt yuv420p -an wall-intra60.webm
echo "wrote assets/video/wall-intra60.webm"
