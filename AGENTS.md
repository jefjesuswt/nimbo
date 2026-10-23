# AGENTS.md — Nimbo Android

Tu nube personal sobre Telegram Saved Messages: archivos, carpetas virtuales,
música (player), fotos y texto. Kotlin + Compose Material 3. Rendimiento
primero, sin over-engineering. Nació dentro de Toolbox (ver ADR 0006).

## Comandos
- Build: `./gradlew assembleDebug`
- Tests: `./gradlew testDebugUnitTest`
- Lint: `./gradlew lintDebug`
- Instalar al celu (WiFi adb): `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Logs: `adb logcat --pid=$(adb shell pidof com.nimbo)`
- `gradlew` es shim al Gradle cacheado + JDK 17 (ver archivo). Java 27 default rompe AGP.

## Estructura
- `app/src/main/java/com/nimbo/` — `MainActivity.kt` (navbar estilo Mihon: Files · Music · Photos · More), `ui/telegram/`, `ui/settings/`, `ui/common/`, `ui/theme/`
- Dynamic color: `dynamicLightColorScheme()` / `dynamicDarkColorScheme()` en Android 12+, fallback con seed.
- Logo: disco con agujero de nube (`docs/logo.svg`), blanco y negro. Icono adaptativo en `res/drawable/ic_launcher_*`; la capa `monochrome` (Android 13+) la tiñe el launcher con Material You si el usuario activa iconos temáticos. Solo cuenta el alfa: el agujero va con `evenOdd`, nunca pintado encima.

## Reglas
- Solo componentes M3 estándar. Sin colores hardcodeados: `MaterialTheme.colorScheme`.
- Minimo dependencias. Stdlib / Compose primero. Nada de abstracciones de un solo uso.
- Componentes compartidos en `ui/common/` (`NimboScaffold`, `NimboTopBar`, `NimboListRow`, `FilterChipRow`, `RadioDialog`, `SectionLabel`, `EmptyState`, `LeadingCircle`, `EqBars`). Patrón usado 2+ veces → va ahí.
- `ponytail:` marca shortcuts con techo conocido.
- PLAN.md: leer al inicio, marcar `[x]` al completar, añadir scope nuevo al momento.
- Arquitectura: ADR en `docs/adr/`, trabajo por `specs/0N-*.md`.
- TDD en lógica pura: `app/src/main/.../*.kt` + `app/src/test/.../*Test.kt` (JUnit + kotlin-test).

## Formato en la nube (contrato, no romper)
- Carpeta: `#nimbo/<ruta>` en el caption. Se lee también `#toolbox/<ruta>` (subidas viejas); el retag migra a `#nimbo/`.
- Audio: `#tgmeta <seg>s "título" "artista"`. Cover de carpeta: `#tgcover *` (+ `#tggen` si salió del arte embebido, + `#tgname "cover.jpg"` si era archivo). Carpeta vacía: texto `#tgkeep`. Traducción guardada: `#tgtrans`.
- Carátulas: SOLO arte embebido en el audio o archivo canónico de la misma carpeta (`cover.jpg`, `folder.png`, `front.jpg`…, ver `CoverNames`). Sin heredar de subcarpetas/padres, sin emparejar por nombre de pista.

## Seguridad
- `api_id`/`api_hash` y la clave de la base TDLib viven solo en EncryptedSharedPreferences. Nunca en código ni repo.
- Base TDLib cifrada (`TgSecrets.dbKey`). `allowBackup=false` + `data_extraction_rules`: la sesión no sale del teléfono.
- Nada de datos personales en tests/docs: nombres de ejemplo neutros.
