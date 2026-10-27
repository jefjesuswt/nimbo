# Spec 12 — Cifrado antes de subir (opcional)

Saved Messages NO es cifrado extremo a extremo: Telegram puede leer lo
que se guarda. Opción para que solo el usuario pueda abrir sus archivos.

## Comportamiento

- More → Cifrado: configurar con una contraseña (mín. 8). Aviso claro:
  sin la contraseña no hay forma de recuperar nada.
- Global: "Cifrar subidas nuevas" (se activa al configurar). Lo ya subido
  en claro se queda como está; Nimbo lee los dos tipos.
- Activo pero sin clave en el teléfono → la subida falla; nunca sube en
  claro por accidente.
- Archivos cifrados se suben como `<azar>.nimbo` (documento opaco) con
  `#enc <info>`; la carpeta (`#nimbo/…`) SÍ va en claro para que la app
  oficial siga ordenada.
- En Nimbo se ven normales: nombre real, tipo, tamaño y título de audio
  salen de `#enc`. Vídeo: streaming descifrando por bloques (seek normal).
  Resto (audio, fotos, texto, abrir con…, descargar): se une/descifra a
  una caché propia (`PlainFiles`).
- Otro teléfono / tras bloquear: los cifrados salen "Bloqueado" con nombre
  opaco hasta desbloquear con la contraseña.
- Bloquear en este teléfono: olvida la clave y borra la caché descifrada.
  Cerrar sesión hace lo mismo.

## Técnica (implementado)

- Formato v1 (`data/crypto/EncFormat.kt`): cabecera 18 B (`NIMBOENC`,
  versión, log2 del bloque, prefijo de nonce de 8 B) + bloques de 1 MiB
  AES-256-GCM. Nonce = prefijo + índice del bloque (impide reordenar), AAD
  = cabecera + flag de último bloque (impide truncar).
- Clave maestra aleatoria de 32 B. Se envuelve con PBKDF2-HMAC-SHA256
  (600k iteraciones, sal de 16 B) + AES-GCM y se guarda como texto en Saved
  Messages: `#tgkey 1 <iter> <sal> <envuelta>`. En el teléfono, la maestra
  desenvuelta vive en `SecretStore` (EncryptedSharedPreferences).
- Sin clave por archivo: la maestra + prefijo de nonce aleatorio de 64 bits
  por archivo. Cambiar la contraseña solo re-envuelve la maestra (v2).
- `#enc`: base64url de nonce + GCM("nombre\ntamaño\n#tgmeta").
- Con troceo (spec 13): primero se cifra, luego se parte por bytes
  cifrados (`encryptRange` cifra solo los bloques de cada parte).
- Sin miniaturas ni covers para cifrados (v1): las covers generadas no se
  suben; un `cover.jpg` viaja como imagen cifrada más.
- El índice local guarda los cifrados opacos: los nombres reales solo
  viven en memoria.

## Pendiente

- Cambiar contraseña (re-envolver y editar el `#tgkey`).
- Cifrar lo ya subido (bajar, cifrar, resubir, borrar original tras verificar).
- Miniaturas cifradas.

## Riesgos

- Es la parte con más riesgo de bugs que pierden datos: tests de ida y
  vuelta, partes, clave errónea, manipulación y truncado en
  `EncFormatTest`. Verificar en celu antes de publicar.
