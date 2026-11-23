# Spike 16.0 — escritorio (solo Linux x64)

Estado: parcial (2026-11-22). Alcance reducido a Linux por decisión del dueño (sin Windows, MSI ni firma). Código en `spike/` (proyecto Gradle aparte; no entra en `main`).

## Dependencias

```kotlin
plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}
dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation(platform("it.tdlight:tdlight-java-bom:3.5.9+td.1.8.68"))
    implementation("it.tdlight:tdlight-java")
    implementation(group = "it.tdlight", name = "tdlight-natives", classifier = "linux_amd64_gnu_ssl3")
}
```

Resuelto: `tdlight-java 3.5.9+td.1.8.68`, `tdlight-api 4.0.565`, `tdlight-natives 4.0.597` (jar de 19,5 MB; `.so` de 63,9 MB sin comprimir). Solo `it.tdlight` desde `mvn.mchv.eu` (`exclusiveContent`).

## Resultados

| Punto | Resultado |
|---|---|
| Carga del nativo | OK. `GetOption("version")` = `1.8.68`. `execute` exige `initialize` antes (si no, error 503). |
| Workdir | Se extrae en `it.tdlight.native.workdir` (`$XDG_DATA_HOME/nimbo-spike/natives`). Por defecto lo borra tras cargarlo; `it.tdlight.native.deleteLibAfterLoading=false` lo conserva. |
| `ldd` | Sin `not found`. Enlaza `libssl.so.3`/`libcrypto.so.3` (en Arch vienen de `openssl-3`, porque `openssl` ya es la 4), `libz`, `libstdc++` y brotli/zstd. El paquete debe declarar OpenSSL 3. |
| `SetTdlibParameters` | Mismos 14 argumentos y mismo orden que `TdlibClient.kt` de Android. |
| Material 3 | El estable de CMP (`material3 1.9.0`) no trae Wavy; `1.12.0-alpha03` sí (compila `LinearWavyProgressIndicator`). No hace falta plan B por M3. |
| Login | QR (`RequestQrCodeAuthentication`) + 2FA en la ventana: OK. |
| Listado de Saved Messages | 1831 mensajes, 1828 con `#nimbo/`, en 35 s en la primera carga (sin caché local; `GetChatHistory` de 100 en 100). |
| Escalado en Hyprland (1,2×) | **Mal.** La ventana va por XWayland (AWT no tiene backend Wayland en OpenJDK) y Hyprland la estira: se ve borrosa. Salidas: `xwayland:force_zero_scaling` + densidad propia, o JetBrains Runtime con WLToolkit (experimental). |
| Streaming (audio/vídeo + seek) | Pendiente. Servidor HTTP con `Range` sobre `DownloadFile` + `ReadFilePart` (`spike/src/main/kotlin/spike/Stream.kt`), reproducido con mpv externo. |
| Topología `:app` + módulo compartido | Pendiente. |

## GPUI (Rust) — comparación

Código en `nimbo-desktop/` (antes `spike-gpui/`; GPUI Kit 0.10 + `tdlib-rs` 1.4 con `download-tdlib`, TDLib 1.8.61 por JSON).

| Punto | Resultado |
|---|---|
| Escalado en Hyprland (1,2×) | **Bien.** Ventana Wayland nativa, escala 1.2, texto nítido. |
| Fuente del sistema | `gsettings` (`Google Sans 10`) → `Theme::font_family`/`font_size`. GPUI no la toma sola. |
| TDLib | `libtdjson` precompilado de `tdlib-rs` hecho con clang: pide `libc++.so.1`, `libc++abi.so.1` (repos oficiales de Arch, no instalados por defecto) y `libunwind.so.1` de LLVM (no está en Arch; en el spike, enlace a `libgcc_s.so.1`). Un paquete tendría que llevarlos o compilar TDLib con gcc. |
| Login | QR + 2FA: OK. |
| Listado | 1852 mensajes, 1849 con `#nimbo/`, en 31 s (Compose: 35 s; lo marca el servidor, no el cliente). |
| Compilación | 9 min la primera vez; binario debug de 1,1 GB (release por medir). |
| Streaming | OK. Servidor `tiny_http` con `Range` (`src/stream.rs`) sobre `DownloadFile` + `ReadFilePart`; audio y vídeo con seek, probado por el dueño. |
| Reproductor embebido | OK. libmpv 2.6 por FFI (`src/mpv.rs`), render por software (`bgr0`, máx. 1280 px de ancho) pintado como `RenderImage`; barra inferior para audio y vista de vídeo en la ventana. Riesgo: el render SW puede ir justo en 1080p; la versión de verdad usaría el render GL/dmabuf de mpv. |
| Listado con caché local | 1849 archivos en 0,8 s al reabrir. |
| Subida | `SendMessage` + `InputMessageDocument` con caption `#nimbo/<ruta>` (mismo contrato que el teléfono); selector del portal y arrastrar a la ventana. `#tgmeta` con `lofty`; cover de carpeta canónica (`#tgcover * #tgname`) o arte embebido (`#tgcover * #tggen`), como `walkDir`. |
| Contrato | `src/captions.rs` porta `Captions.kt` con los casos de `CaptionsTest.kt` (`cargo test`). |
| Sincronía en vivo | `UpdateNewMessage`, `UpdateMessageContent` (relee el mensaje), `UpdateDeleteMessages` (permanentes) y `UpdateMessageSendSucceeded` (id temporal → definitivo), solo de Saved Messages. |
| Escritorio | Atajos (espacio, ←/→, Ctrl+←/→, Esc, Ctrl+F, Retroceso/Alt+←, F) con contexto `!Input`. MPRIS con `souvlaki` (zbus): `playerctl -l` lista `nimbo`. Portadas de carpeta (`folderCovers`) y de audio (`applyCoverLinks`), vista de álbumes en Music, retomar audios ≥ 5 min y vídeos > 1 min (`start` de mpv), visor de texto (Markdown con `TextView`; UTF-8/UTF-16/Shift-JIS con `encoding_rs`). |
| Producto | `api_id`/`api_hash` y clave de base en el llavero (`keyring`, Secret Service; llamadas en `spawn_blocking` porque zbus sobre tokio se cuelga si no). Primer arranque pide las claves o importa las de nimbo-cli. Base TDLib cifrada: una base vieja sin clave se re-cifra al entrar (`SetDatabaseEncryptionKey`). Cerrar sesión crea un cliente nuevo y vuelve al login, borrando estado y posiciones. Datos en `~/.local/share/nimbo-desktop` (se migra la carpeta del spike). |
| Release | `cargo build --release` en 7 min: binario de 57 MB + `libtdjson` de 41 MB (debug: 1,1 GB). `packaging/PKGBUILD` instala en `/usr/lib/nimbo-desktop` y reescribe `libunwind.so.1` → `libgcc_s.so.1` con `patchelf`. ADR 0010 propone GPUI frente a la ADR 0008. |
| Gestión | Como `CloudOps`: mover/renombrar carpeta = retag (400 ms entre ediciones), renombrar archivo = título en `#tgmeta`, carpeta vacía = texto `#tgkeep` (se borra y recrea al mover), borrar = `DeleteMessages` con revoke. Menú contextual, «⋯» y selección con Ctrl/Mayús. |
| Caché | Tope configurable (512 MB / 1 GB / 5 GB / sin límite, por defecto 1 GB), TTL 7 días, inmunidad 1 h, cada 30 min: los mismos valores que `StoragePolicy` del teléfono. |
| Descargas | `DownloadFile` → copia a `~/Downloads/Nimbo/<ruta>` → `DeleteFile` si no estaba ya en caché. |
| Reproductor | Cola con aleatorio (estilo Spotify), repetir off/todo/una (`loop-file`), volumen guardado, vista «Now playing» con la cola. |

## Abierto

- El dueño evalúa GPUI (Rust, Wayland nativo) frente a esta vía: reescribiría en Rust el contrato de `Captions.kt` y `EncFormat.kt`, con casos de prueba comunes para no divergir.
