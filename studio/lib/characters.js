/*
 * NMSD.A — Finance Character Cast (reusable vector characters)
 * ------------------------------------------------------------
 * Every function returns an SVG <g> string for one character, drawn centred
 * around (0,0) inside roughly a 300 x 380 box (feet near y=+180, head near y=-180).
 * Position/scale a character in a scene by wrapping it:
 *     `<g transform="translate(540,900) scale(1.1)">${PENNY()}</g>`
 *
 * Each character exposes stable element ids (prefixed by its name) so a scene's
 * seek(t) can animate eyes/arms/mouth. Pass an id prefix suffix via `k` when you
 * place the SAME character twice in one scene, to keep ids unique.
 *
 * Cast:
 *   PENNY  — gold coin, the HOST / narrator (appears in every video)
 *   VAULT  — green bank safe, calm mentor = assets / stored wealth
 *   BUCK   — dollar bill, energetic = income / cash flow
 *   CARDY  — credit card, sly = debt / liabilities (the trap)
 *   PIGGY  — piggy bank, cheerful = saving & compounding
 *
 * Shared palette is in PALETTE for scene backgrounds/captions.
 */

const PALETTE = {
  ink:    '#1b2330',
  gold:   '#f7c948',
  goldHi: '#ffe08a',
  goldRim:'#c6950c',
  green:  '#2f9e6f',
  greenDk:'#1d7a53',
  bill:   '#4caf7d',
  billHi: '#dff3e6',
  indigo: '#6c5ce7',
  indigoDk:'#4834b5',
  pink:   '#ff9ec4',
  pinkDk: '#e56a9e',
  cream:  '#fff7e6',
  night1: '#141e30',
  night2: '#243b55',
};

// small helper: a friendly eye pair with pupils that an animator can move
function eyes(pfx, cx, cy, gap, r, pr) {
  return `
    <circle cx="${cx-gap}" cy="${cy}" r="${r}" fill="#fff" stroke="${PALETTE.ink}" stroke-width="3"/>
    <circle cx="${cx+gap}" cy="${cy}" r="${r}" fill="#fff" stroke="${PALETTE.ink}" stroke-width="3"/>
    <circle id="${pfx}-pL" cx="${cx-gap}" cy="${cy}" r="${pr}" fill="${PALETTE.ink}"/>
    <circle id="${pfx}-pR" cx="${cx+gap}" cy="${cy}" r="${pr}" fill="${PALETTE.ink}"/>
    <rect id="${pfx}-lidL" x="${cx-gap-r-2}" y="${cy-r-2}" width="${2*r+4}" height="0" rx="3" fill="transparent"/>
    <rect id="${pfx}-lidR" x="${cx+gap-r-2}" y="${cy-r-2}" width="${2*r+4}" height="0" rx="3" fill="transparent"/>`;
}

function PENNY(k = '') {
  const p = 'penny' + k;
  return `<g id="${p}">
    <ellipse cx="0" cy="186" rx="96" ry="20" fill="rgba(0,0,0,0.20)"/>
    <!-- legs -->
    <rect x="-34" y="120" width="20" height="60" rx="10" fill="${PALETTE.goldRim}"/>
    <rect x="14" y="120" width="20" height="60" rx="10" fill="${PALETTE.goldRim}"/>
    <ellipse cx="-24" cy="184" rx="26" ry="14" fill="${PALETTE.ink}"/>
    <ellipse cx="24" cy="184" rx="26" ry="14" fill="${PALETTE.ink}"/>
    <!-- coin body -->
    <circle cx="0" cy="20" r="132" fill="${PALETTE.goldRim}"/>
    <circle cx="0" cy="20" r="120" fill="${PALETTE.gold}"/>
    <circle cx="0" cy="20" r="120" fill="none" stroke="${PALETTE.goldHi}" stroke-width="6" stroke-dasharray="6 10" opacity="0.7"/>
    <circle cx="0" cy="20" r="96" fill="none" stroke="${PALETTE.goldRim}" stroke-width="4" opacity="0.5"/>
    <text x="0" y="-54" font-family="Arial Black, Arial" font-size="56" font-weight="900" fill="${PALETTE.goldRim}" opacity="0.35" text-anchor="middle">$</text>
    <!-- arms -->
    <path id="${p}-armL" d="M-118 30 Q-168 40 -176 92" stroke="${PALETTE.goldRim}" stroke-width="16" fill="none" stroke-linecap="round"/>
    <path id="${p}-armR" d="M118 30 Q168 20 182 -22" stroke="${PALETTE.goldRim}" stroke-width="16" fill="none" stroke-linecap="round"/>
    <circle cx="-178" cy="96" r="16" fill="#fff" stroke="${PALETTE.ink}" stroke-width="3"/>
    <circle id="${p}-handR" cx="184" cy="-26" r="16" fill="#fff" stroke="${PALETTE.ink}" stroke-width="3"/>
    <!-- face -->
    ${eyes(p, 0, 6, 34, 22, 10)}
    <path d="M-16 -16 Q-34 -6 -40 -24" stroke="${PALETTE.ink}" stroke-width="5" fill="none" stroke-linecap="round"/>
    <path d="M16 -16 Q34 -6 40 -24" stroke="${PALETTE.ink}" stroke-width="5" fill="none" stroke-linecap="round"/>
    <path id="${p}-mouth" d="M-30 54 Q0 92 30 54" stroke="${PALETTE.ink}" stroke-width="8" fill="none" stroke-linecap="round"/>
    <circle cx="-54" cy="46" r="12" fill="#ff7a7a" opacity="0.5"/>
    <circle cx="54" cy="46" r="12" fill="#ff7a7a" opacity="0.5"/>
  </g>`;
}

function VAULT(k = '') {
  const p = 'vault' + k;
  return `<g id="${p}">
    <ellipse cx="0" cy="190" rx="110" ry="20" fill="rgba(0,0,0,0.20)"/>
    <rect x="-120" y="-120" width="240" height="300" rx="34" fill="${PALETTE.greenDk}"/>
    <rect x="-120" y="-120" width="240" height="300" rx="34" fill="none" stroke="#10543a" stroke-width="8"/>
    <rect x="-96" y="-96" width="192" height="252" rx="22" fill="${PALETTE.green}"/>
    <!-- feet -->
    <rect x="-86" y="176" width="40" height="26" rx="8" fill="${PALETTE.ink}"/>
    <rect x="46" y="176" width="40" height="26" rx="8" fill="${PALETTE.ink}"/>
    <!-- dial (belly) -->
    <circle cx="20" cy="96" r="46" fill="#0f4a33"/>
    <circle cx="20" cy="96" r="38" fill="#bfe8d4"/>
    <g id="${p}-dial"><rect x="16" y="66" width="8" height="34" rx="4" fill="#0f4a33"/></g>
    <circle cx="20" cy="96" r="6" fill="#0f4a33"/>
    <!-- handle -->
    <rect x="-86" y="86" width="22" height="22" rx="6" fill="#0f4a33"/>
    <!-- face -->
    ${eyes(p, 0, -2, 40, 24, 11)}
    <path d="M-20 -30 L-50 -38" stroke="${PALETTE.ink}" stroke-width="6" stroke-linecap="round"/>
    <path d="M20 -30 L50 -38" stroke="${PALETTE.ink}" stroke-width="6" stroke-linecap="round"/>
    <path id="${p}-mouth" d="M-26 46 Q0 70 26 46" stroke="${PALETTE.ink}" stroke-width="8" fill="none" stroke-linecap="round"/>
    <circle cx="-58" cy="36" r="12" fill="#2bd49b" opacity="0.5"/>
    <circle cx="58" cy="36" r="12" fill="#2bd49b" opacity="0.5"/>
  </g>`;
}

function BUCK(k = '') {
  const p = 'buck' + k;
  return `<g id="${p}">
    <ellipse cx="0" cy="150" rx="120" ry="18" fill="rgba(0,0,0,0.18)"/>
    <!-- arms behind -->
    <path id="${p}-armL" d="M-150 10 Q-196 30 -192 76" stroke="${PALETTE.greenDk}" stroke-width="14" fill="none" stroke-linecap="round"/>
    <path id="${p}-armR" d="M150 10 Q196 -6 206 -44" stroke="${PALETTE.greenDk}" stroke-width="14" fill="none" stroke-linecap="round"/>
    <circle cx="-192" cy="80" r="15" fill="#fff" stroke="${PALETTE.ink}" stroke-width="3"/>
    <circle id="${p}-handR" cx="208" cy="-48" r="15" fill="#fff" stroke="${PALETTE.ink}" stroke-width="3"/>
    <!-- bill body -->
    <rect x="-150" y="-96" width="300" height="200" rx="18" fill="${PALETTE.greenDk}"/>
    <rect x="-140" y="-86" width="280" height="180" rx="14" fill="${PALETTE.bill}"/>
    <rect x="-118" y="-64" width="236" height="136" rx="10" fill="none" stroke="${PALETTE.billHi}" stroke-width="4" opacity="0.7"/>
    <text x="-116" y="-40" font-family="Arial Black, Arial" font-size="40" font-weight="900" fill="${PALETTE.billHi}" opacity="0.8">$</text>
    <text x="104" y="86" font-family="Arial Black, Arial" font-size="40" font-weight="900" fill="${PALETTE.billHi}" opacity="0.8" text-anchor="end">$</text>
    <!-- centre portrait oval holds the face -->
    <ellipse cx="0" cy="4" rx="78" ry="90" fill="${PALETTE.billHi}"/>
    <ellipse cx="0" cy="4" rx="78" ry="90" fill="none" stroke="${PALETTE.greenDk}" stroke-width="4"/>
    ${eyes(p, 0, -8, 30, 19, 9)}
    <path id="${p}-mouth" d="M-26 36 Q0 62 26 36" stroke="${PALETTE.ink}" stroke-width="7" fill="none" stroke-linecap="round"/>
    <circle cx="-44" cy="30" r="10" fill="#ff8f8f" opacity="0.5"/>
    <circle cx="44" cy="30" r="10" fill="#ff8f8f" opacity="0.5"/>
    <!-- feet -->
    <rect x="-56" y="104" width="22" height="46" rx="10" fill="${PALETTE.greenDk}"/>
    <rect x="34" y="104" width="22" height="46" rx="10" fill="${PALETTE.greenDk}"/>
    <ellipse cx="-46" cy="150" rx="26" ry="12" fill="${PALETTE.ink}"/>
    <ellipse cx="44" cy="150" rx="26" ry="12" fill="${PALETTE.ink}"/>
  </g>`;
}

function CARDY(k = '') {
  const p = 'cardy' + k;
  return `<g id="${p}">
    <ellipse cx="0" cy="150" rx="115" ry="18" fill="rgba(0,0,0,0.20)"/>
    <!-- arms -->
    <path id="${p}-armL" d="M-140 0 Q-186 16 -182 64" stroke="${PALETTE.indigoDk}" stroke-width="13" fill="none" stroke-linecap="round"/>
    <path id="${p}-armR" d="M140 0 Q188 -14 198 -50" stroke="${PALETTE.indigoDk}" stroke-width="13" fill="none" stroke-linecap="round"/>
    <circle cx="-182" cy="68" r="14" fill="#fff" stroke="${PALETTE.ink}" stroke-width="3"/>
    <circle id="${p}-handR" cx="200" cy="-54" r="14" fill="#fff" stroke="${PALETTE.ink}" stroke-width="3"/>
    <!-- card body -->
    <rect x="-140" y="-92" width="280" height="196" rx="22" fill="${PALETTE.indigoDk}"/>
    <rect x="-130" y="-82" width="260" height="176" rx="18" fill="${PALETTE.indigo}"/>
    <rect x="-130" y="-54" width="260" height="26" fill="${PALETTE.indigoDk}" opacity="0.6"/>
    <!-- gold chip -->
    <rect x="-104" y="6" width="54" height="42" rx="8" fill="${PALETTE.gold}" stroke="${PALETTE.goldRim}" stroke-width="3"/>
    <line x1="-104" y1="27" x2="-50" y2="27" stroke="${PALETTE.goldRim}" stroke-width="2"/>
    <line x1="-77" y1="6" x2="-77" y2="48" stroke="${PALETTE.goldRim}" stroke-width="2"/>
    <text x="118" y="86" font-family="monospace" font-size="20" fill="#cfc8ff" text-anchor="end" opacity="0.8">1337</text>
    <!-- sly face -->
    ${eyes(p, 30, -18, 30, 18, 8)}
    <path d="M12 -40 Q30 -50 50 -42" stroke="${PALETTE.ink}" stroke-width="5" fill="none" stroke-linecap="round"/>
    <path id="${p}-mouth" d="M8 24 Q40 44 74 20" stroke="${PALETTE.ink}" stroke-width="7" fill="none" stroke-linecap="round"/>
  </g>`;
}

function PIGGY(k = '') {
  const p = 'piggy' + k;
  return `<g id="${p}">
    <ellipse cx="0" cy="152" rx="120" ry="20" fill="rgba(0,0,0,0.20)"/>
    <!-- legs -->
    <rect x="-78" y="96" width="34" height="56" rx="14" fill="${PALETTE.pinkDk}"/>
    <rect x="44" y="96" width="34" height="56" rx="14" fill="${PALETTE.pinkDk}"/>
    <!-- body -->
    <ellipse cx="0" cy="14" rx="150" ry="118" fill="${PALETTE.pink}"/>
    <ellipse cx="0" cy="14" rx="150" ry="118" fill="none" stroke="${PALETTE.pinkDk}" stroke-width="5"/>
    <!-- coin slot -->
    <rect x="-34" y="-96" width="68" height="14" rx="7" fill="${PALETTE.pinkDk}"/>
    <!-- ears -->
    <path d="M-120 -70 L-70 -108 L-62 -56 Z" fill="${PALETTE.pink}" stroke="${PALETTE.pinkDk}" stroke-width="4"/>
    <path d="M120 -70 L70 -108 L62 -56 Z" fill="${PALETTE.pink}" stroke="${PALETTE.pinkDk}" stroke-width="4"/>
    <!-- snout -->
    <ellipse cx="0" cy="44" rx="56" ry="40" fill="${PALETTE.pinkDk}"/>
    <circle cx="-20" cy="44" r="9" fill="#7a2f52"/>
    <circle cx="20" cy="44" r="9" fill="#7a2f52"/>
    <!-- face -->
    ${eyes(p, 0, -20, 34, 20, 9)}
    <path id="${p}-mouth" d="M-30 92 Q0 112 30 92" stroke="${PALETTE.pinkDk}" stroke-width="7" fill="none" stroke-linecap="round"/>
    <circle cx="-72" cy="34" r="14" fill="#ff6fa8" opacity="0.5"/>
    <circle cx="72" cy="34" r="14" fill="#ff6fa8" opacity="0.5"/>
    <!-- coin dropping (animatable) -->
    <circle id="${p}-coin" cx="0" cy="-150" r="20" fill="${PALETTE.gold}" stroke="${PALETTE.goldRim}" stroke-width="4"/>
  </g>`;
}

const CAST = { PENNY, VAULT, BUCK, CARDY, PIGGY };
if (typeof module !== 'undefined') module.exports = { CAST, PALETTE, PENNY, VAULT, BUCK, CARDY, PIGGY };
