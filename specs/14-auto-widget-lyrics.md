# Spec 14 — Android Auto, widget y letras

## 14a — Android Auto

- La `MediaSession` de `PlaybackService` ya existe: exponer un
  `MediaLibraryService` con árbol navegable (Álbumes de Audio →
  pistas; Recientes; Favoritos) para que Android Auto lo muestre.
- Manifest: `automotive_app_desc.xml` + meta-data. Sin UI propia.

## 14b — Widget del reproductor

- Widget de pantalla de inicio (Glance) con carátula, título y
  anterior/play/siguiente; abre la app al tocar. Nueva dep Glance solo si
  se hace; si no, RemoteViews a mano.

## 14c — Letras sincronizadas

- `.lrc` con el mismo nombre base en la misma carpeta → se baja y se
  muestra en el player (línea actual resaltada, scroll automático, tap en
  una línea = seek). Sin `.lrc`: letra embebida (USLT) si viene en el
  caption meta (v2).
- Puras + tests: parser LRC (tiempos `[mm:ss.xx]`, varias marcas por
  línea, offset).
