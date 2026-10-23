# Spec 10 — Copia automática de la galería (estilo Google Fotos)

> **Descartada.** Implementada y retirada (PR #3 cerrado): arrancar la sesión
> TDLib desde WorkManager y depender de restricciones de carga/red la hizo
> frágil y difícil de probar. Queda como referencia si se retoma.

Nimbo pasa de "subo cuando me acuerdo" a "mi nube de verdad".

## Comportamiento

- More → Copia automática: activar + elegir carpetas del móvil (Cámara,
  Screenshots, WhatsApp Images…) vía SAF (permiso persistente) o
  MediaStore (Android 13+: READ_MEDIA_IMAGES/VIDEO).
- Destino en la nube: `#nimbo/Backup/<nombre carpeta>/` (configurable).
- Condiciones: solo Wi‑Fi (por defecto), solo cargando (opcional), no
  con ahorro de batería.
- Sube lo nuevo desde la última copia; primera vez pregunta: "¿subir
  también las N fotos existentes?".
- Estado en More: última copia, pendientes, errores; notificación
  discreta mientras sube.
- "Liberar espacio" (v2): borrar del móvil lo ya subido > 30 días.

## Técnica

- WorkManager (nueva dep, justificada: reintentos, restricciones de red y
  carga, sobrevive reinicios). Worker periódico 1 h + `ContentObserver`
  sobre MediaStore para disparar al momento con la app abierta.
- Dedupe: registro local `backup_log(uri, size, mtime, sha256?)` en
  SQLite; hash solo si tamaño+fecha chocan.
- Reutiliza `TgRepo.runOne` (staging, caption con carpeta, espera upload).
- TDLib necesita la sesión: el Worker inicia `TgClient` si no está vivo.

## Riesgos

- Telegram puede aplicar flood-wait con miles de archivos: cola lenta con
  pausa entre envíos y respeto a `FLOOD_WAIT_X`.
- Batería: subir por lotes, no uno por uno con wakeups.
