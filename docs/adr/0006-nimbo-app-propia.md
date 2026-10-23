# ADR 0006 — La nube sale de Toolbox: Nimbo

- Estado: aceptado
- Fecha: 2026-10-23
- Sucede a: ADR 0005 (en lo que toca a dónde vive)

## Contexto

La nube sobre Saved Messages empezó como pestaña de Toolbox (spec 07) y
creció a app completa: carpetas virtuales, player con cola, visor de fotos,
lector con traducción, subida de carpetas en segundo plano. Toolbox es un
cajón de utilidades de un solo uso; la nube es de uso diario.

## Decisión

- App propia **Nimbo**, paquete `com.nimbo`, repo nuevo sin historial
  (el historial de Toolbox guarda heurísticas personales que no viajan).
- Una pantalla (nube) + ajustes. Sin NavigationBar.
- Fuera: archives, torrents, motor de compresión, `TransferCenter`
  (en Nimbo nadie lo leía), ajustes que no hacían nada.
- Tag de carpeta `#nimbo/`; `#toolbox/` se sigue leyendo para no perder lo
  ya subido.
- Carátulas: solo arte embebido o archivo canónico en la misma carpeta.
- Toolbox pierde la pestaña Telegram y sus dependencias (TDLib, Media3,
  security-crypto).

## Consecuencias

- Paquete distinto = sesión TDLib nueva: hay que volver a loguear.
- La sesión vive en base cifrada y no entra en backups.
- 7h2 (comprimidos) necesita volver a añadir Commons Compress cuando toque.
