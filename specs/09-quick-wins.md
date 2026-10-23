# Spec 09 — Mejoras rápidas: continuar, deshacer, refrescar, ordenar

Cuatro cambios pequeños de uso diario. Cada uno con su commit.

## 9a — Continuar donde lo dejaste

- Guardar posición por archivo (audio y vídeo) en el índice SQLite:
  columna `pos_ms` en `tg_files` (migración de versión de `TgIndex`).
- Se guarda al pausar, al cambiar de pista y cada 10 s; se ignora si el
  archivo dura < 3 min o si quedan < 20 s (se considera terminado → 0).
- Al reproducir: retoma la posición; en el player, chip "Continuar en 12:34"
  con opción de empezar de cero.
- Lista/grid: barra fina de progreso bajo el título en archivos empezados.
- Puras + tests: `resumeFrom(posMs, durMs)`.

## 9b — Papelera y deshacer

- Borrar (archivo, selección o carpeta) oculta al instante y muestra
  snackbar "Borrado · Deshacer" 5 s; solo después se llama a
  `DeleteMessages`. Si la app muere antes, no se borra (seguro).
- v2 opcional: papelera real 30 días = mover a `#nimbo/.trash/<fecha>/` (retag,
  cero bytes) + pantalla Papelera en More con vaciar/restaurar.

## 9c — Deslizar para refrescar

- `PullToRefreshBox` (M3) en Files/Audio/Fotos y en álbumes; reemplaza al
  Refresh del ⋮ (se puede dejar en el ⋮ también).

## 9d — Ordenar

- En la hoja de filtros: ordenar por nombre, fecha, tamaño, duración;
  ascendente/descendente; recordado por pestaña (SharedPreferences).
- Carpetas siempre primero en Files.
- Puras + tests: comparadores.
