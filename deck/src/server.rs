//! HTTP and WebSocket server. Serves the deck app to phones and keeps one live connection per device.
//! Everything except the static files and a plain "is this Dotdeck?" probe needs the pairing token.

use crate::app::{App, Out, VERSION};
use crate::config::{Action, Deck};
use axum::Router;
use axum::extract::ws::{CloseFrame, Message, WebSocket, WebSocketUpgrade};
use axum::extract::{Query, State};
use axum::http::{HeaderValue, StatusCode, Uri, header};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use futures_util::{SinkExt, StreamExt};
use rust_embed::RustEmbed;
use serde::Deserialize;
use serde_json::{Value, json};
use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use tokio::sync::{broadcast, mpsc};

#[derive(RustEmbed)]
#[folder = "web/"]
struct Web;

pub fn router(app: Arc<App>) -> Router {
    Router::new()
        .route("/ws", get(ws))
        .route("/api/hello", get(hello))
        .route("/api/qr.svg", get(qr))
        .fallback(get(static_file))
        .with_state(app)
}

#[derive(Deserialize)]
struct Params {
    k: Option<String>,
    i: Option<usize>,
}

/// Compares in constant time so the token can't be guessed a character at a time.
fn authorized(app: &App, given: Option<&str>) -> bool {
    let Some(given) = given else { return false };
    let token = app.store.token();
    let (a, b) = (token.as_bytes(), given.as_bytes());
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

async fn hello() -> impl IntoResponse {
    axum::Json(json!({ "app": "dotdeck", "version": VERSION }))
}

async fn qr(State(app): State<Arc<App>>, Query(p): Query<Params>) -> Response {
    if !authorized(&app, p.k.as_deref()) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    let urls = app.pair_urls();
    let base = urls
        .get(p.i.unwrap_or(0))
        .or(urls.first())
        .cloned()
        .unwrap_or_default();
    match crate::qr::svg(&format!("{base}/#k={}", app.store.token())) {
        Some(svg) => (
            [
                (header::CONTENT_TYPE, "image/svg+xml"),
                (header::CACHE_CONTROL, "no-store"),
            ],
            svg,
        )
            .into_response(),
        None => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

async fn static_file(uri: Uri) -> Response {
    let path = uri.path().trim_start_matches('/');
    let path = if path.is_empty() { "index.html" } else { path };
    let Some(file) = Web::get(path) else {
        return (StatusCode::NOT_FOUND, "Not found").into_response();
    };
    let mime = if path.ends_with(".webmanifest") {
        "application/manifest+json".to_string()
    } else {
        mime_guess::from_path(path)
            .first_or_octet_stream()
            .to_string()
    };
    let mut res = (StatusCode::OK, file.data.into_owned()).into_response();
    let h = res.headers_mut();
    h.insert(header::CONTENT_TYPE, HeaderValue::from_str(&mime).unwrap());
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static("no-cache"));
    h.insert(
        header::X_CONTENT_TYPE_OPTIONS,
        HeaderValue::from_static("nosniff"),
    );
    if mime.starts_with("text/html") {
        h.insert(header::X_FRAME_OPTIONS, HeaderValue::from_static("DENY"));
        h.insert(
            header::REFERRER_POLICY,
            HeaderValue::from_static("no-referrer"),
        );
        h.insert(
            header::CONTENT_SECURITY_POLICY,
            HeaderValue::from_static(
                "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; \
                 connect-src 'self' ws: wss:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
            ),
        );
    }
    res
}

async fn ws(
    State(app): State<Arc<App>>,
    Query(p): Query<Params>,
    upgrade: WebSocketUpgrade,
) -> Response {
    let ok = authorized(&app, p.k.as_deref());
    let token = p.k.unwrap_or_default();
    // Big enough for a deck full of button pictures.
    upgrade
        .max_message_size(32 << 20)
        .on_upgrade(move |mut socket| async move {
            if ok {
                session(app, socket, token).await
            } else {
                unpaired(&mut socket).await
            }
        })
}

/// Tells the device its pairing is missing or stale, so it can ask for a fresh scan.
async fn unpaired(socket: &mut WebSocket) {
    let _ = socket.send(text(json!({ "t": "unpaired" }))).await;
    let _ = socket
        .send(Message::Close(Some(CloseFrame {
            code: 4001,
            reason: "unpaired".into(),
        })))
        .await;
}

fn text(v: Value) -> Message {
    Message::Text(v.to_string().into())
}

fn toast(msg: &str) -> Value {
    json!({ "t": "toast", "text": msg })
}

async fn session(app: Arc<App>, socket: WebSocket, mut token: String) {
    let (mut sink, mut stream) = socket.split();
    let mut events = app.tx.subscribe();
    for msg in [
        app.hello_msg(),
        app.deck_msg(None),
        app.vars_msg(),
        app.on_msg(),
    ] {
        if sink.send(text(msg)).await.is_err() {
            return;
        }
    }
    // Replies from button presses running in the background (errors, mostly).
    let (reply_tx, mut replies) = mpsc::unbounded_channel::<Value>();
    let mut holds: HashMap<String, Arc<AtomicBool>> = HashMap::new();

    loop {
        tokio::select! {
            incoming = stream.next() => {
                let Some(Ok(msg)) = incoming else { break };
                let Message::Text(body) = msg else {
                    if matches!(msg, Message::Close(_)) { break }
                    continue;
                };
                let Ok(v) = serde_json::from_str::<Value>(body.as_str()) else { continue };
                if let Some(direct) = handle(&app, v, &reply_tx, &mut holds) {
                    // The device that asked for a new token keeps its connection.
                    if direct["t"] == "token" && let Some(t) = direct["token"].as_str() {
                        token = t.to_string();
                    }
                    if sink.send(text(direct)).await.is_err() {
                        break;
                    }
                }
            }
            event = events.recv() => match event {
                Ok(Out::Text(s)) => {
                    if sink.send(Message::Text(s.to_string().into())).await.is_err() { break }
                }
                Ok(Out::TokenChanged) => {
                    if !authorized(&app, Some(&token)) {
                        let mut socket = sink.reunite(stream).expect("halves of one socket");
                        unpaired(&mut socket).await;
                        break;
                    }
                }
                Err(broadcast::error::RecvError::Lagged(_)) => {
                    // Missed some updates; catch up with the full picture.
                    for msg in [app.deck_msg(None), app.vars_msg(), app.on_msg()] {
                        let _ = sink.send(text(msg)).await;
                    }
                }
                Err(broadcast::error::RecvError::Closed) => break,
            },
            Some(reply) = replies.recv() => {
                if sink.send(text(reply)).await.is_err() { break }
            }
        }
    }
    for held in holds.values() {
        held.store(false, Ordering::SeqCst);
    }
}

/// Handles one message from a device. Anything returned goes straight back to that device.
fn handle(
    app: &Arc<App>,
    v: Value,
    replies: &mpsc::UnboundedSender<Value>,
    holds: &mut HashMap<String, Arc<AtomicBool>>,
) -> Option<Value> {
    let kind = v.get("t").and_then(Value::as_str).unwrap_or_default();
    match kind {
        "ping" => Some(json!({ "t": "pong", "n": v.get("n") })),
        "press" => {
            let id = v.get("id").and_then(Value::as_str)?.to_string();
            let held = Arc::new(AtomicBool::new(true));
            if let Some(old) = holds.insert(id.clone(), held.clone()) {
                old.store(false, Ordering::SeqCst);
            }
            let (app, replies) = (app.clone(), replies.clone());
            tokio::spawn(async move {
                if let Err(e) = app.press(&id, Some(held)).await {
                    let _ = replies.send(toast(&e));
                }
            });
            None
        }
        "release" => {
            let id = v.get("id").and_then(Value::as_str)?;
            if let Some(held) = holds.remove(id) {
                held.store(false, Ordering::SeqCst);
            }
            None
        }
        "run" => {
            // "Test" in the editor: run actions that may not be saved yet.
            let actions: Vec<Action> = match serde_json::from_value(v.get("actions")?.clone()) {
                Ok(a) => a,
                Err(e) => return Some(toast(&format!("Can't run that: {e}"))),
            };
            let (app, replies) = (app.clone(), replies.clone());
            tokio::spawn(async move {
                let msg = match crate::actions::run(&app.keyboard, &actions).await {
                    Ok(()) => json!({ "t": "toast", "text": "Test ran", "ok": true }),
                    Err(e) => toast(&e),
                };
                let _ = replies.send(msg);
            });
            None
        }
        "save" => {
            let deck: Deck = match serde_json::from_value(v.get("deck")?.clone()) {
                Ok(d) => d,
                Err(e) => return Some(toast(&format!("Couldn't save: {e}"))),
            };
            match app.save_deck(deck, v.get("by").and_then(Value::as_str)) {
                Ok(()) => None,
                Err(e) => {
                    // Put the device back in step with what's actually saved.
                    let _ = replies.send(app.deck_msg(None));
                    Some(toast(&e))
                }
            }
        }
        "reset" => match app.reset_token() {
            Ok(token) => Some(json!({ "t": "token", "token": token })),
            Err(e) => Some(toast(&e)),
        },
        _ => None,
    }
}
