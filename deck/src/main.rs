// No console window on Windows; the tray icon and the browser are the interface.
#![cfg_attr(all(windows, not(debug_assertions)), windows_subsystem = "windows")]

mod actions;
mod app;
mod config;
mod keys;
mod qr;
mod server;
mod stats;
mod tray;

use app::App;
use config::Store;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

const HELP: &str =
    "Dotdeck: a dot-matrix macro deck. Run it on your computer, scan the QR code with your phone.

Usage: dotdeck [options]

  --port <n>       Port to listen on (default 8420, or the one in config.json)
  --config <dir>   Where to keep config.json (default: your user config folder)
  --no-open        Don't open the editor in the browser on start
  -h, --help       Show this help
  -V, --version    Show the version
";

struct Args {
    port: Option<u16>,
    dir: Option<PathBuf>,
    open: bool,
}

fn parse_args() -> Args {
    let mut args = Args {
        port: None,
        dir: None,
        open: true,
    };
    let mut it = std::env::args().skip(1);
    while let Some(a) = it.next() {
        match a.as_str() {
            "--port" => args.port = it.next().and_then(|p| p.parse().ok()),
            "--config" => args.dir = it.next().map(PathBuf::from),
            "--no-open" => args.open = false,
            "-h" | "--help" => {
                print!("{HELP}");
                std::process::exit(0);
            }
            "-V" | "--version" => {
                println!("dotdeck {}", app::VERSION);
                std::process::exit(0);
            }
            other => {
                eprintln!("Unknown option {other}\n\n{HELP}");
                std::process::exit(2);
            }
        }
    }
    args
}

fn main() {
    let args = parse_args();
    let dir = args.dir.unwrap_or_else(Store::default_dir);
    let _ = std::fs::create_dir_all(&dir);
    init_logging(&dir);

    let store = match Store::open(&dir) {
        Ok(s) => s,
        Err(e) => fail(&format!(
            "Couldn't open the config folder {}: {e}",
            dir.display()
        )),
    };
    let wanted = args.port.unwrap_or_else(|| store.cfg.lock().unwrap().port);

    let listener = match bind(wanted) {
        Bound::Listener(l) => l,
        Bound::AlreadyRunning(port) => {
            // Opening Dotdeck twice just brings up the editor of the one already running.
            open_url(&editor_url(port, &store.token()));
            return;
        }
        Bound::Failed(e) => fail(&format!("Couldn't listen on port {wanted}: {e}")),
    };
    let port = listener.local_addr().map(|a| a.port()).unwrap_or(wanted);

    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("tokio runtime");
    let app = App::new(store, port);
    {
        let app = app.clone();
        rt.spawn(async move {
            listener
                .set_nonblocking(true)
                .expect("non-blocking listener");
            let listener = tokio::net::TcpListener::from_std(listener).expect("tokio listener");
            let router = server::router(app);
            if let Err(e) = axum::serve(
                listener,
                router.into_make_service_with_connect_info::<SocketAddr>(),
            )
            .await
            {
                tracing::error!("server stopped: {e}");
            }
        });
    }
    rt.spawn(stats::run(app.clone()));

    banner(&app);
    if args.open {
        open_editor(&app);
    }
    tray::run(app, rt);
}

enum Bound {
    Listener(TcpListener),
    AlreadyRunning(u16),
    Failed(std::io::Error),
}

/// Binds the port on all interfaces so phones on the Wi-Fi can reach it. If it's taken by another
/// Dotdeck, say so; if it's taken by something else, try the next few ports.
fn bind(port: u16) -> Bound {
    let mut first_err = None;
    for p in port..port.saturating_add(10) {
        match TcpListener::bind((Ipv4Addr::UNSPECIFIED, p)) {
            Ok(l) => {
                if p != port {
                    tracing::warn!("port {port} is busy, using {p}");
                }
                return Bound::Listener(l);
            }
            Err(e) => {
                if is_dotdeck(p) {
                    return Bound::AlreadyRunning(p);
                }
                first_err.get_or_insert(e);
            }
        }
    }
    Bound::Failed(first_err.unwrap_or_else(|| std::io::Error::other("no free port")))
}

fn is_dotdeck(port: u16) -> bool {
    let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    let Ok(mut s) = TcpStream::connect_timeout(&addr, Duration::from_millis(500)) else {
        return false;
    };
    let _ = s.set_read_timeout(Some(Duration::from_secs(1)));
    if s.write_all(b"GET /api/hello HTTP/1.0\r\nHost: localhost\r\n\r\n")
        .is_err()
    {
        return false;
    }
    let mut body = String::new();
    let _ = s.read_to_string(&mut body);
    body.contains("\"dotdeck\"")
}

fn editor_url(port: u16, token: &str) -> String {
    format!("http://localhost:{port}/#k={token}")
}

pub fn open_editor(app: &Arc<App>) {
    open_url(&editor_url(app.port, &app.store.token()));
}

fn open_url(url: &str) {
    if let Err(e) = open::that_detached(url) {
        tracing::warn!("couldn't open the browser: {e}");
    }
}

fn banner(app: &Arc<App>) {
    let token = app.store.token();
    let urls = app.pair_urls();
    let phone = format!("{}/#k={token}", urls[0]);
    println!("\n  ● DOTDECK {}\n", app::VERSION);
    println!("  Scan with your phone's camera (same Wi-Fi as this computer):\n");
    if let Some(q) = qr::terminal(&phone) {
        print!("{q}");
    }
    println!("\n  Phone:   {phone}");
    for other in urls.iter().skip(1) {
        println!("  Or:      {other}/#k={token}");
    }
    println!("  Editor:  {}", editor_url(app.port, &token));
    println!(
        "  Config:  {}\n",
        app.store.dir().join("config.json").display()
    );
    tracing::info!("listening on port {}", app.port);
}

fn init_logging(dir: &std::path::Path) {
    use tracing_subscriber::fmt::writer::MakeWriterExt;
    let filter = tracing_subscriber::filter::LevelFilter::INFO;
    // Appends, so a second copy of Dotdeck (which only hands over to the first) doesn't wipe the log.
    let path = dir.join("dotdeck.log");
    let too_big = std::fs::metadata(&path).is_ok_and(|m| m.len() > 1_000_000);
    let file = std::fs::OpenOptions::new()
        .create(true)
        .append(!too_big)
        .write(true)
        .truncate(too_big)
        .open(path);
    match file {
        Ok(file) => tracing_subscriber::fmt()
            .with_max_level(filter)
            .with_ansi(false)
            .with_writer(std::io::stderr.and(std::sync::Mutex::new(file)))
            .init(),
        Err(_) => tracing_subscriber::fmt().with_max_level(filter).init(),
    }
}

fn fail(msg: &str) -> ! {
    tracing::error!("{msg}");
    eprintln!("{msg}");
    std::process::exit(1);
}
