//! The menu-bar / system-tray icon on Windows and macOS: open the editor, or quit.
//! Linux has no tray here; Dotdeck runs in the terminal and stops with Ctrl+C.

use crate::app::App;
use std::sync::Arc;
use tokio::runtime::Runtime;

#[cfg(any(windows, target_os = "macos"))]
pub fn run(app: Arc<App>, rt: Runtime) {
    use tao::event::{Event, StartCause};
    use tao::event_loop::{ControlFlow, EventLoopBuilder};
    use tray_icon::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem};
    use tray_icon::{MouseButton, TrayIconBuilder, TrayIconEvent};

    enum UserEvent {
        Tray(TrayIconEvent),
        Menu(MenuEvent),
    }

    #[allow(unused_mut)]
    let mut event_loop = EventLoopBuilder::<UserEvent>::with_user_event().build();
    #[cfg(target_os = "macos")]
    {
        // A menu-bar app: no Dock icon.
        use tao::platform::macos::{ActivationPolicy, EventLoopExtMacOS};
        event_loop.set_activation_policy(ActivationPolicy::Accessory);
    }
    let proxy = event_loop.create_proxy();
    TrayIconEvent::set_event_handler(Some(move |e| {
        let _ = proxy.send_event(UserEvent::Tray(e));
    }));
    let proxy = event_loop.create_proxy();
    MenuEvent::set_event_handler(Some(move |e| {
        let _ = proxy.send_event(UserEvent::Menu(e));
    }));

    let url = app.pair_urls().into_iter().next().unwrap_or_default();
    let open_item = MenuItem::new("Open Dotdeck", true, None);
    let address = MenuItem::new(url.trim_start_matches("http://"), false, None);
    let quit_item = MenuItem::new("Quit Dotdeck", true, None);
    let menu = Menu::new();
    let _ = menu.append_items(&[
        &open_item,
        &address,
        &PredefinedMenuItem::separator(),
        &quit_item,
    ]);

    let mut tray = None;
    let mut rt = Some(rt);
    event_loop.run(move |event, _, flow| {
        *flow = ControlFlow::Wait;
        match event {
            // The icon must be made once the loop is running (macOS is strict about this).
            Event::NewEvents(StartCause::Init) => {
                tray = TrayIconBuilder::new()
                    .with_menu(Box::new(menu.clone()))
                    .with_menu_on_left_click(cfg!(target_os = "macos"))
                    .with_tooltip(format!("Dotdeck · {url}"))
                    .with_icon(icon())
                    .build()
                    .map_err(|e| tracing::warn!("no tray icon: {e}"))
                    .ok();
            }
            Event::UserEvent(UserEvent::Menu(e)) if e.id == *open_item.id() => {
                crate::open_editor(&app)
            }
            Event::UserEvent(UserEvent::Menu(e)) if e.id == *quit_item.id() => {
                tray.take();
                if let Some(rt) = rt.take() {
                    rt.shutdown_timeout(std::time::Duration::from_millis(300));
                }
                *flow = ControlFlow::Exit;
            }
            Event::UserEvent(UserEvent::Tray(TrayIconEvent::DoubleClick {
                button: MouseButton::Left,
                ..
            })) => crate::open_editor(&app),
            _ => {}
        }
    });
}

#[cfg(not(any(windows, target_os = "macos")))]
pub fn run(_app: Arc<App>, rt: Runtime) {
    rt.block_on(async {
        let _ = tokio::signal::ctrl_c().await;
    });
    println!("\n  Dotdeck stopped.");
}

/// A 32x32 tray icon drawn in code: a black rounded tile with a 3x3 grid of dots, one of them red.
#[cfg(any(windows, target_os = "macos"))]
fn icon() -> tray_icon::Icon {
    let n = 32usize;
    let mut px = vec![0u8; n * n * 4];
    let inside = |x: f32, y: f32| {
        let r = 8.0;
        let cx = x.clamp(r, n as f32 - r);
        let cy = y.clamp(r, n as f32 - r);
        (x - cx).powi(2) + (y - cy).powi(2) <= r * r
    };
    for y in 0..n {
        for x in 0..n {
            let (fx, fy) = (x as f32 + 0.5, y as f32 + 0.5);
            let i = (y * n + x) * 4;
            if !inside(fx, fy) {
                continue;
            }
            let mut c = [0u8, 0, 0, 255];
            for gy in 0..3 {
                for gx in 0..3 {
                    let (dx, dy) = (8.0 + gx as f32 * 8.0, 8.0 + gy as f32 * 8.0);
                    if (fx - dx).powi(2) + (fy - dy).powi(2) <= 2.9f32.powi(2) {
                        c = if (gx, gy) == (2, 0) {
                            [0xD7, 0x19, 0x21, 255]
                        } else {
                            [0xF2, 0xF2, 0xF2, 255]
                        };
                    }
                }
            }
            px[i..i + 4].copy_from_slice(&c);
        }
    }
    tray_icon::Icon::from_rgba(px, n as u32, n as u32).expect("valid icon")
}
