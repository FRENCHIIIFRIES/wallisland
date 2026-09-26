// The deck: connects to Dotdeck on the computer, draws the pages of buttons and sends presses.

import {
  changed,
  clamp,
  cols,
  currentPage,
  findButton,
  flush,
  h,
  haptic,
  hooks,
  local,
  mainPages,
  pageById,
  rows,
  send,
  settings,
  state,
  toast,
  uid,
} from "./core.js";
import { iconNode, isLive, paintTile, tileNode } from "./tiles.js";
import { openButtonEditor, openPageEditor } from "./editor.js";
import { openSettings } from "./settings.js";

const $ = {};
/** Opened on the computer running Dotdeck (the editor), rather than on a phone. */
const onHost = ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname);

// ---------- Pairing token ----------

function readToken() {
  // The QR code carries the token after "#k=". Keep it, then tidy the address bar.
  const m = (location.hash + "&" + location.search).match(/[#?&]k=([A-Za-z0-9_-]+)/);
  if (m) {
    local.set("token", m[1]);
    history.replaceState(null, "", location.pathname);
  }
  return local.get("token", null);
}

// ---------- Connection ----------

let retries = 0;
let retryTimer = 0;
let lastHeard = 0;
let lostTimer = 0;

function connect() {
  clearTimeout(retryTimer);
  if (!state.token) {
    setConn("unpaired");
    return;
  }
  if (state.ws) return;
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const ws = new WebSocket(`${proto}://${location.host}/ws?k=${encodeURIComponent(state.token)}`);
  state.ws = ws;
  ws.onopen = () => {
    retries = 0;
    lastHeard = Date.now();
  };
  ws.onmessage = (e) => {
    lastHeard = Date.now();
    try {
      onMessage(JSON.parse(e.data));
    } catch (err) {
      console.error(err);
    }
  };
  ws.onclose = (e) => {
    if (state.ws !== ws) return;
    state.ws = null;
    if (e.code === 4001 || state.conn === "unpaired") {
      setConn("unpaired");
      return;
    }
    setConn(state.everOpen ? "lost" : "connecting");
    retryTimer = setTimeout(connect, Math.min(4000, 300 * 2 ** retries++));
  };
}

function reconnectNow() {
  retries = 0;
  if (state.ws) {
    const old = state.ws;
    state.ws = null;
    old.onclose = null;
    old.close();
  }
  connect();
}

// A phone's Wi-Fi can drop silently; ping so a dead link is noticed within seconds.
setInterval(() => {
  if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
  if (Date.now() - lastHeard > 9000) reconnectNow();
  else send({ t: "ping" });
}, 3000);

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState !== "visible") return;
  if (!state.ws || Date.now() - lastHeard > 4000) reconnectNow();
});

function setConn(c) {
  if (c === "lost" && state.conn !== "lost") state.lostAt = Date.now();
  state.conn = c;
  clearTimeout(lostTimer);
  if (c === "open") state.everOpen = true;
  render();
  // Only show "reconnecting" if it takes a moment, so quick blips don't flash the screen.
  if (c === "lost") lostTimer = setTimeout(renderOverlay, 1200);
}

function onMessage(m) {
  switch (m.t) {
    case "hello":
      state.hello = m;
      break;
    case "deck": {
      state.rev = m.rev;
      const first = !state.deck;
      const mine = m.by === state.me;
      // Our own unsaved edits win; they're sent below.
      const keepOurs = state.dirty && !first;
      if (!mine && !keepOurs) {
        state.deck = m.deck;
        if (!pageById(state.pageId)) {
          const saved = local.get("page", null);
          state.pageId = pageById(saved) && !pageById(saved).folder ? saved : mainPages()[0]?.id;
          state.stack = [];
        }
      }
      if (state.conn !== "open") setConn("open");
      else if (!mine && !keepOurs) render();
      if (!mine && !keepOurs) hooks.refreshPanel?.();
      if (keepOurs) flush();
      // First visit on the computer itself: show the QR code straight away.
      if (first && onHost && !local.get("welcomed", false)) {
        local.set("welcomed", true);
        setTimeout(() => document.body.classList.contains("panel-open") || openSettings(), 400);
      }
      break;
    }
    case "vars":
      state.vars = m.vars || {};
      tick();
      break;
    case "on":
      state.on = new Set(m.ids || []);
      for (const el of $.grid.querySelectorAll(".tile")) el.classList.toggle("on", state.on.has(el.dataset.id));
      break;
    case "toast":
      toast(m.text, !!m.ok);
      break;
    case "token":
      state.token = m.token;
      local.set("token", m.token);
      hooks.refreshPanel?.();
      break;
    case "unpaired":
      local.set("token", null);
      state.token = null;
      setConn("unpaired");
      break;
  }
}

// ---------- Layout ----------

function build() {
  $.back = iconNode("back");
  $.status = h("span.status.off");
  $.title = h("h1.doto");
  $.titleBtn = h("button.bar-title", { type: "button", onclick: onTitle }, $.back, $.status, $.title);
  $.tag = h("span.tag", "Editing");
  $.host = h("span.host");
  $.pair = round("qr", "Pair a phone", () => openSettings());
  $.full = round("maximize", "Fullscreen", toggleFullscreen);
  $.edit = round("brush", "Edit buttons", toggleEdit);
  $.gear = round("gear", "Settings", () => openSettings());
  $.bar = h("header.bar", $.titleBtn, $.tag, $.host, $.pair, $.full, $.edit, $.gear);
  $.grid = h("div.grid");
  $.stage = h("main.stage", $.grid);
  $.pages = h("nav.pages", { "aria-label": "Pages" });
  $.deck = h("div.deck", $.bar, $.stage, $.pages);
  // The click that follows the tap which opened a sheet lands on this scrim; don't let it close the sheet.
  $.scrim = h("div.scrim", { onclick: () => performance.now() - panelOpenedAt > 450 && closePanel() });
  $.panel = h("aside.panel", { "aria-label": "Editor" });
  $.overlay = h("div.overlay");
  $.toast = h("div.toast", { role: "status", "aria-live": "polite" });
  document.getElementById("app").append($.deck, $.scrim, $.panel, $.overlay, $.toast);

  $.grid.addEventListener("pointerdown", onGridDown);
  $.grid.addEventListener("keydown", onGridKey);
  $.grid.addEventListener("contextmenu", (e) => e.preventDefault());
  // Swipe between pages anywhere but on a button (buttons fire the moment they're touched).
  swipeable($.stage, (e) => !e.target.closest(".tile"));
  swipeable($.pages);
  new ResizeObserver(layout).observe($.stage);
  addEventListener("resize", () => render());
  addEventListener("keydown", onKey);
}

function round(icon, label, onclick) {
  return h("button.round", { type: "button", "aria-label": label, title: label, onclick }, iconNode(icon));
}

/** Columns and rows as drawn. A wide deck turns sideways on a portrait phone so buttons stay big. */
function view() {
  const c = cols();
  const r = rows();
  const rotate = innerHeight > innerWidth && c > r && local.get("rotate", true);
  return rotate
    ? { cols: r, rows: c, at: (x, y) => [y, x] }
    : { cols: c, rows: r, at: (x, y) => [x, y] };
}

function layout() {
  if (!$.stage) return;
  const v = view();
  const box = $.stage.getBoundingClientRect();
  const w = box.width - 32;
  const hgt = box.height - 8;
  let t = Math.min(w / (v.cols + (v.cols - 1) * 0.12), hgt / (v.rows + (v.rows - 1) * 0.12));
  const gap = clamp(Math.round(t * 0.12), 6, 20);
  t = Math.floor(Math.min((w - gap * (v.cols - 1)) / v.cols, (hgt - gap * (v.rows - 1)) / v.rows, 230));
  $.grid.style.setProperty("--t", Math.max(t, 24) + "px");
  $.grid.style.setProperty("--gap", gap + "px");
}

// ---------- Drawing ----------

let live = [];

function render() {
  applyAccent();
  document.body.classList.toggle("editing", state.edit);
  $.deck.style.visibility = state.deck ? "" : "hidden";
  if (!state.deck) {
    renderOverlay();
    return;
  }
  const page = currentPage();
  state.pageId = page.id;
  const folder = !!page.folder;
  $.back.style.display = folder ? "" : "none";
  $.status.style.display = folder ? "none" : "";
  $.status.classList.toggle("off", state.conn !== "open");
  $.title.textContent = page.name || "Page";
  $.titleBtn.setAttribute("aria-label", folder ? `Back from ${page.name}` : page.name || "Page");
  $.tag.hidden = !state.edit;
  $.host.textContent = state.hello?.host || "";
  $.host.hidden = state.edit;
  $.full.hidden = state.edit || !document.fullscreenEnabled || onHost;
  $.pair.hidden = state.edit || !onHost;
  $.edit.classList.toggle("lit", state.edit);
  $.edit.setAttribute("aria-label", state.edit ? "Done editing" : "Edit buttons");
  renderGrid(page);
  renderPages(page);
  renderOverlay();
  layout();
}

function renderGrid(page) {
  const v = view();
  $.grid.style.setProperty("--cols", v.cols);
  $.grid.style.setProperty("--rows", v.rows);
  const kids = [];
  const taken = new Set();
  live = [];
  for (const b of page.buttons) {
    // Buttons outside a shrunken grid are kept, just not shown.
    if (b.x < 0 || b.y < 0 || b.x >= cols() || b.y >= rows() || taken.has(b.x + "," + b.y)) continue;
    taken.add(b.x + "," + b.y);
    const el = tileNode(b);
    if (b.id === state.selected) el.classList.add("sel");
    place(el, v, b.x, b.y);
    if (isLive(b)) live.push([el, b]);
    kids.push(el);
  }
  for (let y = 0; y < rows(); y++) {
    for (let x = 0; x < cols(); x++) {
      if (taken.has(x + "," + y)) continue;
      const cell = h("div.cell", { "aria-hidden": state.edit ? null : "true" }, iconNode("plus"));
      cell.dataset.x = x;
      cell.dataset.y = y;
      place(cell, v, x, y);
      kids.push(cell);
    }
  }
  $.grid.replaceChildren(...kids);
}

function place(el, v, x, y) {
  const [c, r] = v.at(x, y);
  el.style.gridColumn = c + 1;
  el.style.gridRow = r + 1;
}

/** Once a second (and whenever new values arrive): clocks, gauges and {vars} in labels. */
function tick() {
  for (const [el, b] of live) if (el.isConnected) paintTile(el, b);
}
setInterval(tick, 1000);

function renderPages(page) {
  const list = mainPages();
  const root = page.folder ? state.stack[0] : page.id;
  const dots = list.map((p) =>
    h("button", {
      type: "button",
      class: p.id === root ? "cur" : null,
      "aria-label": p.name,
      "aria-current": p.id === root ? "page" : null,
      onclick: () => goTo(p.id, true),
    }, h("i")),
  );
  if (state.edit) {
    dots.push(h("button.add", { type: "button", "aria-label": "Add a page", onclick: addPage }, h("i", iconNode("plus"))));
  }
  $.pages.replaceChildren(...dots);
  $.pages.style.visibility = list.length > 1 || state.edit ? "visible" : "hidden";
}

function renderOverlay() {
  const o = $.overlay;
  const brand = h("h2.brand.doto", "Dotdeck");
  let show = true;
  if (state.conn === "unpaired") {
    o.replaceChildren(
      h("div",
        brand,
        h("p", "This device isn't paired yet. Open ", h("b", "Dotdeck"), " on your computer and scan its QR code with this device's camera."),
        h("p", "Both need to be on the same Wi-Fi."),
        h("div.loader.err", h("i"), h("i"), h("i"), h("i"), h("i")),
      ),
    );
  } else if (!state.deck) {
    o.replaceChildren(h("div", brand, h("p", "Connecting to your computer"), h("div.loader", h("i"), h("i"), h("i"), h("i"), h("i"))));
  } else if (state.conn === "lost" && Date.now() - state.lostAt >= 1100) {
    const host = state.hello?.host || "your computer";
    o.replaceChildren(
      h("div",
        brand,
        h("p", "Lost ", h("b", host), ". Reconnecting."),
        h("p", "Is Dotdeck still running there, and is this device on the same Wi-Fi?"),
        h("div.loader", h("i"), h("i"), h("i"), h("i"), h("i")),
      ),
    );
  } else {
    show = false;
  }
  o.classList.toggle("show", show);
}

function applyAccent() {
  const accent = /^#[0-9a-f]{6}$/i.test(settings().accent || "") ? settings().accent : "#D71921";
  const root = document.documentElement.style;
  root.setProperty("--accent", accent);
  const [r, g, b] = [1, 3, 5].map((i) => parseInt(accent.slice(i, i + 2), 16) / 255);
  root.setProperty("--accent-fg", 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.6 ? "#000" : "#fff");
}

// ---------- Pages ----------

export function goTo(id, fromDots = false) {
  const target = pageById(id);
  if (!target) {
    toast("That page no longer exists");
    return;
  }
  if (target.id === state.pageId) return;
  if (target.folder && !fromDots) state.stack.push(state.pageId);
  else state.stack = [];
  state.pageId = target.id;
  if (!target.folder) local.set("page", target.id);
  hooks.closePanel(true);
  render();
}

function goBack() {
  const prev = state.stack.pop();
  state.pageId = prev && pageById(prev) ? prev : mainPages()[0]?.id;
  render();
}

function step(dir) {
  const list = mainPages();
  const page = currentPage();
  const root = page.folder ? state.stack[0] : page.id;
  const i = list.findIndex((p) => p.id === root);
  const next = list[i + dir];
  if (next) {
    haptic(5);
    goTo(next.id, true);
  }
}

function onTitle() {
  const page = currentPage();
  if (!page) return;
  if (state.edit) openPageEditor(page.id);
  else if (page.folder) goBack();
}

function addPage() {
  const page = { id: uid(), name: `Page ${mainPages().length + 1}`, buttons: [] };
  const root = currentPage()?.folder ? state.stack[0] : state.pageId;
  const at = state.deck.pages.findIndex((p) => p.id === root);
  state.deck.pages.splice(at + 1, 0, page);
  changed();
  goTo(page.id, true);
  openPageEditor(page.id);
}

function swipeable(el, accept) {
  el.addEventListener("pointerdown", (e) => {
    if (accept && !accept(e)) return;
    const x0 = e.clientX;
    const y0 = e.clientY;
    const t0 = performance.now();
    const up = (u) => {
      removeEventListener("pointerup", up);
      const dx = u.clientX - x0;
      const dy = u.clientY - y0;
      if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5 && performance.now() - t0 < 900) step(dx < 0 ? 1 : -1);
    };
    addEventListener("pointerup", up);
  });
}

function onKey(e) {
  if (e.target.closest?.("input, textarea, select")) return;
  if (e.key === "Escape" && document.body.classList.contains("panel-open")) closePanel();
  else if (e.key === "ArrowRight") step(1);
  else if (e.key === "ArrowLeft") step(-1);
  else if (e.key === "Backspace" && currentPage()?.folder) goBack();
}

// ---------- Pressing buttons ----------

const NAV = new Set(["page", "back"]);

function needsComputer(b) {
  return b.mode === "toggle" || b.mode === "hold" || (b.actions || []).some((a) => !NAV.has(a.type));
}

function navigateFor(b) {
  for (const a of b.actions || []) {
    if (a.type === "page" && a.page) return goTo(a.page);
    if (a.type === "back") return goBack();
  }
}

function press(b) {
  if (needsComputer(b) && !send({ t: "press", id: b.id })) toast("Not connected to your computer");
}

function onGridDown(e) {
  if (e.button > 0) return;
  const target = e.target.closest(".tile, .cell");
  if (state.edit) return editDown(e, target);
  if (!target || !target.classList.contains("tile")) return;
  const found = findButton(target.dataset.id);
  if (!found) return;
  e.preventDefault();
  const b = found.button;
  const t0 = performance.now();
  target.classList.add("down");
  haptic();
  try {
    target.setPointerCapture(e.pointerId);
  } catch {}
  press(b);
  let done = false;
  const finish = (ev) => {
    if (done) return;
    done = true;
    for (const t of ["pointerup", "pointercancel", "lostpointercapture"]) target.removeEventListener(t, finish);
    if (b.mode === "hold") send({ t: "release", id: b.id });
    setTimeout(() => target.classList.remove("down"), Math.max(0, 110 - (performance.now() - t0)));
    if (ev.type === "pointerup") navigateFor(b);
  };
  for (const t of ["pointerup", "pointercancel", "lostpointercapture"]) target.addEventListener(t, finish);
}

/** Enter / Space on a focused button, for keyboards and switch access. */
function onGridKey(e) {
  if (e.key !== "Enter" && e.key !== " ") return;
  const tile = e.target.closest(".tile");
  const found = tile && findButton(tile.dataset.id);
  if (!found) return;
  e.preventDefault();
  if (e.repeat) return;
  if (state.edit) return openButtonEditor(found.button.id);
  press(found.button);
  if (found.button.mode === "hold") send({ t: "release", id: found.button.id });
  navigateFor(found.button);
}

// ---------- Editing ----------

function toggleEdit() {
  state.edit = !state.edit;
  if (!state.edit) {
    closePanel();
    flush();
  }
  render();
}

function editDown(e, target) {
  if (!target) return;
  e.preventDefault();
  if (target.classList.contains("cell")) {
    try {
      target.setPointerCapture(e.pointerId);
    } catch {}
    const up = (u) => {
      target.removeEventListener("pointerup", up);
      target.removeEventListener("pointercancel", up);
      if (u.type === "pointerup" && Math.hypot(u.clientX - e.clientX, u.clientY - e.clientY) < 10) {
        createAt(+target.dataset.x, +target.dataset.y);
      }
    };
    target.addEventListener("pointerup", up);
    target.addEventListener("pointercancel", up);
    return;
  }
  const tile = target;
  const id = tile.dataset.id;
  let dragging = false;
  let over = null;
  try {
    tile.setPointerCapture(e.pointerId);
  } catch {}
  const move = (m) => {
    const dx = m.clientX - e.clientX;
    const dy = m.clientY - e.clientY;
    if (!dragging && Math.hypot(dx, dy) > 8) {
      dragging = true;
      tile.classList.add("lifted");
      haptic(5);
    }
    if (!dragging) return;
    tile.style.transform = `translate(${dx}px, ${dy}px) scale(1.06)`;
    const under = document.elementFromPoint(m.clientX, m.clientY)?.closest(".tile, .cell");
    const next = under && under !== tile && $.grid.contains(under) ? under : null;
    if (next !== over) {
      over?.classList.remove("drop");
      over = next;
      over?.classList.add("drop");
    }
  };
  const end = (u) => {
    tile.removeEventListener("pointermove", move);
    tile.removeEventListener("pointerup", end);
    tile.removeEventListener("pointercancel", end);
    if (!dragging) {
      if (u.type === "pointerup") openButtonEditor(id);
      return;
    }
    tile.classList.remove("lifted");
    tile.style.transform = "";
    over?.classList.remove("drop");
    if (!over || u.type !== "pointerup") return;
    const page = currentPage();
    const b = page.buttons.find((x) => x.id === id);
    if (over.classList.contains("cell")) {
      b.x = +over.dataset.x;
      b.y = +over.dataset.y;
    } else {
      // Dropped on another button: swap places.
      const other = page.buttons.find((x) => x.id === over.dataset.id);
      [b.x, b.y, other.x, other.y] = [other.x, other.y, b.x, b.y];
    }
    haptic(5);
    changed();
  };
  tile.addEventListener("pointermove", move);
  tile.addEventListener("pointerup", end);
  tile.addEventListener("pointercancel", end);
}

function createAt(x, y) {
  const page = currentPage();
  const b = { id: uid(), x, y, mode: "press", actions: [] };
  page.buttons.push(b);
  changed();
  openButtonEditor(b.id, true);
}

// ---------- Panel (editor / settings) ----------

let panelClose = null;
let panelOpenedAt = 0;

function showPanel({ title, body, foot, onClose, keepScroll }) {
  const oldBody = $.panel.querySelector(".panel-body");
  const scroll = keepScroll && oldBody ? oldBody.scrollTop : 0;
  if (panelClose && !keepScroll) {
    const fn = panelClose;
    panelClose = null;
    fn(true);
  }
  panelClose = onClose || null;
  const bodyEl = h("div.panel-body", body);
  const parts = [h("div.grab"), h("div.panel-head", h("h2.doto", title), round("close", "Close", () => closePanel())), bodyEl];
  if (foot) parts.push(h("div.panel-foot", foot));
  $.panel.replaceChildren(...parts);
  bodyEl.scrollTop = scroll;
  if (!document.body.classList.contains("panel-open")) panelOpenedAt = performance.now();
  document.body.classList.add("panel-open");
}

function closePanel(quiet = false) {
  if (!document.body.classList.contains("panel-open")) return;
  const fn = panelClose;
  panelClose = null;
  hooks.refreshPanel = null;
  state.selected = null;
  document.body.classList.remove("panel-open");
  fn?.();
  flush();
  if (!quiet) render();
}

hooks.render = render;
hooks.showPanel = showPanel;
hooks.closePanel = closePanel;
hooks.goTo = goTo;

// ---------- Fullscreen ----------

function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen?.();
  else document.documentElement.requestFullscreen?.({ navigationUI: "hide" }).catch(() => toast("Fullscreen isn't allowed here"));
}
hooks.toggleFullscreen = toggleFullscreen;
document.addEventListener("fullscreenchange", () => render());

// ---------- Start ----------

build();
state.token = readToken();
render();
connect();
