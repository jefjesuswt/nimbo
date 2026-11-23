# ADR 0010 — Escritorio nativo en Rust con GPUI

- Estado: aceptado (2026-11-22)
- Fecha: 2026-11-22
- Sustituye, si se acepta: ADR 0008 (escritorio con lógica y UI compartidas en JVM, Compose Multiplatform).
- Contexto: [spike 16.0](../spike-16-0.md). Se probaron las dos vías en Linux (Hyprland, escala 1,2). Compose Desktop arranca y lista, pero AWT va por XWayland y Hyprland lo estira: se ve borroso. GPUI abre una ventana Wayland nativa y nítida. Sobre el spike de GPUI se construyó un cliente usable: login por QR, carpetas, reproductor embebido, subidas y descargas, sincronía en vivo y gestión de archivos.

## Decisión

El cliente de escritorio es **una app aparte en Rust con GPUI** (vía `gpui-kit`), no un módulo Kotlin compartido con Android:

| Pieza | Elección |
|---|---|
| UI | GPUI + `gpui-component` (`gpui-kit 0.10`), Wayland nativo, fuente del sistema por `gsettings` |
| TDLib | `tdlib-rs 1.4` (interfaz JSON, TDLib 1.8.61), `libtdjson` precompilado (`download-tdlib`) |
| Reproductor | `libmpv` del sistema por FFI, render por software a imagen; `hwdec=auto-copy` |
| Streaming | servidor HTTP local con `Range` sobre `DownloadFile` + `ReadFilePart` |
| Secretos | Secret Service (`keyring`): `api_id`, `api_hash` y la clave de la base TDLib (cifrada) |
| Media | MPRIS por zbus (`souvlaki`), etiquetas y carátulas de audio con `lofty` |
| Paquete | PKGBUILD local; `patchelf` cambia `libunwind.so.1` por `libgcc_s.so.1` en `libtdjson` |

### Reglas

- **El contrato en la nube no se duplica a ciegas.** `src/captions.rs` porta `data/cloud/Captions.kt` función a función y lleva los mismos casos de `CaptionsTest.kt`. Un cambio del contrato toca los dos archivos y los dos tests en el mismo PR.
- **Mismas operaciones que el teléfono:** mover/renombrar carpeta = retag del caption (400 ms entre ediciones), renombrar archivo = título en `#tgmeta`, carpeta vacía = texto `#tgkeep`, subir = documento con `#nimbo/<ruta>` + `#tgmeta` + cover de carpeta (`walkDir`).
- **Solo Linux** por ahora. Windows y macOS no se descartan (GPUI los soporta), pero no se prueban ni se empaquetan.
- **Seguridad como en Android:** nada de claves en archivos ni en el repo; base TDLib cifrada; cerrar sesión borra estado en memoria, índice y posiciones.

## Alternativas

1. **Compose Multiplatform (ADR 0008).** Comparte ~4.600 líneas de UI y toda la lógica, pero exige antes las costuras de la spec 16.2 en Android (interfaz de cliente propia, `SecretVault`, `PlayerEngine`…), un espejo Maven de TDlight y CI nueva. En Hyprland se ve borroso salvo `xwayland:force_zero_scaling` o JetBrains Runtime con WLToolkit (experimental). Descartada por la calidad de la ventana y el coste previo.
2. **Tauri/Electron sobre `tdjson`.** Mismo problema de duplicar el contrato que esta ADR, sin la ventaja de una UI nativa. Descartada.
3. **Compilar TDLib con gcc** para no depender de libc++. Más limpio para distribuir, pero son ~30 min de compilación y una cadena CMake/OpenSSL/gperf propia. Queda como siguiente paso si se publica fuera de Arch.
4. **Render de vídeo por GPU** (render GL/dmabuf de mpv). GPUI no expone texturas externas; haría falta un parche a GPUI o una superficie Wayland aparte. Pendiente.
5. **No hacer escritorio.** Sigue siendo válido, pero el spike ya cubre el uso diario.

## Consecuencias

- **Dos bases de código** (Kotlin y Rust). El riesgo es que el contrato diverja; se mitiga con los tests espejo de `captions.rs` y con revisar ambos lados en cada cambio de `Captions.kt`.
- **Versiones de TDLib desacopladas:** Android en 1.8.56 (`tdlibx`), escritorio en 1.8.61 (`tdlib-rs`). Cada uno sube por su cuenta.
- **GPUI es pre-1.0** (`gpui-pre-*` 0.3): habrá cambios de API al actualizar. Se fija la versión en `Cargo.lock`.
- **`libtdjson` precompilado** está hecho con clang: depende de `libc++`/`libc++abi` (repos oficiales de Arch) y de `libunwind.so.1`, que se resuelve con `patchelf`. Fuera de Arch habría que revisar esas dependencias o compilar TDLib.
- **Vídeo por software:** la decodificación va por hardware y el escalado se ajusta a la vista, pero copiar y escalar en CPU cuesta en 4K.
- **Licencias:** GPUI (Apache-2.0), `gpui-kit`/`gpui-component` (Apache-2.0), `tdlib-rs` (MIT/Apache-2.0), TDLib (BSL-1.0), `libmpv` (LGPL/GPL según cómo esté compilado; se enlaza la del sistema), `lofty` y `keyring` (MIT o Apache-2.0), `souvlaki` (MIT). Compatible con el MIT de Nimbo; el paquete incluye la licencia de Nimbo.
- **Dónde vive** (decisión del dueño, 2026-11-22): en este mismo repo, carpeta `nimbo-desktop/` (proyecto Cargo aparte, fuera de Gradle). Sus builds se publican en los mismos Releases de GitHub que el APK.
