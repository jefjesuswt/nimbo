# ADR 0007 — Capas, Hilt y ViewModels

- Estado: aceptado (implementado en spec 15)
- Fecha: 2026-10-24
- Sustituye: alternativa 3 de la ADR 0001 ("clean architecture… se introduce cuando exista motor"). El motor ya existe (TDLib, Media3, ML Kit).
- Contexto: [auditoría](../auditoria-arquitectura.md). Hay 13 `object` globales con estado, ningún ViewModel, `TgRepo` tiene 1100 líneas y `TelegramRoute` 1760, y hay bugs causados por el diseño (A1-A8).

## Decisión

Un solo módulo `:app` (por ahora sin multi-módulo), separado en capas por paquete, con **Hilt** para la DI y **ViewModel + `StateFlow<UiState>`** por pantalla.

```
com.nimbo
├── NimboApplication.kt        @HiltAndroidApp
├── MainActivity.kt            @AndroidEntryPoint, solo NimboNavHost
├── core/
│   ├── di/                    AppModule (scope de app, dispatchers), qualifiers
│   ├── tdlib/                 TdClient (interfaz) + TdlibClient (impl) + TdResult
│   └── prefs/                 AppPrefs (un único SharedPreferences "nimbo", claves tipadas)
├── data/
│   ├── cloud/                 CloudRepository (listado + índice), FileOps (rename/move/copy/delete, carpetas)
│   ├── cloud/caption/         CaptionCodec = TgFolders.kt actual (puro, contrato intacto)
│   ├── cloud/local/           CloudIndex (SQLite actual, sin Room)
│   ├── transfer/              TransferManager (lotes, conflictos, cancelación) + UploadService
│   ├── auth/                  AuthRepository (estado auth + TgSecrets/SecretStore)
│   ├── media/                 ThumbRepository, CoverRepository (LruCache), TgArt
│   └── translate/             Translator + TranslatorModule por flavor
├── player/                    PlayerController (@Singleton), PlaybackService, TgStreamSource
└── feature/
    ├── auth/                  AuthViewModel + Keys/Login/Code/Password screens
    ├── files/                 FilesViewModel + FilesScreen (carpetas, selección, portapapeles)
    ├── music/  photos/        sus ViewModels (álbumes, orden por pestaña)
    ├── viewer/ video/ reader/ destinos de navegación (antes overlays con flags globales)
    ├── player/                PlayerSheet + MiniPlayer sobre PlayerController
    └── settings/              SettingsViewModel + SettingsScreen + About
ui/common, ui/theme            se quedan como están
```

### Reglas

- **Dependencias en un solo sentido:** `feature → data/player → core`. `data` no importa `R`, `Intent` ni Compose: emite tipos (`TransferEvent.Cancelled`, `ResultFailed(names)`) y la UI los convierte en strings.
- **Nada de `object` con estado.** Las clases son `@Singleton` con `@Inject constructor`. Los `object` solo pueden contener funciones puras o constantes.
- **`TdClient`** es una interfaz `suspend fun <T : TdApi.Object> send(fn): TdResult<T>` (sealed `Ok`/`Err`), con `authState: StateFlow` y `fileUpdates(fileId): Flow<TdApi.File>` respaldado por un `StateFlow` por archivo, para que el último estado de cada archivo nunca se pierda (corrige A3). La implementación real envuelve `Client.create`; los tests usan `FakeTdClient`.
- **Scope de aplicación:** `@ApplicationScope CoroutineScope` (SupervisorJob + `Dispatchers.Default`) y `@IoDispatcher` inyectados. Así no hay `CoroutineScope(...)` sueltos en cada object.
- **ViewModels:** `@HiltViewModel` con un `UiState` inmutable expuesto como `StateFlow` y eventos de una vez (snackbar) por `Channel`. Las acciones son `suspend` y se lanzan en `viewModelScope`; se acabaron los callbacks `done: (Boolean) -> Unit` desde hilos IO.
- **Estados tipados:** `enum Tab`, `sealed AuthStep`, `enum ThemeMode`, `data class SortKey(field, asc)`, `enum RepeatMode`, `enum ClipMode`, `enum BatchItemState`.
- **Navegación:** `navigation-compose` con rutas tipadas (`@Serializable`) para pestañas y para visor, vídeo, lector y player. El back stack sustituye a los `BackHandler` encadenados y a `ViewerNav`/`VideoNav`/`PlayerNav`. Lo compartido desde fuera (`ACTION_SEND`) entra como argumento o evento de la Activity, no por `SharedUpload`.
- **Player:** `PlayerController` es dueño del `ExoPlayer`. `PlaybackService` (`@AndroidEntryPoint`) lo recibe inyectado. Las posiciones se guardan en un `PlaybackPositions` (index) inyectado, no se le piden a `CloudRepository`.
- **Flavors:** `src/public/.../TranslatorModule.kt` y `src/personal/.../TranslatorModule.kt` aportan `@IntoSet Translator`. Se sigue sin código del traductor web en `main`.
- **Contrato en la nube** (AGENTS.md): `CaptionCodec` es el `TgFolders.kt` actual movido de paquete, con los mismos tests. Ningún cambio de formato.

## Alternativas

1. **DI manual (`AppContainer`)**: cero dependencias, pero cada ViewModel necesita su factory y los servicios acceden a mano. Escala peor; se descarta a petición del usuario.
2. **Koin**: más ligero, pero sin verificación en compilación. Descartado.
3. **Multi-módulo (`:core:tdlib`, `:data`, `:feature:*`)**: compila en paralelo y aísla mejor, pero hoy es over-engineering para una app de una persona. Los paquetes ya marcan las fronteras para partir más adelante si hace falta.
4. **Room / DataStore**: el índice SQLite y SharedPreferences funcionan. Se dejan detrás de `CloudIndex`/`AppPrefs` y se pueden cambiar sin tocar las capas de arriba (DataStore sí entra con la migración de C5).

## Consecuencias

- Dependencias nuevas: Hilt (`hilt-android` + KSP), `hilt-navigation-compose`, `kotlinx-serialization` (rutas tipadas) y `kotlinx-coroutines-test` (tests). Se va `material3-window-size-class`. Cambia la regla de AGENTS.md "mínimo dependencias" para estas; se actualiza al aceptar la ADR.
- La migración es incremental (spec 15): cada fase compila, pasa tests y se verifica en el teléfono antes de la siguiente. Durante la transición, los `object` que queden pueden delegar en el `@Singleton` nuevo (fachada temporal marcada con `ponytail:`) para no romper a quien aún los llame.
- Reversión: cada fase es un PR separado y se puede revertir por separado.

## Implementación (2026-10-24)

Lo hecho difiere del borrador en estos puntos:

- `CaptionCodec` no es una clase: son funciones puras de primer nivel en `data/cloud/Captions.kt` (contrato) y `Library.kt` (árbol, álbumes, orden, búsqueda), igual que antes pero en su capa.
- `FileOps` se llama `CloudOps`. El índice SQLite (`CloudIndex`) queda detrás de `CloudStore` para poder probar los repos sin Android.
- `TdClient.send` sigue devolviendo `TdApi.Object` crudo; las llamadas nuevas usan `call<T>()` (`TdResult`) y `ok()`.
- Las pestañas son destinos del NavHost (con `saveState`/`restoreState`). La hoja del player sigue siendo un overlay sobre el contenido (no es una ruta), abierta desde el minibar o al reproducir.
- Thumbs y carátulas llegan a la UI por `LocalArtwork` (un `@Singleton` que provee la Activity, al estilo del ImageLoader de Coil), sin repetirlos en cada ViewModel.
- La UI del player lee los `StateFlow` de `PlayerController`; `MediaController` queda pendiente (B9 a medias: ya no es global, pero comparte instancia con el servicio por inyección).
- Kotlin pasa de 2.2.21 a 2.4.21: Navigation 2.10 / Lifecycle 2.11 traen kotlin-stdlib 2.4.
