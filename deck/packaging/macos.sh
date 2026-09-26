#!/usr/bin/env bash
# Builds Dotdeck.app (Apple Silicon + Intel) and zips it. Run from deck/ on a Mac.
set -euo pipefail
build="${1:-0}"
version=$(sed -n 's/^version = "\(.*\)"/\1/p' Cargo.toml | head -1)

cargo build --release --target aarch64-apple-darwin
cargo build --release --target x86_64-apple-darwin

app=target/Dotdeck.app
rm -rf "$app" target/dotdeck.iconset
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources" target/dotdeck.iconset
lipo -create -output "$app/Contents/MacOS/dotdeck" \
  target/aarch64-apple-darwin/release/dotdeck target/x86_64-apple-darwin/release/dotdeck

for s in 16 32 128 256 512; do
  sips -z $s $s assets/dotdeck-512.png --out "target/dotdeck.iconset/icon_${s}x${s}.png" >/dev/null
  d=$((s * 2))
  if [ $d -le 512 ]; then
    sips -z $d $d assets/dotdeck-512.png --out "target/dotdeck.iconset/icon_${s}x${s}@2x.png" >/dev/null
  fi
done
iconutil -c icns target/dotdeck.iconset -o "$app/Contents/Resources/dotdeck.icns"

sed -e "s/VERSION/$version/" -e "s/BUILD/$build/" packaging/Info.plist > "$app/Contents/Info.plist"
# Ad-hoc signature: Apple Silicon won't run unsigned code at all.
codesign --force --deep --sign - "$app"
ditto -c -k --keepParent "$app" "${2:-Dotdeck-macos.zip}"
