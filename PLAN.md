# PLAN.md — Nimbo

## Fase 0: Separación de Toolbox (ADR 0006)
- [x] Nombre (Nimbo), paquete `com.nimbo`, logo adaptativo + monochrome
- [x] Copiar módulo nube + common + theme + settings; fuera archives/torrents/engine/transfers
- [x] Shell propia: una pantalla (nube) + settings, minibar sin navbar
- [x] Tag `#nimbo/` con lectura de `#toolbox/` (compat con lo ya subido)
- [x] Carátulas: solo embebidas o archivo canónico de la carpeta (fuera heurísticas por nombre/subcarpetas/padres y "Set as cover")
- [x] Auditoría: base TDLib cifrada, `allowBackup=false`, FileProvider acotado, logs con nombres fuera, settings falsos fuera, datos personales fuera de docs/tests
- [x] Build + tests verdes (compila en Windows con scoop: temurin17 + android-clt)
- [ ] Verificar en celu: login nuevo (paquete distinto = sesión nueva), carpetas `#toolbox/` se ven, subir carpeta con cover.jpg y con arte embebido
- [x] Repo GitHub `nimbo` (privado) + push
- [ ] Decidir traductor: endpoint web de Google no oficial (hoy) vs ML Kit on-device vs API con key

## Pendiente heredado (spec 07)
- [ ] 7a verificar en celu (login real + sesión persiste al reabrir)
- [ ] 7b verificar en celu (archivos reales, refresh, offline desde índice)
- [ ] 7c verificar en celu (subir mp3 chico → card % → lista + Telegram oficial)
- [x] Landscape: rotar no pierde estado (configChanges), player en 2 columnas, grid adaptativo
- [x] Búsqueda en topbar (todo el árbol, sin acentos, AND por palabras, en memoria)
- [ ] Verificar landscape + búsqueda en celu
- [x] i18n: en (base) + es neutro, pt-BR, fr, ja; selector de idioma en More (Android 13+) + Ajustes del sistema
- [x] Verificar idiomas en celu (tabs, player, login, plurales)
- [ ] 7h2 Navegar/reproducir dentro de comprimidos (necesita volver a meter Commons Compress)
- [ ] Antes de publicar: `applicationId` definitivo (único en Play), R8 con keep rules de TDLib

## Fase 1: Roadmap (specs 08-14)
- [x] 08 Reproductor de vídeo propio (pantalla completa, gestos, velocidad, subtítulos .srt/.vtt, PiP)
- [x] 08 verificar en celu (mp4 grande, gestos, PiP, .srt) · [ ] streaming progresivo · [ ] pellizco/seek horizontal
- [x] 09a Continuar donde lo dejaste (posición por archivo, chip "empezar de cero")
- [x] 09b Deshacer al borrar (5 s) · [ ] papelera 30 días (v2)
- [x] 09c Deslizar para refrescar
- [x] 09d Ordenar (nombre/fecha/tamaño/duración, asc/desc, por pestaña)
- [ ] 09a barra de progreso en lista/grid para archivos empezados
- [x] Verificar 09a-d en celu
- [ ] 10 Copia automática de la galería (estilo Google Fotos, WorkManager)
- [ ] 11 Disponible sin conexión + pantalla de almacenamiento
- [ ] 14a Android Auto · [ ] 14b widget del player · [ ] 14c letras .lrc
- [ ] 13 Archivos > 2 GB (troceo)
- [ ] 12 Cifrado antes de subir (al final: mayor riesgo de perder datos)
