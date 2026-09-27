"""Quality checks on a rendered film.

- one frame per beat -> contact sheet
- single-frame pops: frame-difference spikes more than 3x both neighbours (and above a noise floor)
- first frame vs last frame

    python3 qc.py ../out/film/master.mkv
"""
import sys, subprocess, pathlib, json
import numpy as np
from PIL import Image, ImageDraw

FFMPEG = '/usr/local/bin/ffmpeg'
src = pathlib.Path(sys.argv[1]).resolve()
out = src.parent / 'qc'
out.mkdir(exist_ok=True)
S = 360

raw = subprocess.run([FFMPEG, '-v', 'error', '-i', str(src), '-vf', f'scale={S}:{S}:flags=area,format=gray', '-f', 'rawvideo', '-'],
                     capture_output=True, check=True).stdout
fr = np.frombuffer(raw, np.uint8).reshape(-1, S, S).astype(np.float32)
n = len(fr)
d = np.abs(np.diff(fr, axis=0)).mean(axis=(1, 2))          # d[i] = change from frame i to i+1
FLOOR = 0.35
spikes = []
for i in range(1, len(d) - 1):
    if d[i] > 3 * max(d[i - 1], d[i + 1]) and d[i] > FLOOR:
        spikes.append((i + 1, round(float(d[i]), 3), round(float(d[i - 1]), 3), round(float(d[i + 1]), 3)))
print(f'{n} frames; mean change {d.mean():.3f}; max change {d.max():.3f} at frame {int(d.argmax()) + 1}')
print('single-frame spikes (frame, diff, prev, next):', spikes if spikes else 'none')

# first vs last at full resolution
full = lambda k: subprocess.run([FFMPEG, '-v', 'error', '-i', str(src), '-vf', f'select=eq(n\\,{k})', '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                                capture_output=True, check=True).stdout
a = np.frombuffer(full(0), np.uint8).astype(int); b = np.frombuffer(full(n - 1), np.uint8).astype(int)
print(f'first vs last frame: max diff {np.abs(a - b).max()}, mean {np.abs(a - b).mean():.4f}')

# one frame per beat
W = 240
cols = 9
beats = list(range(0, 55))
rows = (len(beats) + cols - 1) // cols
sheet = Image.new('RGB', (cols * W, rows * (W + 18)), 'white'); dr = ImageDraw.Draw(sheet)
col = subprocess.run([FFMPEG, '-v', 'error', '-i', str(src), '-vf', f'scale={W}:{W}:flags=area', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                     capture_output=True, check=True).stdout
cf = np.frombuffer(col, np.uint8).reshape(-1, W, W, 3)
for j, bt in enumerate(beats):
    k = min(n - 1, bt * 30)
    x, y = (j % cols) * W, (j // cols) * (W + 18)
    sheet.paste(Image.fromarray(cf[k]), (x, y)); dr.text((x + 3, y + W + 3), f'b{bt} {bt * 0.5:.1f}s', fill='black')
sheet.save(out / 'beats.jpg', quality=88)
# difference plot
H = 160
plot = Image.new('RGB', (len(d), H), 'white'); pd = ImageDraw.Draw(plot)
mx = max(1e-6, float(np.percentile(d, 99.5)))
for i, v in enumerate(d):
    pd.line([(i, H), (i, H - min(H, v / mx * (H - 10)))], fill=(40, 40, 40))
for s in spikes:
    pd.line([(s[0] - 1, 0), (s[0] - 1, H)], fill=(220, 40, 40))
for bt in range(0, 55, 4):
    pd.line([(bt * 30, 0), (bt * 30, 6)], fill=(0, 120, 255))
plot.save(out / 'diff_plot.png')
json.dump({'spikes': spikes, 'diff': [round(float(v), 4) for v in d]}, open(out / 'qc.json', 'w'))
print('wrote', out / 'beats.jpg', out / 'diff_plot.png')
