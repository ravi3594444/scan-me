#!/usr/bin/env bash
# Mixes the soundtrack, muxes it with the rendered master and runs the QC pass.
#   ./assemble.sh   ->  out/constrivo-film.mp4
#
# The first and last frames are the same picture: their raw renders are identical. So frames 0-1618 are encoded,
# and frame 0's own compressed packet (an IDR) is appended as frame 1619. The loop point then decodes to
# identical pixels after compression as well.
set -euo pipefail
cd "$(dirname "$0")/../out/film"
python3 ../../tools/mix.py
LAST=1619
ffmpeg -v error -y -i master.mkv -vf "trim=end_frame=$LAST,setpts=N/(60*TB)" -r 60 -an \
  -c:v libx264 -preset slow -crf 14 -pix_fmt yuv420p -profile:v high partA.mp4
ffmpeg -v error -y -i partA.mp4 -frames:v 1 -c copy first_au.mp4
printf "file 'partA.mp4'\nfile 'first_au.mp4'\n" > loop.txt
ffmpeg -v error -y -f concat -safe 0 -i loop.txt -i ../audio/soundtrack.wav -map 0:v -map 1:a \
  -c:v copy -c:a aac -b:a 320k -ar 48000 -movflags +faststart -t 27 ../constrivo-film.mp4
python3 ../../tools/qc.py ../constrivo-film.mp4
ffmpeg -hide_banner -i ../constrivo-film.mp4 -map 0:v -f framemd5 - 2>/dev/null | grep -v "^#" |
  awk -F', *' 'NR==1{f=$6} END{print NR " frames; first frame md5 " f ", last " $6 (f==$6 ? " (identical)" : " (DIFFERENT)")}'
ffmpeg -hide_banner -nostats -i ../constrivo-film.mp4 -af ebur128=peak=true -f null - 2>&1 | grep -A16 "Summary" | grep -E "I:|LRA:|Peak:" || true
