# Spec 07 — Telegram cloud real (TDLib + Media3)

> Escrita cuando vivía en Toolbox. Desde ADR 0006 es Nimbo: tag `#nimbo/`
> (se lee `#toolbox/`), sin Archives/TransferCenter/CompressSheet.

Fuente: ADR 0005. Sucede a spec 05 (contrato UI intacto).

Modelo: nube-first. Todo en Saved Messages. Cero copias locales salvo
descarga explícita. Streaming = descarga progresiva a caché temp borrable.

## 7a — Auth + keys

- Settings: campos `api_id`, `api_hash` (EncryptedSharedPreferences) + estado
  sesión. Sin keys → TelegramRoute pide keys antes que teléfono.
- Flujo: teléfono → OTP 5 dígitos (UI actual) → 2FA password si TDLib lo pide
  → sesión persistida en `filesDir/tdlib`.
- Puras testeables: `parseApiId(s): Int?`, `isCodeOk` reuse, `needs2fa(state)`.
- Verify: login real en celu → cierra sesión, reabre app → sigue dentro.

## 7b — Lista nube (Drive-style, sin local)

- `TgRepo`: `history(limit, offset)` → `List<TgFile>` con
  `TgFile(id=messageId, fileId, name, kind, size, date, tag)`.
- Filtros All/Audio/Documents + búsqueda por nombre (local sobre página
  cargada) + toggle list/grid (UI actual reuse).
- Paginación `getChatHistory` 50/página, scroll infinito. Pull-to-refresh.
- Verify: sube 3 archivos desde Telegram oficial → aparecen en app.

## 7g — Fotos/vídeo + metadatos audio (después)

Hoy `mapMessage` ignora `MessagePhoto`/`MessageVideo` (tu imagen no sale)
y tira título/artista/caratula de `Audio`.

- Kinds nuevos `IMAGE, VIDEO`: mapeo + filtros + grid art con thumbnail
  TDLib (`downloadFile` del thumb → `BitmapFactory`, plataforma, 0 deps;
  `// ponytail:` sin Coil hasta que duela).
- Metadatos audio: `displayTitle` (title ⊃ fileName), artista, carátula en
  player/lista. Backup preserva tags (TDLib no los toca al subir).
- Verify: foto en Saved Messages → sale en grid con thumb; mp3 con tags →
  artista + carátula visibles.

## 7c — Backup atajos (cualquier archivo)

- Staging `cacheDir/tg-upload` (TDLib exige path) → `SendMessage` Saved
  Messages con caption `#toolbox/<carpeta>` → espera `updateFile` completed
  (serie con Mutex) → borra staging → refresh. Nube-first: staging temporal.
- Telegram FAB → upload real múltiple. Card upload prototipo (tonal + %).
  Sin X v1 (cancelar exige DeleteMessages con id aún desconocido).
- CompressSheet botón "Backup" sobre picked uris (rows mock sin archivo
  real no tienen ⋮; spec original pedía ⋮, se mueve a archivos reales).
- Sistema `ACTION_SEND` → `SharedUpload` → sube al abrir Telegram con sesión.
- Progreso upload en TransferCenter (UPLOAD) + card.
- Verify: backup mp3 desde Files (share) → card % → aparece en lista y en
  Telegram oficial, `tg-upload` vacío.

## 7d — Streaming audio (Media3, caché temp)

- Player: ExoPlayer + `ProgressiveMediaSource` + `CacheDataSource`
  (`cacheDir/tg-stream`, LRU 200MB). Minibar actual → controles reales
  play/pause/seek. No-audio (pdf/zip): tap → snackbar "Descarga para ver"
  + botón Descargar.
- Player sheet completo: tap en minibar abre sheet con arte, título, slider
  seek, play/pause, anterior/siguiente (cola = lista filtrada actual).
- Eq animado en lista/minibar/grid: barras con `rememberInfiniteTransition`
  solo mientras suena; quieto en pausa. Grid sin iconos play: card en repro
  muestra solo barra progreso sutil abajo + títulos `minLines = 2` (uniformes).
- Caché temp: botón Settings "Borrar caché Telegram". Nunca en Downloads.
- Visores imagen/doc: `ACTION_VIEW` al sistema (0 código propio).
  Visor propio solo si el intent falla en la práctica.
- 7g visor fotos propio (pager+zoom+share/download) + lector texto simple
  (txt/md/srt/..., tope 2MB). Resto de docs → sistema.
- Futuro lector: traducir con ML Kit Translate on-device (modelos por
  idioma, botón + selector en el lector).
- Futuro audio: transcribir on-device con whisper.cpp (NDK + modelo
  150-500MB, Media3 transcribe a wav 16kHz). Un móvil de gama alta lo corre bien.
- Verify: play flac 140MB sin WiFi rápido → arranca <3s (progresivo), seek
  funciona, `cacheDir/tg-stream` se llena, Downloads vacío.

## 7e — Gestión grid (rename/delete/descargar SAF)

- Grid 2 col reuse + long-press → renombrar (caption edit), borrar mensaje,
  descargar explícita (SAF a Downloads), compartir link.
- Búsqueda + filtro tag carpeta. EmptyState intacto.
- Verify: renombra, borra, descarga → Telegram oficial refleja cambio.

## 7f — Carpetas virtuales (índice SQLite sobre Saved Messages)

Telegram no tiene carpetas: caption guarda ruta, SQLite indexa.
Sí, tags sirven para rutas largas: caption libre (1024 chars),
`#toolbox/music/albums/some-artist/night-drive` cabe sobrado.

- Verdad = caption (`#toolbox/<ruta>`, niveles con `/`, sin tag = raíz).
  Nube manda: reinstalas, resync, carpetas intactas.
- Índice = `SQLiteOpenHelper` plataforma (0 deps, `// ponytail:` sin Room
  hasta 3+ tablas o migraciones reales): `tg_files(message_id PK, file_id,
  name, kind, size, date, folder, caption)`. 7b hace upsert al paginar.
- Puras + tests: `parseFolder(caption)`, `tagFolder(caption, ruta)`,
  `normalizePath()` (trim, sin `//`, sin `/` extremos).
- UI: breadcrumb (raíz → sub) + filas carpeta (leading folder icon, conteo)
  + archivos. Back sube un nivel. Mover/renombrar = UPDATE prefijo + editar
  captions en fondo (TransferCenter); nube gana en conflicto.
- Search con `LIKE` primero; FTS solo si mide lento (`// ponytail:`).
- Verify: mueve álbum a `music/albums/...`, mata app, offline → árbol y
  búsqueda siguen; Telegram oficial muestra tag en caption.

## No-entra

- Carpetas reales (canales), carpetas anidadas, Room offline, thumbnails
  video, autoplay lista. `// ponytail:` cada simplificación con techo conocido.

## 7h — Subir carpetas + navegar/reproducir comprimidos

- 7h1 carpetas (hecho): FAB carpeta → walk SAF → archivos con ruta +
  cover de carpeta (`#tgcover *`): `cover.jpg`/`folder.png`… si existe,
  si no el arte embebido del primer audio (`#tggen`, oculta). Presta thumb
  solo a los audios de esa carpeta. `applyCoverLinks` pura + tests.
- 7h2 navegar comprimidos (pendiente): tap en `.zip/.7z` → descarga a
  caché temp → lista con Commons Compress (hay que volver a añadirlo) → tap archivo
  interno → extrae ese entry a caché y abre/reproduce (visor/player
  existente). Streaming dentro de zip = entry completo a caché primero
  (sin random remoto; `// ponytail:` rango-remoto si duele).
- Verify: sube carpeta con 3 mp3 → cada uno con su ruta; abre zip con
  flacs → play sin extraer todo.

## Orden

7a → 7b → 7c → 7d → 7e → 7f → 7g → 7h. Cada fase build + test + install celu antes siguiente.
