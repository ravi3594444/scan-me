// Renders frames of film.html with Playwright.
//   node render.mjs --times 0,2.58 --names first,open --out ../out/stills
import { chromium } from 'playwright-core';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const args = Object.fromEntries(process.argv.slice(2).reduce((a, v, i, all) => {
  if (v.startsWith('--')) a.push([v.slice(2), all[i + 1]]);
  return a;
}, []));
const times = (args.times || '0').split(',').map(Number);
const names = (args.names || '').split(',').filter(Boolean);
const out = path.resolve(here, args.out || '../out/stills');
fs.mkdirSync(out, { recursive: true });

const types = { '.html': 'text/html', '.js': 'text/javascript', '.woff2': 'font/woff2', '.jpg': 'image/jpeg', '.png': 'image/png', '.mp4': 'video/mp4', '.webm': 'video/webm', '.json': 'application/json' };
const server = http.createServer((req, res) => {
  const p = path.join(root, decodeURIComponent(new URL(req.url, 'http://x').pathname));
  if (!p.startsWith(root) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'Content-Type': types[path.extname(p)] || 'application/octet-stream' });
  fs.createReadStream(p).pipe(res);
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const port = server.address().port;

const browser = await chromium.launch({
  executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome',
  args: ['--force-color-profile=srgb', '--disable-lcd-text', '--font-render-hinting=none', '--autoplay-policy=no-user-gesture-required'],
});
const page = await browser.newPage({ viewport: { width: 1440, height: 1440 }, deviceScaleFactor: 1 });
page.on('console', m => console.log('[page]', m.text()));
page.on('requestfailed', r => console.log('[reqfail]', r.url()));
page.on('response', r => { if (r.status() >= 400) console.log('[http]', r.status(), r.url()); });
page.on('pageerror', e => console.log('[pageerror]', e.message));
await page.goto(`http://127.0.0.1:${port}/${args.page || "film.html"}`);
await page.evaluate(() => window.filmReady);
for (let i = 0; i < times.length; i++) {
  const t = times[i];
  await page.evaluate(t => window.seek(t), t);
  const name = names[i] || `t${t.toFixed(3)}`;
  await page.screenshot({ path: path.join(out, `${name}.png`), clip: { x: 0, y: 0, width: 1440, height: 1440 } });
  console.log('wrote', name, t);
}
await browser.close();
server.close();
