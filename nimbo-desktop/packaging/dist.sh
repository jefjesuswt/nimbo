#!/usr/bin/env bash
# Tarball para los Releases de GitHub (junto al APK):
#   nimbo-desktop-<versión>-linux-x86_64.tar.gz
#     bin/nimbo-desktop            (runpath $ORIGIN/../lib)
#     lib/libtdjson.so.1.8.61      (libunwind.so.1 → libgcc_s.so.1)
#     share/applications, share/icons, LICENSE
# Necesita cargo y patchelf. Se ejecuta desde cualquier sitio.
set -euo pipefail
cd "$(dirname "$0")/.."

ver=$(sed -n 's/^version = "\(.*\)"/\1/p' Cargo.toml | head -1)
name="nimbo-desktop-$ver-linux-x86_64"
out="target/dist/$name"

command -v patchelf >/dev/null || { echo "patchelf is missing (pacman -S patchelf)"; exit 1; }

cargo build --release --locked
lib=$(find target/release/build -path '*out/tdlib/lib/libtdjson.so.1.8.61' | head -1)
[ -n "$lib" ] || { echo "libtdjson not found"; exit 1; }

rm -rf "$out" && mkdir -p "$out"/{bin,lib,share/applications,share/icons/hicolor/scalable/apps}
install -m755 target/release/nimbo-desktop "$out/bin/"
install -m755 "$lib" "$out/lib/"
patchelf --replace-needed libunwind.so.1 libgcc_s.so.1 "$out/lib/libtdjson.so.1.8.61"
patchelf --set-rpath '$ORIGIN/../lib' "$out/bin/nimbo-desktop"
install -m644 packaging/nimbo-desktop.desktop "$out/share/applications/"
install -m644 packaging/nimbo-desktop.svg "$out/share/icons/hicolor/scalable/apps/"
install -m644 ../LICENSE "$out/"

tar -C target/dist -czf "target/dist/$name.tar.gz" "$name"
sha256sum "target/dist/$name.tar.gz" | tee "target/dist/$name.tar.gz.sha256"
