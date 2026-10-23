# Spec 13 — Archivos de más de 2 GB

Telegram limita cada archivo a 2 GB (4 GB con Premium).

- Al subir algo mayor que el límite (detectar Premium con `GetMe`):
  partir en trozos `nombre.ext.001`, `.002`… con caption
  `#nimbo/… #part 1/3 <id>`; se suben como un lote.
- En la lista se ven como UN archivo (tamaño total, icono normal).
- Descargar/abrir: baja todas las partes y las une en local; reproducir
  vídeo grande = streaming parte a parte (DataSource que concatena) en v2.
- Borrar/mover/renombrar actúa sobre todas las partes.
- Puras + tests: plan de troceo, agrupar partes al listar, detectar
  partes faltantes (se marca como incompleto, no se oculta).
