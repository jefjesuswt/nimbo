# Spec 15 — Migración de arquitectura (ADR 0007)

Objetivo: pasar de `object`s globales y Composables gigantes a capas + Hilt + ViewModels **sin cambiar el comportamiento ni el formato en la nube**. Un PR por fase. Cada fase termina con `./gradlew testPublicDebugUnitTest testPersonalDebugUnitTest lintPublicDebug assemblePersonalDebug` en verde y verificación en el teléfono.

Referencias: [auditoría](../docs/auditoria-arquitectura.md) (A*/B*/C* = hallazgos), [ADR 0007](../docs/adr/0007-capas-hilt-viewmodels.md).

## 15.0 Limpieza y bugs (sin cambio de arquitectura)
- Quitar `navigation-compose:2.7.7` y `material3-window-size-class` (C1). Vuelve una navegación actual en 15.5.
- Introducir `gradle/libs.versions.toml` y subir core-ktx/activity/lifecycle a lo que pide el BOM (C2).
- A1: `TgFile` con `equals` completo. Sacar `thumbMini` a un `value class MiniThumb(val bytes: ByteArray)` con `equals` por contenido, o comparar con `contentEquals`. Test: renombrar el título emite una lista nueva.
- A2: logout → `TgRepo.reset()` (savedChat, myUserId, files, keeps, coverMsgs), `TgIndex.clear()` y `TgPlayer.stop()`. Test con el índice.
- A3: un `StateFlow<TdApi.File>` por fileId para el estado de cada archivo (o `BufferOverflow.SUSPEND` + `emit` en un hilo propio). No se puede volver a perder un `UpdateFile` completado.
- A4: `LruCache` (por bytes) en thumbs y covers.
- A5/A6: ids de lote con `AtomicLong`; mapas de lote `ConcurrentHashMap` (o `Mutex`).
- A7: `TgSecrets` cachea la instancia de prefs (lazy) y `hasKeys` se lee fuera del hilo principal.
- C6: `BuildConfig.VERSION_NAME` en `SetTdlibParameters`. C7: imports en vez de FQN.

## 15.1 Hilt + core
- `NimboApplication` (`@HiltAndroidApp`), `MainActivity`/`PlaybackService`/`UploadService` como `@AndroidEntryPoint`.
- `core/di/AppModule`: `@ApplicationScope CoroutineScope`, `@IoDispatcher`.
- `core/tdlib/TdClient` (interfaz + `TdResult`) y `TdlibClient` = el `TgClient` actual. El `object TgClient` pasa a ser una fachada que delega (`ponytail:`, se borra en 15.6).
- `core/prefs/AppPrefs`: las 8 lecturas de `getSharedPreferences("nimbo")` pasan por aquí (B8). Mismas claves, nada que migrar.
- `FakeTdClient` en `test/`.

## 15.2 Partir TgRepo
- `CaptionCodec` (= `TgFolders.kt` + `mapMessage`/`coverLinkOf`, puro) a `data/cloud/caption/`. Los tests solo cambian de paquete.
- `CloudRepository`: carga, índice, `files/keeps/loading`, deshacer al borrar, offline.
- `FileOps`: rename, move, copy, delete, carpetas (crear/renombrar/borrar). `suspend` que devuelven `Result`.
- `TransferManager`: lotes, conflictos, cancelación, descargas a Downloads, `UploadService`. Emite `TransferEvent` en vez de strings (B3).
- `PlaybackPositions`: posiciones (sale de TgRepo; rompe el ciclo `TgPlayer → TgRepo`, B2).
- Tests nuevos con `FakeTdClient`: conflictos SKIP/DUPLICATE/REPLACE, cancelar borra lo parcial, deshacer dentro de los 5 s no borra.

## 15.3 Player y media
- `PlayerController` `@Singleton` (= `TgPlayer`), `TgStreamSource` con `TdClient` inyectado y `TgDownload` dentro de `TransferManager`/`TdClient`.
- `PlaybackService` recibe el controller inyectado. Se estudia `MediaController` para la UI (B9).
- `ThumbRepository`/`CoverRepository` con LruCache (A4 ya hecho) y el scope inyectado.
- Tests: cola, repeat/shuffle y `resumeFrom` con un player falso.

## 15.4 ViewModels por feature
- `AuthViewModel` (`sealed AuthStep` en vez de `view = -1..3`), `SettingsViewModel` (ThemeMode/Seed/dynamic + traductor + keys).
- `FilesViewModel` (dir, selección, portapapeles `ClipMode`, búsqueda, orden), `MusicViewModel`, `PhotosViewModel` (álbumes, `SortKey`, grid por pestaña).
- Partir `TelegramRoute.kt` en `feature/auth`, `feature/files`, `feature/music`, `feature/photos`, `feature/transfer` (cards de lote). Ningún archivo de UI pasa de unas 400 líneas.
- Enums tipados: `Tab`, `ThemeMode`, `RepeatMode`, `BatchItemState` (B7).

## 15.5 Navegación
- `navigation-compose` actual + `kotlinx-serialization`, con rutas `@Serializable`: pestañas, `PhotoViewer(index)`, `Video(messageId)`, `Reader(messageId)` y el sheet del player.
- Se van `ViewerNav`, `VideoNav`, `PlayerNav`, `SharedUpload` y los `BackHandler` encadenados (B6). El PiP lo resuelve la Activity a partir del destino actual.

## 15.6 Cierre
- Borrar las fachadas `object` que queden. Grep de `^object ` → solo puros/constantes.
- Actualizar AGENTS.md (estructura, regla de dependencias) y la ADR 0007 → aceptado.
- Opcional: `targetSdk` al mínimo actual de Play (C3), R8 con keep rules de TDLib (C4), migrar `EncryptedSharedPreferences` (C5) en su propia spec.
