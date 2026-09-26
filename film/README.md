# constrivo. launch film

A 27-second, 54-beat, 1440 × 1440 launch film. It is one HTML file (`film.html`) whose every style is a pure function of time through `async seek(t)`.

- `film.html`: the engine. It holds the closed-form springs, the camera, the cursor, the liquid glass (feImage displacement clones), the glass glyphs, goo, the iris, the wordmark squeeze and the footage.
- `beatmap.json`: the beat map and song edit map, the source of truth for the timeline.
- `tools/render.mjs`: renders frames with Playwright, for example `node render.mjs --times 0,2.58 --names first,open`.
- `tools/prep.sh`: rebuilds the all-intra VP9 wall footage from `assets/video/wall-src.mp4`.
- `tools/review_page.py`: builds the review page from `beatmap.json` and `out/review/*.jpg`.
- `out/review/`: the reviewed stills.

Setup: `cd tools && npm install && ./prep.sh`, then `node render.mjs`.

Credits: "Winter Breeze" by Arulo (Mixkit, free license). Wall footage is Pexels 8516672. The relight pair is Pexels 6528707. Photos are Pexels 5847445, 11435856, 15397751, 17697814, 3034343, 35209848, 35260667, 33724910, 36248805 and 38238573.
