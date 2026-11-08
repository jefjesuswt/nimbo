# Spec 16 — Inicio (feed) como primera pantalla

> **Idea para más adelante.** No empezar hasta cerrar las verificaciones en
> el celu pendientes en PLAN.md.

Hoy Nimbo abre en Archivos. La idea es abrir en una pestaña **Inicio** con
lo de la propia nube, al estilo de los feeds de las apps de fotos y
música: Inicio · Archivos · Música · Fotos · More.

## Qué sale (todo de la nube del usuario, nada externo)

Tarjetas que se ocultan solas si no hay datos:

- **Continuar**: vídeos y audios empezados (ya está en `PlaybackPositions`).
- **Escuchado hace poco**: chips con carátula, de más reciente a más antiguo.
- **Tu mix de hoy**: pistas al azar con semilla = la fecha (el mismo mix
  todo el día), con más peso para las que más se escuchan.
- **Artistas que más escuchas**: los que más se escucharon en 30 días;
  tocar uno abre sus pistas.
- **Recuerdos**: "tal día como hoy" (fotos de este mismo día en años
  anteriores) y "lo mejor de <mes pasado>" (fotos al azar de ese mes). Por
  fecha del mensaje; EXIF si viene en el caption (v2). Sin IA.
- **Subido hace poco**: fotos, vídeos y álbumes nuevos.
- **Estadísticas de la semana**: tiempo escuchado, reproducciones,
  promedio por día, pista más escuchada y barras por día (lunes a domingo).
- **Copia automática**: tarjeta de estado activada/desactivada (spec 10b).

Fuera de alcance: recomendaciones de música nueva o de otros artistas
(necesitan un servicio externo), edición o "remix" de fotos.

## Técnica

- Nueva tabla en el índice SQLite: `play_log(message_id, started_at,
  listened_ms)`. `PlayerController` registra al cambiar de pista o parar
  (si se escucharon ≥ 30 s). Se vacía al cerrar sesión, como las posiciones.
- `feature/home/` con `HomeViewModel`: un `StateFlow<HomeUi>` hecho de
  `repo.files` + `play_log` + posiciones. Sin red propia.
- Puras + tests: semilla del mix diario, top de artistas, "tal día como
  hoy" (años anteriores y 29 de febrero), cubetas por día de la semana,
  promedio por día.
- Solo componentes M3 estándar (cards, `LazyRow`, chips); las barras se
  dibujan con `Canvas` y `colorScheme`.

## Riesgos

- Biblioteca grande: calcular el feed en `io` y dejarlo guardado hasta que
  cambie `repo.files` o `play_log`.
- `play_log` crece sin tope: compactar lo que tenga más de 1 año (por día).
