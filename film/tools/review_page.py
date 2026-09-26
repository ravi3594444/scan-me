# Builds the beat-map review page (single HTML file, stills embedded) from beatmap.json.
import json, base64, html, sys, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
bm = json.loads((root / 'beatmap.json').read_text())
out = pathlib.Path(sys.argv[1])
img = lambda n: 'data:image/jpeg;base64,' + base64.b64encode((root / 'out/review' / f'{n}_1000.jpg').read_bytes()).decode()
E = html.escape
sec_of = lambda b: next(s for s in bm['sections'] if s['from'] <= b < s['to'] or (b == 54 and s['id'] == 'end'))
still_beats = {int(s['beat']): s['name'] for s in bm['stills'] if s['name'] != 'first'}
music = {12: 'Drop', 28: 'Edit', 43: 'Bass cuts', 44: 'Breakdown', 48: 'Edit', 52: 'Return'}

captions = {
  'open':  ('Open', 'The iris snaps open onto Taipei. The pill has already become a circle behind the closed blades, and the cursor is leaving after the click.'),
  'glass': ('Glass', 'Mid-drag. The knob has swelled into a glass lens, and the golden-hour frame wipes in behind it along an exact line, with no crossfade. The time chip counts along.'),
  'stage': ('Stage', 'The wallpaper, long-pressed off the phone, hangs under the cursor as a drag preview over the Safari tab bar. The glass clock and music player refract the wallpaper.'),
  'wall':  ('Wall', 'The print on the real wall footage. The same palm shadow is multiplied across the frame, offset a little because the frame stands off the wall.'),
}

strip = []
for bt in range(55):
    s = sec_of(bt)
    cls = ['cell', 's-' + s['id']]
    if bt in still_beats: cls.append('still')
    m = music.get(bt, '')
    strip.append(f'<div class="{" ".join(cls)}" title="b{bt} · {bt*0.5:.1f} s"><span class="bn">{bt}</span>{f"<span class=mk>{E(m)}</span>" if m else ""}</div>')

rows = []
cur = None
for bt, what, cursor, sfx in bm['beats']:
    s = sec_of(bt)
    if s['id'] != cur:
        cur = s['id']
        rows.append(f'<tr class="sec"><th colspan="5"><span class="sec-name">{E(s["label"])}</span><span class="sec-range">b{s["from"]}–b{s["to"] if s["id"] != "end" else 54}</span></th></tr>')
    tag = ''
    if bt in music: tag += f'<span class="chip gold">{E(music[bt])}</span>'
    if bt in still_beats: tag += f'<span class="chip ink">Still: {E(captions[still_beats[bt]][0])}</span>'
    rows.append(f'<tr><td class="num">{bt}</td><td class="num">{bt*0.5:.1f}</td><td>{E(what)} {tag}</td><td class="dim">{E(cursor)}</td><td class="dim">{E(sfx)}</td></tr>')

edits = ''.join(
  f'<tr><td class="num">b{e["film"][0]}–b{e["film"][1]}</td><td class="num">{int(e["song"][0]//60)}:{e["song"][0]%60:04.1f} – {int(e["song"][1]//60)}:{e["song"][1]%60:04.1f}</td><td class="num">{E(e["bars"])}</td><td>{E(e["note"])}</td></tr>'
  for e in bm['song']['edits'])

stills = ''.join(
  f'''<figure class="still"><img src="{img(s["name"])}" alt="{E(captions[s["name"]][0])} still at {s["t"]:.2f} s" width="1000" height="1000">
  <figcaption><div class="cap-head"><span class="cap-name">{E(captions[s["name"]][0])}</span><span class="num">t = {s["t"]:.2f} s · b{s["beat"]:g}</span></div><p>{E(captions[s["name"]][1])}</p></figcaption></figure>'''
  for s in bm['stills'] if s['name'] != 'first')

page = f'''<title>constrivo. beat map</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Archivo:wdth,wght@62..125,100..900&family=Geist:wght@400..700&family=Geist+Mono:wght@400..600&display=swap">
<style>
:root {{
  --paper: #f2efe9; --ink: #0b0b0c; --ink-2: #5a554d; --rule: #dcd6cb; --panel: #e8e3da; --panel-2: #dfd9ce; --gold: #b86a24; --gold-bg: #f3e1cc;
  --display: Archivo, "Helvetica Neue", Arial, sans-serif; --ui: Geist, system-ui, -apple-system, "Segoe UI", sans-serif; --mono: "Geist Mono", ui-monospace, "SF Mono", Menlo, monospace;
}}
@media (prefers-color-scheme: dark) {{
  :root:not([data-theme="light"]) {{ --paper: #10100f; --ink: #efebe4; --ink-2: #a39c91; --rule: #2b2825; --panel: #1b1917; --panel-2: #24211e; --gold: #e79a55; --gold-bg: #3a2a1b; color-scheme: dark; }}
}}
:root[data-theme="dark"] {{ --paper: #10100f; --ink: #efebe4; --ink-2: #a39c91; --rule: #2b2825; --panel: #1b1917; --panel-2: #24211e; --gold: #e79a55; --gold-bg: #3a2a1b; color-scheme: dark; }}
* {{ box-sizing: border-box; }}
body {{ background: var(--paper); color: var(--ink); font: 15px/1.55 var(--ui); margin: 0; }}
.wrap {{ max-width: 1120px; margin: 0 auto; padding-inline: 24px; padding-block: 48px 72px; display: grid; gap: 56px; }}
@media (max-width: 560px) {{ .wrap {{ padding-inline: 16px; padding-block: 28px 48px; gap: 40px; }} }}
header {{ display: grid; grid-template-columns: 1fr auto; gap: 32px; align-items: end; }}
@media (max-width: 720px) {{ header {{ grid-template-columns: 1fr; }} }}
.wordmark {{ font: 800 clamp(56px, 11vw, 124px)/0.9 var(--display); font-stretch: 125%; font-variation-settings: "wdth" 125; letter-spacing: 0; margin: 0; }}
.kicker {{ font: 600 12px/1 var(--ui); letter-spacing: .12em; text-transform: uppercase; color: var(--ink-2); margin: 0 0 18px; }}
.lede {{ max-width: 62ch; margin: 18px 0 0; color: var(--ink-2); font-size: 16px; }}
.lede strong {{ color: var(--ink); font-weight: 600; }}
.frame0 {{ width: 188px; margin: 0; }}
.frame0 img {{ display: block; width: 100%; height: auto; border-radius: 6px; box-shadow: 0 0 0 1px var(--rule); }}
.frame0 figcaption {{ font: 500 12px/1.4 var(--ui); color: var(--ink-2); margin-top: 8px; }}
.facts {{ display: flex; flex-wrap: wrap; gap: 8px 28px; margin: 24px 0 0; padding: 0; list-style: none; font: 500 13px/1.3 var(--mono); color: var(--ink-2); font-variant-numeric: tabular-nums; }}
.facts b {{ color: var(--ink); font-weight: 600; }}
h2 {{ font: 800 28px/1.05 var(--display); font-stretch: 125%; font-variation-settings: "wdth" 125; margin: 0 0 6px; text-wrap: balance; }}
.sub {{ color: var(--ink-2); margin: 0 0 22px; max-width: 70ch; }}
.asks {{ border-top: 2px solid var(--ink); padding-top: 20px; display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 18px 36px; }}
.ask h3 {{ font: 650 15px/1.3 var(--ui); margin: 0 0 4px; }}
.ask p {{ margin: 0; color: var(--ink-2); font-size: 14px; }}
.stills {{ display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 28px; }}
@media (max-width: 720px) {{ .stills {{ grid-template-columns: 1fr; }} }}
.still {{ margin: 0; }}
.still img {{ display: block; width: 100%; height: auto; aspect-ratio: 1; border-radius: 6px; background: var(--panel); }}
.still figcaption {{ padding-top: 12px; }}
.cap-head {{ display: flex; justify-content: space-between; gap: 12px; align-items: baseline; }}
.cap-name {{ font: 800 17px/1 var(--display); font-stretch: 125%; font-variation-settings: "wdth" 125; }}
.still p {{ margin: 6px 0 0; color: var(--ink-2); font-size: 14px; max-width: 60ch; }}
.num {{ font-family: var(--mono); font-variant-numeric: tabular-nums; font-size: 13px; white-space: nowrap; }}
.stripbox {{ overflow-x: auto; padding-bottom: 30px; }}
.strip {{ display: grid; grid-template-columns: repeat(55, minmax(18px, 1fr)); gap: 3px; min-width: 900px; }}
.cell {{ position: relative; height: 54px; border-radius: 3px; background: var(--panel); display: flex; flex-direction: column; justify-content: space-between; padding: 4px 0 5px; align-items: center; }}
.cell .bn {{ font: 500 10px/1 var(--mono); color: var(--ink-2); }}
.cell .mk {{ position: absolute; top: 60px; left: 50%; transform: translateX(-50%); font: 600 10px/1 var(--ui); color: var(--gold); white-space: nowrap; letter-spacing: .02em; }}
.cell:has(.mk)::after {{ content: ""; position: absolute; left: 50%; bottom: -3px; width: 2px; height: 8px; background: var(--gold); transform: translateX(-50%); }}
.s-open {{ background: var(--panel); }} .s-glass {{ background: var(--panel-2); }} .s-stage {{ background: var(--panel); }}
.s-order {{ background: var(--ink); }} .s-order .bn {{ color: var(--paper); }}
.s-wall {{ background: var(--panel-2); }} .s-end {{ background: var(--panel); }}
.cell.still {{ box-shadow: inset 0 0 0 2px var(--ink); }}
.strip-labels {{ display: grid; grid-template-columns: repeat(55, minmax(18px, 1fr)); gap: 3px; min-width: 900px; margin-bottom: 8px; font: 700 11px/1 var(--ui); letter-spacing: .1em; text-transform: uppercase; color: var(--ink-2); }}
.legend {{ display: flex; flex-wrap: wrap; gap: 8px 22px; margin-top: 30px; font-size: 13px; color: var(--ink-2); }}
.legend span::before {{ content: ""; display: inline-block; width: 12px; height: 12px; border-radius: 2px; margin-right: 7px; vertical-align: -2px; }}
.lg-still::before {{ box-shadow: inset 0 0 0 2px var(--ink); background: var(--panel); }}
.lg-music::before {{ background: var(--gold); width: 3px !important; }}
.lg-order::before {{ background: var(--ink); }}
.tbox {{ overflow-x: auto; }}
table {{ width: 100%; border-collapse: collapse; min-width: 720px; }}
th, td {{ text-align: left; vertical-align: top; padding: 9px 12px 9px 0; border-bottom: 1px solid var(--rule); }}
thead th {{ font: 600 11px/1 var(--ui); letter-spacing: .1em; text-transform: uppercase; color: var(--ink-2); padding-bottom: 10px; border-bottom: 1px solid var(--ink); }}
td.dim {{ color: var(--ink-2); font-size: 14px; }}
tr.sec th {{ padding-top: 26px; border-bottom: 1px solid var(--ink); }}
.sec-name {{ font: 800 18px/1 var(--display); font-stretch: 125%; font-variation-settings: "wdth" 125; margin-right: 12px; }}
.sec-range {{ font: 500 12px/1 var(--mono); color: var(--ink-2); }}
.chip {{ display: inline-block; font: 600 11px/1 var(--ui); letter-spacing: .03em; padding: 4px 7px; border-radius: 99px; margin-left: 6px; vertical-align: 1px; white-space: nowrap; }}
.chip.gold {{ background: var(--gold-bg); color: var(--gold); }}
.chip.ink {{ background: var(--ink); color: var(--paper); }}
.notes {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(300px, 1fr)); gap: 22px 40px; }}
.note h3 {{ font: 650 15px/1.3 var(--ui); margin: 0 0 4px; }}
.note p {{ margin: 0; color: var(--ink-2); font-size: 14px; max-width: 60ch; }}
.credits {{ font-size: 13px; color: var(--ink-2); columns: 2 300px; column-gap: 40px; }}
.credits p {{ margin: 0 0 8px; break-inside: avoid; }}
a {{ color: inherit; text-decoration-color: var(--rule); text-underline-offset: 3px; }}
a:hover {{ text-decoration-color: var(--ink); }}
</style>
<div class="wrap">
<header>
  <div>
    <p class="kicker">Launch film · beat map and stills for review</p>
    <h1 class="wordmark">constrivo.</h1>
    <p class="lede">One continuous 2D take, 54 beats at 120 BPM. Every scene is made out of the previous one. <strong>The four stills are real frames from film.html's seek(t)</strong>, rendered in headless Chromium at 1440 × 1440. They are not mockups. The beats between them are mapped below but not wired yet.</p>
    <ul class="facts"><li><b>54</b> beats</li><li><b>27.0</b> s</li><li><b>120.000</b> BPM</li><li><b>1440²</b> · 60 fps</li><li>drop <b>b12</b></li><li>breakdown <b>b44</b></li><li>return <b>b52</b></li></ul>
  </div>
  <figure class="frame0"><img src="{img('first')}" alt="First frame: the constrivo. wordmark" width="1000" height="1000"><figcaption>Frame 0, which is also the last frame. Archivo, wdth 125, weight 800.</figcaption></figure>
</header>

<section>
  <h2>Needs your call</h2>
  <p class="sub">I made these choices to get to stills. Each one is easy to change before I build the full film.</p>
  <div class="asks">
    <div class="ask"><h3>The return lands on b52, not b54</h3><p>54 beats is 13½ bars. With the song entering on a downbeat, b54 falls on beat 3 of a bar, so no drum return can land there without a half-bar edit. I land the letters on the b52 downbeat and let the last two beats (1.0 s) ring out on the settled wordmark. The alternatives are a 2-beat edit in the breakdown, or a 56-beat film.</p></div>
    <div class="ask"><h3>Copy: "Make it real" and "light"</h3><p>The pill label and the glass word are placeholders I chose. The glass word melts into the droplet that becomes the toolbar, so a short word of 4–6 letters works best.</p></div>
    <div class="ask"><h3>The relight is a wipe that follows the knob</h3><p>Crossfades are banned, so the golden-hour frame is revealed along a hard line that tracks the lens. The two shots are frames 0 s and 3 s of a locked-off 4K Taipei 101 timelapse, aligned to within 1 px.</p></div>
    <div class="ask"><h3>Photos: warm, sunlit architecture</h3><p>Ten Pexels photos chosen to fit a brand called constrivo. The hero print, the one that becomes the wallpaper, the landing page and the print on the wall, is the curved white fins (Pexels 5847445).</p></div>
  </div>
</section>

<section>
  <h2>Four stills</h2>
  <p class="sub">Open, glass, stage, wall. Each is a single call to <span class="num">seek(t)</span> with no state carried between frames.</p>
  <div class="stills">{stills}</div>
</section>

<section>
  <h2>Beat map</h2>
  <p class="sub">One cell per beat. Outlined cells are where the stills sit; gold marks are musical events and edits.</p>
  <div class="stripbox">
    <div class="strip-labels"><span style="grid-column: 1 / span 12">Open</span><span style="grid-column: 13 / span 14">Glass</span><span style="grid-column: 27 / span 14">Stage</span><span style="grid-column: 41 / span 4">Order</span><span style="grid-column: 45 / span 8">Wall</span><span style="grid-column: 53 / span 3">End</span></div>
    <div class="strip">{"".join(strip)}</div>
  </div>
  <div class="legend"><span class="lg-still">still</span><span class="lg-music">music event or edit</span><span class="lg-order">the one black shape (Order)</span></div>
</section>

<section>
  <h2>Every beat</h2>
  <div class="tbox"><table>
    <thead><tr><th style="width:52px">Beat</th><th style="width:60px">Sec</th><th>What happens</th><th style="width:120px">Cursor</th><th style="width:130px">Sound (Mixkit)</th></tr></thead>
    <tbody>{"".join(rows)}</tbody>
  </table></div>
</section>

<section>
  <h2>Song map</h2>
  <p class="sub">{E(bm["song"]["title"])} by {E(bm["song"]["artist"])} (Mixkit #207). Measured at 120.000 BPM: the kick sits on t = 0.5k s, bars are every 2.0 s, the drop is at 0:32.0, the bass cuts at 1:03.5 and the beat returns at 1:28.0. Every edit falls on a bar line.</p>
  <div class="tbox"><table>
    <thead><tr><th style="width:110px">Film</th><th style="width:170px">Song</th><th style="width:70px">Bars</th><th>Why</th></tr></thead>
    <tbody>{edits}</tbody>
  </table></div>
</section>

<section>
  <h2>Build notes</h2>
  <div class="notes">
    <div class="note"><h3>Footage is VP9, all-intra</h3><p>Playwright's Chromium ships without an H.264 decoder, so the wall clip is re-encoded as VP9 WebM with -g 1 (every frame a keyframe). It loads as a blob URL, and every frame awaits 'seeked' before it is drawn.</p></div>
    <div class="note"><h3>Glass rims never fold</h3><p>Displacement is clamped below bezel ÷ profile, so a rim bends the photo without mirroring it. This is what stopped the noisy edges in the first renders. Chromatic spread is kept to 5–6%.</p></div>
    <div class="note"><h3>The wall shadow falls on the print</h3><p>The footage is levelled so the bare wall reads as white, then multiplied over the frame. The palm shadow crosses the mat and the photo, not only the wall.</p></div>
    <div class="note"><h3>Sound is next</h3><p>The sound column above is the plan. Every effect will be a downloaded Mixkit file placed by its measured peak. The song edits are measured by chroma and timbre, not auditioned, so b48 gets a bar-line crossfade.</p></div>
  </div>
</section>

<section class="credits">
  <p><b>Song</b> · Winter Breeze by Arulo, Mixkit Stock Music Free License.</p>
  <p><b>Wall footage</b> · Pexels video 8516672 (palm shadow on a white wall), 4K.</p>
  <p><b>Relight pair</b> · Pexels video 6528707 (Taipei 101 timelapse), frames at 3.0 s and 0.0 s.</p>
  <p><b>Photos</b> · Pexels 5847445 (hero), 11435856, 15397751, 17697814, 3034343, 35209848, 35260667, 33724910, 36248805, 38238573.</p>
  <p><b>Type</b> · Archivo (wdth 62–125) for the wordmark, Geist for UI.</p>
</section>
</div>
'''
out.write_text(page)
print(out, len(page) // 1024, 'KB')
