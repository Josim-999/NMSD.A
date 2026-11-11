/* Render every frame of a video's scene.html to work/frames/NNNN.png
 * Usage: node render.js <video_dir> */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const vdir = path.resolve(process.argv[2]);
  const timings = JSON.parse(fs.readFileSync(path.join(vdir, 'timings.json'), 'utf8'));
  const fps = timings.fps, total = timings.total;
  const nframes = Math.ceil(total * fps);
  const framesDir = path.join(vdir, 'work', 'frames');
  fs.rmSync(framesDir, { recursive: true, force: true });
  fs.mkdirSync(framesDir, { recursive: true });

  const browser = await chromium.launch({ args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
  await page.goto('file://' + path.join(vdir, 'scene.html'));
  for (let f = 0; f < nframes; f++) {
    await page.evaluate((t) => window.seek(t), f / fps);
    await page.screenshot({ path: path.join(framesDir, String(f).padStart(4, '0') + '.png') });
    if (f % 60 === 0) process.stdout.write(`\r  frame ${f}/${nframes}`);
  }
  await browser.close();
  console.log(`\n  rendered ${nframes} frames @ ${fps}fps`);
})();
