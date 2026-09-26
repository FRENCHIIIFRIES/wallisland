//! Turns hotkey text like `ctrl+shift+m` or `ctrl+k, ctrl+c` into keys to press.

use enigo::Key;

/// A chord: modifiers held down while the last key is tapped.
pub type Combo = Vec<Key>;

/// Parses one or more comma-separated combos. Names are case-insensitive; `+` alone is written `plus`.
pub fn parse(text: &str) -> Result<Vec<Combo>, String> {
    let mut combos = Vec::new();
    for chord in text.split(',') {
        let chord = chord.trim();
        if chord.is_empty() {
            continue;
        }
        // "ctrl++" means ctrl and the plus key.
        let (body, plus) = match chord.strip_suffix("++") {
            Some(body) => (body, true),
            None if chord == "+" => ("", true),
            None => (chord, false),
        };
        let mut keys = Vec::new();
        for name in body.split('+').map(str::trim).filter(|p| !p.is_empty()) {
            keys.push(key(name).ok_or_else(|| format!("Unknown key \"{name}\""))?);
        }
        if plus {
            keys.push(Key::Unicode('+'));
        }
        if keys.is_empty() {
            continue;
        }
        combos.push(keys);
    }
    if combos.is_empty() {
        return Err("No keys set".into());
    }
    Ok(combos)
}

pub fn key(name: &str) -> Option<Key> {
    let n = name.trim().to_lowercase();
    let k = match n.as_str() {
        "ctrl" | "control" | "ctl" | "⌃" => Key::Control,
        "shift" | "⇧" => Key::Shift,
        "alt" | "option" | "opt" | "⌥" => Key::Alt,
        "win" | "windows" | "super" | "meta" | "cmd" | "command" | "⌘" => Key::Meta,
        "enter" | "return" => Key::Return,
        "esc" | "escape" => Key::Escape,
        "tab" => Key::Tab,
        "space" | "spacebar" => Key::Space,
        "backspace" | "bksp" => Key::Backspace,
        "delete" | "del" => Key::Delete,
        "home" => Key::Home,
        "end" => Key::End,
        "pageup" | "pgup" => Key::PageUp,
        "pagedown" | "pgdn" => Key::PageDown,
        "up" | "arrowup" => Key::UpArrow,
        "down" | "arrowdown" => Key::DownArrow,
        "left" | "arrowleft" => Key::LeftArrow,
        "right" | "arrowright" => Key::RightArrow,
        "capslock" | "caps" => Key::CapsLock,
        #[cfg(not(target_os = "macos"))]
        "insert" | "ins" => Key::Insert,
        #[cfg(not(target_os = "macos"))]
        "printscreen" | "prtsc" | "print" | "prtscn" => Key::PrintScr,
        #[cfg(not(target_os = "macos"))]
        "pausebreak" | "break" => Key::Pause,
        #[cfg(not(target_os = "macos"))]
        "numlock" => Key::Numlock,
        "num0" => Key::Numpad0,
        "num1" => Key::Numpad1,
        "num2" => Key::Numpad2,
        "num3" => Key::Numpad3,
        "num4" => Key::Numpad4,
        "num5" => Key::Numpad5,
        "num6" => Key::Numpad6,
        "num7" => Key::Numpad7,
        "num8" => Key::Numpad8,
        "num9" => Key::Numpad9,
        "numadd" | "num+" => Key::Add,
        "numsub" | "num-" => Key::Subtract,
        "nummul" | "num*" => Key::Multiply,
        "numdiv" | "num/" => Key::Divide,
        "numdec" | "num." => Key::Decimal,
        "plus" => Key::Unicode('+'),
        "minus" | "dash" => Key::Unicode('-'),
        "equal" | "equals" => Key::Unicode('='),
        "comma" => Key::Unicode(','),
        "period" | "dot" => Key::Unicode('.'),
        "slash" => Key::Unicode('/'),
        "backslash" => Key::Unicode('\\'),
        "semicolon" => Key::Unicode(';'),
        "quote" | "apostrophe" => Key::Unicode('\''),
        "backquote" | "grave" | "backtick" => Key::Unicode('`'),
        "bracketleft" | "lbracket" => Key::Unicode('['),
        "bracketright" | "rbracket" => Key::Unicode(']'),
        _ => {
            if let Some(k) = media(&n) {
                return Some(k);
            }
            if let Some(k) = function_key(&n) {
                return Some(k);
            }
            let mut chars = n.chars();
            match (chars.next(), chars.next()) {
                (Some(c), None) => Key::Unicode(c),
                _ => return None,
            }
        }
    };
    Some(k)
}

/// Media and volume keys, by the names the editor uses.
pub fn media(name: &str) -> Option<Key> {
    Some(match name {
        "playpause" | "play" | "mediaplaypause" => Key::MediaPlayPause,
        "next" | "nexttrack" | "medianext" => Key::MediaNextTrack,
        "prev" | "previous" | "prevtrack" | "mediaprev" => Key::MediaPrevTrack,
        #[cfg(not(target_os = "macos"))]
        "stop" | "mediastop" => Key::MediaStop,
        "volup" | "volumeup" => Key::VolumeUp,
        "voldown" | "volumedown" => Key::VolumeDown,
        "mute" | "volumemute" => Key::VolumeMute,
        _ => return None,
    })
}

fn function_key(name: &str) -> Option<Key> {
    let n: u8 = name.strip_prefix('f')?.parse().ok()?;
    Some(match n {
        1 => Key::F1,
        2 => Key::F2,
        3 => Key::F3,
        4 => Key::F4,
        5 => Key::F5,
        6 => Key::F6,
        7 => Key::F7,
        8 => Key::F8,
        9 => Key::F9,
        10 => Key::F10,
        11 => Key::F11,
        12 => Key::F12,
        13 => Key::F13,
        14 => Key::F14,
        15 => Key::F15,
        16 => Key::F16,
        17 => Key::F17,
        18 => Key::F18,
        19 => Key::F19,
        20 => Key::F20,
        #[cfg(not(target_os = "macos"))]
        21 => Key::F21,
        #[cfg(not(target_os = "macos"))]
        22 => Key::F22,
        #[cfg(not(target_os = "macos"))]
        23 => Key::F23,
        #[cfg(not(target_os = "macos"))]
        24 => Key::F24,
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_combos_and_chords() {
        assert_eq!(
            parse("ctrl+shift+m").unwrap(),
            vec![vec![Key::Control, Key::Shift, Key::Unicode('m')]]
        );
        assert_eq!(parse(" Ctrl + K , ctrl+c").unwrap().len(), 2);
        assert_eq!(
            parse("ctrl++").unwrap(),
            vec![vec![Key::Control, Key::Unicode('+')]]
        );
        assert_eq!(parse("+").unwrap(), vec![vec![Key::Unicode('+')]]);
        assert_eq!(
            parse("win+l").unwrap(),
            vec![vec![Key::Meta, Key::Unicode('l')]]
        );
        assert_eq!(parse("f13").unwrap(), vec![vec![Key::F13]]);
        assert_eq!(parse("alt+f4").unwrap(), vec![vec![Key::Alt, Key::F4]]);
        assert_eq!(parse("A").unwrap(), vec![vec![Key::Unicode('a')]]);
    }

    #[test]
    fn rejects_nonsense() {
        assert!(parse("ctrl+banana").is_err());
        assert!(parse("").is_err());
        assert!(parse("f99").is_err());
    }
}
