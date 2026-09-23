// Browser end-to-end test of the receive page (WP9; testing T-21 in Chromium only).
//
// Starts the server with sample files (./gradlew :web-receive:e2eServer), then drives headless Chromium through the
// page: approval wait, file list, a download through fetch + ReadableStream with the progress bar, a plain-link
// download, the streamed zip (checked entry by entry, CRC-32 included), dark mode, "Send files back", a second
// browser being refused, and no console or CSP errors. See web-receive/README.md for how to run it.
//
//   NODE_PATH="$(npm root -g)" PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers node web-receive/e2e/receive.e2e.mjs

import { execSync, spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir, readFile, readdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Playwright is installed globally: require() finds it through NODE_PATH (ESM imports ignore NODE_PATH), or else in
// `npm root -g`. Browsers come from PLAYWRIGHT_BROWSERS_PATH (default /opt/pw-browsers when it exists).
if (!process.env.PLAYWRIGHT_BROWSERS_PATH && existsSync('/opt/pw-browsers')) process.env.PLAYWRIGHT_BROWSERS_PATH = '/opt/pw-browsers';
const require = createRequire(import.meta.url);
function loadPlaywright() {
  try {
    return require('playwright');
  } catch {
    return require(path.join(execSync('npm root -g').toString().trim(), 'playwright'));
  }
}
const { chromium } = loadPlaywright();

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..', '..');
const outDir = path.join(root, 'web-receive', 'build', 'e2e');

let failures = 0;
function check(condition, what) {
  if (condition) {
    console.log(`  ok    ${what}`);
  } else {
    failures++;
    console.log(`  FAIL  ${what}`);
  }
}

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  return table;
})();

function crc32(bytes) {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** Reads a (non-ZIP64) stored zip through its central directory: [{name, crc, data, flags, method}]. */
function readZip(buf) {
  let end = -1;
  for (let i = buf.length - 22; i >= 0; i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { end = i; break; }
  }
  if (end < 0) throw new Error('no end of central directory');
  const count = buf.readUInt16LE(end + 10);
  let p = buf.readUInt32LE(end + 16);
  const entries = [];
  for (let i = 0; i < count; i++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('bad central header');
    const flags = buf.readUInt16LE(p + 8);
    const method = buf.readUInt16LE(p + 10);
    const crc = buf.readUInt32LE(p + 16);
    const size = buf.readUInt32LE(p + 20);
    const nameLength = buf.readUInt16LE(p + 28);
    const extraLength = buf.readUInt16LE(p + 30);
    const commentLength = buf.readUInt16LE(p + 32);
    const local = buf.readUInt32LE(p + 42);
    const name = buf.subarray(p + 46, p + 46 + nameLength).toString('utf8');
    if (buf.readUInt32LE(local) !== 0x04034b50) throw new Error('bad local header');
    const dataStart = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
    const data = buf.subarray(dataStart, dataStart + size);
    const descriptor = dataStart + size;
    const descriptorOk = buf.readUInt32LE(descriptor) === 0x08074b50 && buf.readUInt32LE(descriptor + 4) === crc;
    entries.push({ name, crc, data, flags, method, descriptorOk });
    p += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}

async function startServer() {
  const gradlew = path.join(root, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  const child = spawn(gradlew, ['--console=plain', '-q', '--max-workers=2', ':web-receive:e2eServer'], {
    cwd: root,
    stdio: ['pipe', 'pipe', 'inherit'],
  });
  const ready = await new Promise((resolve, reject) => {
    let buffered = '';
    const timer = setTimeout(() => reject(new Error('server did not start within 5 minutes')), 300_000);
    child.stdout.on('data', (chunk) => {
      buffered += chunk.toString();
      let newline;
      while ((newline = buffered.indexOf('\n')) >= 0) {
        const line = buffered.slice(0, newline).trim();
        buffered = buffered.slice(newline + 1);
        if (line.startsWith('E2E_READY ')) {
          clearTimeout(timer);
          resolve(JSON.parse(line.slice('E2E_READY '.length)));
        }
      }
    });
    child.on('exit', (code) => reject(new Error(`server exited early with ${code}`)));
  });
  return { child, ready };
}

async function stopServer(child) {
  if (child.exitCode !== null) return;
  const exited = new Promise((resolve) => child.on('exit', resolve));
  child.stdin.end('stop\n');
  const timeout = new Promise((resolve) => setTimeout(() => resolve('timeout'), 20_000));
  if ((await Promise.race([exited, timeout])) === 'timeout') child.kill('SIGTERM');
}

async function saveDownload(page, trigger) {
  const [download] = await Promise.all([page.waitForEvent('download', { timeout: 60_000 }), trigger()]);
  const file = await download.path();
  return { name: download.suggestedFilename(), bytes: await readFile(file) };
}

async function run() {
  await mkdir(outDir, { recursive: true });
  const { child, ready } = await startServer();
  console.log(`server: ${ready.url}`);
  // Chromium on Linux falls back to "download" for non-ASCII file names under a non-UTF-8 locale (LANG=C).
  const utf8 = /utf-?8/i.test(process.env.LC_ALL || process.env.LANG || '');
  const browser = await chromium.launch(utf8 ? {} : { env: { ...process.env, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8' } });
  try {
    const context = await browser.newContext({ acceptDownloads: true, colorScheme: 'light', viewport: { width: 800, height: 900 } });
    const page = await context.newPage();
    const problems = [];
    page.on('console', (msg) => { if (msg.type() === 'error') problems.push(msg.text()); });
    page.on('pageerror', (error) => problems.push(String(error)));

    console.log('approval gate (N15)');
    const response = await page.goto(ready.url);
    check(response.status() === 200, 'page loads with the token');
    await page.waitForSelector('text=tap Allow', { timeout: 5_000 });
    check(await page.isVisible('#waiting'), 'shows "tap Allow" while the phone decides');
    await page.waitForSelector('#offer:not([hidden])', { timeout: 15_000 });
    const title = await page.textContent('h1');
    check(title === `${ready.sender} wants to send you ${ready.summary}`, `header reads "${title}"`);

    console.log('file list');
    const names = await page.$$eval('#files li a', (links) => links.map((a) => a.textContent));
    check(JSON.stringify(names) === JSON.stringify(ready.files.map((f) => f.name)), `lists ${names.length} files by name`);
    const glyphs = await page.$$eval('#files li .glyph svg', (svgs) => svgs.length);
    check(glyphs === ready.files.length, 'every file has a type glyph');
    const sizes = await page.$$eval('#files li .size', (els) => els.map((e) => e.textContent));
    check(sizes[0] === '22 B' && sizes[2] === '0 B' && sizes[3] === '12 MB', `sizes shown (${sizes.join(', ')})`);
    check((await page.textContent('#all')).startsWith('Download all (zip)'), 'primary button "Download all (zip)"');
    const light = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    check(light === 'rgb(247, 248, 250)', `light background token bg (${light})`);
    await page.screenshot({ path: path.join(outDir, 'light.png'), fullPage: true });

    console.log('keyboard and labels');
    await page.keyboard.press('Tab');
    check(await page.evaluate(() => document.activeElement && document.activeElement.id) === 'all', 'Tab reaches "Download all" first');
    const unnamed = await page.$$eval('button, a[href], input', (els) =>
      els.filter((e) => !(e.getAttribute('aria-label') || e.textContent.trim() || (e.labels && e.labels.length))).length);
    check(unnamed === 0, 'every control has an accessible name');
    check(await page.getAttribute('html', 'lang') === 'en', 'document language set');

    console.log('downloads (fetch + ReadableStream)');
    const hello = await saveDownload(page, () => page.click('#files li:nth-child(1) a'));
    check(hello.name === ready.files[0].name && sha256(hello.bytes) === ready.files[0].sha256, `"${hello.name}" bytes match`);
    const photo = await saveDownload(page, () => page.click('#files li:nth-child(2) a'));
    check(photo.name === ready.files[1].name && sha256(photo.bytes) === ready.files[1].sha256, `unicode name "${photo.name}" bytes match`);
    const empty = await saveDownload(page, () => page.click('#files li:nth-child(3) a'));
    check(empty.bytes.length === 0, 'empty file downloads');

    // Throttle the network so the progress bar and MB/s readout are visible for the 12 MB file.
    const cdp = await context.newCDPSession(page);
    await cdp.send('Network.enable');
    await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: 3_000_000, uploadThroughput: -1 });
    const bigDownload = saveDownload(page, () => page.click('#files li:nth-child(4) a'));
    await page.waitForFunction(() => /^[1-9]\d*\.\d MB\/s/.test(document.getElementById('speed').textContent), null, { timeout: 15_000 });
    const speed = await page.textContent('#speed');
    const bar = await page.getAttribute('#bar', 'aria-valuenow');
    check(await page.isVisible('#progress'), 'progress bar shown while downloading');
    check(/MB\/s( · .+ left)?$/.test(speed), `speed readout "${speed}"`);
    check(Number(bar) > 0 && Number(bar) < 100, `progress ${bar}%`);
    await page.screenshot({ path: path.join(outDir, 'progress.png') });
    const big = await bigDownload;
    await cdp.send('Network.emulateNetworkConditions', { offline: false, latency: 0, downloadThroughput: -1, uploadThroughput: -1 });
    check(sha256(big.bytes) === ready.files[3].sha256, '12 MB file bytes match after the progress run');
    check((await page.textContent('#status')).includes('Received'), 'done message shown');

    console.log('download all (zip)');
    const zip = await saveDownload(page, () => page.click('#all'));
    check(zip.name === ready.archiveName, `zip named "${zip.name}"`);
    check(zip.bytes.length === ready.archiveSize, `zip is exactly ${ready.archiveSize} bytes`);
    const entries = readZip(zip.bytes);
    check(entries.length === ready.files.length, `${entries.length} entries`);
    entries.forEach((entry, i) => {
      const expected = ready.files[i];
      check(entry.name === expected.name && entry.method === 0 && (entry.flags & 0x0808) === 0x0808,
        `entry "${entry.name}" stored with a data descriptor and a UTF-8 name`);
      check(sha256(entry.data) === expected.sha256 && crc32(entry.data) === entry.crc && entry.descriptorOk,
        `entry "${entry.name}" bytes and CRC-32 match`);
    });

    console.log('plain-link fallback (no ReadableStream)');
    const plain = await context.newPage();
    await plain.addInitScript(() => { window.ReadableStream = undefined; });
    await plain.goto(ready.url);
    await plain.waitForSelector('#offer:not([hidden])', { timeout: 15_000 });
    const viaLink = await saveDownload(plain, () => plain.click('#files li:nth-child(5) a'));
    check(viaLink.name === ready.files[4].name && sha256(viaLink.bytes) === ready.files[4].sha256,
      `link download "${viaLink.name}" bytes match`);
    await plain.close();

    console.log('dark mode');
    await page.emulateMedia({ colorScheme: 'dark' });
    const dark = await page.evaluate(() => {
      const body = getComputedStyle(document.body);
      const button = getComputedStyle(document.getElementById('all'));
      return { bg: body.backgroundColor, text: body.color, button: button.backgroundColor, onButton: button.color };
    });
    check(dark.bg === 'rgb(14, 17, 22)', `dark background (${dark.bg})`);
    check(dark.text === 'rgb(236, 239, 243)', `dark text (${dark.text})`);
    check(dark.button === 'rgb(76, 130, 255)' && dark.onButton === 'rgb(14, 17, 22)', 'dark primary button with dark label');
    await page.screenshot({ path: path.join(outDir, 'dark.png'), fullPage: true });
    await page.emulateMedia({ colorScheme: 'light' });

    console.log('send files back (P1)');
    check(await page.isVisible('#upload'), 'drop zone shown when the server takes uploads');
    const back = Buffer.from('Sent back from the computer ✓\n');
    await page.setInputFiles('#upload-input', { name: 'from computer.txt', mimeType: 'text/plain', buffer: back });
    await page.waitForFunction(() => {
      const status = document.querySelector('#uploads li span:last-child');
      return status && status.textContent === 'Sent';
    }, null, { timeout: 15_000 });
    const saved = await readdir(ready.uploadDir);
    check(saved.includes('from computer.txt'), `upload saved (${saved.join(', ')})`);
    const content = await readFile(path.join(ready.uploadDir, 'from computer.txt'));
    check(content.equals(back), 'uploaded bytes match');

    console.log('second browser (N15)');
    const other = await browser.newContext();
    const second = await other.newPage();
    await second.goto(ready.url);
    await second.waitForSelector('#problem:not([hidden])', { timeout: 15_000 });
    const refusal = await second.textContent('#problem-text');
    check(refusal.includes('did not allow'), `second browser refused: "${refusal}"`);
    check(!(await second.isVisible('#offer')), 'second browser sees no files');
    const wrong = await second.goto(ready.url.replace(/\/t\/[^/]+\//, '/t/wrongtoken00/'));
    check(wrong.status() === 404, 'a wrong token is 404');
    await other.close();

    check(problems.length === 0, `no console errors or CSP violations${problems.length ? ': ' + problems.join(' | ') : ''}`);
    await context.close();
  } finally {
    await browser.close();
    await stopServer(child);
  }
}

run().then(
  () => {
    console.log(failures === 0 ? '\nE2E PASSED' : `\nE2E FAILED (${failures} checks)`);
    process.exit(failures === 0 ? 0 : 1);
  },
  (error) => {
    console.error(error);
    process.exit(2);
  },
);
