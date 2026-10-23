# PLAN.md — Nimbo

## Fase 0: Separación de Toolbox (ADR 0006)
- [x] Nombre (Nimbo), paquete `com.nimbo`, logo adaptativo + monochrome
- [x] Copiar módulo nube + common + theme + settings; fuera archives/torrents/engine/transfers
- [x] Shell propia: una pantalla (nube) + settings, minibar sin navbar
- [x] Tag `#nimbo/` con lectura de `#toolbox/` (compat con lo ya subido)
- [x] Carátulas: solo embebidas o archivo canónico de la carpeta (fuera heurísticas por nombre/subcarpetas/padres y "Set as cover")
- [x] Auditoría: base TDLib cifrada, `allowBackup=false`, FileProvider acotado, logs con nombres fuera, settings falsos fuera, datos personales fuera de docs/tests
- [ ] Build + tests verdes en el Arch (`./gradlew testDebugUnitTest assembleDebug`)
- [ ] Verificar en celu: login nuevo (paquete distinto = sesión nueva), carpetas `#toolbox/` se ven, subir carpeta con cover.jpg y con arte embebido
- [ ] Repo GitHub `nimbo` (privado) + push
- [ ] Decidir traductor: endpoint web de Google no oficial (hoy) vs ML Kit on-device vs API con key

## Pendiente heredado (spec 07)
- [ ] 7a verificar en celu (login real + sesión persiste al reabrir)
- [ ] 7b verificar en celu (archivos reales, refresh, offline desde índice)
- [ ] 7c verificar en celu (subir mp3 chico → card % → lista + Telegram oficial)
- [x] Landscape: rotar no pierde estado (configChanges), player en 2 columnas, grid adaptativo
- [x] Búsqueda en topbar (todo el árbol, sin acentos, AND por palabras, en memoria)
- [ ] Verificar landscape + búsqueda en celu
- [ ] 7h2 Navegar/reproducir dentro de comprimidos (necesita volver a meter Commons Compress)
- [ ] Antes de publicar: `applicationId` definitivo (único en Play), R8 con keep rules de TDLib
