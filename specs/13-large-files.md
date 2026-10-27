# Spec 13 — Archivos de más de 2 GB

Telegram limita cada archivo a 2 GB (4 GB con Premium).

- Al subir algo mayor que el límite (detectar Premium con `GetMe`):
  partir en trozos `nombre.ext.001`, `.002`… con caption
  `#nimbo/… #part 1/3 <id>`; se suben como un lote.
- En la lista se ven como UN archivo (tamaño total, icono normal).
- Descargar: baja las partes una a una al mismo archivo de Download/Nimbo
  (cada parte sale de la caché de TDLib al copiarla).
- Reproducir: streaming parte a parte (`ConcatTgSource`, `tg://parts?f=…`);
  el seek elige parte con `partAt`. Incompleto no se reproduce.
- Abrir troceados no-vídeo en el visor: pendiente (pide descargar).
- Partes de ~1900 MiB (3900 con Premium), margen bajo el límite.
- Borrar/mover/renombrar actúa sobre todas las partes.
- Puras + tests: plan de troceo, agrupar partes al listar, detectar
  partes faltantes (se marca como incompleto, no se oculta).
