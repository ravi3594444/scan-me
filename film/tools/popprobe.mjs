// Renders 60fps frames around hand-off moments and reports frame-difference spikes.
import { chromium } from 'playwright-core';
import http from 'node:http'; import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const types = { '.html': 'text/html', '.woff2': 'font/woff2', '.jpg': 'image/jpeg', '.webm': 'video/webm' };
const server = http.createServer((req, res) => { const p = path.join(root, decodeURIComponent(new URL(req.url, 'http://x').pathname)); if (!fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); } res.writeHead(200, { 'Content-Type': types[path.extname(p)] || 'application/octet-stream' }); fs.createReadStream(p).pipe(res); });
await new Promise(r => server.listen(0, '127.0.0.1', r));
const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium-1194/chrome-linux/chrome', args: ['--force-color-profile=srgb', '--disable-lcd-text', '--font-render-hinting=none'] });
const page = await browser.newPage({ viewport: { width: 1440, height: 1440 } });
page.on('pageerror', e => console.log('[pageerror]', e.message));
await page.goto(`http://127.0.0.1:${server.address().port}/film.html`);
await page.evaluate(() => window.filmReady);
const times = process.argv.slice(2).map(Number);
const grab = async t => { await page.evaluate(t => window.seek(t), t); return page.evaluate(() => null).then(() => page.screenshot({ type: 'png' })); };
const { PNG } = await import('./png.mjs');
for (const ts of times) {
  const f = Math.round(ts * 60);
  const ims = [];
  for (let k = -2; k <= 2; k++) ims.push(PNG((await grab((f + k) / 60))));
  const d = []; for (let i = 0; i < 4; i++) d.push(ims[i].diff(ims[i + 1]));
  const spike = d.map((v, i) => { const nb = [d[i - 1], d[i + 1]].filter(x => x !== undefined); return v > 3 * Math.max(...nb) && v > 0.5; });
  console.log(`t=${ts.toFixed(3)} diffs ${d.map(v => v.toFixed(3)).join(' ')} ${spike.some(Boolean) ? '<-- SPIKE' : ''}`);
}
await browser.close(); server.close();
