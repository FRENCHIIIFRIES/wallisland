//! The pairing QR code, drawn in dots to match the rest of Dotdeck: black on a white card, a lone
//! module is a dot and neighbours join into pills. Loose dots look airier, but decoders miss them at
//! some sizes; joined runs read reliably at every size we tried.

use qrcode::{Color, EcLevel, QrCode};
use std::fmt::Write;

pub fn svg(data: &str) -> Option<String> {
    let code = QrCode::with_error_correction_level(data, EcLevel::M).ok()?;
    let w = code.width();
    let colors = code.to_colors();
    let quiet = 3.0;
    let size = w as f64 + quiet * 2.0;
    let mut s = String::new();
    let _ = write!(
        s,
        r##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {size} {size}" shape-rendering="geometricPrecision"><rect width="{size}" height="{size}" rx="2.4" fill="#fff"/><g fill="#000">"##
    );
    let in_finder = |x: usize, y: usize| (x < 7 || x >= w - 7) && y < 7 || x < 7 && y >= w - 7;
    let dark =
        |x: usize, y: usize| x < w && y < w && colors[y * w + x] == Color::Dark && !in_finder(x, y);
    for y in 0..w {
        for x in 0..w {
            if !dark(x, y) {
                continue;
            }
            let (cx, cy) = (x as f64 + quiet + 0.5, y as f64 + quiet + 0.5);
            let _ = write!(s, r#"<circle cx="{cx}" cy="{cy}" r="0.5"/>"#);
            if dark(x + 1, y) {
                let _ = write!(
                    s,
                    r#"<rect x="{cx}" y="{}" width="1" height="1"/>"#,
                    cy - 0.5
                );
            }
            if dark(x, y + 1) {
                let _ = write!(
                    s,
                    r#"<rect x="{}" y="{cy}" width="1" height="1"/>"#,
                    cx - 0.5
                );
            }
        }
    }
    // The three corner squares stay solid (rounded) so scanners lock on quickly.
    for (fx, fy) in [(0, 0), (w - 7, 0), (0, w - 7)] {
        let (x, y) = (fx as f64 + quiet, fy as f64 + quiet);
        let _ = write!(
            s,
            r##"<rect x="{}" y="{}" width="6" height="6" rx="1.8" fill="none" stroke="#000" stroke-width="1"/><rect x="{}" y="{}" width="3" height="3" rx="0.9"/>"##,
            x + 0.5,
            y + 0.5,
            x + 2.0,
            y + 2.0
        );
    }
    s.push_str("</g></svg>");
    Some(s)
}

/// A compact QR for the terminal, two rows per line of text.
pub fn terminal(data: &str) -> Option<String> {
    let code = QrCode::with_error_correction_level(data, EcLevel::L).ok()?;
    let w = code.width();
    let colors = code.to_colors();
    let dark = |x: isize, y: isize| {
        x >= 0
            && y >= 0
            && (x as usize) < w
            && (y as usize) < w
            && colors[y as usize * w + x as usize] == Color::Dark
    };
    let mut out = String::new();
    let q = 2isize;
    let mut y = -q;
    while y < w as isize + q {
        out.push_str("  ");
        for x in -q..w as isize + q {
            // Light text on a dark terminal: print the light modules.
            out.push(match (!dark(x, y), !dark(x, y + 1)) {
                (true, true) => '█',
                (true, false) => '▀',
                (false, true) => '▄',
                (false, false) => ' ',
            });
        }
        out.push('\n');
        y += 2;
    }
    Some(out)
}
