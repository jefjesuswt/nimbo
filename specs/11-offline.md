# Spec 11 — Disponible sin conexión

- Long-press en archivo, carpeta o álbum → "Disponible sin conexión".
  Se descarga completo a almacenamiento propio de la app
  (`filesDir/offline`, no Downloads), con progreso en la card de cola.
- Marca visible (icono de nube con check) en lista/grid.
- Reproducir/abrir usa la copia local primero (sin red, sin espera).
- Carpeta/álbum marcado: lo nuevo que se suba ahí también se baja.
- More → Almacenamiento: espacio usado por offline y por caché temporal
  (stream, thumbs, covers), botones para vaciar cada uno, límite de
  caché configurable (hoy LRU fijo).
- Índice: columna `offline_path` en `tg_files`.
