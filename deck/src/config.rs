//! Everything Dotdeck remembers, kept in one JSON file: the pairing token, the port and the deck
//! itself (pages of buttons). Written atomically, with the previous version kept as a backup.

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub const DEFAULT_PORT: u16 = 8420;

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Config {
    pub token: String,
    #[serde(default = "default_port")]
    pub port: u16,
    pub deck: Deck,
}

fn default_port() -> u16 {
    DEFAULT_PORT
}

/// The deck as the clients see it. The server only interprets what it needs to run buttons (ids,
/// modes and actions); labels, icons, colours and layout ride along untouched in `ui`.
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Deck {
    #[serde(default)]
    pub settings: Map<String, Value>,
    pub pages: Vec<Page>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Page {
    pub id: String,
    #[serde(default)]
    pub buttons: Vec<Button>,
    #[serde(flatten)]
    pub ui: Map<String, Value>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Button {
    pub id: String,
    /// "press" (default), "toggle" (alternates between `actions` and `off`) or "hold" (repeats while held).
    #[serde(default)]
    pub mode: Mode,
    #[serde(default)]
    pub actions: Vec<Action>,
    /// Actions for turning a toggle button off.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub off: Vec<Action>,
    #[serde(flatten)]
    pub ui: Map<String, Value>,
}

#[derive(Serialize, Deserialize, Clone, Copy, Debug, Default, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Mode {
    #[default]
    Press,
    Toggle,
    Hold,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum Action {
    /// One or more key combos, e.g. `ctrl+shift+m` or `ctrl+k, ctrl+c`.
    Hotkey {
        keys: String,
    },
    /// Types the text as if from a keyboard.
    Text {
        text: String,
    },
    /// playpause, next, prev, stop, volup, voldown, mute.
    Media {
        key: String,
    },
    /// Opens a file, folder or app with its default handler, or runs it with `args`.
    Open {
        path: String,
        #[serde(default)]
        args: String,
    },
    Url {
        url: String,
    },
    /// A shell command (cmd on Windows, sh elsewhere). Fire and forget.
    Cmd {
        command: String,
    },
    Delay {
        ms: u64,
    },
    /// Page navigation happens on the device that pressed the button, not here.
    Page {
        #[serde(default)]
        page: String,
    },
    Back,
    #[serde(other)]
    Unknown,
}

impl Deck {
    pub fn button(&self, id: &str) -> Option<&Button> {
        self.pages
            .iter()
            .flat_map(|p| p.buttons.iter())
            .find(|b| b.id == id)
    }

    pub fn buttons(&self) -> impl Iterator<Item = &Button> {
        self.pages.iter().flat_map(|p| p.buttons.iter())
    }
}

pub struct Store {
    dir: PathBuf,
    pub cfg: Mutex<Config>,
}

impl Store {
    pub fn default_dir() -> PathBuf {
        dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("Dotdeck")
    }

    /// Loads the config from `dir`, creating a fresh one (new token, starter deck) if there is none.
    pub fn open(dir: &Path) -> std::io::Result<Store> {
        fs::create_dir_all(dir)?;
        let path = dir.join("config.json");
        let cfg = match fs::read(&path) {
            Ok(bytes) => match serde_json::from_slice::<Config>(&bytes) {
                Ok(cfg) => cfg,
                Err(e) => {
                    // Keep the unreadable file for the user rather than silently overwriting it.
                    let broken = dir.join("config.broken.json");
                    let _ = fs::rename(&path, &broken);
                    tracing::warn!(
                        "config.json was unreadable ({e}); moved it to {}",
                        broken.display()
                    );
                    fresh()
                }
            },
            Err(_) => fresh(),
        };
        let store = Store {
            dir: dir.to_path_buf(),
            cfg: Mutex::new(cfg),
        };
        store.save()?;
        Ok(store)
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    pub fn save(&self) -> std::io::Result<()> {
        let cfg = self.cfg.lock().unwrap();
        let bytes = serde_json::to_vec_pretty(&*cfg).map_err(std::io::Error::other)?;
        drop(cfg);
        let path = self.dir.join("config.json");
        let tmp = self.dir.join("config.json.tmp");
        fs::write(&tmp, bytes)?;
        if path.exists() {
            let _ = fs::copy(&path, self.dir.join("config.backup.json"));
        }
        fs::rename(&tmp, &path)
    }

    pub fn token(&self) -> String {
        self.cfg.lock().unwrap().token.clone()
    }
}

pub fn new_token() -> String {
    use base64::Engine;
    let mut bytes = [0u8; 18];
    rand::fill(&mut bytes);
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

pub fn new_id() -> String {
    let mut bytes = [0u8; 6];
    rand::fill(&mut bytes);
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn fresh() -> Config {
    Config {
        token: new_token(),
        port: DEFAULT_PORT,
        deck: starter_deck(),
    }
}

/// Hotkeys differ per OS, so the starter deck is built for the machine it first runs on.
struct Os {
    screenshot: &'static str,
    lock: &'static str,
    calc: &'static str,
    terminal: &'static str,
    mod_key: &'static str,
    /// The window-control folder: (icon, label, keys).
    windows: [(&'static str, &'static str, &'static str); 8],
}

fn os() -> Os {
    if cfg!(target_os = "macos") {
        Os {
            screenshot: "cmd+shift+4",
            lock: "ctrl+cmd+q",
            calc: "/System/Applications/Calculator.app",
            terminal: "/System/Applications/Utilities/Terminal.app",
            mod_key: "cmd",
            windows: [
                ("minimize", "Minimise", "cmd+m"),
                ("maximize", "Full scr", "ctrl+cmd+f"),
                ("hide", "Hide", "cmd+h"),
                ("grid", "Mission", "ctrl+up"),
                ("desktop", "Desktop", "f11"),
                ("switch", "Switch", "cmd+tab"),
                ("close", "Close", "cmd+w"),
                ("power", "Quit app", "cmd+q"),
            ],
        }
    } else if cfg!(windows) {
        Os {
            screenshot: "win+shift+s",
            lock: "win+l",
            calc: "calc.exe",
            terminal: "cmd.exe",
            mod_key: "ctrl",
            windows: [
                ("left", "Snap L", "win+left"),
                ("maximize", "Max", "win+up"),
                ("right", "Snap R", "win+right"),
                ("grid", "Tasks", "win+tab"),
                ("desktop", "Desktop", "win+d"),
                ("minimize", "Min", "win+down"),
                ("switch", "Switch", "alt+tab"),
                ("close", "Close", "alt+f4"),
            ],
        }
    } else {
        Os {
            screenshot: "printscreen",
            lock: "win+l",
            calc: "gnome-calculator",
            terminal: "x-terminal-emulator",
            mod_key: "ctrl",
            windows: [
                ("left", "Snap L", "win+left"),
                ("maximize", "Max", "win+up"),
                ("right", "Snap R", "win+right"),
                ("grid", "Overview", "win"),
                ("desktop", "Desktop", "win+d"),
                ("minimize", "Min", "win+h"),
                ("switch", "Switch", "alt+tab"),
                ("close", "Close", "alt+f4"),
            ],
        }
    }
}

fn btn(x: u32, y: u32, ui: Value, mode: &str, actions: Value) -> Value {
    let mut b = json!({ "id": new_id(), "x": x, "y": y, "mode": mode, "actions": actions });
    if let (Some(obj), Value::Object(extra)) = (b.as_object_mut(), ui) {
        obj.extend(extra);
    }
    b
}

pub fn starter_deck() -> Deck {
    let o = os();
    let home = dirs::home_dir()
        .map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_else(|| "~".into());
    let copy = format!("{}+c", o.mod_key);
    let paste = format!("{}+v", o.mod_key);
    let mut window_buttons: Vec<Value> = o
        .windows
        .iter()
        .enumerate()
        .map(|(i, (icon, label, keys))| {
            let (x, y) = (i as u32 % 5, i as u32 / 5);
            btn(
                x,
                y,
                json!({ "icon": icon, "label": label }),
                "press",
                json!([{ "type": "hotkey", "keys": keys }]),
            )
        })
        .collect();
    window_buttons.push(btn(
        4,
        2,
        json!({ "icon": "back", "label": "Back", "style": "ghost" }),
        "press",
        json!([{ "type": "back" }]),
    ));
    let deck = json!({
        "settings": { "cols": 5, "rows": 3, "accent": "#D71921", "clock24": true },
        "pages": [
            {
                "id": "main", "name": "Main",
                "buttons": [
                    btn(0, 0, json!({ "widget": "clock" }), "press", json!([])),
                    btn(1, 0, json!({ "widget": "cpu", "label": "CPU" }), "press", json!([])),
                    btn(2, 0, json!({ "widget": "ram", "label": "RAM" }), "press", json!([])),
                    btn(3, 0, json!({ "icon": "globe", "label": "Web" }), "press",
                        json!([{ "type": "url", "url": "https://www.google.com" }])),
                    btn(4, 0, json!({ "icon": "folder", "label": "Windows", "style": "light" }), "press",
                        json!([{ "type": "page", "page": "windows" }])),
                    btn(0, 1, json!({ "icon": "prev", "label": "Prev" }), "press",
                        json!([{ "type": "media", "key": "prev" }])),
                    btn(1, 1, json!({ "icon": "playpause", "label": "Play", "style": "accent" }), "press",
                        json!([{ "type": "media", "key": "playpause" }])),
                    btn(2, 1, json!({ "icon": "next", "label": "Next" }), "press",
                        json!([{ "type": "media", "key": "next" }])),
                    btn(3, 1, json!({ "icon": "voldown", "label": "Vol -" }), "hold",
                        json!([{ "type": "media", "key": "voldown" }])),
                    btn(4, 1, json!({ "icon": "volup", "label": "Vol +" }), "hold",
                        json!([{ "type": "media", "key": "volup" }])),
                    btn(0, 2, json!({ "icon": "mute", "label": "Mute", "off": [{ "type": "media", "key": "mute" }] }),
                        "toggle", json!([{ "type": "media", "key": "mute" }])),
                    btn(1, 2, json!({ "icon": "copy", "label": "Copy" }), "press",
                        json!([{ "type": "hotkey", "keys": copy }])),
                    btn(2, 2, json!({ "icon": "paste", "label": "Paste" }), "press",
                        json!([{ "type": "hotkey", "keys": paste }])),
                    btn(3, 2, json!({ "icon": "screenshot", "label": "Snip" }), "press",
                        json!([{ "type": "hotkey", "keys": o.screenshot }])),
                    btn(4, 2, json!({ "icon": "lock", "label": "Lock" }), "press",
                        json!([{ "type": "hotkey", "keys": o.lock }])),
                ]
            },
            {
                "id": "apps", "name": "Apps",
                "buttons": [
                    btn(0, 0, json!({ "icon": "files", "label": "Home" }), "press",
                        json!([{ "type": "open", "path": home }])),
                    btn(1, 0, json!({ "icon": "calc", "label": "Calc" }), "press",
                        json!([{ "type": "open", "path": o.calc }])),
                    btn(2, 0, json!({ "icon": "terminal", "label": "Term" }), "press",
                        json!([{ "type": "open", "path": o.terminal }])),
                    btn(3, 0, json!({ "icon": "keyboard", "label": "Hello" }), "press",
                        json!([{ "type": "text", "text": "Hello from Dotdeck" }])),
                    btn(4, 0, json!({ "icon": "code", "label": "GitHub" }), "press",
                        json!([{ "type": "url", "url": "https://github.com" }])),
                    btn(0, 1, json!({ "label": "{time}", "sub": "{day} {date}", "style": "light" }), "press",
                        json!([])),
                ]
            },
            {
                "id": "windows", "name": "Windows", "folder": true,
                "buttons": window_buttons
            }
        ]
    });
    serde_json::from_value(deck).expect("starter deck is valid")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn starter_deck_round_trips() {
        let deck = starter_deck();
        let text = serde_json::to_string(&deck).unwrap();
        let back: Deck = serde_json::from_str(&text).unwrap();
        assert_eq!(back.pages.len(), 3);
        let first = &back.pages[0].buttons[0];
        assert_eq!(
            first.ui.get("widget").and_then(|v| v.as_str()),
            Some("clock")
        );
        assert!(
            back.pages[2]
                .ui
                .get("folder")
                .and_then(|v| v.as_bool())
                .unwrap()
        );
    }

    #[test]
    fn unknown_actions_are_tolerated() {
        let b: Button = serde_json::from_value(json!({
            "id": "a", "actions": [{ "type": "teleport", "where": "moon" }, { "type": "delay", "ms": 5 }]
        }))
        .unwrap();
        assert!(matches!(b.actions[0], Action::Unknown));
        assert!(matches!(b.actions[1], Action::Delay { ms: 5 }));
    }
}
