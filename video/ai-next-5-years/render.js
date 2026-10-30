// Renders scene.html frame by frame and pipes JPEG frames into ffmpeg.
const { chromium } = require('playwright');
const { spawn } = require('child_process');
const fs = require('fs'), path = require('path');

const [proj, out, onlyFrame] = process.argv.slice(2);
const FPS = 24;
(async () => {
  const tl = JSON.parse(fs.readFileSync(path.join(proj, 'build/timeline.json')));
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1080, height: 1920 } });
  await page.goto('file://' + path.resolve(proj, 'scene.html'));
  await page.evaluate(tl => window.setup(tl), tl);
  if (onlyFrame !== undefined) {               // preview a single time in seconds
    await page.evaluate(t => window.render(t), Number(onlyFrame));
    await page.screenshot({ path: out, type: 'jpeg', quality: 90 });
    return browser.close();
  }
  const ff = spawn('ffmpeg', ['-y', '-v', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'mjpeg',
    '-i', '-', '-c:v', 'libx264', '-preset', 'medium', '-crf', '16', '-pix_fmt', 'yuv420p', out], { stdio: ['pipe', 'inherit', 'inherit'] });
  const n = Math.ceil(tl.duration * FPS);
  for (let f = 0; f < n; f++) {
    await page.evaluate(t => window.render(t), f / FPS);
    const buf = await page.screenshot({ type: 'jpeg', quality: 95 });
    if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
    if (f % 120 === 0) console.log(`frame ${f}/${n}`);
  }
  ff.stdin.end();
  await new Promise(r => ff.on('close', r));
  await browser.close();
})();
