# constrivo. launch film

A 27-second, 54-beat launch film at 1440 × 1440 and 60 fps. It is one continuous 2D take. The whole film is one HTML file, `film.html`, where every style is a pure function of time through `async seek(t)`. Nothing carries over from one frame to the next, so any frame can be rendered alone.

## Files

- `film.html` is the engine and every scene:
  - closed-form springs, with a value that has many targets built as the sum of one spring per change
  - the camera, zooming in log space with the cursor scaling with it
  - liquid glass: a per-element clone, an feImage rounded-rect distance field and 3 chromatic displacement maps
  - glass glyphs from canvas distance fields, the goo filter and the 6-blade iris
  - the wordmark squeeze and the all-intra footage
- `beatmap.json` holds the beat map and the song edits. It is the source of truth for the timeline and for `mix.py`.
- `assets/`:
  - fonts: Archivo VF and Geist VF
  - photos from Pexels
  - the song, Mixkit #207
  - the wall clip, Pexels 8516672
  - `sfx/`, the Mixkit sound effects, with `CREDITS.md`

## Build

```sh
cd tools
npm install                    # playwright-core; Chromium is expected at /opt/pw-browsers
./prep.sh                      # all-intra VP9 wall footage, motion-interpolated to 60 fps
node render.mjs --times 0,9.8  # single frames to out/stills
node render_film.mjs --workers 4   # 1620 frames x 4 subframes (180° shutter) -> out/film/master.mkv
./assemble.sh                  # mix (song edit + SFX, -14 LUFS) + mux + QC -> out/constrivo-film.mp4
python3 audio_plot.py          # the soundtrack against the beat grid -> out/audio/soundtrack.png
```

## Sound

`mix.py` builds the soundtrack:
- **Song edit.** The song is cut on bar lines as `beatmap.json` lists. Its kick sits 3 ms after the film's 0.5 s grid, so the song is shifted by that much. The drop lands on the zoom, the breakdown carries the wall, and the beat returns with the wordmark.
- **Effects.** Each effect is a Mixkit file, placed by its measured peak (or its onset, for sustained sounds).
- **Levels.** Each level is measured as well: an effect's loudest 50 ms sits a set number of dB from the music's RMS over the second around it. Very spiky files are held so their peaks stay within 18 dB of that level.
- **Loudness.** A linear gain to -14 LUFS, then a 4× oversampled lookahead limiter keeps the true peak under -1 dBTP.

The first and last frames render identically. `assemble.sh` encodes frames 0–1618, then appends frame 0's own compressed packet (an IDR) as frame 1619. The loop point therefore decodes to identical pixels after compression too.

## Result

`out/constrivo-film.mp4`:
- 27.00 s, 1440 × 1440, 60 fps, H.264 High 4:2:0 (CRF 14, about 8 Mb/s) with 320 kb/s AAC
- 1620 frames with no single-frame pops
- the first and last frames are identical: same md5, max diff 0
- -14.0 LUFS integrated, true peak -1.2 dBTP

`out/qc/` holds the beat contact sheet and the difference plot. `out/audio/` holds the soundtrack plot and every placed cue.

`qc.py <video>` writes these to a `qc/` folder next to the video:
- a contact sheet with one frame per beat
- a frame-difference plot, with single-frame pops marked (spikes more than 3× both neighbours)
- a check that the last frame is identical to the first

`popprobe.mjs` checks named hand-off moments before a full render.

## Credits

- **Song:** "Winter Breeze" by Arulo (Mixkit Stock Music Free License).
- **Sound effects:** Mixkit Sound Effects Free License. See `assets/sfx/CREDITS.md`.
- **Wall footage:** Pexels 8516672.
- **Relight pair:** Pexels 6528707 (Taipei 101 timelapse, frames at 3.0 s and 0.0 s).
- **Photos:** Pexels 5847445, 11435856, 15397751, 17697814, 3034343, 35209848, 35260667, 33724910, 36248805 and 38238573.
