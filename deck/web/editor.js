// Editing a button (look, behaviour, actions) and a page (name, folder, order).

import {
  armed,
  changed,
  findButton,
  flush,
  freeSpot,
  h,
  hooks,
  mainPages,
  pageById,
  send,
  state,
  toast,
  uid,
} from "./core.js";
import { ICON_NAMES } from "./icons.js";
import { iconNode, loadPicture, paintTile, tileNode } from "./tiles.js";

const STYLES = [
  ["dark", "Dark"],
  ["light", "Light"],
  ["accent", "Accent"],
  ["ghost", "Outline"],
];
const WIDGETS = [
  ["", "None"],
  ["clock", "Clock"],
  ["cpu", "CPU"],
  ["ram", "RAM"],
];
const MODES = [
  ["press", "Tap", "Runs the actions every time you tap it."],
  ["toggle", "Toggle", "Lights up when on. Tapping again runs the \"turned off\" actions."],
  ["hold", "Hold", "Runs, then repeats while your finger stays down, like holding a key. Good for volume."],
];

const isMac = () => state.hello?.os === "macos";
const isWin = () => state.hello?.os === "windows";

export const TYPES = {
  hotkey: { name: "Hotkey", icon: "keyboard", make: () => ({ keys: "" }) },
  text: { name: "Type text", icon: "chat", make: () => ({ text: "" }) },
  media: { name: "Media key", icon: "playpause", make: () => ({ key: "playpause" }) },
  open: { name: "Open app / file", icon: "files", make: () => ({ path: "", args: "" }) },
  url: { name: "Website", icon: "globe", make: () => ({ url: "" }) },
  cmd: { name: "Command", icon: "terminal", make: () => ({ command: "" }) },
  delay: { name: "Wait", icon: "timer", make: () => ({ ms: 250 }) },
  page: { name: "Go to page", icon: "folder", make: () => ({ page: "" }) },
  back: { name: "Back", icon: "back", make: () => ({}) },
};

const MEDIA = [
  ["playpause", "Play / pause"],
  ["prev", "Previous"],
  ["next", "Next"],
  ["stop", "Stop"],
  ["voldown", "Vol -"],
  ["volup", "Vol +"],
  ["mute", "Mute"],
];

// ---------- Little form parts ----------

function section(title, ...kids) {
  return h("div.sec", h("div.sec-title", title), ...kids);
}

function field(label, input, hint) {
  return h("div.field", h("label", label), input, hint ? h("p.hint", hint) : null);
}

function textInput(value, oninput, props = {}) {
  const tag = props.multiline ? "textarea" : "input";
  const el = h(`${tag}.input`, {
    value: value ?? "",
    spellcheck: "false",
    autocomplete: "off",
    autocapitalize: "off",
    ...props,
    multiline: null,
    oninput: (e) => oninput(e.target.value),
  });
  if (props.multiline) el.rows = props.rows || 3;
  return el;
}

function pills(options, current, onpick) {
  const wrap = h("div.pills");
  for (const [value, label] of options) {
    wrap.append(
      h("button.pill", {
        type: "button",
        class: value === current ? "sel" : null,
        "aria-pressed": String(value === current),
        onclick: () => {
          for (const p of wrap.children) {
            p.classList.remove("sel");
            p.setAttribute("aria-pressed", "false");
          }
          const me = [...wrap.children][options.findIndex((o) => o[0] === value)];
          me.classList.add("sel");
          me.setAttribute("aria-pressed", "true");
          onpick(value);
        },
      }, label),
    );
  }
  return wrap;
}

export function toggleRow(title, hint, on, onchange) {
  const sw = h("button.switch", { type: "button", role: "switch", "aria-checked": String(!!on), class: on ? "on" : null });
  sw.addEventListener("click", () => {
    const next = !sw.classList.contains("on");
    sw.classList.toggle("on", next);
    sw.setAttribute("aria-checked", String(next));
    onchange(next);
  });
  return h("div.row", h("div.grow", h("div.row-title", title), hint ? h("p.hint", hint) : null), sw);
}

// ---------- Button editor ----------

export function openButtonEditor(id, isNew = false) {
  if (!findButton(id)) return;
  state.selected = id;
  hooks.render();
  let preview = null;
  let adding = null; // which action list is showing its "add" picker

  const refreshPreview = () => {
    const f = findButton(id);
    if (f && preview) paintTile(preview, f.button);
  };
  const update = () => {
    changed();
    refreshPreview();
  };

  const draw = (keepScroll = true) => {
    const f = findButton(id);
    if (!f) {
      hooks.closePanel();
      return;
    }
    const { button: b, page } = f;
    preview = tileNode(b);
    preview.tabIndex = -1;
    const body = [
      h("div.preview", preview),
      lookSection(b, update, draw),
      section("Behaviour", h("div.card",
        pills(MODES.map(([v, l]) => [v, l]), b.mode || "press", (v) => {
          b.mode = v;
          if (v === "toggle") b.off = b.off || [];
          update();
          draw();
        }),
        h("p.hint", MODES.find((m) => m[0] === (b.mode || "press"))[2]),
      )),
      section(b.mode === "toggle" ? "When turned on" : "Actions", actionList(b, "actions", update, draw, adding, (v) => (adding = v))),
      b.mode === "toggle"
        ? section("When turned off", actionList(b, "off", update, draw, adding, (v) => (adding = v)))
        : null,
      section("Move", h("div.card", field("To page", pageSelect(page.id, (target) => moveTo(b, page, target))))),
    ];

    const test = h("button.pill.quiet", {
      type: "button",
      onclick: () => {
        const list = (b.actions || []).filter((a) => a.type !== "page" && a.type !== "back");
        if (!list.length) return toast("Nothing to test here: add an action first");
        if (!send({ t: "run", actions: list })) toast("Not connected to your computer");
      },
    }, iconNode("play"), "Test");
    const copy = h("button.pill.quiet", { type: "button", onclick: () => duplicate(b, page) }, "Copy");
    const del = armed(h("button.pill.danger", { type: "button" }, "Delete"), "Sure?", () => {
      page.buttons = page.buttons.filter((x) => x.id !== b.id);
      changed();
      hooks.closePanel();
    });
    const done = h("button.pill.primary", { type: "button", onclick: () => hooks.closePanel() }, "Done");

    hooks.showPanel({
      title: isNew ? "New button" : "Button",
      body,
      foot: [test, copy, del, h("span.grow"), done],
      keepScroll,
      onClose: () => {
        // A new button left empty isn't worth keeping.
        const f2 = findButton(id);
        if (f2 && isBlank(f2.button)) {
          f2.page.buttons = f2.page.buttons.filter((x) => x.id !== id);
          changed();
        }
      },
    });
    hooks.refreshPanel = () => draw();
  };
  draw(false);
}

function isBlank(b) {
  return !b.label && !b.sub && !b.icon && !b.image && !b.widget && !(b.actions || []).length && !(b.off || []).length;
}

function lookSection(b, update, draw) {
  const iconGrid = h("div.icons", { role: "listbox", "aria-label": "Icon" });
  const pick = (name) => {
    b.icon = name || undefined;
    if (name) {
      delete b.image;
      delete b.dots;
      delete b.fill;
    }
    update();
    draw();
  };
  iconGrid.append(
    h("button.none", { type: "button", class: !b.icon ? "sel" : null, "aria-label": "No icon", onclick: () => pick(null) }, "NONE"),
    ...ICON_NAMES.map((n) =>
      h("button", { type: "button", class: b.icon === n && !b.image ? "sel" : null, title: n, "aria-label": n, onclick: () => pick(n) }, iconNode(n)),
    ),
  );

  const file = h("input", {
    type: "file",
    accept: "image/*",
    style: { display: "none" },
    onchange: async (e) => {
      const f = e.target.files?.[0];
      if (!f) return;
      try {
        b.image = await loadPicture(f);
        update();
        draw();
      } catch (err) {
        toast(err.message);
      }
    },
  });
  const picture = h("div.card",
    h("div.pills",
      h("button.pill", { type: "button", onclick: () => file.click() }, iconNode("image"), b.image ? "Change picture" : "Add picture"),
      b.image
        ? h("button.pill.quiet", { type: "button", onclick: () => { delete b.image; delete b.dots; delete b.fill; update(); draw(); } }, "Remove")
        : null,
      file,
    ),
    b.image ? toggleRow("Dot effect", "Redraw the picture in coloured dots.", b.dots, (v) => { b.dots = v || undefined; update(); }) : null,
    b.image ? toggleRow("Fill the button", null, b.fill, (v) => { b.fill = v || undefined; update(); }) : null,
  );

  return section("Look",
    h("div.card",
      field("Label", textInput(b.label, (v) => { b.label = v || undefined; update(); }, { placeholder: "e.g. Mute", maxlength: 40 })),
      field("Second line", textInput(b.sub, (v) => { b.sub = v || undefined; update(); }, { placeholder: "Optional", maxlength: 40 }),
        "Live values work in both: {cpu}% · {ram}% · {time} · {day} · {date}"),
    ),
    h("div.card", field("Icon", iconGrid)),
    picture,
    h("div.card",
      field("Colour", pills(STYLES, b.style || "dark", (v) => { b.style = v === "dark" ? undefined : v; update(); })),
      field("Show", pills(WIDGETS, b.widget || "", (v) => { b.widget = v || undefined; update(); })),
    ),
  );
}

function pageSelect(current, onpick, { allowNew = false } = {}) {
  const sel = h("select.input", {
    onchange: (e) => onpick(e.target.value),
  });
  if (!current) sel.append(h("option", { value: "", selected: true, disabled: true }, "Pick a page"));
  for (const p of state.deck.pages) {
    sel.append(h("option", { value: p.id, selected: p.id === current }, (p.folder ? "Folder: " : "") + (p.name || "Page")));
  }
  if (allowNew) sel.append(h("option", { value: "__new" }, "+ New folder"));
  return sel;
}

function moveTo(b, from, targetId) {
  const target = pageById(targetId);
  if (!target || target === from) return;
  const spot = freeSpot(target);
  if (!spot) {
    toast(`${target.name} is full`);
    hooks.refreshPanel?.();
    return;
  }
  from.buttons = from.buttons.filter((x) => x.id !== b.id);
  Object.assign(b, spot);
  target.buttons.push(b);
  changed();
  hooks.closePanel();
  toast(`Moved to ${target.name}`, true);
}

function duplicate(b, page) {
  const spot = freeSpot(page);
  if (!spot) return toast("This page is full");
  const copy = JSON.parse(JSON.stringify(b));
  copy.id = uid();
  Object.assign(copy, spot);
  page.buttons.push(copy);
  changed();
  openButtonEditor(copy.id);
}

// ---------- Actions ----------

function actionList(b, key, update, draw, adding, setAdding) {
  const list = (b[key] = b[key] || []);
  const wrap = h("div");
  list.forEach((a, i) => wrap.append(actionCard(a, i, list, update, draw)));
  if (!list.length) wrap.append(h("p.hint", { style: { margin: "0 4px 10px" } }, key === "off" ? "Nothing happens when it turns off." : "Nothing happens yet. Add what this button should do."));
  if (adding === key) {
    wrap.append(h("div.card",
      h("div.types", Object.entries(TYPES).map(([type, t]) =>
        h("button.pill.quiet", {
          type: "button",
          onclick: () => {
            list.push({ type, ...t.make() });
            setAdding(null);
            update();
            draw();
          },
        }, iconNode(t.icon), t.name),
      )),
      h("div.pills", { style: { marginTop: "10px" } }, h("button.pill.small.quiet", { type: "button", onclick: () => { setAdding(null); draw(); } }, "Cancel")),
    ));
  } else {
    wrap.append(h("button.pill", { type: "button", style: { marginTop: list.length ? "10px" : "0" }, onclick: () => { setAdding(key); draw(); } }, iconNode("plus"), "Add action"));
  }
  return wrap;
}

function actionCard(a, i, list, update, draw) {
  const t = TYPES[a.type] || { name: a.type, icon: "dot" };
  const mini = (icon, label, onclick, disabled) =>
    h("button.mini", { type: "button", "aria-label": label, title: label, disabled, onclick }, iconNode(icon));
  const head = h("div.action-head",
    iconNode(t.icon),
    h("span.name", t.name),
    mini("up", "Move up", () => { [list[i - 1], list[i]] = [list[i], list[i - 1]]; update(); draw(); }, i === 0),
    mini("down", "Move down", () => { [list[i + 1], list[i]] = [list[i], list[i + 1]]; update(); draw(); }, i === list.length - 1),
    mini("close", "Remove", () => { list.splice(i, 1); update(); draw(); }),
  );
  return h("div.card.action", head, actionFields(a, update, draw));
}

function actionFields(a, update, draw) {
  const set = (k) => (v) => {
    a[k] = v;
    update();
  };
  switch (a.type) {
    case "hotkey":
      return hotkeyFields(a, update);
    case "text":
      return field("Text to type", textInput(a.text, set("text"), { multiline: true, rows: 3 }));
    case "media":
      return pills(MEDIA.filter(([k]) => !(k === "stop" && isMac())), a.key, set("key"));
    case "open":
      return h("div",
        field("App, file or folder", textInput(a.path, set("path"), {
          placeholder: isWin() ? "notepad  or  C:\\Games\\game.exe" : isMac() ? "/Applications/Spotify.app" : "firefox  or  ~/Documents",
          class: "mono",
        })),
        field("Arguments", textInput(a.args, set("args"), { placeholder: "Optional", class: "mono" }),
          "Opens with its usual app. With arguments, runs it directly."),
      );
    case "url":
      return field("Address", textInput(a.url, set("url"), { placeholder: "https://…", inputmode: "url" }),
        "App links work too, like spotify: or steam://");
    case "cmd":
      return field("Command", textInput(a.command, set("command"), { multiline: true, rows: 2, class: "mono", placeholder: isWin() ? "shutdown /s /t 60" : "say hello" }),
        isWin() ? "Runs in cmd, in the background." : "Runs in sh, in the background.");
    case "delay":
      return field("Milliseconds", textInput(a.ms, (v) => { a.ms = Math.max(0, Math.min(60000, parseInt(v, 10) || 0)); update(); }, { type: "number", inputmode: "numeric", min: 0, max: 60000, step: 50 }),
        "Pauses before the next action.");
    case "page":
      return field("Page", pageSelect(a.page, (v) => {
        if (v === "__new") {
          const b = findButton(state.selected)?.button;
          const folder = {
            id: uid(),
            name: b?.label || "Folder",
            folder: true,
            buttons: [{ id: uid(), x: 0, y: 0, mode: "press", icon: "back", label: "Back", style: "ghost", actions: [{ type: "back" }] }],
          };
          state.deck.pages.push(folder);
          a.page = folder.id;
          update();
          draw();
          toast(`Made folder "${folder.name}". Open it from this button to fill it.`, true);
        } else {
          a.page = v;
          update();
        }
      }, { allowNew: true }), "Folders stay out of the page dots and have a back arrow.");
    case "back":
      return h("p.hint", { style: { margin: 0 } }, "Goes back from a folder to where you came from.");
    default:
      return h("p.hint", "This action comes from a newer Dotdeck.");
  }
}

// ---------- Hotkeys ----------

const MODS = [
  ["ctrl", ["ctrl", "control", "ctl"]],
  ["shift", ["shift"]],
  ["alt", ["alt", "option", "opt"]],
  ["win", ["win", "windows", "cmd", "command", "meta", "super"]],
];

const modOf = (part) => MODS.find(([, names]) => names.includes(part.toLowerCase()))?.[0];

function splitFirst(keys) {
  const combos = (keys || "").split(",");
  return { combos, parts: combos[0].split("+").map((s) => s.trim()).filter(Boolean) };
}

function toggleMod(keys, mod) {
  const { combos, parts } = splitFirst(keys);
  const has = parts.some((p) => modOf(p) === mod);
  const next = has ? parts.filter((p) => modOf(p) !== mod) : [mod === "win" && isMac() ? "cmd" : mod, ...parts];
  const order = (p) => {
    const m = modOf(p);
    return m ? MODS.findIndex(([n]) => n === m) : 9;
  };
  next.sort((x, y) => order(x) - order(y));
  combos[0] = next.join("+");
  return combos.map((c) => c.trim()).filter(Boolean).join(", ");
}

const CODE_NAMES = {
  Enter: "enter", NumpadEnter: "enter", Escape: "esc", Tab: "tab", Space: "space", Backspace: "backspace",
  Delete: "delete", Insert: "insert", Home: "home", End: "end", PageUp: "pageup", PageDown: "pagedown",
  ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right", CapsLock: "capslock",
  PrintScreen: "printscreen", Minus: "minus", Equal: "equal", Comma: "comma", Period: "period", Slash: "slash",
  Backslash: "backslash", Semicolon: "semicolon", Quote: "quote", Backquote: "backquote",
  BracketLeft: "bracketleft", BracketRight: "bracketright", NumpadAdd: "numadd", NumpadSubtract: "numsub",
  NumpadMultiply: "nummul", NumpadDivide: "numdiv", NumpadDecimal: "numdec", MediaPlayPause: "playpause",
  MediaTrackNext: "next", MediaTrackPrevious: "prev", MediaStop: "stop", AudioVolumeUp: "volup",
  AudioVolumeDown: "voldown", AudioVolumeMute: "mute",
};

function keyName(e) {
  const c = e.code || "";
  if (/^Key[A-Z]$/.test(c)) return c.slice(3).toLowerCase();
  if (/^Digit\d$/.test(c)) return c.slice(5);
  if (/^F\d{1,2}$/.test(c)) return c.toLowerCase();
  if (/^Numpad\d$/.test(c)) return "num" + c.slice(6);
  return CODE_NAMES[c] || null;
}

function hotkeyFields(a, update) {
  const input = textInput(a.keys, (v) => { a.keys = v; update(); syncChips(); }, { placeholder: "ctrl+shift+m", class: "mono" });
  const chips = h("div.pills");
  const syncChips = () => {
    const { parts } = splitFirst(a.keys);
    chips.querySelectorAll("[data-mod]").forEach((c) => c.classList.toggle("sel", parts.some((p) => modOf(p) === c.dataset.mod)));
  };
  for (const [mod] of MODS) {
    const label = mod === "win" ? (isMac() ? "Cmd" : "Win") : mod === "alt" && isMac() ? "Opt" : mod;
    const chip = h("button.pill.small", {
      type: "button",
      onclick: () => {
        a.keys = toggleMod(a.keys, mod);
        input.value = a.keys;
        update();
        syncChips();
      },
    }, label);
    chip.dataset.mod = mod;
    chips.append(chip);
  }

  let recording = false;
  const rec = h("button.pill.small.quiet", { type: "button" }, iconNode("record"), "Record");
  const stop = () => {
    recording = false;
    rec.lastChild.textContent = "Record";
    input.classList.remove("recording");
    removeEventListener("keydown", onDown, true);
    removeEventListener("keyup", onUp, true);
  };
  let pressedKey = false;
  const held = (e) => [e.ctrlKey && "ctrl", e.shiftKey && "shift", e.altKey && "alt", e.metaKey && (isMac() ? "cmd" : "win")].filter(Boolean);
  const onDown = (e) => {
    e.preventDefault();
    e.stopPropagation();
    const k = keyName(e);
    if (!k) return; // a modifier on its own: wait for the real key
    pressedKey = true;
    a.keys = [...held(e), k].join("+");
    input.value = a.keys;
    update();
    syncChips();
    stop();
  };
  const onUp = (e) => {
    // Letting go of a lone modifier (e.g. just the Windows key) records that modifier.
    if (pressedKey) return;
    const mod = { Control: "ctrl", Shift: "shift", Alt: "alt", Meta: isMac() ? "cmd" : "win" }[e.key];
    if (!mod) return;
    e.preventDefault();
    a.keys = mod;
    input.value = a.keys;
    update();
    syncChips();
    stop();
  };
  rec.addEventListener("click", () => {
    if (recording) return stop();
    recording = true;
    pressedKey = false;
    rec.lastChild.textContent = "Press keys…";
    input.classList.add("recording");
    addEventListener("keydown", onDown, true);
    addEventListener("keyup", onUp, true);
  });
  chips.append(rec);
  syncChips();
  return h("div",
    field("Keys", input, "Like ctrl+shift+m · alt+f4 · f13 · ctrl+k, ctrl+c. Record works with a keyboard."),
    h("div", { style: { marginTop: "10px" } }, chips),
  );
}

// ---------- Page editor ----------

export function openPageEditor(pageId) {
  const draw = () => {
    const page = pageById(pageId);
    if (!page) return hooks.closePanel();
    const list = mainPages();
    const i = list.indexOf(page);
    const move = (dir) => {
      const other = list[i + dir];
      if (!other) return;
      const pages = state.deck.pages;
      const a = pages.indexOf(page);
      const b = pages.indexOf(other);
      [pages[a], pages[b]] = [pages[b], pages[a]];
      changed();
      draw();
    };
    const body = [
      section("Page", h("div.card",
        field("Name", textInput(page.name, (v) => { page.name = v; changed(); }, { maxlength: 24, placeholder: "Page" })),
        toggleRow("Folder", "Hidden from the page dots. Open it with a button's \"Go to page\" action.", page.folder, (v) => {
          page.folder = v || undefined;
          if (v && state.stack.length === 0) state.stack = [list.find((p) => p !== page)?.id].filter(Boolean);
          if (!v) state.stack = [];
          changed();
          draw();
        }),
      )),
      !page.folder && list.length > 1
        ? section("Order", h("div.card", h("div.pills",
          h("button.pill", { type: "button", disabled: i <= 0, onclick: () => move(-1) }, iconNode("left"), "Earlier"),
          h("button.pill", { type: "button", disabled: i >= list.length - 1, onclick: () => move(1) }, "Later", iconNode("right")),
        )))
        : null,
    ];
    const del = armed(h("button.pill.danger", { type: "button" }, "Delete page"), "Sure? Buttons too", () => {
      if (!page.folder && mainPages().length <= 1) return toast("Keep at least one page");
      state.deck.pages = state.deck.pages.filter((p) => p !== page);
      state.pageId = state.stack.pop() || mainPages()[0]?.id;
      changed();
      hooks.closePanel();
    });
    hooks.showPanel({
      title: page.folder ? "Folder" : "Page",
      body,
      foot: [del, h("span.grow"), h("button.pill.primary", { type: "button", onclick: () => hooks.closePanel() }, "Done")],
      keepScroll: true,
      onClose: () => flush(),
    });
    hooks.refreshPanel = draw;
  };
  draw();
}

export { section, field, pills, textInput };
