import { createRequire } from 'node:module';
import { mkdir, readFile, writeFile, stat } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import os from 'node:os';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const localRequire = createRequire(import.meta.url);
function dependency(name) {
  try { return localRequire(name); }
  catch {
    const bundled = process.env.CODEX_NODE_MODULES || path.join(os.homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules');
    return localRequire(path.join(bundled, name));
  }
}
const { chromium } = dependency('playwright');
const sharp = dependency('sharp');
const base = process.env.WHEREDAY_PREVIEW_URL || 'http://127.0.0.1:8863';
const stillsOnly = process.argv.includes('--stills');
const fps = 30, duration = 16;
const specs = [
  { format: 'header', width: 3840, height: 1646 },
  { format: 'search', width: 1920, height: 1280 },
];
await mkdir(path.join(root, 'exports'), { recursive: true });
await mkdir(path.join(root, 'review'), { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', chromiumSandbox: true, headless: true });
const manifest = {
  concept: 'A day, drawn together', duration, fps, audio: false,
  source: 'Original generated illustration; separately authored SVG route and markers.',
  generatedWith: 'Built-in image_gen tool',
  specifications: 'https://developer.apple.com/help/app-store-connect/reference/app-information/creative-assets-specifications',
  approved: false, uploaded: false, assets: [], checks: [],
};
try {
  for (const spec of specs) {
    const { format, width, height } = spec;
    const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(`${base}/source/scene.html?format=${format}&capture`, { waitUntil: 'networkidle' });
    await page.waitForFunction(() => window.__ready);
    const capture = async (time, still = false) => {
      await page.evaluate(([time, still]) => window.__seek(time, still), [time, still]);
      return page.screenshot({ type: 'png' });
    };
    const first = await capture(0);
    const seam = await capture(duration);
    const empty = await capture(15.6);
    const still = await capture(12, true);
    const rest = await capture(12);
    const filename = `whereday-${format}-${width}x${height}`;
    await sharp(still).removeAlpha().png().toFile(path.join(root, 'exports', filename + '.png'));
    // Capture PNGs are opaque RGB; never export the alpha-bearing browser buffer.
    const raw = async buffer => sharp(buffer).removeAlpha().raw().toBuffer();
    const firstRaw = await raw(first);
    const compare = async buffer => firstRaw.equals(await raw(buffer));
    for (const [name, buffer] of Object.entries({ first, seam, empty })) await writeFile(path.join(root, 'review', `${format}-${name}.png`), buffer);
    const bounds = await page.evaluate(() => {
      const el = document.getElementById('journey');
      const box = el.getBBox();
      const matrix = el.getScreenCTM();
      const a = new DOMPoint(box.x - 40, box.y - 40).matrixTransform(matrix);
      const b = new DOMPoint(box.x + box.width + 40, box.y + box.height + 40).matrixTransform(matrix);
      return { left: Math.floor(a.x), top: Math.floor(a.y), right: Math.ceil(b.x), bottom: Math.ceil(b.y) };
    });
    let outsideChanges = 0;
    const sheetFrames = [];
    for (const t of [0, .9, 3.5, 6.1, 8.4, 10.6, 12, 14.6]) {
      const frame = await capture(t);
      const data = await raw(frame);
      for (let y = 0; y < height; y++) for (let x = 0; x < width; x++) {
        if (x >= bounds.left && x <= bounds.right && y >= bounds.top && y <= bounds.bottom) continue;
        const p = (y * width + x) * 3;
        if (data[p] !== firstRaw[p] || data[p+1] !== firstRaw[p+1] || data[p+2] !== firstRaw[p+2]) outsideChanges++;
      }
      sheetFrames.push(await sharp(frame).resize(480, Math.round(height / width * 480)).png().toBuffer());
    }
    const thumbHeight = Math.round(height / width * 480);
    await sharp({ create: { width: 480 * 4, height: thumbHeight * 2, channels: 3, background: '#f8f5eb' } })
      .composite(sheetFrames.map((input, i) => ({ input, left: (i % 4) * 480, top: Math.floor(i / 4) * thumbHeight })))
      .jpeg({ quality: 95 }).toFile(path.join(root, 'review', format + '-contact-sheet.jpg'));
    const check = {
      format, seamIdentical: await compare(seam), emptyBeatIdentical: await compare(empty),
      restIdenticalToStill: (await raw(still)).equals(await raw(rest)),
      outsideChanges, animatedBounds: bounds, pageErrors: errors,
    };
    manifest.checks.push(check);
    if (!check.seamIdentical || !check.emptyBeatIdentical || !check.restIdenticalToStill || outsideChanges || errors.length) {
      throw new Error(`Visual verification failed: ${JSON.stringify(check)}`);
    }
    manifest.assets.push({ file: filename + '.png', width, height, bytes: (await stat(path.join(root, 'exports', filename + '.png'))).size });
    console.log(`${format}: still, seam, rest, empty beat, and fixed background verified.`);
    if (!stillsOnly) {
      // Seek once per frame. Feed exact screenshots to the encoder; never record real time.
      const mp4 = path.join(root, 'exports', filename + '.mp4');
      const ffmpeg = spawn('ffmpeg', [
        '-y', '-loglevel', 'error', '-f', 'image2pipe', '-vcodec', 'mjpeg', '-framerate', String(fps), '-i', '-',
        '-an', '-vf', 'scale=in_range=full:out_range=limited,format=yuv420p',
        '-c:v', 'libx264', '-preset', 'medium', '-crf', '15', '-pix_fmt', 'yuv420p', '-color_range', 'tv',
        '-r', String(fps), '-color_primaries', 'bt709', '-color_trc', 'bt709', '-colorspace', 'bt709',
        '-movflags', '+faststart', mp4,
      ], { stdio: ['pipe', 'inherit', 'inherit'] });
      const finished = once(ffmpeg, 'close');
      ffmpeg.stdin.on('error', () => {});
      for (let frame = 0; frame < duration * fps; frame++) {
        await page.evaluate(t => window.__seek(t), frame / fps);
        const jpeg = await page.screenshot({ type: 'jpeg', quality: 98 });
        if (!ffmpeg.stdin.write(jpeg)) await once(ffmpeg.stdin, 'drain');
        if (frame % 120 === 0) console.log(`${format}: ${frame}/${duration * fps} frames`);
      }
      ffmpeg.stdin.end();
      const [code] = await finished;
      if (code !== 0) throw new Error(`ffmpeg failed with exit code ${code}`);
      manifest.assets.push({ file: filename + '.mp4', width, height, duration, fps, bytes: (await stat(mp4)).size });
    }
    await page.close();
  }
  await writeFile(path.join(root, 'exports', 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
  console.log('Exports saved to ' + path.join(root, 'exports'));
} finally { await browser.close(); }
