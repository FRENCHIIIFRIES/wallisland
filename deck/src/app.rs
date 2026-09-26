//! Shared state: the stored deck, toggle states, live values, and the broadcast every connected
//! device listens to.

use crate::actions::{self, Keyboarder};
use crate::config::{Deck, Mode, Store, new_token};
use serde_json::{Map, Value, json};
use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, Ipv4Addr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::broadcast;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");

#[derive(Clone, Debug)]
pub enum Out {
    /// A JSON message for every device.
    Text(Arc<str>),
    /// The pairing token changed; devices using the old one are disconnected.
    TokenChanged,
}

pub struct App {
    pub store: Store,
    pub port: u16,
    pub tx: broadcast::Sender<Out>,
    pub keyboard: Keyboarder,
    rev: AtomicU64,
    vars: Mutex<Map<String, Value>>,
    toggles: Mutex<HashMap<String, bool>>,
}

impl App {
    pub fn new(store: Store, port: u16) -> Arc<App> {
        let (tx, _) = broadcast::channel(64);
        Arc::new(App {
            store,
            port,
            tx,
            keyboard: Keyboarder::start(),
            rev: AtomicU64::new(1),
            vars: Mutex::new(Map::new()),
            toggles: Mutex::new(HashMap::new()),
        })
    }

    fn broadcast(&self, msg: Value) {
        let _ = self.tx.send(Out::Text(msg.to_string().into()));
    }

    pub fn hello_msg(&self) -> Value {
        json!({
            "t": "hello",
            "version": VERSION,
            "host": host_name(),
            "os": std::env::consts::OS,
            "port": self.port,
            "urls": self.pair_urls(),
        })
    }

    /// The whole deck. `by` names the device whose edit this is, so it can skip its own echo.
    pub fn deck_msg(&self, by: Option<&str>) -> Value {
        let cfg = self.store.cfg.lock().unwrap();
        json!({ "t": "deck", "deck": cfg.deck, "rev": self.rev.load(Ordering::SeqCst), "by": by })
    }

    pub fn vars_msg(&self) -> Value {
        json!({ "t": "vars", "vars": *self.vars.lock().unwrap() })
    }

    pub fn on_msg(&self) -> Value {
        let toggles = self.toggles.lock().unwrap();
        let ids: Vec<&String> = toggles
            .iter()
            .filter(|(_, on)| **on)
            .map(|(id, _)| id)
            .collect();
        json!({ "t": "on", "ids": ids })
    }

    pub fn set_vars(&self, vars: Map<String, Value>) {
        *self.vars.lock().unwrap() = vars;
        self.broadcast(self.vars_msg());
    }

    /// Replaces the deck after an edit on any device, and sends it to all of them.
    pub fn save_deck(&self, deck: Deck, by: Option<&str>) -> Result<(), String> {
        validate(&deck)?;
        {
            let ids: HashSet<&str> = deck.buttons().map(|b| b.id.as_str()).collect();
            self.toggles
                .lock()
                .unwrap()
                .retain(|id, _| ids.contains(id.as_str()));
            self.store.cfg.lock().unwrap().deck = deck;
        }
        self.store
            .save()
            .map_err(|e| format!("Couldn't save: {e}"))?;
        self.rev.fetch_add(1, Ordering::SeqCst);
        self.broadcast(self.deck_msg(by));
        self.broadcast(self.on_msg());
        Ok(())
    }

    /// Makes a new pairing token. Every device has to scan the new QR code.
    pub fn reset_token(&self) -> Result<String, String> {
        let token = new_token();
        self.store.cfg.lock().unwrap().token = token.clone();
        self.store
            .save()
            .map_err(|e| format!("Couldn't save: {e}"))?;
        let _ = self.tx.send(Out::TokenChanged);
        Ok(token)
    }

    /// A button was pressed on some device. Returns an error to show on that device.
    pub async fn press(
        self: &Arc<Self>,
        id: &str,
        held: Option<Arc<AtomicBool>>,
    ) -> Result<(), String> {
        let Some(button) = self.store.cfg.lock().unwrap().deck.button(id).cloned() else {
            return Err("That button no longer exists".into());
        };
        match button.mode {
            Mode::Press => actions::run(&self.keyboard, &button.actions).await,
            Mode::Toggle => {
                let was_on = self
                    .toggles
                    .lock()
                    .unwrap()
                    .get(id)
                    .copied()
                    .unwrap_or(false);
                // Flip first so the button lights up straight away, then run.
                self.toggles.lock().unwrap().insert(id.to_string(), !was_on);
                self.broadcast(self.on_msg());
                actions::run(
                    &self.keyboard,
                    if was_on { &button.off } else { &button.actions },
                )
                .await
            }
            Mode::Hold => {
                actions::run(&self.keyboard, &button.actions).await?;
                let Some(held) = held else { return Ok(()) };
                // Repeat while the finger stays down, like holding a key. Capped in case a release is lost.
                tokio::time::sleep(Duration::from_millis(400)).await;
                let mut repeats = 0;
                while held.load(Ordering::SeqCst) && repeats < 400 {
                    actions::run(&self.keyboard, &button.actions).await?;
                    tokio::time::sleep(Duration::from_millis(90)).await;
                    repeats += 1;
                }
                Ok(())
            }
        }
    }

    /// Addresses a phone on the same network can reach this computer at, best guess first.
    pub fn pair_urls(&self) -> Vec<String> {
        lan_ips()
            .into_iter()
            .map(|ip| format!("http://{ip}:{}", self.port))
            .collect()
    }
}

fn validate(deck: &Deck) -> Result<(), String> {
    if deck.pages.is_empty() {
        return Err("A deck needs at least one page".into());
    }
    if deck.pages.len() > 200 {
        return Err("Too many pages".into());
    }
    let mut pages = HashSet::new();
    let mut buttons = HashSet::new();
    for page in &deck.pages {
        if page.id.is_empty() || !pages.insert(page.id.as_str()) {
            return Err("Two pages share an id".into());
        }
        for b in &page.buttons {
            if b.id.is_empty() || !buttons.insert(b.id.as_str()) {
                return Err("Two buttons share an id".into());
            }
        }
    }
    Ok(())
}

pub fn host_name() -> String {
    hostname::get()
        .map(|h| h.to_string_lossy().into_owned())
        .unwrap_or_else(|_| "This computer".into())
}

/// LAN IPv4 addresses, with the one the default route uses first.
pub fn lan_ips() -> Vec<Ipv4Addr> {
    let mut ips: Vec<Ipv4Addr> = Vec::new();
    // Connecting a UDP socket sends nothing; it just asks the OS which interface it would use.
    if let Ok(sock) = UdpSocket::bind("0.0.0.0:0")
        && sock
            .connect("192.168.0.1:9")
            .or_else(|_| sock.connect("8.8.8.8:53"))
            .is_ok()
        && let Ok(addr) = sock.local_addr()
        && let IpAddr::V4(ip) = addr.ip()
        && usable(ip)
    {
        ips.push(ip);
    }
    if let Ok(ifaces) = if_addrs::get_if_addrs() {
        let mut rest: Vec<Ipv4Addr> = ifaces
            .into_iter()
            .filter_map(|i| match i.ip() {
                IpAddr::V4(ip) if usable(ip) => Some(ip),
                _ => None,
            })
            .filter(|ip| !ips.contains(ip))
            .collect();
        // Home networks first: 192.168.x, then 10.x, then the rest.
        rest.sort_by_key(|ip| match ip.octets() {
            [192, 168, ..] => 0,
            [10, ..] => 1,
            _ => 2,
        });
        rest.dedup();
        ips.extend(rest);
    }
    if ips.is_empty() {
        ips.push(Ipv4Addr::LOCALHOST);
    }
    ips
}

fn usable(ip: Ipv4Addr) -> bool {
    !ip.is_loopback() && !ip.is_link_local() && !ip.is_unspecified() && !ip.is_multicast()
}
