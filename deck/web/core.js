// Shared state and small helpers used by the deck and the editors.

export const state = {
  token: null,
  ws: null,
  conn: "connecting", // connecting | open | lost | unpaired
  everOpen: false,
  hello: null,
  deck: null,
  rev: 0,
  vars: {},
  on: new Set(),
  pageId: null,
  stack: [], // folders opened on top of a page
  edit: false,
  dirty: false,
  me: Math.random().toString(36).slice(2, 10),
};

/** Hooks filled in by app.js, so editors can ask for a redraw without importing it. */
export const hooks = { render() {}, closePanel() {} };

// ---------- DOM ----------

/** h("div.card", {onclick}, child, "text") */
export function h(spec, props, ...kids) {
  const [tag, ...classes] = spec.split(".");
  const el = document.createElement(tag || "div");
  if (classes.length) el.className = classes.join(" ");
  if (props && (typeof props !== "object" || props instanceof Node || Array.isArray(props))) {
    kids.unshift(props);
    props = null;
  }
  for (const [k, v] of Object.entries(props || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className += (el.className ? " " : "") + v;
    else if (k === "html") el.innerHTML = v; // only ever our own SVG
    else if (k === "text") el.textContent = v;
    else if (k === "value") el.value = v; // a textarea ignores the attribute
    else if (k === "style" && typeof v === "object") Object.assign(el.style, v);
    else if (k in el && typeof v !== "string") el[k] = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue;
    el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return el;
}

export const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

export function uid() {
  const a = new Uint8Array(6);
  crypto.getRandomValues(a);
  return [...a].map((b) => b.toString(16).padStart(2, "0")).join("");
}

// ---------- This device's own settings ----------

export const local = {
  get(key, fallback) {
    try {
      const v = localStorage.getItem("dotdeck." + key);
      return v == null ? fallback : JSON.parse(v);
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem("dotdeck." + key, JSON.stringify(value));
    } catch {}
  },
};

// ---------- Talking to the computer ----------

export function send(msg) {
  const ws = state.ws;
  if (!ws || ws.readyState !== WebSocket.OPEN) return false;
  ws.send(JSON.stringify(msg));
  return true;
}

let saveTimer = 0;

/** Call after editing state.deck: redraws now, saves shortly after. */
export function changed() {
  state.dirty = true;
  hooks.render();
  clearTimeout(saveTimer);
  saveTimer = setTimeout(flush, 450);
}

export function flush() {
  clearTimeout(saveTimer);
  if (!state.dirty || !state.deck) return;
  if (send({ t: "save", deck: state.deck, by: state.me })) state.dirty = false;
}

// ---------- Deck lookups ----------

export const settings = () => state.deck?.settings || {};
export const cols = () => clamp(settings().cols | 0 || 5, 1, 12);
export const rows = () => clamp(settings().rows | 0 || 3, 1, 12);
export const pageById = (id) => state.deck?.pages.find((p) => p.id === id);
export const currentPage = () => pageById(state.pageId) || state.deck?.pages[0];
export const mainPages = () => (state.deck?.pages || []).filter((p) => !p.folder);

export function findButton(id) {
  for (const p of state.deck?.pages || []) {
    const b = p.buttons.find((b) => b.id === id);
    if (b) return { page: p, button: b };
  }
  return null;
}

export function buttonAt(page, x, y) {
  return page.buttons.find((b) => b.x === x && b.y === y);
}

/** First empty spot on a page, reading order. */
export function freeSpot(page) {
  for (let y = 0; y < rows(); y++)
    for (let x = 0; x < cols(); x++) if (!buttonAt(page, x, y)) return { x, y };
  return null;
}

// ---------- Live text: {cpu}, {time}, ... ----------

const DAYS = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"];
const MONTHS = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];

export function clockText(d = new Date()) {
  let hr = d.getHours();
  if (settings().clock24 === false) hr = hr % 12 || 12;
  return `${String(hr).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

export function localVars(d = new Date()) {
  return {
    time: clockText(d),
    seconds: String(d.getSeconds()).padStart(2, "0"),
    date: String(d.getDate()),
    day: DAYS[d.getDay()],
    month: MONTHS[d.getMonth()],
    year: String(d.getFullYear()),
  };
}

export const hasVars = (s) => typeof s === "string" && s.includes("{");

export function fill(text) {
  if (!hasVars(text)) return text || "";
  const vars = { ...state.vars, ...localVars() };
  return text.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m));
}

// ---------- Feedback ----------

let toastTimer = 0;
export function toast(text, ok = false) {
  const el = document.querySelector(".toast");
  if (!el) return;
  el.textContent = text;
  el.classList.toggle("err", !ok);
  el.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove("show"), ok ? 1800 : 4200);
}

export function haptic(ms = 8) {
  if (local.get("haptics", true) && navigator.vibrate) navigator.vibrate(ms);
}

/** Tap once to arm, again within 3 s to confirm. For deletes and the like. */
export function armed(btn, label, run) {
  let timer = 0;
  const original = btn.textContent;
  btn.addEventListener("click", () => {
    if (btn.classList.contains("armed")) {
      clearTimeout(timer);
      run();
      return;
    }
    btn.classList.add("armed");
    btn.textContent = label;
    timer = setTimeout(() => {
      btn.classList.remove("armed");
      btn.textContent = original;
    }, 3000);
  });
  return btn;
}
