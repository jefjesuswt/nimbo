# AGENTS.md — Nimbo Android

Tu nube personal sobre Telegram Saved Messages: archivos, carpetas virtuales,
música (player), fotos y texto. Kotlin + Compose Material 3. Rendimiento
primero, sin over-engineering. Nació dentro de Toolbox (ver ADR 0006).

## Comandos
- Flavors (dimensión `dist`): `public` (por defecto; la de Releases, solo ML Kit) y `personal` (añade el traductor web de Google por multibinding Hilt; código en `src/personal/`, nunca en `main`).
- Build: `./gradlew assemblePublicDebug` · personal: `./gradlew assemblePersonalDebug`
- Tests: `./gradlew testPublicDebugUnitTest testPersonalDebugUnitTest`
- Lint: `./gradlew lintPublicDebug`
- Instalar al celu (WiFi adb): `adb install -r app/build/outputs/apk/personal/debug/app-personal-debug.apk`
- Logs: `adb logcat --pid=$(adb shell pidof com.nimbo)`
- `gradlew` es shim al Gradle cacheado + JDK 17 (ver archivo). Java 27 default rompe AGP.

## Estructura (ADR 0007: capas + Hilt + ViewModels)
- `app/src/main/java/com/nimbo/`:
  - `NimboApplication` (`@HiltAndroidApp`), `MainActivity` (tema, compartir, permisos; nada más).
  - `core/` — `di/` (scope de app, dispatchers), `tdlib/` (`TdClient` interfaz + `TdlibClient`, `SecretStore`), `prefs/AppPrefs` (único dueño del SharedPreferences), `util/`.
  - `data/` — `cloud/` (`CloudRepository`, `CloudOps`, `PlaybackPositions`, captions y árbol puros, `local/` índice SQLite tras `CloudStore`), `transfer/` (`TransferManager`, `FileFetcher`, `UploadService`), `media/` (thumbs/covers con tope de memoria), `translate/`, `auth/`.
  - `player/` — `PlayerController` (dueño del ExoPlayer), `PlaybackService`, streaming TDLib.
  - `feature/` — una carpeta por pantalla con su `@HiltViewModel`: `main/` (NavHost + rutas tipadas: Files · Music · Photos · More estilo Mihon, visores encima), `auth/`, `cloud/`, `transfer/`, `player/`, `viewer/`, `video/`, `reader/`, `settings/`.
  - `ui/common/`, `ui/theme/` — componentes y tema compartidos.
- Dynamic color: `dynamicLightColorScheme()` / `dynamicDarkColorScheme()` en Android 12+, fallback con seed.
- Logo: disco con agujero de nube (`docs/logo.svg`), blanco y negro. Icono adaptativo en `res/drawable/ic_launcher_*`; la capa `monochrome` (Android 13+) la tiñe el launcher con Material You si el usuario activa iconos temáticos. Solo cuenta el alfa: el agujero va con `evenOdd`, nunca pintado encima.

## Reglas
- Solo componentes M3 estándar. Sin colores hardcodeados: `MaterialTheme.colorScheme`.
- Minimo dependencias. Stdlib / Compose primero. Nada de abstracciones de un solo uso. Base aceptada: Hilt (KSP), Navigation Compose + kotlinx-serialization (ADR 0007).
- Capas: `feature → data/player → core`. `data` no importa `R` de UI, `Intent` ni Compose (devuelve tipos; la UI pone el texto con `UiText`).
- Nada de `object` con estado: `@Singleton` + `@Inject constructor`. Los `object` solo para funciones puras/constantes.
- Estado de pantalla en el ViewModel (`StateFlow`); eventos de una vez por `Channel`. Trabajo que debe sobrevivir a la pantalla (retag, subidas, borrado diferido) en `@ApplicationScope`.
- Estados con `enum`/`sealed`, no `String`/`Int` mágicos.
- Imports: sin nombres totalmente cualificados en el código ni imports sin usar.
- Componentes compartidos en `ui/common/` (`NimboScaffold`, `NimboTopBar`, `NimboListRow`, `FilterChipRow`, `RadioDialog`, `SectionLabel`, `EmptyState`, `LeadingCircle`, `EqBars`). Patrón usado 2+ veces → va ahí.
- `ponytail:` marca shortcuts con techo conocido.
- PLAN.md: leer al inicio, marcar `[x]` al completar, añadir scope nuevo al momento.
- Arquitectura: ADR en `docs/adr/`, trabajo por `specs/0N-*.md`.
- TDD en lógica pura: `app/src/main/.../*.kt` + `app/src/test/.../*Test.kt` (JUnit + kotlin-test). Lógica con estado: `FakeTdClient` + `MemoryStore` (`test/.../fakes/`) y `kotlinx-coroutines-test`.

## Releases
- CHANGELOG.md (Keep a Changelog, en inglés): lo nuevo va en [Unreleased]; al publicar se pasa a [x.y.z] - fecha.
- Subir versionName (semver) y versionCode (+1) en app/build.gradle.kts; tag vX.Y.Z; `./gradlew assemblePublicRelease` (nunca el personal); gh release create con ese APK y la sección del CHANGELOG como notas. Comprobar que el APK no contiene `translate.googleapis.com`.
- Firma: NIMBO_KEYSTORE / NIMBO_KEYSTORE_PASSWORD en ~/.gradle/gradle.properties (nunca en el repo). Sin esa clave no se pueden publicar actualizaciones instalables encima.

## Formato en la nube (contrato, no romper)
- Carpeta: `#nimbo/<ruta>` en el caption. Se lee también `#toolbox/<ruta>` (subidas viejas); el retag migra a `#nimbo/`.
- Audio: `#tgmeta <seg>s "título" "artista"`. Cover de carpeta: `#tgcover *` (+ `#tggen` si salió del arte embebido, + `#tgname "cover.jpg"` si era archivo). Carpeta vacía: texto `#tgkeep`. Traducción guardada: `#tgtrans`.
- Archivo > límite: partes `nombre.ext.001`… con `#part <i>/<n> <grupo>` (se agrupan por carpeta + grupo; mover/borrar actúa sobre todas).
- Contrato en `data/cloud/Captions.kt`. Carátulas: SOLO arte embebido en el audio o archivo canónico de la misma carpeta (`cover.jpg`, `folder.png`, `front.jpg`…, ver `CoverNames`). Sin heredar de subcarpetas/padres, sin emparejar por nombre de pista.

## Seguridad
- `api_id`/`api_hash` y la clave de la base TDLib viven solo en EncryptedSharedPreferences (`SecretStore`). Nunca en código ni repo.
- Base TDLib cifrada (`SecretStore.dbKey`). Cerrar sesión vacía memoria, índice y posiciones (`CloudRepository` observa `auth`). `allowBackup=false` + `data_extraction_rules`: la sesión no sale del teléfono.
- Nada de datos personales en tests/docs: nombres de ejemplo neutros.
