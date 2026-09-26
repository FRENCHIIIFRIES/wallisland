//! Runs a button's actions on this computer.
//!
//! Keyboard input goes through one dedicated thread that owns the input connection (on macOS it isn't
//! allowed to hop threads), so a button's keys, text and delays play out in order.

use crate::config::Action;
use crate::keys;
use enigo::{Direction, Enigo, Key, Keyboard, Settings};
use std::sync::mpsc;
use std::time::Duration;
use tokio::sync::oneshot;

enum Input {
    Combos(Vec<keys::Combo>),
    Text(String),
    Tap(Key),
}

struct Job {
    input: Input,
    done: oneshot::Sender<Result<(), String>>,
}

#[derive(Clone)]
pub struct Keyboarder {
    tx: mpsc::Sender<Job>,
}

impl Keyboarder {
    pub fn start() -> Keyboarder {
        let (tx, rx) = mpsc::channel::<Job>();
        std::thread::Builder::new()
            .name("keyboard".into())
            .spawn(move || {
                // Created lazily and retried on failure: on macOS the first attempt fails until the user
                // grants Accessibility access, and on Linux the display may come up after we do.
                let mut enigo: Option<Enigo> = None;
                for job in rx {
                    if enigo.is_none() {
                        match Enigo::new(&Settings::default()) {
                            Ok(e) => enigo = Some(e),
                            Err(e) => {
                                let _ = job.done.send(Err(no_keyboard(&e.to_string())));
                                continue;
                            }
                        }
                    }
                    let result = play(enigo.as_mut().unwrap(), job.input);
                    let _ = job.done.send(result);
                }
            })
            .expect("keyboard thread");
        Keyboarder { tx }
    }

    async fn send(&self, input: Input) -> Result<(), String> {
        let (done, rx) = oneshot::channel();
        self.tx
            .send(Job { input, done })
            .map_err(|_| "Keyboard thread stopped".to_string())?;
        rx.await
            .map_err(|_| "Keyboard thread stopped".to_string())?
    }
}

fn no_keyboard(detail: &str) -> String {
    if cfg!(target_os = "macos") {
        "Allow Dotdeck in System Settings > Privacy & Security > Accessibility, then try again"
            .into()
    } else {
        format!("Can't send keys on this computer: {detail}")
    }
}

fn play(enigo: &mut Enigo, input: Input) -> Result<(), String> {
    let gap = Duration::from_millis(8);
    let err = |e: enigo::InputError| e.to_string();
    match input {
        Input::Combos(combos) => {
            for combo in combos {
                let (last, mods) = combo.split_last().expect("combos are never empty");
                for m in mods {
                    enigo.key(*m, Direction::Press).map_err(err)?;
                    std::thread::sleep(gap);
                }
                let tapped = enigo.key(*last, Direction::Click).map_err(err);
                std::thread::sleep(gap);
                // Always let go of the modifiers, even if the key itself failed.
                for m in mods.iter().rev() {
                    let _ = enigo.key(*m, Direction::Release);
                    std::thread::sleep(gap);
                }
                tapped?;
                std::thread::sleep(Duration::from_millis(30));
            }
            Ok(())
        }
        Input::Text(text) => enigo.text(&text).map_err(err),
        Input::Tap(key) => enigo.key(key, Direction::Click).map_err(err),
    }
}

/// Runs the actions in order, stopping at the first one that fails.
pub async fn run(kb: &Keyboarder, actions: &[Action]) -> Result<(), String> {
    for action in actions {
        run_one(kb, action).await?;
    }
    Ok(())
}

async fn run_one(kb: &Keyboarder, action: &Action) -> Result<(), String> {
    match action {
        Action::Hotkey { keys } => kb.send(Input::Combos(keys::parse(keys)?)).await,
        Action::Text { text } => {
            if text.is_empty() {
                return Ok(());
            }
            kb.send(Input::Text(text.clone())).await
        }
        Action::Media { key } => {
            let k = keys::media(key)
                .ok_or_else(|| format!("\"{key}\" isn't available on this computer"))?;
            kb.send(Input::Tap(k)).await
        }
        Action::Open { path, args } => open_path(path.trim(), args.trim()),
        Action::Url { url } => {
            let url = url.trim();
            if url.is_empty() {
                return Err("No website set".into());
            }
            // "example.com" means the website, not a file called that.
            let url = if url.contains(':') {
                url.to_string()
            } else {
                format!("https://{url}")
            };
            open::that_detached(&url).map_err(|e| format!("Couldn't open {url}: {e}"))
        }
        Action::Cmd { command } => spawn_shell(command),
        Action::Delay { ms } => {
            tokio::time::sleep(Duration::from_millis((*ms).min(60_000))).await;
            Ok(())
        }
        // Handled by the device that pressed the button.
        Action::Page { .. } | Action::Back | Action::Unknown => Ok(()),
    }
}

fn open_path(path: &str, args: &str) -> Result<(), String> {
    if path.is_empty() {
        return Err("No file or app set".into());
    }
    let path = expand_home(path);
    if args.is_empty() {
        return open::that_detached(&path).map_err(|e| format!("Couldn't open {path}: {e}"));
    }
    let mut cmd = tokio::process::Command::new(&path);
    cmd.args(split_args(args));
    hide_window(&mut cmd);
    reap(
        cmd.spawn()
            .map_err(|e| format!("Couldn't start {path}: {e}"))?,
    );
    Ok(())
}

fn spawn_shell(command: &str) -> Result<(), String> {
    let command = command.trim();
    if command.is_empty() {
        return Err("No command set".into());
    }
    let mut cmd = if cfg!(windows) {
        let mut c = tokio::process::Command::new("cmd");
        c.arg("/C").arg(command);
        c
    } else {
        let mut c = tokio::process::Command::new("sh");
        c.arg("-c").arg(command);
        c
    };
    if let Some(home) = dirs::home_dir() {
        cmd.current_dir(home);
    }
    hide_window(&mut cmd);
    reap(
        cmd.spawn()
            .map_err(|e| format!("Couldn't run the command: {e}"))?,
    );
    Ok(())
}

/// Waits for the child in the background so it doesn't linger as a zombie.
fn reap(mut child: tokio::process::Child) {
    tokio::spawn(async move {
        let _ = child.wait().await;
    });
}

#[cfg(windows)]
fn hide_window(cmd: &mut tokio::process::Command) {
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    cmd.creation_flags(CREATE_NO_WINDOW);
}

#[cfg(not(windows))]
fn hide_window(_: &mut tokio::process::Command) {}

fn expand_home(path: &str) -> String {
    if let Some(rest) = path.strip_prefix('~')
        && let Some(home) = dirs::home_dir()
    {
        return format!("{}{rest}", home.to_string_lossy());
    }
    path.to_string()
}

/// Splits an argument string on spaces, keeping "quoted parts" together.
fn split_args(s: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut quoted = false;
    let mut any = false;
    for c in s.chars() {
        match c {
            '"' => {
                quoted = !quoted;
                any = true;
            }
            c if c.is_whitespace() && !quoted => {
                if any {
                    out.push(std::mem::take(&mut cur));
                    any = false;
                }
            }
            c => {
                cur.push(c);
                any = true;
            }
        }
    }
    if any {
        out.push(cur);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_quoted_args() {
        assert_eq!(
            split_args(r#"--flag "two words" x"#),
            vec!["--flag", "two words", "x"]
        );
        assert_eq!(split_args(r#"  "" a  "#), vec!["", "a"]);
        assert!(split_args("   ").is_empty());
    }

    #[tokio::test]
    async fn runs_commands_and_delays() {
        let dir = std::env::temp_dir().join(format!("dotdeck-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let marker = dir.join("ran");
        let kb = Keyboarder::start();
        let actions = vec![
            Action::Delay { ms: 10 },
            Action::Cmd {
                command: format!("echo hi > \"{}\"", marker.display()),
            },
            Action::Page { page: "x".into() },
        ];
        run(&kb, &actions).await.unwrap();
        for _ in 0..50 {
            if marker.exists() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        assert!(marker.exists());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[tokio::test]
    async fn reports_bad_hotkeys() {
        let kb = Keyboarder::start();
        let err = run(
            &kb,
            &[Action::Hotkey {
                keys: "ctrl+nope".into(),
            }],
        )
        .await
        .unwrap_err();
        assert!(err.contains("nope"));
    }
}
