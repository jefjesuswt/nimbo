# ADR 0003 — Telegram vía user API (MTProto), no bot

- Estado: aceptado
- Fecha: 2026-10-17
- Contexto: se evaluó bot token (@BotFather) como alternativa simple.

## Decisión

Login con teléfono + OTP como en prototipo (`s-tg`). Motor real futuro: TDLib/MTProto con sesión persistida. Archivos = Saved Messages, streaming directo, límite 2GB.

## Alternativa descartada

Bot token: sin OTP y con `getMe` trivial de validar, pero archivos viven en chat del bot (no Saved Messages), tope 50MB vía Bot API cloud, y sin API de historial (lista local obligatoria). No cumple "tu nube de Telegram".

## Consecuencias

- UI actual (login → OTP → archivos mock + minibar) queda como contrato.
- Engine TDLib: spec nueva cuando toque (JNI, sesión, `messages.getHistory`, upload/download 2GB).
- PR #3 no cambia.
