// Settings: pairing another device, grid size, accent, this device's preferences, backup.

import { armed, changed, h, hooks, local, send, settings, state, toast, cols, rows, uid } from "./core.js";
import { field, section, toggleRow } from "./editor.js";
import { iconNode } from "./tiles.js";

const ACCENTS = [
  ["Red", "#D71921"],
  ["Orange", "#FF6A1A"],
  ["Yellow", "#FFC21A"],
  ["Green", "#2ED573"],
  ["Blue", "#2F80FF"],
  ["Purple", "#9B5CFF"],
  ["Pink", "#FF4D97"],
  ["White", "#F2F2F2"],
];

let qrIndex = 0;

export function openSettings() {
  const draw = (keepScroll = true) => {
    hooks.showPanel({ title: "Settings", body: body(draw), keepScroll });
    hooks.refreshPanel = () => draw();
  };
  draw(false);
}

function body(draw) {
  return [pairing(draw), grid(draw), look(draw), device(draw), backup(), danger(), about()];
}

function pairing(draw) {
  const urls = state.hello?.urls || [];
  if (!state.token || !urls.length) return null;
  qrIndex = Math.min(qrIndex, urls.length - 1);
  const link = `${urls[qrIndex]}/#k=${state.token}`;
  return section("Pair a device", h("div.card",
    h("div.qr", h("img", {
      src: `api/qr.svg?k=${encodeURIComponent(state.token)}&i=${qrIndex}`,
      alt: "QR code to pair a phone or tablet",
      width: 240,
      height: 240,
    })),
    h("p.link", link),
    urls.length > 1
      ? h("div.pills", { style: { justifyContent: "center", marginTop: "12px" } },
        urls.map((u, i) => h("button.pill.small", {
          type: "button",
          class: i === qrIndex ? "sel" : null,
          onclick: () => {
            qrIndex = i;
            draw();
          },
        }, u.replace(/^https?:\/\//, "").replace(/:\d+$/, ""))))
      : null,
    h("p.hint", { style: { textAlign: "center" } },
      "Scan with the phone's camera. It must be on the same Wi-Fi as ",
      h("b", state.hello?.host || "this computer"),
      ". Can't connect? Try the other address, or allow Dotdeck through the firewall."),
  ));
}

function stepper(value, min, max, onchange) {
  const out = h("b.doto", String(value));
  const set = (v) => {
    v = Math.min(max, Math.max(min, v));
    out.textContent = v;
    onchange(v);
  };
  return h("div.stepper",
    h("button.round", { type: "button", "aria-label": "Fewer", onclick: () => set(+out.textContent - 1) }, iconNode("minus")),
    out,
    h("button.round", { type: "button", "aria-label": "More", onclick: () => set(+out.textContent + 1) }, iconNode("plus")),
  );
}

function grid() {
  const s = () => (state.deck.settings = state.deck.settings || {});
  return section("Grid", h("div.card",
    h("div.row", h("div.grow.row-title", "Columns"), stepper(cols(), 1, 10, (v) => { s().cols = v; changed(); })),
    h("div.row", h("div.grow.row-title", "Rows"), stepper(rows(), 1, 8, (v) => { s().rows = v; changed(); })),
    h("p.hint", "Shared by every device. Buttons outside a smaller grid are kept, just hidden."),
  ));
}

function look(draw) {
  const current = (settings().accent || "#D71921").toUpperCase();
  return section("Look", h("div.card",
    field("Accent colour", h("div.swatches", ACCENTS.map(([name, hex]) =>
      h("button.swatch", {
        type: "button",
        class: hex === current ? "sel" : null,
        "aria-label": name,
        title: name,
        style: { background: hex },
        onclick: () => {
          state.deck.settings.accent = hex;
          changed();
          draw();
        },
      })))),
    h("div", { style: { marginTop: "16px" } },
      toggleRow("24-hour clock", null, settings().clock24 !== false, (v) => { state.deck.settings.clock24 = v; changed(); })),
  ));
}

function device() {
  return section("This device", h("div.card",
    toggleRow("Haptics", "A small buzz on every press (Android).", local.get("haptics", true), (v) => local.set("haptics", v)),
    toggleRow("Turn grid in portrait", "A wide deck turns sideways when this device is upright, so buttons stay big.",
      local.get("rotate", true), (v) => { local.set("rotate", v); hooks.render(); }),
    document.fullscreenEnabled
      ? h("div.pills", { style: { marginTop: "16px" } },
        h("button.pill", { type: "button", onclick: () => hooks.toggleFullscreen() }, iconNode("maximize"), document.fullscreenElement ? "Exit fullscreen" : "Fullscreen"))
      : h("p.hint", "Tip: add this page to your home screen for a full-screen deck."),
  ));
}

function backup() {
  const file = h("input", {
    type: "file",
    accept: "application/json,.json",
    style: { display: "none" },
    onchange: async (e) => {
      const f = e.target.files?.[0];
      if (!f) return;
      try {
        const deck = JSON.parse(await f.text());
        if (!Array.isArray(deck.pages) || !deck.pages.length) throw new Error("no pages");
        for (const p of deck.pages) {
          p.id = p.id || uid();
          p.buttons = (p.buttons || []).map((b) => ({ ...b, id: b.id || uid(), x: b.x | 0, y: b.y | 0 }));
        }
        state.deck = deck;
        state.pageId = null;
        state.stack = [];
        changed();
        toast("Deck imported", true);
      } catch {
        toast("That file isn't a Dotdeck backup");
      }
    },
  });
  const exportDeck = () => {
    const blob = new Blob([JSON.stringify(state.deck, null, 2)], { type: "application/json" });
    const a = h("a", { href: URL.createObjectURL(blob), download: "dotdeck.json" });
    document.body.append(a);
    a.click();
    setTimeout(() => {
      URL.revokeObjectURL(a.href);
      a.remove();
    }, 1000);
  };
  return section("Backup", h("div.card",
    h("div.pills",
      h("button.pill", { type: "button", onclick: exportDeck }, iconNode("download"), "Export"),
      h("button.pill", { type: "button", onclick: () => file.click() }, iconNode("upload"), "Import"),
      file,
    ),
    h("p.hint", "Saves every page and button to one file. Importing replaces the current deck."),
  ));
}

function danger() {
  const btn = armed(h("button.pill.danger", { type: "button" }, "Unpair every device"), "Tap again to unpair", () => {
    if (!send({ t: "reset" })) toast("Not connected to your computer");
  });
  return section("Security", h("div.card",
    h("div.pills", btn),
    h("p.hint", "Makes a new QR code. Every phone, tablet and browser has to scan it again, including this one."),
  ));
}

function about() {
  const hello = state.hello || {};
  const os = { windows: "Windows", macos: "macOS", linux: "Linux" }[hello.os] || hello.os || "";
  return h("p.hint", { style: { textAlign: "center", marginTop: "26px" } },
    `Dotdeck ${hello.version || ""} · ${hello.host || ""} · ${os}`, h("br"), "Doto & Space Mono · SIL Open Font License");
}
