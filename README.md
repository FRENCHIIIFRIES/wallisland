# Wallisland

A Dynamic Island for Android, in the spirit of MT Capsule and Apple's island, drawn in the Nothing
design language: black, white, one red, and dots everywhere.

| | |
|---|---|
| ![Media, compact](docs/screens/media-compact.png) | ![Charging](docs/screens/charging.png) |
| ![Notification](docs/screens/notification.png) | ![Silent](docs/screens/silent.png) |
| ![Call](docs/screens/call.png) | ![Timer](docs/screens/timer.png) |
| ![Navigation](docs/screens/nav.png) | ![Download](docs/screens/download.png) |
| ![Earbuds](docs/screens/earbuds.png) | ![Quick toggles](docs/screens/toggles.png) |
| ![Volume](docs/screens/volume.png) | ![Do Not Disturb](docs/screens/dnd.png) |
| ![Peek with weather](docs/screens/peek.png) | ![Next event](docs/screens/event.png) |
| ![Incoming call](docs/screens/call-incoming.png) | ![Hang up](docs/screens/call-hangup.png) |
| ![Delivery](docs/screens/delivery.png) | ![Ride](docs/screens/ride.png) |
| ![Timers](docs/screens/timers.png) | ![Brightness](docs/screens/brightness.png) |
| ![Unlock](docs/screens/unlock.png) | ![Wi-Fi](docs/screens/wifi.png) |
| ![Split island](docs/screens/split.png) | ![Casting](docs/screens/cast.png) |

![Recent notifications](docs/screens/history.png)

![Notification with quick replies and edge light](docs/screens/notification-actions.png)

| ![Now playing, dot art](docs/screens/media-expanded.png) | ![Now playing, cover art](docs/screens/media-expanded-cover.png) |
|---|---|

<img src="docs/screens/settings.png" width="320" alt="Settings screen">

## What it does

- **Now playing.** Halftone dot album art and a dot equaliser on either side of the camera while music
  plays; it disappears 5 seconds after you pause. Tap to
  expand into the full player:
  - Drag along the dotted progress bar to scrub, or tap to jump.
  - Back 10 s, previous, play / pause, next and forward 10 s, all as dot-matrix buttons.
  - Tap the album art to switch between the real cover and the dot-matrix version.
  - **Colour dots** (on by default): the dot art is a colour halftone of the cover, and the equaliser
    and progress bar take the cover's strongest colour. Turn it off for classic white dots.
  - Tap the song title to open the music app.
- **Notifications.** Only alerts that would make a sound open the island. Chats show the latest
  message. Tap to open the notification, or swipe up to dismiss it.
- **Calls.** During a phone, WhatsApp, Instagram or other call, the island shows a breathing red
  dot and a live call timer. Tap it to return to the call, or swipe up to hide it.
- **Live activities.** Clock-app timers and stopwatches count down beside the camera; Google Maps (and
  other navigation apps) show the next turn and distance; downloads, uploads and updates show a
  filling ring of dots with the percentage.
- **Unlock.** The dot padlock's shackle lifts and swings open, a ring of dots bursts out and a tick
  writes itself in, with a little hop of the pill, when you unlock by face, fingerprint or PIN.
- **Earbuds.** When Bluetooth headphones connect, the island shows their battery (needs the optional
  Nearby devices permission).
- **Quick toggles.** Long-press the island for five big buttons: torch, volume, brightness, timers
  and recent notifications. Swap any for sound mode or rotation lock in *Quick panel buttons*.
- **Timers and stopwatch.** TIMER opens 1, 5, 10 and 25 minute countdowns and a stopwatch; the
  running one sits in the pill with a row of dots that drains as it counts down. Tap it to stop.
- **Brightness.** BRIGHT opens a dot bar to drag, on a perceptual curve (needs "change system
  settings"; dragging switches adaptive brightness off, like the system slider).
- **Deliveries and rides.** Uber, Uber Eats, Deliveroo, Just Eat, DoorDash, Swiggy, Zomato, Lyft, Bolt
  and others: a car or bag, the arrival time ("8-12 MIN", "7:45"), and a dot travelling along the
  bottom of the pill (real progress when the app reports it).
- **Split island.** Two things at once (music and a timer, a call and music): the second pops out as
  its own bubble beside the pill; tap it to open that one.
- **Casting.** Music playing on a TV or speaker (Cast, Spotify Connect) shows a cast glyph and
  "ON LIVING ROOM TV"; the volume keys and the volume bar drive that device instead of the phone.
- **Wi-Fi and hotspot.** Joining a network shows its name (with precise location allowed) and signal in
  dots. Turning the hotspot on keeps a live pill with the mobile data used since (Android doesn't let
  apps count the connected devices).
- **Mic and camera in use.** An orange (mic) or green (camera) dot in the idle pill, and a short note
  naming the app on screen when it starts.
- **Steps.** The double-tap peek shows today's steps beside the weather, with a ring of dots filling
  toward 10,000 (physical activity permission; the phone's own step counter, batched to save battery).
- **Vibration styles.** Soft, Sharp or Off, separately for touches, notifications and alerts.
- **Battery Saver.** At 15% the island offers Battery Saver in one tap: switched on directly with the
  same adb grant as pop-ups, otherwise it opens the Battery Saver screen.
- **Volume in the island.** With *Show above status bar* on, the volume keys drive a dot-matrix
  volume bar in the island instead of the system panel (it falls back to the normal panel while
  the island is hidden or the phone is ringing). Slide sideways on the bar to set the volume by
  touch, or open it from the long-press panel's *Volume* button. Presses are routed like the
  hardware keys (media, calls, Cast); an app that leaves the phone in call mode with no call going
  on no longer steals them, and if a press ever moves nothing the island adjusts the volume
  directly instead.
- **Toggle confirmations.** Do Not Disturb, Wi-Fi and Bluetooth flash ON / OFF with a dot icon.
- **Buttons and quick replies.** Notifications show the app's own buttons (Mark as read, Like…) and
  one-tap replies (👍, "On my way") sent straight through the app's Reply.
- **Edge light.** Two comets of dots race around the island's outline, in the app's colour, when a
  notification arrives.
- **Peek and weather.** Double-tap the empty pill for the time, battery and the weather (from
  Open-Meteo). It uses a rough phone location when allowed, otherwise the city estimated from your
  internet connection (geojs.io), and is fetched in the background so it's ready.
- **Calls.** A ringing call opens a card with green Answer and red Decline. Tap an ongoing call's
  pill for a red hang-up button. Uses the call's own notification buttons; for calls without them,
  the optional *Phone calls* permission lets the island answer and hang up through Android.
- **Recent notifications.** Tap the empty pill, swipe down on it, or use RECENT in the long-press
  panel for your last five
  notifications, including ones already in the shade; tap one to open it. Tap "ALL ›" or swipe down
  again for the full notification shade.
- **Island replaces pop-ups.** Stops notifications showing twice (the system banner and the island).
  Needs a one-time `adb shell pm grant com.wallisland.island android.permission.WRITE_SECURE_SETTINGS`;
  the system banners come back by themselves whenever the island is hidden or turned off.
- **Time to full.** While charging, the island alternates the percentage with "FULL 42M".
- **Focus timer.** A 25-minute timer from the long-press panel, shown as a live countdown.
- **Next event.** Ten minutes before a calendar event you get a banner, then a countdown
  (optional calendar permission).
- **Essential Key remap.** Settings → *Essential Key* → **Learn**, press the key, then choose short
  and long press actions: torch, play/pause, quick toggles, screenshot, camera, Assistant, sound
  mode, focus timer or any app. Uses the accessibility service; if Learn never sees the key, the
  phone handles it before apps can. Nothing OS still opens Essential Space itself, so with
  **Stop Essential Space** on (the default) the island closes it the moment a remapped press opens
  it. To turn Essential Space off completely, so it saves no capture, run once from a computer:
  `adb shell pm disable-user --user 0 com.nothing.ntessentialspace` and the same for
  `com.nothing.ntessentialrecorder` (`pm enable` undoes it).
- **Per-app control.** *Apps that can pop up* lets you turn island notifications off app by app.
  Muted apps and silent notifications never pop up but still appear in the recent list.
- **App colours.** The dot, badge and edge light take each app's colour from its icon (WhatsApp
  green, Instagram pink), since many apps leave their notification colour on the default blue.
- **Accent colour.** Swap the Nothing red for orange, yellow, green, blue, purple, pink or white.
- **Charging and low battery.** A five-dot gauge and the percentage in Doto. It turns red at 20%.
  Plugging in sweeps a wave of dots up to the battery level, then a pulse runs along it.
- **Ring / vibrate / silent.** A short confirmation whenever the ringer mode changes.
- **Gestures.** Tap to expand or open, swipe up to dismiss, swipe down to expand, swipe music left or
  right to skip, long-press for quick toggles. Tapping outside an expanded island collapses it.
- **Motion.** Spring-driven morphing between shapes, a press-to-squish response, and cross-faded
  content.

## Built to stay on

- A foreground service with `START_STICKY`, restarted after swiping from recents, reboots and app
  updates.
- The notification listener is kept bound by the system, starts the overlay whenever it connects, and
  asks to be rebound if an OEM skin drops it.
- A one-tap request for unrestricted battery use in settings.
- A Quick Settings tile to switch it on and off.
- It hides for full-screen video and games, in landscape, and while the camera or a voice recorder
  is in use (but stays during video and voice calls). Each can be turned off.
- **Opacity** slider (85% by default): the island is slightly see-through, with a near-solid top strip
  so status-bar icons never show through behind it.
- It centres on the front-camera cut-out automatically. Settings shows exactly where it found the
  camera, and **Fit to camera** sizes the pill around it. You can fine-tune everything with live
  preview.

## Install

On your phone, download
**[Wallisland.apk](https://github.com/FRENCHIIIFRIES/wallisland/releases/latest/download/Wallisland.apk)**
(always the newest build) and open it to install. Or build it yourself:

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Then open the app and allow the permissions it asks for:

1. **Show above status bar** (recommended). Tap Allow, then turn on *Wallisland* in Accessibility.
   Accessibility overlays are the only windows Android lets an app place above the status bar
   icons. The service reads nothing on screen. Without it, the island still works
   but the status-bar icons sit on top of it.
2. **Draw over apps**, needed if you don't use the option above.
3. **Notification access**, for notifications and now playing. On Android 13+ this (and the Accessibility
   switch) may be greyed out for sideloaded apps. To enable it, go to *Settings → Apps → Wallisland → ⋮ → Allow restricted
   settings*, then try again.
4. **Unrestricted battery**, so the system doesn't kill the island.

Use the **Try it** buttons in the app to preview each state.

### Updating

Open Wallisland and scroll to **Updates**. It checks this repo's latest release when the app opens;
tap **Update** to download and install it in place. (The first time, Android asks you to allow
Wallisland to install apps.) Every build from build 13 on is signed with the same key, so updates
install over each other. If you're coming from build 12 or older, uninstall once and install fresh.

Requires Android 8.0 (API 26) or newer.

## Fonts

[Doto](https://fonts.google.com/specimen/Doto) (dot-matrix) and
[Space Mono](https://fonts.google.com/specimen/Space+Mono), both under the SIL Open Font License
(see `licenses/`). Wallisland is not affiliated with Nothing Technology.
