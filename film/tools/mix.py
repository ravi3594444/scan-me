"""Builds the film's soundtrack.

Song edits follow beatmap.json (all on bar lines; the song's kick sits 3 ms after the 0.5 s grid,
so the song is shifted by that much to put every kick exactly on the film's beats). Every sound
effect is a Mixkit file placed by its measured peak (or onset, for sustained sounds), at a level
measured against the music. The mix is loudness-normalised to -14 LUFS with a -1 dBTP ceiling.

    python3 mix.py   ->  out/audio/soundtrack.wav
"""
import json, pathlib, subprocess
import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
FFMPEG = '/usr/local/bin/ffmpeg'
SR = 48000
DUR = 27.0
KICK_OFFSET = 0.003
OUT = ROOT / 'out/audio'
OUT.mkdir(parents=True, exist_ok=True)


def decode(path):
    raw = subprocess.run([FFMPEG, '-v', 'error', '-i', str(path), '-ac', '2', '-ar', str(SR), '-f', 'f32le', '-'],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.float32).reshape(-1, 2).astype(np.float64)


def song_edit():
    bm = json.loads((ROOT / 'beatmap.json').read_text())
    song = decode(ROOT / 'assets/audio/winter-breeze-207.mp3')
    out = np.zeros((int(DUR * SR), 2))
    edits = bm['song']['edits']
    for i, e in enumerate(edits):
        f0, f1 = e['film'][0] * 0.5, e['film'][1] * 0.5
        s0 = e['song'][0] + KICK_OFFSET
        # crossfade: the incoming piece starts a little early, the outgoing one runs a little long
        xf = 0.08 if e['film'][0] == 48 else 0.012
        pre = xf / 2 if i > 0 else 0.0
        post = (0.08 if e['film'][1] == 48 else 0.012) / 2 if i < len(edits) - 1 else 0.0
        a, b = int((f0 - pre) * SR), int((f1 + post) * SR)
        seg = song[int((s0 - pre) * SR): int((s0 - pre) * SR) + (b - a)].copy()
        n = len(seg)
        env = np.ones(n)
        if pre:
            k = int(2 * pre * SR); env[:k] = np.sin(np.linspace(0, np.pi / 2, k)) ** 1
        if post:
            k = int(2 * post * SR); env[n - k:] = np.cos(np.linspace(0, np.pi / 2, k)) ** 1
        b = min(b, len(out))
        seg = seg[: b - a] * env[: b - a, None]
        out[max(0, a): b] += seg[max(0, -a):]
    # the returned beat rings out for the last two beats
    t = np.arange(len(out)) / SR
    fade = np.clip((DUR - t) / (DUR - 26.3), 0, 1)
    out *= np.where(t > 26.3, np.sin(fade * np.pi / 2) ** 2, 1.0)[:, None]
    out[: int(0.003 * SR)] *= np.linspace(0, 1, int(0.003 * SR))[:, None]
    return out


def envelope_peak(x):
    """time of the loudest 10 ms, and the first time the envelope reaches 10% of it"""
    m = np.abs(x).max(1)
    n = int(0.01 * SR)
    rms = np.sqrt(np.convolve(m ** 2, np.ones(n) / n, 'same'))
    pk = int(np.argmax(rms))
    on = int(np.argmax(rms > 0.1 * rms[pk]))
    return pk / SR, on / SR, rms[pk]


# (film time of the visual hit, effect, level dB, align on 'peak' or 'onset', options)
# level: where the effect's loudest 50 ms sits against the music around it (RMS over +-0.5 s, floored at the
# song's RMS - 9 dB so the quiet breakdown still carries its effects). Very spiky files are held so that their
# peak stays within CREST_MAX of that level. options: rate (varispeed, pitch and speed together), until (film
# time the effect has faded out by), fade (length of that fade), fade_in, span (window for the music level).
POP = 0.096     # first arrival of SP.pop, when a popped element reaches full size
PENTA = [1, 9 / 8, 5 / 4, 3 / 2, 5 / 3]      # the glass letters rise through a major pentatonic
FLOOR = -9.0
CREST_MAX = 18.0
CUES = [
    (0.60, 'squeeze', -10, 'peak'),
    (1.08, 'pop_soft', -8, 'peak'),
    (1.56, 'tick', -12, 'peak'),
    (1.96, 'click', -9, 'peak'),
    (2.14, 'shutter_close', -7, 'peak'),
    (2.56, 'shutter_open', -6, 'peak'),
    (3.08, 'tick', -13, 'peak'),
    (3.58, 'swoosh_small', -10, 'peak'),
    (4.08, 'paper_flap', -8, 'peak'),
    (4.58, 'paper_flap_big', -9, 'peak'),
    (5.12, 'swoosh_small', -10, 'peak', {'rate': 1.12}),
    (5.46, 'click', -9, 'peak'),
    (6.00, 'riser_whoosh', -3, 'peak'),
    *[(6.5 + 0.055 * i + POP, 'glass_tap', -12, 'peak', {'rate': PENTA[i]}) for i in range(5)],
    (7.18, 'bloop', -8, 'peak'),
    (7.60, 'stretch', -10, 'peak'),
    *[(7.68 + 0.05 * i + POP, 'pop_icon', -12, 'peak', {'rate': 1 + 0.06 * i}) for i in range(4)],
    (7.97, 'click', -9, 'peak'),
    (8.56, 'tick', -12, 'peak'),
    (8.98, 'haptic', -6, 'peak'),
    (9.50, 'slide', -11, 'onset', {'until': 10.45, 'fade': 0.25}),
    (10.60, 'bubble', -8, 'peak'),
    (11.10, 'pop_soft', -8, 'peak', {'rate': 0.9}),
    (11.62, 'whoosh', -10, 'peak'),
    *[(11.64 + 0.06 * i + POP, 'glass_tap', -11, 'peak', {'rate': [1, 5 / 4, 3 / 2, 2][i]}) for i in range(4)],
    (12.05, 'tick', -12, 'peak'),
    (12.50, 'whoosh', -11, 'onset'),
    (12.66, 'stretch', -10, 'peak', {'rate': 0.9}),
    (13.10, 'haptic', -8, 'peak'),
    (13.62, 'stretch', -9, 'peak'),
    (14.08, 'bloop', -8, 'peak', {'rate': 1.12}),
    (14.10, 'whoosh', -11, 'onset'),
    (14.60, 'pop_soft', -8, 'peak'),
    (15.00, 'blind_roll', -9, 'onset', {'until': 15.62, 'fade': 0.15}),
    (15.50, 'click', -10, 'peak'),
    (15.80, 'haptic', -6, 'peak'),
    (15.86, 'slide', -12, 'onset', {'until': 16.5, 'fade': 0.25}),
    (16.24, 'tick', -12, 'peak'),
    (16.60, 'swipe', -9, 'peak'),
    (17.00, 'drop_thud', -6, 'peak'),
    (17.52, 'scroll_ticks', -11, 'onset'),
    (18.10, 'wood_tap', -8, 'peak'),
    (18.47, 'click', -9, 'peak'),
    (18.56, 'brush', -10, 'onset'),
    (18.97, 'click', -9, 'peak'),
    (19.52, 'whoosh', -11, 'onset'),
    (19.97, 'click', -8, 'peak'),
    (20.02, 'success', -7, 'onset'),
    (20.58, 'printer', -13, 'onset', {'until': 21.0, 'fade': 0.1}),
    (21.28, 'van', -11, 'peak', {'until': 21.58, 'fade': 0.18}),
    (21.56, 'doorbell', -8, 'peak'),
    (21.90, 'boom_low', -1, 'peak'),
    (22.55, 'room_tone', -10, 'onset', {'fade_in': 0.35, 'until': 24.62, 'fade': 0.2, 'span': (22.55, 24.62)}),
    (22.85, 'reverse_whoosh', -6, 'peak'),
    (22.97, 'click', -9, 'peak'),
    (23.06, 'shutter_open', -6, 'peak'),
    (24.12, 'shutter_close', -7, 'peak'),
    (24.70, 'boom_low', -2, 'peak', {'rate': 1.06}),
    (25.35, 'reverse_short', -7, 'peak'),
    (25.56, 'tick', -11, 'peak'),
    (26.00, 'impact', -2, 'peak'),
]


def rms_db(x, win):
    """loudest `win`-second RMS of a stereo signal, in dB"""
    m = x.mean(1) if x.ndim == 2 else x
    n = max(1, int(win * SR))
    r = np.sqrt(np.convolve(m ** 2, np.ones(n) / n, 'valid'))
    return 20 * np.log10(r.max() + 1e-12)


def varispeed(x, rate):
    if rate == 1:
        return x
    n = int(len(x) / rate)
    src = np.arange(n) * rate
    return np.stack([np.interp(src, np.arange(len(x)), x[:, c]) for c in range(2)], 1)


def main():
    music = song_edit()
    music_db = rms(music)
    sfx_dir = ROOT / 'assets/sfx'
    meta = json.loads((sfx_dir / 'sfx.json').read_text())
    src, cache = {}, {}
    fx = np.zeros_like(music)
    tt = np.arange(len(fx)) / SR
    placed = []
    for cue in CUES:
        t, name, gain, align = cue[:4]
        opt = cue[4] if len(cue) > 4 else {}
        if name not in meta:
            print('missing effect', name); continue
        if name not in src:
            src[name] = decode(sfx_dir / meta[name]['file'])
        key = (name, opt.get('rate', 1))
        if key not in cache:
            x = varispeed(src[name], key[1])
            pk, on, _ = envelope_peak(x)
            r = rms_db(x, 0.05)
            x = x * 10 ** (-r / 20)          # loudest 50 ms at 0 dB, then the cue sets the level
            cache[key] = (x, pk, on, 20 * np.log10(np.abs(x).max()))
        x, pk, on, crest = cache[key]
        ref = pk if align == 'peak' else on
        start = int(round((t - ref) * SR))
        w0, w1 = opt.get('span', (t - 0.5, t + 0.5))
        loc = rms(music[max(0, int(w0 * SR)): int(w1 * SR)])
        level = max(loc, music_db + FLOOR) + gain - max(0.0, crest - CREST_MAX)
        seg = x * 10 ** (level / 20)
        a, b = max(0, start), min(len(fx), start + len(seg))
        if b <= a:
            continue
        seg = seg[a - start: b - start].copy()
        ts = tt[a:b]
        env = np.ones(len(seg))
        if 'fade_in' in opt:
            env *= np.clip((ts - (t - ref + on)) / opt['fade_in'], 0, 1)
        if 'until' in opt:
            f = opt.get('fade', 0.08)
            env *= np.sin(np.clip((opt['until'] - ts) / f, 0, 1) * np.pi / 2) ** 2
        fx[a:b] += seg * env[:, None]
        placed.append({'t': round(t, 3), 'effect': name, 'rate': key[1], 'ref': round(ref, 3), 'file_start': round(t - ref, 3),
                       'level_dbfs': round(level, 1), 'vs_local_music': round(level - loc, 1), 'vs_song_rms': round(level - music_db, 1)})
    # effects never run past the last frame
    fx *= np.clip((DUR - tt) / 0.35, 0, 1)[:, None]
    mix = music + fx
    raw = OUT / 'mix_raw.wav'
    write_wav(raw, mix)
    write_wav(OUT / 'music_only.wav', music)
    write_wav(OUT / 'fx_only.wav', fx)
    json.dump(placed, open(OUT / 'cues_placed.json', 'w'), indent=1)
    print(f'music RMS {music_db:.1f} dBFS; {len(placed)} cues placed')
    for c in placed:
        print(f"  {c['t']:6.2f} {c['effect']:15s} x{c['rate']:<5.3g} file@{c['file_start']:7.3f}  {c['vs_local_music']:6.1f} dB vs local music, {c['vs_song_rms']:6.1f} vs song RMS")
    # linear gain to -14 LUFS, then a 4x-oversampled lookahead limiter holds the true peak under -1 dBTP;
    # the gain is re-measured after the limiter until the loudness lands within 0.05 LU
    gain = -14.0 - ebur128(raw)[0]
    for _ in range(4):
        af = (f'volume={gain:.3f}dB,aresample=192000:resampler=soxr,alimiter=limit={10 ** (-1.3 / 20):.5f}:attack=0.8:release=40:level=0:latency=1,'
              f'aresample=48000:resampler=soxr')
        subprocess.run([FFMPEG, '-v', 'error', '-y', '-i', str(raw), '-af', af, '-c:a', 'pcm_s24le', str(OUT / 'soundtrack.wav')], check=True)
        i, tp = ebur128(OUT / 'soundtrack.wav')
        if abs(i + 14.0) < 0.05:
            break
        gain += -14.0 - i
    print(f'raw mix I {ebur128(raw)[0]:.2f} LUFS; gain {gain:+.2f} dB -> soundtrack I {i:.2f} LUFS, true peak {tp:.2f} dBTP')


def rms(x):
    m = x.mean(1)
    return 20 * np.log10(np.sqrt((m ** 2).mean()) + 1e-12)


def ebur128(path):
    r = subprocess.run([FFMPEG, '-hide_banner', '-nostats', '-i', str(path), '-af', 'ebur128=peak=true', '-f', 'null', '-'],
                       capture_output=True, text=True).stderr
    summ = r[r.rindex('Summary:'):]
    i = float(summ.split('I:')[1].split('LUFS')[0])
    tp = float(summ.split('Peak:')[1].split('dBFS')[0])
    return i, tp


def write_wav(path, x):
    x = x.astype(np.float32)          # float wav: peaks above 0 dBFS survive until the final gain and limiter set the level
    subprocess.run([FFMPEG, '-v', 'error', '-y', '-f', 'f32le', '-ar', str(SR), '-ac', '2', '-i', '-', '-c:a', 'pcm_f32le', str(path)],
                   input=x.tobytes(), check=True)


if __name__ == '__main__':
    main()
