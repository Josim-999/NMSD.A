const { chromium } = require('playwright');
const { CAST, PALETTE } = require('./characters.js');
const path = require('path');

const html = `<!doctype html><html><head><meta charset="utf-8"><style>
 html,body{margin:0;width:1080px;height:1920px;overflow:hidden;font-family:Arial,Helvetica,sans-serif}
 #bg{width:1080px;height:1920px;background:
   radial-gradient(circle at 50% 18%, ${PALETTE.night2} 0%, ${PALETTE.night1} 70%);}
 .title{position:absolute;top:70px;width:100%;text-align:center;color:${PALETTE.gold};
   font-size:76px;font-weight:900;letter-spacing:1px;text-shadow:0 4px 0 rgba(0,0,0,.4)}
 .sub{position:absolute;top:180px;width:100%;text-align:center;color:#cdd7e5;font-size:34px}
 .label{fill:#fff;font-size:30px;font-weight:800;text-anchor:middle}
 .role{fill:#9fb3c8;font-size:22px;text-anchor:middle}
</style></head><body>
<div id="bg">
 <div class="title">THE MONEY CREW</div>
 <div class="sub">Your finance cast — in every video</div>
 <svg width="1080" height="1920" viewBox="0 0 1080 1920">
   <g transform="translate(300,520) scale(0.95)">${CAST.PENNY()}</g>
   <text class="label" x="300" y="770">PENNY</text><text class="role" x="300" y="806">the host · gold coin</text>

   <g transform="translate(780,520) scale(0.8)">${CAST.VAULT()}</g>
   <text class="label" x="780" y="770">VAULT</text><text class="role" x="780" y="806">assets · stored wealth</text>

   <g transform="translate(300,1080) scale(0.8)">${CAST.BUCK()}</g>
   <text class="label" x="300" y="1300">BUCK</text><text class="role" x="300" y="1336">income · cash flow</text>

   <g transform="translate(780,1080) scale(0.9)">${CAST.CARDY()}</g>
   <text class="label" x="780" y="1300">CARDY</text><text class="role" x="780" y="1336">debt · the trap</text>

   <g transform="translate(540,1560) scale(0.82)">${CAST.PIGGY()}</g>
   <text class="label" x="540" y="1740">PIGGY</text><text class="role" x="540" y="1776">saving · compounding</text>
 </svg>
</div></body></html>`;

(async () => {
  const browser = await chromium.launch({ args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 2 });
  await page.setContent(html);
  await page.screenshot({ path: path.join(__dirname, '..', 'assets', 'cast_lineup.png') });
  await browser.close();
  console.log('lineup rendered');
})();
