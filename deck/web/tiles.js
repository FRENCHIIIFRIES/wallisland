// Drawing one button: icon, picture, label, or a live widget (clock, CPU and RAM rings).

import { dotIcon } from "./icons.js";
import { clamp, clockText, fill, h, hasVars, localVars, state } from "./core.js";

const svgCache = new Map();

export function iconNode(name, opts) {
  const key = name + JSON.stringify(opts || {});
  if (!svgCache.has(key)) {
    const t = document.createElement("template");
    t.innerHTML = dotIcon(name, opts);
    svgCache.set(key, t.content.firstChild);
  }
  const node = svgCache.get(key);
  return node ? node.cloneNode(true) : document.createTextNode("");
}

export const isLive = (b) => !!b.widget || hasVars(b.label) || hasVars(b.sub);

export function tileNode(b) {
  const el = h("button.tile", { type: "button" });
  paintTile(el, b);
  return el;
}

const KEEP = ["down", "sel", "lifted", "drop"];

export function paintTile(el, b) {
  const keep = KEEP.filter((c) => el.classList.contains(c));
  el.className = `tile s-${b.style || "dark"}`;
  el.classList.add(...keep);
  el.classList.toggle("on", state.on.has(b.id));
  el.dataset.id = b.id;
  const label = fill(b.label);
  el.setAttribute("aria-label", label || b.widget || b.icon || "Button");
  el.replaceChildren(...content(b, el, label));
}

function content(b, el, label) {
  const sub = fill(b.sub);
  if (b.widget === "clock") {
    const v = localVars();
    return [h("div.big.clock", clockText()), h("div.sub", b.sub ? sub : `${v.day} ${v.date} ${v.month}`)];
  }
  if (b.widget === "cpu" || b.widget === "ram") {
    const val = Number(state.vars[b.widget]);
    return [gauge(Number.isFinite(val) ? val : null, label || b.widget)];
  }
  const kids = [];
  let pic = null;
  if (b.image) {
    pic = picture(b);
    kids.push(pic);
    el.classList.toggle("pic-fill", !!b.fill);
  } else if (b.icon) {
    kids.push(iconNode(b.icon));
  }
  if (label) {
    if (!pic && !b.icon) {
      const big = h("div.big", label);
      big.style.setProperty("--len", Math.max(3, [...label].length));
      kids.push(big);
    } else {
      kids.push(h("div.lb", label));
    }
  }
  if (sub) kids.push(h("div.sub", sub));
  el.classList.toggle("only-pic", !!pic && !label && !sub);
  return kids;
}

/** A ring of dots that fills up with the value, like a dot-matrix gauge. */
function gauge(val, label) {
  const n = 28;
  const lit = val == null ? 0 : Math.round((clamp(val, 0, 100) / 100) * n);
  const hot = val != null && val >= 85;
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 100 100");
  svg.setAttribute("class", "gauge");
  for (let i = 0; i < n; i++) {
    const a = ((-90 + (i * 360) / n) * Math.PI) / 180;
    const c = document.createElementNS(ns, "circle");
    c.setAttribute("cx", (50 + 41 * Math.cos(a)).toFixed(2));
    c.setAttribute("cy", (50 + 41 * Math.sin(a)).toFixed(2));
    c.setAttribute("r", "3.2");
    c.setAttribute("class", i < lit ? (hot ? "hot" : "lit") : "dim");
    svg.append(c);
  }
  const num = document.createElementNS(ns, "text");
  num.setAttribute("x", "50");
  num.setAttribute("y", "56");
  num.setAttribute("text-anchor", "middle");
  num.setAttribute("class", "num");
  num.textContent = val == null ? "--" : String(Math.round(val));
  const unit = document.createElementNS(ns, "text");
  unit.setAttribute("x", "50");
  unit.setAttribute("y", "71");
  unit.setAttribute("text-anchor", "middle");
  unit.setAttribute("class", "unit");
  unit.textContent = `${String(label).toUpperCase().slice(0, 8)} %`;
  svg.append(num, unit);
  return svg;
}

function picture(b) {
  const img = h("img.pic", { alt: "", draggable: false });
  if (b.dots) {
    halftone(b.image).then((url) => (img.src = url));
  } else {
    img.src = b.image;
  }
  return img;
}

const halftones = new Map();

/** The picture redrawn as a grid of coloured dots, sized by brightness. Cached per picture. */
export function halftone(src) {
  if (halftones.has(src)) return halftones.get(src);
  const job = new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      const n = 16;
      const pitch = 12;
      const small = document.createElement("canvas");
      small.width = small.height = n;
      const sx = small.getContext("2d", { willReadFrequently: true });
      const s = Math.min(img.width, img.height);
      sx.drawImage(img, (img.width - s) / 2, (img.height - s) / 2, s, s, 0, 0, n, n);
      const px = sx.getImageData(0, 0, n, n).data;
      const out = document.createElement("canvas");
      out.width = out.height = n * pitch;
      const o = out.getContext("2d");
      for (let y = 0; y < n; y++) {
        for (let x = 0; x < n; x++) {
          const i = (y * n + x) * 4;
          const alpha = px[i + 3] / 255;
          if (alpha < 0.1) continue;
          const [r, g, bl] = [px[i], px[i + 1], px[i + 2]];
          const lum = (0.2126 * r + 0.7152 * g + 0.0722 * bl) / 255;
          const rad = pitch * 0.47 * (0.35 + 0.65 * Math.sqrt(lum));
          o.fillStyle = `rgba(${r},${g},${bl},${alpha})`;
          o.beginPath();
          o.arc(x * pitch + pitch / 2, y * pitch + pitch / 2, rad, 0, Math.PI * 2);
          o.fill();
        }
      }
      resolve(out.toDataURL("image/png"));
    };
    img.onerror = () => resolve(src);
    img.src = src;
  });
  halftones.set(src, job);
  return job;
}

/** Shrinks a picked image to a square the deck can carry around (about 10-40 KB). */
export function loadPicture(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      const size = 192;
      const c = document.createElement("canvas");
      c.width = c.height = size;
      const x = c.getContext("2d");
      const scale = Math.max(size / img.width, size / img.height);
      const w = img.width * scale;
      const hgt = img.height * scale;
      x.drawImage(img, (size - w) / 2, (size - hgt) / 2, w, hgt);
      URL.revokeObjectURL(url);
      let out = c.toDataURL("image/webp", 0.86);
      if (!out.startsWith("data:image/webp")) out = c.toDataURL("image/png");
      resolve(out);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error("That file isn't a picture this browser can read"));
    };
    img.src = url;
  });
}
