# ADR 0001 — Single-Activity Compose M3, sin engine propio

- Estado: aceptado
- Fecha: 2026-10-17
- Contexto: Toolbox gestiona archivos/torrents/Telegram. Prototipo en `index.html` (7 screens). Sin motor real de compresión ni red.

## Decisión

Single-Activity + Compose Navigation. UI 100% Material 3 estándar (tabla `#compose` de `design-system.html`). Sin wrappers propios. Dynamic color sistema. Datos mock en memoria hasta tener engine.

## Alternativas

1. **Multi-Activity + Views** — descartado: doble toolkit, motion M3 Expressive (wavy, shared axis) más caro, sin beneficio.
2. **Flutter/RN** — descartado: usuario pide Kotlin rendimiento máximo, M3 nativo.
3. **Clean-architecture completa desde día 1 (use cases + repos + DI)** — descartado por ahora: sin engine real, abstracción sin implementación = deuda. Se introduce cuando exista motor (Satisfy `s-progress`, `s-detail` con datos reales).

## Consecuencias

- `MainActivity` + `NavHost`. En Nimbo: 2 destinos (cloud/settings), ver ADR 0006.
- `ponytail:` marca atajos con techo conocido.
- Reversión: si engine exige procesos separados (foreground service), ADR nueva, no parche.
