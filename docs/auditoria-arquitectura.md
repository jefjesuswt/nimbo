# Auditoría de arquitectura — Nimbo Android

- Fecha: 2026-10-24 · Base: `main` @ `ba234dd` (v1.0.0 + proveedores de traducción)
- Alcance: `app/src/{main,public,personal,test}` (~8.7k líneas Kotlin, 1 módulo)
- Resultado: la arquitectura objetivo está en [ADR 0007](adr/0007-capas-hilt-viewmodels.md) y el plan de trabajo en [spec 15](../specs/15-arquitectura.md).

## Resumen

La app funciona y la lógica pura (captions, carpetas, arte embebido, orden y búsqueda) está bien separada y testeada. El problema está en cómo se unen las piezas:

1. **Todo es un `object` global con estado mutable.** Hay 13 singletons (`TgClient`, `TgRepo`, `TgPlayer`, `TgCovers`, `TgThumbs`, `TgDownload`, `TgSecrets`, `Translators`, `SettingsPrefs`, `SharedUpload`, `PlayerNav`, `ViewerNav`, `VideoNav`) que se llaman directamente desde Composables, servicios y la Activity. No hay inyección de dependencias, ni costuras para tests, ni ciclo de vida definido (`init(ctx)` se llama a mano desde varios sitios).
2. **No hay capa de presentación.** No hay ni un solo `ViewModel`. Todo el estado de pantalla vive en `remember` dentro de Composables enormes (`TelegramRoute.kt` tiene 1760 líneas y `TelegramRoute()` sola ocupa unas 640, con unas 40 variables `remember` y unas 15 `collectAsState`).
3. **Hay "god objects" en datos.** `TgRepo.kt` (1102 líneas) mezcla listado, índice offline, operaciones de archivo y carpeta, subidas, descargas, conflictos, deshacer, posiciones de reproducción, deep links, notificaciones y textos traducidos.
4. **La navegación es ad hoc.** Pestañas con `String`, overlays con flags globales (`ViewerNav.open`, `VideoNav.open`, `PlayerNav.openSheet`) y unos 8 `BackHandler` encadenados con condiciones como `enabled = view == 2 && dir.isNotEmpty() && !searching && query.isEmpty() && viewerIdx == null && …`. `navigation-compose` está declarada y no se usa.
5. **Hay bugs latentes que vienen directamente del diseño** (sección A): actualizaciones que no se pintan, una sesión anterior que se filtra tras el logout, eventos de TDLib que se pierden y cachés de imágenes sin límite.

## A. Bugs causados por el diseño (prioridad alta)

| # | Dónde | Problema | Efecto |
|---|---|---|---|
| A1 | `ui/telegram/Telegram.kt:31-35` | `TgFile.equals` solo compara `id/fileId/messageId/name` (por el `ByteArray`). `MutableStateFlow` descarta los valores *iguales*. | `renameTitle` (cambia `title`) y `noteCover` (cambia `coverHash`) generan una lista "igual": `TgRepo.files` no emite y la UI no se entera hasta que cambie otra cosa. |
| A2 | `TgRepo.kt:223-224,313` + `settings/SettingsRoute.kt:319` | `savedChat` y `myUserId` se cachean para siempre. El logout no los resetea, ni vacía `_files`, ni llama a `TgIndex.clear()` (existe en `TgIndex.kt:148`, pero nadie la usa). | Al entrar con otra cuenta se sigue usando el chat de la anterior y el índice offline muestra los archivos de la cuenta anterior. Es un problema de privacidad. |
| A3 | `TgClient.kt:24,46` | `MutableSharedFlow(extraBufferCapacity = 64)` + `tryEmit`. Si un colector va lento, `tryEmit` falla y **el update se pierde**. | Cuando TDLib manda ráfagas de `UpdateFile` (lotes grandes), el `UpdateFile` de "completado" se puede perder: `waitJob`/`awaitFile` esperan hasta el timeout de 180 s y marcan como fallido algo que sí se subió o bajó. |
| A4 | `TgThumbs.kt:14-20`, `TgCovers.kt:22` | El comentario dice "tope 60", pero el `Map<Int, ImageBitmap>` crece sin límite. `TgCovers` igual. | En bibliotecas grandes la memoria crece sin freno (bitmaps de hasta 512 px) y acaba en un OOM. |
| A5 | `TgRepo.kt:207-209` | `batchJobs`, `cancelFlags` y `batchSent` son `mutableMap/Set` normales que se tocan desde `Dispatchers.IO` (varios hilos) y desde la UI. | Hay condiciones de carrera al cancelar mientras arranca otro lote. |
| A6 | `TgRepo.kt:551,839,1009` | El id de lote es `System.currentTimeMillis()`. | Dos lotes en el mismo milisegundo (scan + enqueue) chocan: se cancela o se oculta la tarjeta que no era. |
| A7 | `TelegramRoute.kt:146`, `TgSecrets.kt:16-25` | `TgSecrets.prefs()` reconstruye `MasterKey` y `EncryptedSharedPreferences` en **cada** llamada, y `hasKeys` se ejecuta en el hilo principal durante la composición. | Hay jank al abrir la app (Keystore + Tink en el hilo principal). |
| A8 | `TgRepo` callbacks `done: (Boolean) -> Unit` | Los callbacks llegan desde un hilo IO y la UI escribe estado de Compose desde ahí (`dir = …` en `createFolder`). | Hay escrituras de snapshot fuera del hilo principal, con su riesgo de condiciones de carrera. |

## B. Arquitectura y acoplamiento

- **B1 · Sin DI.** Cada `object` se inicializa con `init(ctx)` cuando alguien se acuerda (`TelegramRoute` llama a `TgRepo.init`, `TgCovers.init` y `TgPlayer.init`; `PlaybackService` llama a `TgPlayer.init`; `createFolder` se reinicializa a sí mismo). Si el orden falla: `IllegalStateException("TgPlayer.init first")`, o un `app == null` que devuelve `false` sin decir nada.
- **B2 · Dependencias circulares entre singletons.** `TgPlayer → TgRepo` (posiciones), `TgCovers → TgDownload` (vive dentro de `TgPlayer.kt`), `TgRepo → UploadService`, y la `MainActivity` lee `TgPlayer`/`VideoNav`. Nada se puede probar por separado.
- **B3 · La capa de datos conoce la UI.** `TgRepo` resuelve strings (`R.string.download_cancelled`, `R.plurals.files_count`, `R.string.root`), arranca servicios y lanza `Intent`s (`openInTg`). `TgView.open` lanza un chooser desde un `suspend`.
- **B4 · Errores como valores ambiguos.** `sendAwait` devuelve `TdApi.Object` y cada llamada hace `as?` o `is TdApi.Error`. Hay 61 `runCatching` y 15 `catch` que tragan la excepción sin log. Los fallos llegan a la UI como un `Boolean`.
- **B5 · Todo cuelga de `ui/`.** El cliente TDLib, el repo, el índice SQLite, el player, los servicios y la traducción están en `com.nimbo.ui.telegram`. El paquete no dice nada de la capa.
- **B6 · Composables gigantes.** `TelegramRoute()` gestiona auth, listado, búsqueda, selección, portapapeles, diálogos, visores, player y subidas. `FilesView` (unas 320 líneas) recibe decenas de lambdas. Compose recompone de más y el código no se puede leer.
- **B7 · Estados con `String` e `Int` mágicos.** `view` vale -1/0/1/2/3 para auth; pestañas `"files"/"audio"/"photos"/"more"`; portapapeles `"cut"/"copy"`; repeat `"off"/"all"/"one"`; tema `"system"/"light"/"dark"`; sort `"name:asc"`; `BatchItem.state` vale 0..3. Hay 32 literales de pestaña y modo repartidos. Deberían ser `enum`/`sealed`.
- **B8 · Preferencias dispersas.** Hay 8 llamadas a `getSharedPreferences("nimbo")` en 5 archivos (tema, orden por pestaña, favoritos, traductor, modelos con datos móviles), con claves sueltas y sin un dueño.
- **B9 · Player global compartido.** `PlaybackService` y la UI comparten el `object TgPlayer` en lugar de usar `MediaController`/`MediaSession`. `TgStreamSource` llama a `TgClient` con `runBlocking`. Funciona en un solo proceso, pero no se puede probar ni reemplazar.

## C. Build y dependencias

- **C1 · Dependencias sin usar:** `androidx.navigation:navigation-compose:2.7.7` (sin `NavHost` en ningún sitio), `androidx.compose.material3:material3-window-size-class` (sin `WindowSizeClass`) y `androidx.lifecycle:lifecycle-viewmodel-compose` (sin `viewModel()`; la ADR 0007 le da uso).
- **C2 · Sin version catalog.** Las coordenadas están escritas a mano en `app/build.gradle.kts`. `core-ktx 1.13.1` y `activity-compose 1.9.2` están muy por detrás del BOM 2026.09, y M3 está fijado a `1.5.0-beta01` por encima del BOM.
- **C3 · `targetSdk = 34`** con `compileSdk = 37`: Play exige un target más reciente para publicar o actualizar.
- **C4 · Release sin R8** (`isMinifyEnabled = false`). Ya está en PLAN como pendiente (keep rules de TDLib).
- **C5 · `security-crypto 1.1.0-alpha06`:** `EncryptedSharedPreferences` está deprecado. Hay que planear la migración a Keystore + DataStore/Tink sin perder la clave de la base TDLib (que sí funciona).
- **C6 · La versión de TDLib está fija en `"1.0"`** (`TgClient.kt:56`) en vez de `BuildConfig.VERSION_NAME`.
- **C7 · Unos 64 nombres totalmente cualificados en el código** (`androidx.compose.material3.AlertDialog(...)`, `android.content.Intent(...)`) en vez de imports. Hay imports desordenados (`MainActivity.kt`).

## D. Tests

- Hay 38 tests, todos de funciones puras (bien). No hay **ningún** test de la lógica con estado: lotes, deshacer, conflictos, cola del player, auth. Esa lógica no se puede probar hoy porque depende de `object`s que hablan directamente con TDLib.
- Con el diseño de la ADR 0007 (`TdClient` como interfaz + `FakeTdClient`, dispatchers inyectados) se pueden probar repos y ViewModels con `kotlinx-coroutines-test`.

## E. Lo que está bien y hay que conservar

- El **contrato de captions** (`TgFolders.kt`) como funciones puras con tests. No se toca (AGENTS.md › Formato en la nube).
- La **seguridad**: base TDLib cifrada, `allowBackup=false`, FileProvider acotado.
- Los parsers propios de arte embebido (`TgArt`) y el streaming por rangos (`TgStreamSource`): son buena ingeniería. Solo cambian de sitio y de dependencias.
- `ui/common/` como sistema de componentes compartidos.
- Sources por flavor (`public`/`personal`) para el traductor web. Con Hilt pasa a ser un `@Module` por flavor.
