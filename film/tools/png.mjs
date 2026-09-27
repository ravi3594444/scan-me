// tiny PNG decode via the browser-free zlib path (RGBA 8-bit, non-interlaced) for frame differencing
import zlib from 'node:zlib';
export function PNG(buf) {
  let p = 8, w, h, ct, idat = [];
  while (p < buf.length) { const len = buf.readUInt32BE(p), type = buf.toString('ascii', p + 4, p + 8), data = buf.subarray(p + 8, p + 8 + len);
    if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); ct = data[9]; } else if (type === 'IDAT') idat.push(data); p += 12 + len; }
  const bpp = ct === 6 ? 4 : 3, raw = zlib.inflateSync(Buffer.concat(idat)), stride = w * bpp, out = Buffer.alloc(h * stride);
  for (let y = 0; y < h; y++) { const ft = raw[y * (stride + 1)], line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)), o = y * stride;
    for (let x = 0; x < stride; x++) { const a = x >= bpp ? out[o + x - bpp] : 0, bb = y ? out[o - stride + x] : 0, c = x >= bpp && y ? out[o - stride + x - bpp] : 0; let v = line[x];
      if (ft === 1) v += a; else if (ft === 2) v += bb; else if (ft === 3) v += (a + bb) >> 1; else if (ft === 4) { const pp = a + bb - c, pa = Math.abs(pp - a), pb = Math.abs(pp - bb), pc = Math.abs(pp - c); v += pa <= pb && pa <= pc ? a : pb <= pc ? bb : c; }
      out[o + x] = v & 255; } }
  return { w, h, bpp, data: out, diff(o2) { let s = 0; const n = this.data.length; for (let i = 0; i < n; i += 4) s += Math.abs(this.data[i] - o2.data[i]) + Math.abs(this.data[i + 1] - o2.data[i + 1]); return s / (n / 4) / 2; } };
}
