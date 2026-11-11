/* Build scene.html for a video from its timings.json, placing the finance cast
 * and choreographing them per scene. Deterministic: window.seek(t) fully
 * determines the frame at time t (seconds). */
const fs = require('fs');
const path = require('path');
const { CAST, PALETTE } = require('./characters.js');

const vdir = process.argv[2];
const timings = JSON.parse(fs.readFileSync(path.join(vdir, 'timings.json'), 'utf8'));

// gold coin for the "coin pool"
const coin = (id) => `<g id="${id}" style="display:none">
  <circle r="30" fill="${PALETTE.goldRim}"/><circle r="25" fill="${PALETTE.gold}"/>
  <text y="10" font-family="Arial Black,Arial" font-size="30" font-weight="900"
        fill="${PALETTE.goldRim}" text-anchor="middle">$</text></g>`;
const coins = Array.from({ length: 14 }, (_, i) => coin('coin' + i)).join('\n');

const html = `<!doctype html><html><head><meta charset="utf-8"><style>
 html,body{margin:0;padding:0;width:1080px;height:1920px;overflow:hidden;
   font-family:'Arial Black',Arial,Helvetica,sans-serif;background:#0d1522}
 #stage{position:relative;width:1080px;height:1920px}
 #glow{position:absolute;inset:0;background:radial-gradient(circle at 50% 42%, #243b55 0%, #0d1522 72%);transition:none}
 #brand{position:absolute;top:54px;width:100%;text-align:center;color:#8fa6c0;
   font-size:30px;letter-spacing:4px;font-weight:800;opacity:.9}
 svg{position:absolute;inset:0}
 #cap{position:absolute;left:60px;right:60px;top:1330px;text-align:center;
   color:#fff;font-size:92px;line-height:1.05;font-weight:900;
   text-shadow:0 6px 0 rgba(0,0,0,.35);transform-origin:50% 50%}
 #cap b{display:block}
 #bar{position:absolute;left:0;top:0;height:10px;background:${PALETTE.gold}}
</style></head><body>
<div id="stage">
  <div id="glow"></div>
  <div id="brand">THE MONEY CREW</div>
  <svg width="1080" height="1920" viewBox="0 0 1080 1920">
    <g id="c-penny" style="display:none">${CAST.PENNY()}</g>
    <g id="c-buck"  style="display:none">${CAST.BUCK()}</g>
    <g id="c-cardy" style="display:none">${CAST.CARDY()}</g>
    <g id="c-piggy" style="display:none">${CAST.PIGGY()}</g>
    <g id="c-vault" style="display:none">${CAST.VAULT()}</g>
    <g id="coins">${coins}</g>
  </svg>
  <div id="cap"></div>
  <div id="bar"></div>
</div>
<script>
const T = ${JSON.stringify(timings)};
const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
const lerp=(a,b,t)=>a+(b-a)*t;
const easeOutBack=t=>{const c=1.70158,c3=c+1;return 1+c3*Math.pow(t-1,3)+c*Math.pow(t-1,2);};
const easeIn=t=>t*t, easeOut=t=>1-Math.pow(1-t,2);

function show(id){const e=document.getElementById(id);e.style.display='block';return e;}
function hide(id){document.getElementById(id).style.display='none';}
function place(id,x,y,s,rot){show(id).setAttribute('transform',
  'translate('+x+','+y+') rotate('+(rot||0)+') scale('+s+')');}
// entrance factor: pop in over first 0.45s of a scene
function enter(localT){return easeOutBack(clamp(localT/0.45,0,1));}

const CH=['c-penny','c-buck','c-cardy','c-piggy','c-vault'];
const NCOIN=14;
function hideAllCoins(){for(let i=0;i<NCOIN;i++)document.getElementById('coin'+i).style.display='none';}
function coinAt(i,x,y,s,op){const e=document.getElementById('coin'+i);e.style.display='block';
  e.setAttribute('transform','translate('+x+','+y+') scale('+(s||1)+')');
  e.setAttribute('opacity',op==null?1:op);}

function sceneAt(t){let acc=0;for(let i=0;i<T.scenes.length;i++){const s=T.scenes[i];
  if(t<acc+s.total||i===T.scenes.length-1)return {i,s,localT:t-acc};acc+=s.total;}}

function setCap(text,accent,pop){
  const cap=document.getElementById('cap');
  cap.style.color=accent;
  cap.innerHTML=text.split('\\n').map(l=>'<b>'+l+'</b>').join('');
  const sc=lerp(0.6,1,clamp(pop,0,1));
  cap.style.opacity=clamp(pop*1.4,0,1);
  cap.style.transform='scale('+sc+')';
  document.getElementById('glow').style.background=
    'radial-gradient(circle at 50% 40%, '+accent+'22 0%, #0d1522 70%)';
}

window.seek=function(t){
  t=clamp(t,0,T.total-0.001);
  CH.forEach(hide); hideAllCoins();
  const {i,s,localT}=sceneAt(t);
  const en=enter(localT), bob=Math.sin(t*3)*14, p=clamp(localT/s.total,0,1);
  document.getElementById('bar').style.width=(t/T.total*1080)+'px';
  const slideY=(1-en)*50;
  setCap(s.caption, s.accent, clamp(localT/0.4,0,1)*clamp((s.total-localT)/0.3,0,1));

  if(s.id==='hook'){
    place('c-penny',540,820+bob+slideY,1.15*en,Math.sin(t*2)*2);
  }
  else if(s.id==='liability'){
    place('c-buck',300,820+Math.sin(t*3)*10+slideY,0.72*en,0);
    const greed=lerp(0.8,1.02,easeOut(p));              // Cardy swells
    place('c-cardy',800,800+Math.sin(t*3.4)*8,0.95*greed*en,Math.sin(t*2)*3);
    // a coin flies Buck -> Cardy twice
    const cyc=(localT*0.9)%1, fly=easeIn(cyc);
    coinAt(0, lerp(360,720,fly), lerp(770,780,fly)-Math.sin(fly*Math.PI)*120, 1, 1-easeIn(Math.max(0,cyc-0.7)/0.3));
  }
  else if(s.id==='asset'){
    place('c-buck',290,830+Math.sin(t*3)*10+slideY,0.68*en,0);
    const grow=lerp(0.78,0.9,easeOut(p));
    place('c-piggy',720,840-easeOut(p)*20,grow*en,0);
    // coins drop from Buck into Piggy then multiply above piggy
    const cyc=(localT*0.8)%1, fly=easeIn(cyc);
    coinAt(0, lerp(360,700,fly), lerp(790,740,fly)-Math.sin(fly*Math.PI)*110,1,1-easeIn(Math.max(0,cyc-0.75)/0.25));
    const grown=Math.floor(p*5);
    for(let k=0;k<grown;k++){const a=k*1.25; coinAt(1+k, 700+Math.cos(a)*90, 700-k*18-Math.abs(Math.sin(t*2+k))*8, 0.8, 0.9);}
  }
  else if(s.id==='compound'){
    place('c-penny',540,760+bob+slideY,0.9*en,Math.sin(t*2)*2);
    // multiplying coins doubling across the screen, rising
    const n=Math.min(NCOIN, 1+Math.floor(p*NCOIN));
    for(let k=0;k<n;k++){
      const col=k%5, row=Math.floor(k/5);
      const x=240+col*150, baseY=1080+row*4;
      const popT=clamp((p*NCOIN-k),0,1);
      const y=baseY - easeOut(popT)*60 - (Math.sin(t*2+k)*6);
      coinAt(k, x, y, 0.7+easeOutBack(popT)*0.3, popT);
    }
  }
  else if(s.id==='cta'){
    // the whole crew lined up, Penny front and centre
    place('c-vault',165,910+slideY,0.46*en,0);
    place('c-buck',345,910+Math.sin(t*3)*6+slideY,0.46*en,0);
    place('c-cardy',735,910+slideY,0.46*en,0);
    place('c-piggy',915,910+slideY,0.46*en,0);
    place('c-penny',540,790+bob+slideY,1.0*en,Math.sin(t*4)*3);
    // switch caption in the final stretch
    if(localT > s.total-2.6){
      setCap('FOLLOW FOR\\nMORE', s.accent, clamp((localT-(s.total-2.6))/0.4,0,1));
    }
  }
};
window.seek(0);
</script></body></html>`;

fs.writeFileSync(path.join(vdir, 'scene.html'), html);
console.log('scene.html written (' + html.length + ' bytes)');
