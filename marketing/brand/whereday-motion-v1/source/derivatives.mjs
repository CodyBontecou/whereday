import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { readFile, writeFile, stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = path.join(root, 'exports');
async function ffmpeg(args) {
  const process = spawn('ffmpeg', ['-y', '-loglevel', 'error', ...args], { stdio: 'inherit' });
  const [code] = await once(process, 'close');
  if (code) throw new Error(`ffmpeg exited with code ${code}`);
}
await ffmpeg(['-i', path.join(out, 'whereday-header-3840x1646.mp4'), '-an',
  '-vf', 'scale=1920:823:flags=lanczos,crop=1920:822', '-c:v', 'libx264', '-preset', 'slow', '-crf', '15',
  '-pix_fmt', 'yuv420p', '-color_range', 'tv', '-movflags', '+faststart', path.join(out, 'whereday-x.mp4')]);
const palette = ',split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle';
await ffmpeg(['-i', path.join(out, 'whereday-header-3840x1646.mp4'), '-filter_complex',
  'fps=12,scale=960:-1:flags=lanczos' + palette, '-loop', '0', path.join(out, 'whereday-loop.gif')]);
await ffmpeg(['-i', path.join(out, 'whereday-search-1920x1280.mp4'), '-filter_complex',
  'fps=12,scale=800:500:force_original_aspect_ratio=decrease:flags=lanczos,pad=800:500:(ow-iw)/2:(oh-ih)/2:color=0xf8f5eb' + palette,
  '-loop', '0', path.join(out, 'whereday-800x500.gif')]);
const manifestPath = path.join(out, 'manifest.json');
const manifest = JSON.parse(await readFile(manifestPath, 'utf8'));
for (const [file, width, height, fps] of [['whereday-x.mp4', 1920, 822, 30], ['whereday-loop.gif', 960, 412, 12], ['whereday-800x500.gif', 800, 500, 12]]) {
  manifest.assets = manifest.assets.filter(asset => asset.file !== file);
  manifest.assets.push({ file, width, height, fps, duration: 16, bytes: (await stat(path.join(out, file))).size });
}
await writeFile(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
console.log('Social MP4 and both GIF derivatives exported.');
