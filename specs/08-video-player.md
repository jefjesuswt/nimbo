# Spec 08 — Reproductor de vídeo propio

Hoy el vídeo suena dentro del PlayerSheet de audio (caja 16:9, sin
controles propios). Spec: visor de vídeo dedicado, sobre el mismo
`TgPlayer` (Media3 ExoPlayer + descarga progresiva TDLib). Cero deps nuevas.

## Comportamiento

- Tap en un vídeo (lista, grid, Fotos) → pantalla completa negra, edge-to-edge,
  barras del sistema ocultas (se muestran con los controles).
- Orientación: sigue al sensor; si el vídeo es horizontal y el móvil está
  vertical, botón "rotar" en controles. Bloqueo de rotación opcional.
- Controles (aparecen con tap, se ocultan a los 3 s reproduciendo):
  título, atrás, play/pausa central, seek con tiempo actual/total,
  anterior/siguiente (cola = carpeta/álbum), velocidad (0.5–2×), subtítulos,
  PiP, menú (descargar, abrir en Telegram, detalles).
- Gestos: doble tap izquierda/derecha = −10/+10 s (acumulable, con
  indicador); deslizar vertical mitad izquierda = brillo de la ventana,
  mitad derecha = volumen; deslizar horizontal = seek fino; pellizco =
  ajustar/rellenar (zoom a recorte).
- Subtítulos: `.srt`/`.vtt` hermano con el mismo nombre base en la misma
  carpeta (`peli.mp4` + `peli.srt` / `peli.es.srt`) → se baja y se pasa
  como `SubtitleConfiguration` de Media3. Selector si hay varios. Sin
  subtítulos embebidos en v1 si Media3 no los expone solo.
- PiP (Android 8+): botón + automático al salir con Home mientras suena
  (`setAutoEnterEnabled` en 12+). Manifest `supportsPictureInPicture`.
- Buffering: indicador + % de descarga (como el player de audio).
- Back: cierra el visor; el audio/vídeo se detiene (vídeo no sigue en 2º plano;
  el minibar es solo para audio).

## Técnica

- `VideoViewer.kt` en `ui/telegram/`: `AndroidView(PlayerView)` con
  `useController = false`, controles en Compose encima.
- Brillo: `window.attributes.screenBrightness`; volumen: `AudioManager`
  STREAM_MUSIC. Se restauran al cerrar.
- `ViewerNav.open = true` mientras está abierto (oculta navbar y minibar).
- Puras + tests: aritmética de doble tap acumulado, mapeo de gesto a
  brillo/volumen (0..1), elección de subtítulo por idioma (`peli.es.srt`
  según locale > `peli.srt`).

## Verify (celu)

- mp4 1080p de 500 MB arranca < 3 s, seek funciona antes de bajar todo.
- Doble tap, brillo, volumen, velocidad, PiP y vuelta, rotación sin cortar.
- `.srt` hermano aparece y se puede apagar.

## Implementado (v1)

`ui/telegram/VideoViewer.kt`. Diferencias con lo de arriba:
- Ajustar/rellenar es un botón, no pellizco; sin seek por arrastre
  horizontal (la barra basta). Siguientes si se echan de menos.
- Arranca tras bajar el archivo entero (igual que el audio): sin
  streaming progresivo todavía.
- Vídeo horizontal gira a horizontal solo; botón de rotar para cambiar.
- Subtítulos: solo `.srt` / `.vtt` hermanos (no embebidos).
