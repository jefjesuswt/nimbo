# Nimbo Desktop

Desktop client for Nimbo on Linux, written in Rust with GPUI. It reads and writes the same cloud as the Android app (folders as `#nimbo/<path>` captions in Saved Messages). Why Rust and not Compose: [ADR 0010](../docs/adr/0010-escritorio-gpui-rust.md).

## Requirements (Arch)

- Rust (stable, edition 2024)
- `mpv` (libmpv), `openssl-3`, `libc++`, `libc++abi`
- A Secret Service keyring (gnome-keyring, KWallet…): the API keys and the database key live there
- `xdg-desktop-portal` for the file picker
- `patchelf` only to package

## Run from source

```bash
cargo build
LD_LIBRARY_PATH=$PWD/shim ./target/debug/nimbo-desktop
```

`shim/libunwind.so.1` points to `libgcc_s.so.1`: the prebuilt `libtdjson` asks for LLVM's libunwind, which Arch doesn't ship. The packages rewrite that dependency instead.

On first launch the app asks for your `api_id` and `api_hash` (my.telegram.org › API development tools), or imports them from nimbo-cli.

## Tests

```bash
cargo test
```

`src/captions.rs` mirrors `app/src/main/java/com/nimbo/data/cloud/Captions.kt` with the same cases as `CaptionsTest.kt`. If you change the cloud format, change both in the same PR.

## Package

- Arch: `cd packaging && makepkg -si`
- GitHub Releases (next to the APK): `packaging/dist.sh` builds `target/dist/nimbo-desktop-<version>-linux-x86_64.tar.gz` and its `.sha256`.

Data lives in `~/.local/share/nimbo-desktop` (TDLib database, preferences, playback positions).
