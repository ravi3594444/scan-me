"""Draws the soundtrack against the beat grid, marking every placed cue.

    python3 audio_plot.py   ->  out/audio/soundtrack.png
"""
import json, pathlib, subprocess
import numpy as np
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parent.parent
SR = 48000
PXS = 80                                   # pixels per second
raw = subprocess.run(['/usr/local/bin/ffmpeg', '-v', 'error', '-i', str(ROOT / 'out/audio/soundtrack.wav'), '-ac', '1', '-ar', str(SR), '-f', 'f32le', '-'],
                     capture_output=True, check=True).stdout
x = np.frombuffer(raw, np.float32)
fx = np.frombuffer(subprocess.run(['/usr/local/bin/ffmpeg', '-v', 'error', '-i', str(ROOT / 'out/audio/fx_only.wav'), '-ac', '1', '-ar', str(SR), '-f', 'f32le', '-'],
                                  capture_output=True, check=True).stdout, np.float32)
cues = json.loads((ROOT / 'out/audio/cues_placed.json').read_text())
W, H = int(27 * PXS), 420
im = Image.new('RGB', (W, H), 'white'); dr = ImageDraw.Draw(im)
spp = SR // PXS
for k in range(W):
    seg = x[k * spp:(k + 1) * spp]
    if len(seg):
        a = float(np.abs(seg).max())
        dr.line([(k, 120 - a * 100), (k, 120 + a * 100)], fill=(60, 60, 60))
    s2 = fx[k * spp:(k + 1) * spp]
    if len(s2):
        a = float(np.abs(s2).max()) / 0.5
        dr.line([(k, 330 - min(1, a) * 80), (k, 330 + min(1, a) * 80)], fill=(215, 90, 40))
for bt in range(55):
    X = bt * 0.5 * PXS
    dr.line([(X, 0), (X, 12 if bt % 4 else 22)], fill=(0, 110, 255))
    if bt % 4 == 0:
        dr.text((X + 2, 2), f'b{bt}', fill=(0, 110, 255))
for i, c in enumerate(cues):
    X = c['t'] * PXS
    dr.line([(X, 236), (X, 250)], fill=(200, 30, 30))
    dr.text((X + 1, 236 + (i % 3) * 0), '', fill='black')
dr.text((4, 222), 'soundtrack (-14 LUFS)', fill='black'); dr.text((4, 244), 'effects only, cue marks in red', fill=(215, 90, 40))
im.save(ROOT / 'out/audio/soundtrack.png')
print('wrote', ROOT / 'out/audio/soundtrack.png')
