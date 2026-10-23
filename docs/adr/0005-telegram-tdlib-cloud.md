# ADR 0005 — Telegram cloud real con TDLib (Saved Messages) + Media3

- Estado: aceptado
- Fecha: 2026-10-19
- Sucede a: ADR 0003 (user API, no bot)

## Contexto

Spec 05 mock (login OTP + lista + minibar). Usuario quiere backup fácil
(audio + cualquier archivo) y streaming sin copias locales, estilo Drive,
con atajos desde Archives. ADR 0003 descartó bot (tope 50MB, sin Saved
Messages). Falta motor real.

## Decisión

- TDLib nativo vía `com.github.tdlibx:td` (JitPack, .so prebuilt arm64/x86_64),
  fallback `org.drinkless:tdlib`. Singleton `TgClient`, sesión en
  `filesDir/tdlib`, un proceso.
- `api_id`/`api_hash` los crea usuario en my.telegram.org, se guardan en
  Settings con EncryptedSharedPreferences (androidx.security-crypto).
- Todo vive en Saved Messages (`getMe` → chat propio). Upload `sendMessage`
  + `inputFileLocal`, caption con tag carpeta (`#toolbox/audio`). Lista vía
  `getChatHistory`, mapa message.document/audio → TgFile(messageId, fileId).
- Nube-first: sin copias locales. Streaming audio con Media3 ExoPlayer sobre
  descarga progresiva TDLib (`downloadFile` + `updateFile`) en caché temp
  (`cacheDir/tg-stream`, LRU 200MB, botón Borrar). Descarga explícita = copia
  a Downloads vía SAF.
- Backup shortcuts: menú row Archives "Backup to Telegram" + botón en
  CompressSheet + `ACTION_SEND` sistema → Saved Messages.

## Alternativas descartadas

- Bot API: tope 50MB, chat bot no Saved Messages, sin historial. No cumple 2GB.
- MTProto a mano (Kotlogram): control total pero meses de trabajo, bugs cripto.
- Room/Folders Telegram: Telegram no tiene carpetas; carpetas = canales
  extra (complejidad). Tags en caption + filtro local bastan hasta dolor real.

## Consecuencias

- Deps nuevas: tdlibx + `androidx.media3:media3-exoplayer` + `security-crypto`.
  APK +~25MB (.so). minSdk 26 OK.
- Spec 07 implementa por fases: 7a auth, 7b lista, 7c upload, 7d streaming, 7e grid gestión.
- UI spec 05 queda como contrato, solo cambia fuente datos mock → `TgRepo`.
