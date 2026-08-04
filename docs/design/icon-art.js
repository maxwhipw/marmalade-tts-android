/* Shared Marmalade icon geometry — used by icon-lab.html and
   icon-position-lab.html. All paths live in the mascot_happy.xml 108x108
   coordinate space. Edit here; both labs follow. */
// All geometry lives in the mascot_happy.xml 108x108 coordinate space.
// Layers are [d, fill, stroke, strokeWidth, opacity, linecap] tuples.
const JAR = [
  ["M29.1,101.8 a24.9,3.7 0 1,0 49.8,0 a24.9,3.7 0 1,0 -49.8,0 Z","#000000",null,0,0.08],
  ["M36.3,35.3 L71.6,35.3 Q79.9,35.3 79.9,43.6 L79.9,89.3 Q79.9,97.6 71.6,97.6 L36.3,97.6 Q28.0,97.6 28.0,89.3 L28.0,43.6 Q28.0,35.3 36.3,35.3 Z","#F5A623"],
  ["M36.7,62.3 L71.3,62.3 Q77.9,62.3 77.9,68.9 L77.9,89.3 Q77.9,95.9 71.3,95.9 L36.7,95.9 Q30.1,95.9 30.1,89.3 L30.1,68.9 Q30.1,62.3 36.7,62.3 Z","#D4831E",null,0,0.5],
  ["M34.3,37.4 L34.2,37.4 Q38.4,37.4 38.4,41.6 L38.4,87.2 Q38.4,91.4 34.2,91.4 L34.3,91.4 Q30.1,91.4 30.1,87.2 L30.1,41.6 Q30.1,37.4 34.3,37.4 Z","#FFFFFF",null,0,0.15],
  ["M37.0,32.2 L71.0,32.2 Q72.7,32.2 72.7,33.9 L72.7,35.7 Q72.7,37.4 71.0,37.4 L37.0,37.4 Q35.3,37.4 35.3,35.7 L35.3,33.9 Q35.3,32.2 37.0,32.2 Z","#E8D5B0"],
  ["M35.1,22.8 L72.9,22.8 Q75.8,22.8 75.8,25.7 L75.8,30.7 Q75.8,33.6 72.9,33.6 L35.1,33.6 Q32.2,33.6 32.2,30.7 L32.2,25.7 Q32.2,22.8 35.1,22.8 Z","#FFF8EE","#D4C4A0",0.4],
  ["M35.8,21.8 L72.3,21.8 Q73.8,21.8 73.8,23.3 L73.8,23.2 Q73.8,24.7 72.3,24.7 L35.8,24.7 Q34.3,24.7 34.3,23.2 L34.3,23.3 Q34.3,21.8 35.8,21.8 Z","#FFF5E6","#D4C4A0",0.3],
  ["M37.4,24.5 L37.4,32.2",null,"#E0D0B0",0.2,0.5],
  ["M43.6,23.9 L43.6,33.3",null,"#E0D0B0",0.2,0.5],
  ["M49.9,23.5 L49.9,33.3",null,"#E0D0B0",0.2,0.5],
  ["M56.1,23.5 L56.1,33.3",null,"#E0D0B0",0.2,0.5],
  ["M62.3,23.9 L62.3,33.3",null,"#E0D0B0",0.2,0.5],
  ["M38.4,78.9 L69.5,78.9 Q71.6,78.9 71.6,81.0 L71.6,90.3 Q71.6,92.4 69.5,92.4 L38.4,92.4 Q36.3,92.4 36.3,90.3 L36.3,81.0 Q36.3,78.9 38.4,78.9 Z","#FFF8EE","#D4B896",0.3],
  ["M39.4,81.4 L68.6,81.4",null,"#E8C9A0",0.2],
  ["M39.4,89.7 L68.6,89.7",null,"#E8C9A0",0.2],
  ["M51.5,76.9 a2.5,2.5 0 1,0 5.0,0 a2.5,2.5 0 1,0 -5.0,0 Z","#F5A623","#D4831E",0.3],
  ["M54.0,74.4 L54.0,79.4",null,"#FFF5E6",0.2,0.6],
  ["M52.3,74.8 L53.2,79.4",null,"#FFF5E6",0.2,0.6],
  ["M55.7,74.8 L54.8,79.4",null,"#FFF5E6",0.2,0.6],
];
const EYES = [
  ["M40.0,60.2 a4.6,5.4 0 1,0 9.2,0 a4.6,5.4 0 1,0 -9.2,0 Z","#3D2B1F"],
  ["M58.7,60.2 a4.6,5.4 0 1,0 9.2,0 a4.6,5.4 0 1,0 -9.2,0 Z","#3D2B1F"],
  ["M44.6,58.2 a1.9,1.9 0 1,0 3.8,0 a1.9,1.9 0 1,0 -3.8,0 Z","#FFFFFF"],
  ["M63.3,58.2 a1.9,1.9 0 1,0 3.8,0 a1.9,1.9 0 1,0 -3.8,0 Z","#FFFFFF"],
];
const BLUSH = [
  ["M36.5,63.5 a3.5,1.8 0 1,0 7.0,0 a3.5,1.8 0 1,0 -7.0,0 Z","#FF6B6B",null,0,0.08],
  ["M64.5,63.5 a3.5,1.8 0 1,0 7.0,0 a3.5,1.8 0 1,0 -7.0,0 Z","#FF6B6B",null,0,0.08],
];
const MOUTH_HAPPY = [["M48.5,69.6 Q54.0,72.0 59.5,69.6",null,"#3D2B1F",0.5,1,"round"]];
const MOUTH_SPEAK = [
  ["M50.0,70.0 a4.0,2.5 0 1,0 8.0,0 a4.0,2.5 0 1,0 -8.0,0 Z","#3D2B1F"],
  ["M51.0,70.2 a3.0,1.5 0 1,0 6.0,0 a3.0,1.5 0 1,0 -6.0,0 Z","#2A1810"],
];
// Winner of the 2026-08-01 face iterations (e5): flat-top :D, rounded corners,
// 10 wide x 5 deep, no tongue. Pairs with the slice-free jar (JAR_NOSLICE).
const MOUTH_GRIN_E5 = [["M49.0,69.2 Q49.0,68.0 50.2,68.0 L57.8,68.0 Q59.0,68.0 59.0,69.2 A5.0,3.8 0 0 1 49.0,69.2 Z","#3D2B1F"]];
const JAR_NOSLICE = JAR.slice(0,15);   // drop the orange-slice label decoration
const FACE_HAPPY = [...EYES, ...MOUTH_HAPPY, ...BLUSH];
const FACE_SPEAK = [...EYES, ...MOUTH_SPEAK, ...BLUSH];
const FACE_GRIN_FINAL = [...EYES, ...MOUTH_GRIN_E5, ...BLUSH];

// Add-ons in jar space (scale with the jar)
const SPEAK_ARCS = [
  ["M45.5,66.5 Q43.0,70.0 45.5,73.5",null,"#3D2B1F",0.7,0.9,"round"],
  ["M62.5,66.5 Q65.0,70.0 62.5,73.5",null,"#3D2B1F",0.7,0.9,"round"],
  ["M42.5,64.8 Q39.2,70.0 42.5,75.2",null,"#3D2B1F",0.7,0.5,"round"],
  ["M65.5,64.8 Q68.8,70.0 65.5,75.2",null,"#3D2B1F",0.7,0.5,"round"],
];
const SIDE_WAVES = [
  ["M70.5,54.5 Q75.5,60.0 70.5,65.5",null,"#FFF8EE",2.2,1,"round"],
  ["M75.5,50.0 Q82.0,60.0 75.5,70.0",null,"#FFF8EE",2.2,0.7,"round"],
  ["M80.5,45.5 Q88.5,60.0 80.5,74.5",null,"#FFF8EE",2.2,0.45,"round"],
];
const wf = (xs, hs, cy, sw, col, op) => xs.map((x,i) =>
  ["M"+x+","+(cy-hs[i]/2)+" L"+x+","+(cy+hs[i]/2),null,col,sw,op||1,"round"]);
const LABEL_WAVE = wf([42,45,48,51,54,57,60,63,66],[3.5,6.5,9,5.5,8,4,7,9.5,5],85.6,1.6,"#D4831E");
const HEADPHONES = [
  ["M26.0,54.0 Q26.0,14.0 54.0,14.0 Q82.0,14.0 82.0,54.0",null,"#D4831E",5.0,1,"round"],
  ["M26.0,54.0 Q26.0,14.0 54.0,14.0 Q82.0,14.0 82.0,54.0",null,"#FFF8EE",3.2,1,"round"],
  ["M22.6,50.0 L29.4,50.0 Q31.4,50.0 31.4,52.0 L31.4,64.0 Q31.4,66.0 29.4,66.0 L22.6,66.0 Q20.6,66.0 20.6,64.0 L20.6,52.0 Q20.6,50.0 22.6,50.0 Z","#FFF8EE","#D4831E",0.9],
  ["M78.6,50.0 L85.4,50.0 Q87.4,50.0 87.4,52.0 L87.4,64.0 Q87.4,66.0 85.4,66.0 L78.6,66.0 Q76.6,66.0 76.6,64.0 L76.6,52.0 Q76.6,50.0 78.6,50.0 Z","#FFF8EE","#D4831E",0.9],
];
const NOTES = [
  ["M69.2,43.6 L69.2,27.0",null,"#D4831E",3.2,1,"round"],
  ["M81.2,41.0 L81.2,24.4",null,"#D4831E",3.2,1,"round"],
  ["M69.2,27.0 L81.2,24.4",null,"#D4831E",4.6,1,"round"],
  ["M69.2,43.6 L69.2,27.0",null,"#FFF8EE",1.8,1,"round"],
  ["M81.2,41.0 L81.2,24.4",null,"#FFF8EE",1.8,1,"round"],
  ["M69.2,27.0 L81.2,24.4",null,"#FFF8EE",3.2,1,"round"],
  ["M63.4,44.0 a3.0,2.3 0 1,0 6.0,0 a3.0,2.3 0 1,0 -6.0,0 Z","#FFF8EE","#D4831E",0.8],
  ["M75.4,41.4 a3.0,2.3 0 1,0 6.0,0 a3.0,2.3 0 1,0 -6.0,0 Z","#FFF8EE","#D4831E",0.8],
];
// Add-ons in canvas space (fixed, drawn before/after the jar group)
const RINGS = [
  ["M54,22 a32,32 0 1,0 0.01,0 Z",null,"#FFF8EE",2,0.35],
  ["M54,13 a41,41 0 1,0 0.01,0 Z",null,"#FFF8EE",2,0.22],
  ["M54,4  a50,50 0 1,0 0.01,0 Z",null,"#FFF8EE",2,0.13],
];
const BUBBLE = [
  ["M63.0,18.0 L89.0,18.0 Q94.0,18.0 94.0,23.0 L94.0,37.0 Q94.0,42.0 89.0,42.0 L72.0,42.0 L61.0,51.0 L65.0,42.0 L63.0,42.0 Q58.0,42.0 58.0,37.0 L58.0,23.0 Q58.0,18.0 63.0,18.0 Z","#FFF8EE","#D4B896",0.8],
  ...wf([64,68,72,76,80,84,88],[5,10,15,8,13,6,10],30,2.2,"#D4831E"),
];

const BGS = [
  {id:"B0", name:"Orange (current — shared with agent)", hex:"#F97316"},
  {id:"B1", name:"Rich brown", hex:"#422006"},
  {id:"B2", name:"Deep amber", hex:"#C2410C"},
  {id:"B3", name:"Stone deep", hex:"#1c1917"},
  {id:"B4", name:"Cream", hex:"#FFEDD5"},
];

// t: transform applied to the jar group; pre/post: canvas-space layers
const VARIANTS = [
  {id:"V0", name:"Current (baseline)", t:null,
   desc:"The shipping icon: happy jar, unscaled. Note the round mask already crops the lid ridge and the jar bottom.",
   jar:[...JAR, ...FACE_HAPPY]},
  {id:"V1", name:"Speaking", t:"translate(54,54) scale(0.86) translate(-54,-60)",
   desc:"mascot_speaking face — open mouth plus small speech ticks. Smallest possible change; app-true (this is the playback expression).",
   jar:[...JAR, ...FACE_SPEAK, ...SPEAK_ARCS]},
  {id:"V2", name:"Sound waves", t:"translate(46,54) scale(0.78) translate(-54,-60)",
   desc:"Happy jar shifted left, cream broadcast arcs off the right side. Reads as \"audio\" even at shelf size.",
   jar:[...JAR, ...FACE_HAPPY], post:SIDE_WAVES},
  {id:"V3", name:"Waveform label", t:"translate(54,54) scale(0.9) translate(-54,-60)",
   desc:"The jar label carries a waveform instead of plain rules. Subtlest option — silhouette stays identical to the agent's.",
   jar:[...JAR, ...LABEL_WAVE, ...FACE_HAPPY]},
  {id:"V4", name:"Headphones", t:"translate(54,56) scale(0.78) translate(-54,-62)",
   desc:"Cream headphones (lid-matched, amber-outlined so they hold on every field) over the jar. Strong \"audio app\" signal and a genuinely different silhouette.",
   jar:[...HEADPHONES.slice(0,2), ...JAR, ...HEADPHONES.slice(2), ...FACE_HAPPY]},
  {id:"V5", name:"Speech bubble", t:"translate(45,64) scale(0.72) translate(-54,-60)",
   desc:"Jar tucked lower-left, cream speech bubble with a waveform. Face is the FINAL pick from the 2026-08-01 :D iterations (e5: rounded-corner flat-top grin, 10 wide, no tongue, orange-slice decoration removed).",
   jar:[...JAR_NOSLICE, ...FACE_GRIN_FINAL], post:BUBBLE},
  {id:"V6", name:"Singing", t:"translate(47,56) scale(0.80) translate(-54,-60)",
   desc:"Speaking face plus a beamed pair of notes. Warmer/cuter than waves; slightly more \"music player\" than \"TTS\".",
   jar:[...JAR, ...FACE_SPEAK], post:NOTES},
  {id:"V7", name:"Broadcast rings", t:"translate(54,54) scale(0.86) translate(-54,-60)",
   desc:"Faint concentric rings behind the unchanged happy jar. Keeps the exact mascot; the field itself says \"transmitting\".",
   jar:[...JAR, ...FACE_HAPPY], pre:RINGS},
  {id:"V8", name:"Face zoom", t:"translate(54,60) scale(1.9) translate(-54,-62)",
   desc:"Speaking face cropped in tight — the bold-crop idiom. Most distinct at 48 px; least \"whole jar\".",
   jar:[...JAR, ...FACE_SPEAK]},
];

let uid = 0;
function pathEl(l){
  const [d, fill, stroke, sw, op, cap] = l;
  let s = '<path d="'+d+'" fill="'+(fill||"none")+'"';
  if (stroke) s += ' stroke="'+stroke+'" stroke-width="'+sw+'"';
  if (op != null && op !== 1) s += ' opacity="'+op+'"';
  if (cap) s += ' stroke-linecap="'+cap+'"';
  return s + '/>';
}
function iconSvg(v, bgHex, mode){
  // mode "full": 0 0 108 108; "round": center-72 crop with circular clip
  const inner =
    '<rect class="bgr" x="0" y="0" width="108" height="108" fill="'+bgHex+'"/>' +
    (v.pre||[]).map(pathEl).join("") +
    '<g'+(v.t ? ' transform="'+v.t+'"' : '')+'>'+v.jar.map(pathEl).join("")+'</g>' +
    (v.post||[]).map(pathEl).join("");
  if (mode === "round"){
    const id = "cl" + (++uid);
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72">' +
      '<defs><clipPath id="'+id+'"><circle cx="54" cy="54" r="36"/></clipPath></defs>' +
      '<g clip-path="url(#'+id+')">'+inner+'</g></svg>';
  }
  return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'+inner+'</svg>';
}
