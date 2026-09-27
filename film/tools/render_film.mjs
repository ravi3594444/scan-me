// Renders the film: 60 fps, 4 subframes per frame blended with ffmpeg tmix (motion blur).
//   node render_film.mjs --workers 3            -> out/film/master.mkv (video only)
//   node render_film.mjs --range 0:540 --seg 0   (worker mode)
import { chromium } from 'playwright-core';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const outDir = path.join(root, 'out/film');
fs.mkdirSync(outDir, { recursive: true });
const args = Object.fromEntries(process.argv.slice(2).reduce((a, v, i, all) => { if (v.startsWith('--')) a.push([v.slice(2), all[i + 1]]); return a; }, []));
const FPS = 60, SUB = 4, DURATION = 27, FRAMES = FPS * DURATION;
const FFMPEG = '/usr/local/bin/ffmpeg';

if (args.range) await worker(); else await main();

async function main() {
  const n = Number(args.workers || 3);
  const per = Math.ceil(FRAMES / n);
  const t0 = Date.now();
  const jobs = [];
  for (let i = 0; i < n; i++) {
    const a = i * per, b = Math.min(FRAMES, (i + 1) * per);
    jobs.push(new Promise((res, rej) => {
      const p = spawn(process.execPath, [fileURLToPath(import.meta.url), '--range', `${a}:${b}`, '--seg', String(i)], { stdio: ['ignore', 'inherit', 'inherit'] });
      p.on('exit', c => c === 0 ? res() : rej(new Error(`worker ${i} exited ${c}`)));
    }));
  }
  await Promise.all(jobs);
  const list = path.join(outDir, 'segments.txt');
  fs.writeFileSync(list, [...Array(n)].map((_, i) => `file 'seg_${i}.mkv'`).join('\n') + '\n');
  await run(FFMPEG, ['-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', list, '-c', 'copy', path.join(outDir, 'master.mkv')]);
  console.log(`rendered ${FRAMES} frames in ${((Date.now() - t0) / 60000).toFixed(1)} min`);
}

async function worker() {
  const [a, b] = args.range.split(':').map(Number);
  const seg = args.seg;
  const types = { '.html': 'text/html', '.woff2': 'font/woff2', '.jpg': 'image/jpeg', '.webm': 'video/webm' };
  const server = http.createServer((req, res) => {
    const p = path.join(root, decodeURIComponent(new URL(req.url, 'http://x').pathname));
    if (!p.startsWith(root) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
    res.writeHead(200, { 'Content-Type': types[path.extname(p)] || 'application/octet-stream' });
    fs.createReadStream(p).pipe(res);
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const browser = await chromium.launch({
    executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
    args: ['--force-color-profile=srgb', '--disable-lcd-text', '--font-render-hinting=none'],
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1440 }, deviceScaleFactor: 1 });
  page.on('pageerror', e => console.log(`[seg ${seg}] pageerror`, e.message));
  await page.goto(`http://127.0.0.1:${server.address().port}/film.html`);
  await page.evaluate(() => window.filmReady);
  const cdp = await page.context().newCDPSession(page);
  // subframes k = 0..3 sit at (n + (k - 1.5) / 6) / 60: a 180-degree shutter centred on the frame time
  const ff = spawn(FFMPEG, ['-v', 'error', '-y', '-f', 'image2pipe', '-framerate', String(FPS * SUB), '-c:v', 'png', '-i', '-',
    '-vf', `tmix=frames=${SUB}:weights='1 1 1 1',select='eq(mod(n\\,${SUB})\\,${SUB - 1})',setpts=N/(${FPS}*TB)`,
    '-r', String(FPS), '-c:v', 'libx264', '-preset', 'fast', '-crf', '6', '-pix_fmt', 'yuv444p', path.join(outDir, `seg_${seg}.mkv`)],
    { stdio: ['pipe', 'inherit', 'inherit'] });
  const done = new Promise(res => ff.on('exit', res));
  const t0 = Date.now();
  for (let n = a; n < b; n++) {
    for (let k = 0; k < SUB; k++) {
      const t = Math.min(DURATION - 1e-4, Math.max(0, (n + (k - (SUB - 1) / 2) * (0.5 / (SUB - 1))) / FPS));
      await page.evaluate(t => window.seek(t), t);
      const { data } = await cdp.send('Page.captureScreenshot', { format: 'png', optimizeForSpeed: true, clip: { x: 0, y: 0, width: 1440, height: 1440, scale: 1 } });
      const buf = Buffer.from(data, 'base64');
      if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
    }
    if ((n - a) % 60 === 59) {
      const el = (Date.now() - t0) / 1000, doneF = n - a + 1;
      console.log(`[seg ${seg}] ${doneF}/${b - a} frames  ${(el / doneF).toFixed(2)} s/frame  eta ${((b - a - doneF) * el / doneF / 60).toFixed(1)} min`);
    }
  }
  ff.stdin.end();
  await done;
  await browser.close();
  server.close();
}

function run(cmd, a) {
  return new Promise((res, rej) => { const p = spawn(cmd, a, { stdio: 'inherit' }); p.on('exit', c => c === 0 ? res() : rej(new Error(cmd + ' exited ' + c))); });
}
