/*
 * Generates the shipping launcher icon from the same geometry the design labs
 * use, so the art can never drift from what was signed off in icon-v2-lab.html.
 *
 *   node docs/design/build-launcher-icon.mjs
 *
 * Writes:
 *   app/src/main/res/drawable/ic_launcher_foreground.xml   (mascot + waves)
 *   app/src/main/res/drawable/ic_launcher_background.xml    (gradient field)
 *   app/src/main/res/drawable/ic_launcher_monochrome.xml    (themed-icon layer)
 *   docs/design/assets/launcher-icon-512.svg                (flattened, for PNG)
 *   docs/design/assets/launcher-icon-monochrome.svg         (preview of the above)
 *
 * The RECIPE below is the signed-off V2 state (Max, 2026-08-01). Changing it
 * here and re-running is the supported way to revise the icon.
 */
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createContext, runInContext } from "node:vm";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = join(HERE, "..", "..");

// icon-art.js is a classic browser script (the labs load it via <script src>).
// Run it in a bare context and read the globals it declares, so the shipping
// icon and the labs draw from one copy of the path data.
// (its top-level `const`s live in the script's lexical scope, not on the
// sandbox object, so take the completion value of a trailing expression.)
const { JAR, JAR_IDX, JAR_SILHOUETTE } = runInContext(
  readFileSync(join(HERE, "icon-art.js"), "utf8") + "\n({JAR, JAR_IDX, JAR_SILHOUETTE});",
  createContext({}), { filename: "icon-art.js" });

/* ------------------------------------------------------------------ recipe */
const RECIPE = {
  // layout — LOCKED
  dx: 4, dy: 0, js: 0.78, ws: 1, gap: 5.5, anchor: 60.2, arcs: 3,
  // background — flat fill sampled from the ramp at `depth` (Max, 2026-08-08);
  // angle/spread/curve only apply to the lab's gradient geometries.
  geometry: "flat", angle: 45, depth: 0.34, spread: 0.62, curve: 1,
  ramp: [[0, "#FDBA74"], [0.38, "#F97316"], [1, "#C2410C"]],   // "deep amber" end
  // mascot
  outline: 2.4, outlineCol: "#FFF8EE",
  shadow: 0.18, shDist: 1.6,
  gloss: 0.75, sheen: 0, rim: 0,
  eyeGloss: 1, eyeDepth: 0, eyeStyle: "solid",
};

/* ------------------------------------------------------------- colour maths */
const hex2rgb = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16));
const rgb2hex = c => "#" + c.map(v => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, "0")).join("");
const mix = (a, b, t) => rgb2hex(hex2rgb(a).map((v, i) => v + (hex2rgb(b)[i] - v) * t));
const clamp01 = t => Math.max(0, Math.min(1, t));
function ramp(t) {
  t = clamp01(t);
  const R = RECIPE.ramp;
  for (let i = 0; i < R.length - 1; i++) {
    const [p0, c0] = R[i], [p1, c1] = R[i + 1];
    if (t <= p1) return mix(c0, c1, (t - p0) / (p1 - p0));
  }
  return R[R.length - 1][1];
}
const bgStops = () => Array.from({ length: 5 }, (_, i) => {
  const u = i / 4;
  return { off: u, col: ramp(RECIPE.depth - RECIPE.spread / 2 + RECIPE.spread * Math.pow(u, RECIPE.curve)) };
});

/* ----------------------------------------------------------------- geometry */
const P = i => JAR[i][0];
const JAR_BASE = "#F5A623";
const WBASE = { step: 5, h0: 5.5, dh: 4.5, b0: 5, db: 2.5, sw: 2.2 };
const FADE_OPS = [1, 0.94, 0.86];
const wavesWidth = ws => ((RECIPE.arcs - 1) * WBASE.step + WBASE.b0 + (RECIPE.arcs - 1) * WBASE.db + 1) * ws;

const { dx, dy, js, ws, gap } = RECIPE;
const jarW = 52 * js;
const left = 54 - (jarW + gap + wavesWidth(ws)) / 2 + dx;
const JX = left + jarW / 2, X0 = left + jarW + gap, JY = 54 + dy;
const WAVE_CY = JY + (RECIPE.anchor - 60) * js;

const waves = Array.from({ length: RECIPE.arcs }, (_, i) => {
  const x = X0 + i * WBASE.step * ws, h = (WBASE.h0 + i * WBASE.dh) * ws, b = (WBASE.b0 + i * WBASE.db) * ws;
  return {
    d: `M${x.toFixed(1)},${(WAVE_CY - h).toFixed(1)} Q${(x + b).toFixed(1)},${WAVE_CY.toFixed(1)} ${x.toFixed(1)},${(WAVE_CY + h).toFixed(1)}`,
    op: FADE_OPS[i], sw: +(WBASE.sw * ws).toFixed(2),
  };
});

// Body path bbox — needed to turn the SVG objectBoundingBox gloss gradient into
// the viewport coordinates VectorDrawable wants.
const BODY_BB = { x1: 28.0, y1: 35.3, x2: 79.9, y2: 97.6 };
const bbX = f => BODY_BB.x1 + f * (BODY_BB.x2 - BODY_BB.x1);
const bbY = f => BODY_BB.y1 + f * (BODY_BB.y2 - BODY_BB.y1);

const GLOSS_LITE = mix(JAR_BASE, "#FFFFFF", 0.30 * RECIPE.gloss);
const GLOSS_DARK = mix(JAR_BASE, "#B45309", 0.45 * RECIPE.gloss);

const EYE_C = [[44.6, 60.2], [63.3, 60.2]];
const GLOSS_C = [[46.5, 58.2], [65.2, 58.2]];
const ell = (cx, cy, rx, ry) =>
  `M${(cx - rx).toFixed(2)},${cy} a${rx},${ry} 0 1,0 ${(2 * rx).toFixed(2)},0 a${rx},${ry} 0 1,0 ${(-2 * rx).toFixed(2)},0 Z`;

/* ------------------------------------------------------- VectorDrawable out */
const A = (n, v) => v === undefined || v === null ? "" : ` android:${n}="${v}"`;
function vpath({ d, fill, fillAlpha, stroke, strokeWidth, strokeAlpha, cap, join }, indent = "    ") {
  return `${indent}<path${A("pathData", d)}${A("fillColor", fill ?? "#00000000")}` +
    `${A("fillAlpha", fillAlpha)}${A("strokeColor", stroke)}${A("strokeWidth", strokeWidth)}` +
    `${A("strokeAlpha", strokeAlpha)}${A("strokeLineCap", cap)}${A("strokeLineJoin", join)}/>`;
}
function gradientPath(d, gradXml, indent = "    ") {
  return `${indent}<path${A("pathData", d)}>\n` +
    `${indent}  <aapt:attr name="android:fillColor">\n${gradXml(indent + "    ")}\n` +
    `${indent}  </aapt:attr>\n${indent}</path>`;
}
// Trailing-newline-safe join for a list of already-indented XML lines.
const lines = a => a.join("\n");
const stopsXml = (stops, indent) =>
  stops.map(s => `${indent}  <item android:offset="${s.off}" android:color="${s.col}"/>`).join("\n");

/* ------------------------------------------------------------ mascot builder
 * Shared by the launcher foreground and the in-app mascot drawables, so the
 * jar can only ever be defined once.
 *   outline  sticker ring width (0 = none — the in-app mascot has none)
 *   face     "happy" | "speaking"
 *   indent   base indent, since the launcher nests it inside a <group>
 */
function mascotVPaths({ outline = 0, face = "happy", indent = "    " } = {}) {
  const inner = indent + "  ";
  const out = [];

  // Drop shadow — two offset silhouette passes (VectorDrawable has no blur).
  // Group alpha isn't supported, so the opacity is baked into each fillAlpha.
  if (RECIPE.shadow > 0 && RECIPE.shDist > 0) {
    for (const [k, a] of [[1, 0.55], [0.5, 0.45]]) {
      const tx = (RECIPE.shDist * k * 0.75).toFixed(2), ty = (RECIPE.shDist * k).toFixed(2);
      out.push(`${indent}<group android:translateX="${tx}" android:translateY="${ty}">`);
      for (const d of JAR_SILHOUETTE)
        out.push(vpath({ d, fill: "#3D2B1F", fillAlpha: +(RECIPE.shadow * a).toFixed(3) }, inner));
      out.push(`${indent}</group>`);
    }
  }
  // Sticker outline — silhouette filled and stroked so the pieces union cleanly.
  if (outline > 0) {
    for (const d of JAR_SILHOUETTE)
      out.push(vpath({
        d, fill: RECIPE.outlineCol, stroke: RECIPE.outlineCol,
        strokeWidth: +(outline * 2).toFixed(2), join: "round",
      }, indent));
  }
  // Jar body with the gloss gradient.
  out.push(gradientPath(P(JAR_IDX.body), ind =>
    `${ind}<gradient android:type="linear"` +
    ` android:startX="${bbX(0.1).toFixed(2)}" android:startY="${bbY(0).toFixed(2)}"` +
    ` android:endX="${bbX(0.85).toFixed(2)}" android:endY="${bbY(1).toFixed(2)}">\n` +
    stopsXml([{ off: 0, col: GLOSS_LITE }, { off: 0.45, col: JAR_BASE }, { off: 1, col: GLOSS_DARK }], ind) +
    `\n${ind}</gradient>`, indent));
  out.push(vpath({ d: P(JAR_IDX.marmalade), fill: "#D4831E", fillAlpha: 0.5 }, indent));
  out.push(vpath({ d: P(JAR_IDX.shine), fill: "#FFFFFF", fillAlpha: +(0.15 + 0.30 * RECIPE.sheen).toFixed(3) }, indent));
  out.push(vpath({ d: P(JAR_IDX.neck), fill: "#E8D5B0" }, indent));
  out.push(vpath({ d: P(JAR_IDX.lid), fill: "#FFF8EE", stroke: "#D4C4A0", strokeWidth: 0.4 }, indent));
  out.push(vpath({ d: P(JAR_IDX.ridge), fill: "#FFF5E6", stroke: "#D4C4A0", strokeWidth: 0.3 }, indent));
  for (const i of JAR_IDX.lidLines)
    out.push(vpath({ d: P(i), stroke: "#E0D0B0", strokeWidth: 0.2, strokeAlpha: 0.5 }, indent));
  out.push(vpath({ d: P(JAR_IDX.label), fill: "#FFF8EE", stroke: "#D4B896", strokeWidth: 0.3 }, indent));
  for (const i of JAR_IDX.labelLines)
    out.push(vpath({ d: P(i), stroke: "#E8C9A0", strokeWidth: 0.2 }, indent));
  out.push(vpath({ d: P(JAR_IDX.slice), fill: JAR_BASE, stroke: "#D4831E", strokeWidth: 0.3 }, indent));
  for (const i of JAR_IDX.sliceLines)
    out.push(vpath({ d: P(i), stroke: "#FFF5E6", strokeWidth: 0.2, strokeAlpha: 0.6 }, indent));
  for (const [cx, cy] of EYE_C) out.push(vpath({ d: ell(cx, cy, 4.6, 5.4), fill: "#3D2B1F" }, indent));
  for (const [cx, cy] of GLOSS_C)
    out.push(vpath({ d: ell(cx, cy, 1.9 * RECIPE.eyeGloss, 1.9 * RECIPE.eyeGloss), fill: "#FFFFFF" }, indent));
  if (face === "speaking") {
    // Open oval mouth + interior, matching the original mascot_speaking.
    out.push(vpath({ d: ell(54.0, 70.0, 4.0, 2.5), fill: "#3D2B1F" }, indent));
    out.push(vpath({ d: ell(54.0, 70.2, 3.0, 1.5), fill: "#2A1810" }, indent));
  } else {
    out.push(vpath({ d: "M48.5,69.6 Q54.0,72.0 59.5,69.6", stroke: "#3D2B1F", strokeWidth: 0.5, cap: "round" }, indent));
  }
  for (const [cx, cy] of [[40.0, 63.5], [68.0, 63.5]])
    out.push(vpath({ d: ell(cx, cy, 3.5, 1.8), fill: "#FF6B6B", fillAlpha: 0.08 }, indent));
  return out;
}

/* ---------------------------------------------------------------- FOREGROUND */
const fg = mascotVPaths({ outline: RECIPE.outline, face: "happy" });

// Waves live in canvas space, outside the jar group.
const waveArt = [];
for (const w of waves)
  waveArt.push(`  <group android:translateX="0.9" android:translateY="1.1">\n` +
    vpath({ d: w.d, stroke: "#3D2B1F", strokeWidth: w.sw, strokeAlpha: +(w.op * 0.30).toFixed(2), cap: "round" }, "    ") +
    `\n  </group>`);
for (const w of waves)
  waveArt.push(`  <path android:pathData="${w.d}" android:strokeWidth="${w.sw}" ` +
    `android:strokeAlpha="${w.op}" android:strokeLineCap="round" android:fillColor="#00000000">\n` +
    `    <aapt:attr name="android:strokeColor">\n` +
    `      <gradient android:type="linear" android:startX="${X0.toFixed(1)}" android:startY="0" ` +
    `android:endX="${(X0 + wavesWidth(ws)).toFixed(1)}" android:endY="0">\n` +
    `        <item android:offset="0" android:color="#FFF8EE"/>\n` +
    `        <item android:offset="1" android:color="#FED7AA"/>\n` +
    `      </gradient>\n` +
    `    </aapt:attr>\n  </path>`);

const foreground = `<?xml version="1.0" encoding="utf-8"?>
<!--
  GENERATED by docs/design/build-launcher-icon.mjs — do not hand-edit.
  Signed-off V2 icon (Max, 2026-08-01): jar with a lid-cream sticker outline,
  gloss-gradient body and a drop shadow, with three sound-wave arcs on the
  eye line. Launcher-only art; the in-app mascot stays mascot_happy /
  mascot_speaking. Pairs with @drawable/ic_launcher_background.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

  <!-- Jar: translate(${JX.toFixed(2)},${JY.toFixed(2)}) scale(${js}) about (54,60) -->
  <group
      android:pivotX="54"
      android:pivotY="60"
      android:scaleX="${js}"
      android:scaleY="${js}"
      android:translateX="${(JX - 54).toFixed(2)}"
      android:translateY="${(JY - 60).toFixed(2)}">
${fg.join("\n")}
  </group>

${waveArt.join("\n")}
</vector>
`;

/* ---------------------------------------------------------------- BACKGROUND */
const a = RECIPE.angle * Math.PI / 180, co = Math.cos(a), si = Math.sin(a);
const FLAT_BG = RECIPE.geometry === "flat";
const flatCol = ramp(RECIPE.depth);
const bgPath = FLAT_BG
  ? `  <path android:pathData="M0,0 L108,0 L108,108 L0,108 Z" android:fillColor="${flatCol}"/>`
  : `  <path android:pathData="M0,0 L108,0 L108,108 L0,108 Z">
    <aapt:attr name="android:fillColor">
      <gradient android:type="linear"
          android:startX="${((0.5 - 0.5 * co) * 108).toFixed(2)}"
          android:startY="${((0.5 - 0.5 * si) * 108).toFixed(2)}"
          android:endX="${((0.5 + 0.5 * co) * 108).toFixed(2)}"
          android:endY="${((0.5 + 0.5 * si) * 108).toFixed(2)}">
${stopsXml(bgStops(), "      ")}
      </gradient>
    </aapt:attr>
  </path>`;
const bg = `<?xml version="1.0" encoding="utf-8"?>
<!--
  GENERATED by docs/design/build-launcher-icon.mjs — do not hand-edit.
  Adaptive-icon background: ${FLAT_BG
    ? `flat ${flatCol} — the amber ramp sampled at depth ${RECIPE.depth}`
    : `${RECIPE.angle}° linear gradient down the locked
  peach → orange → deep amber ramp (depth ${RECIPE.depth}, spread ${RECIPE.spread})`}.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
${bgPath}
</vector>
`;

/* ------------------------------------------------------------ IN-APP MASCOT
 * Same jar, same gloss and drop shadow as the launcher icon, but WITHOUT the
 * sticker outline — in-app it sits on the app surface, not on a coloured
 * field, so the ring has nothing to separate it from.
 *
 * These are the drawables the UI uses (SpeakScreen, onboarding). Notification
 * and Quick-Settings icons deliberately do NOT use them: those surfaces
 * flatten a drawable to its alpha and tint it, which would turn the drop
 * shadow into a ghosted second jar. They point at ic_stat_marmalade* instead.
 */
const mascotDrawable = (face) => `<?xml version="1.0" encoding="utf-8"?>
<!--
  GENERATED by docs/design/build-launcher-icon.mjs — do not hand-edit.
  In-app mascot (${face}): gloss-gradient body + drop shadow, no sticker
  outline. 108x108 viewport, same geometry as the launcher icon.

  NOT for notifications or the QS tile — those tint by alpha and would render
  the drop shadow as a ghost. Use @drawable/ic_stat_marmalade* there.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

${lines(mascotVPaths({ outline: 0, face, indent: "  " }))}
</vector>
`;

/* ---------------------------------------------------------------- MONOCHROME
 * The themed-icon layer. The system throws away our colours and tints the
 * whole drawable one colour, so this has to work as a pure silhouette:
 *   - the jar's outer shapes are concatenated into ONE path so they union;
 *   - the eyes and mouth are appended to that same path wound in the OPPOSITE
 *     direction, which under the default nonZero fill rule punches them out as
 *     holes (the jar shapes run clockwise, the arcs sweep counter-clockwise);
 *   - the waves stay strokes.
 * No shadow, no sticker outline, no gloss — none of that survives tinting.
 */
// The e5 :D grin (icon-art.js MOUTH_GRIN_E5), REVERSED. The original is wound
// clockwise like the jar shapes, so as-is it would union with the silhouette
// instead of punching out; traversing it backwards (arc first, sweep flag
// flipped 1 -> 0, then the flat top right-to-left) makes it a hole. A closed
// grin also reads far better than a hairline smile once it is a knocked-out
// shape under a flat tint.
const MONO_MOUTH = "M49.0,69.2 A5.0,3.8 0 0 0 59.0,69.2 " +
                   "Q59.0,68.0 57.8,68.0 L50.2,68.0 Q49.0,68.0 49.0,69.2 Z";
const monoSilhouette = [
  ...JAR_SILHOUETTE,                                   // clockwise -> union
  ...EYE_C.map(([cx, cy]) => ell(cx, cy, 4.6, 5.4)),   // ccw -> holes
  MONO_MOUTH,                                          // ccw -> hole
].join(" ");

const monochrome = `<?xml version="1.0" encoding="utf-8"?>
<!--
  GENERATED by docs/design/build-launcher-icon.mjs — do not hand-edit.
  Themed-icon (monochrome) layer for Android 13+. Only referenced when the
  user turns themed icons ON; the full-colour icon remains the default
  everywhere else. Single-colour silhouette: the system replaces the fill
  with its own tint, so the face reads as knocked-out holes rather than
  drawn features.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

  <group
      android:pivotX="54"
      android:pivotY="60"
      android:scaleX="${js}"
      android:scaleY="${js}"
      android:translateX="${(JX - 54).toFixed(2)}"
      android:translateY="${(JY - 60).toFixed(2)}">
    <path android:pathData="${monoSilhouette}" android:fillColor="#000000"/>
  </group>

${waves.map(w => `  <path android:pathData="${w.d}" android:fillColor="#00000000" ` +
  `android:strokeColor="#000000" android:strokeWidth="${w.sw}" android:strokeLineCap="round"/>`).join("\n")}
</vector>
`;

const monoSvg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">
<rect x="0" y="0" width="108" height="108" fill="#d8d2ca"/>
<g transform="translate(${JX.toFixed(2)},${JY.toFixed(2)}) scale(${js}) translate(-54,-60)">
<path d="${monoSilhouette}" fill="#33302c" fill-rule="nonzero"/>
</g>
${waves.map(w => `<path d="${w.d}" fill="none" stroke="#33302c" stroke-width="${w.sw}" stroke-linecap="round"/>`).join("")}
</svg>
`;

/* ------------------------------------------------- flattened SVG for the PNG */
const svgStops = bgStops().map(s => `<stop offset="${s.off}" stop-color="${s.col}"/>`).join("");
const sil = JAR_SILHOUETTE;
const shadowSvg = [[1, 0.55], [0.5, 0.45]].map(([k, al]) =>
  `<g transform="translate(${(RECIPE.shDist * k * 0.75).toFixed(2)},${(RECIPE.shDist * k).toFixed(2)})" opacity="${(RECIPE.shadow * al).toFixed(3)}">` +
  sil.map(d => `<path d="${d}" fill="#3D2B1F"/>`).join("") + `</g>`).join("");
const outlineSvg = sil.map(d =>
  `<path d="${d}" fill="${RECIPE.outlineCol}" stroke="${RECIPE.outlineCol}" stroke-width="${(RECIPE.outline * 2).toFixed(2)}" stroke-linejoin="round"/>`).join("");
const jarSvg = [
  `<path d="${P(JAR_IDX.body)}" fill="url(#jb)"/>`,
  `<path d="${P(JAR_IDX.marmalade)}" fill="#D4831E" opacity="0.5"/>`,
  `<path d="${P(JAR_IDX.shine)}" fill="#FFFFFF" opacity="${(0.15 + 0.30 * RECIPE.sheen).toFixed(3)}"/>`,
  `<path d="${P(JAR_IDX.neck)}" fill="#E8D5B0"/>`,
  `<path d="${P(JAR_IDX.lid)}" fill="#FFF8EE" stroke="#D4C4A0" stroke-width="0.4"/>`,
  `<path d="${P(JAR_IDX.ridge)}" fill="#FFF5E6" stroke="#D4C4A0" stroke-width="0.3"/>`,
  ...JAR_IDX.lidLines.map(i => `<path d="${P(i)}" fill="none" stroke="#E0D0B0" stroke-width="0.2" opacity="0.5"/>`),
  `<path d="${P(JAR_IDX.label)}" fill="#FFF8EE" stroke="#D4B896" stroke-width="0.3"/>`,
  ...JAR_IDX.labelLines.map(i => `<path d="${P(i)}" fill="none" stroke="#E8C9A0" stroke-width="0.2"/>`),
  `<path d="${P(JAR_IDX.slice)}" fill="${JAR_BASE}" stroke="#D4831E" stroke-width="0.3"/>`,
  ...JAR_IDX.sliceLines.map(i => `<path d="${P(i)}" fill="none" stroke="#FFF5E6" stroke-width="0.2" opacity="0.6"/>`),
  ...EYE_C.map(([cx, cy]) => `<ellipse cx="${cx}" cy="${cy}" rx="4.6" ry="5.4" fill="#3D2B1F"/>`),
  ...GLOSS_C.map(([cx, cy]) => `<circle cx="${cx}" cy="${cy}" r="${(1.9 * RECIPE.eyeGloss).toFixed(2)}" fill="#FFFFFF"/>`),
  `<path d="M48.5,69.6 Q54.0,72.0 59.5,69.6" fill="none" stroke="#3D2B1F" stroke-width="0.5" stroke-linecap="round"/>`,
  ...[[40.0, 63.5], [68.0, 63.5]].map(([cx, cy]) => `<ellipse cx="${cx}" cy="${cy}" rx="3.5" ry="1.8" fill="#FF6B6B" opacity="0.08"/>`),
].join("");
const wavesSvg =
  waves.map(w => `<path d="${w.d}" transform="translate(0.9,1.1)" fill="none" stroke="#3D2B1F" stroke-width="${w.sw}" stroke-linecap="round" opacity="${(w.op * 0.30).toFixed(2)}"/>`).join("") +
  waves.map(w => `<path d="${w.d}" fill="none" stroke="url(#wg)" stroke-width="${w.sw}" stroke-linecap="round" opacity="${w.op}"/>`).join("");

const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">
<defs>
${FLAT_BG ? "" : `<linearGradient id="bg" x1="${(0.5 - 0.5 * co).toFixed(4)}" y1="${(0.5 - 0.5 * si).toFixed(4)}" x2="${(0.5 + 0.5 * co).toFixed(4)}" y2="${(0.5 + 0.5 * si).toFixed(4)}">${svgStops}</linearGradient>`}
<linearGradient id="jb" x1="0.1" y1="0" x2="0.85" y2="1"><stop offset="0" stop-color="${GLOSS_LITE}"/><stop offset="0.45" stop-color="${JAR_BASE}"/><stop offset="1" stop-color="${GLOSS_DARK}"/></linearGradient>
<linearGradient id="wg" x1="${X0.toFixed(1)}" y1="0" x2="${(X0 + wavesWidth(ws)).toFixed(1)}" y2="0" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FFF8EE"/><stop offset="1" stop-color="#FED7AA"/></linearGradient>
</defs>
<rect x="0" y="0" width="108" height="108" fill="${FLAT_BG ? flatCol : "url(#bg)"}"/>
<g transform="translate(${JX.toFixed(2)},${JY.toFixed(2)}) scale(${js}) translate(-54,-60)">${shadowSvg}${outlineSvg}${jarSvg}</g>
${wavesSvg}
</svg>
`;

writeFileSync(join(REPO, "app/src/main/res/drawable/ic_launcher_foreground.xml"), foreground);
writeFileSync(join(REPO, "app/src/main/res/drawable/ic_launcher_background.xml"), bg);
writeFileSync(join(REPO, "app/src/main/res/drawable/ic_launcher_monochrome.xml"), monochrome);
writeFileSync(join(REPO, "app/src/main/res/drawable/mascot_happy.xml"), mascotDrawable("happy"));
writeFileSync(join(REPO, "app/src/main/res/drawable/mascot_speaking.xml"), mascotDrawable("speaking"));
writeFileSync(join(HERE, "assets/launcher-icon-512.svg"), svg);
writeFileSync(join(HERE, "assets/launcher-icon-monochrome.svg"), monoSvg);
console.log("wrote launcher (fg/bg/monochrome) + in-app mascot drawables and both SVGs");
console.log("background stops:", bgStops().map(s => s.col).join(" "));
console.log("jar gloss:", GLOSS_LITE, "->", JAR_BASE, "->", GLOSS_DARK);
