# Spec 12 — Cifrado antes de subir (opcional)

Saved Messages NO es cifrado extremo a extremo: Telegram puede leer lo
que se guarda. Opción para que solo el usuario pueda abrir sus archivos.

## Comportamiento

- More → Cifrado: activar con una contraseña (o clave generada + copia de
  recuperación). Por carpeta o global.
- Archivos cifrados se suben como `nombre.ext.nimbo` (documento opaco);
  caption con `#nimbo/…` y `#enc` (la ruta de carpeta SÍ va en claro,
  para que la app oficial siga ordenada; opcional ocultarla).
- En Nimbo se ven normales: descifrado al vuelo para ver/reproducir.
- Sin la contraseña no hay forma de recuperar: avisarlo claro.

## Técnica

- AES-256-GCM por bloques (p. ej. 1 MB) para poder hacer seek en audio/
  vídeo sin descifrar todo; clave de archivo aleatoria envuelta con la
  clave maestra (derivada con Argon2/scrypt o PBKDF2 alto de la
  contraseña); maestra guardada con Keystore.
- Formato de cabecera versionado (magic + versión + nonce base + clave
  envuelta) para poder migrar.
- Miniaturas: se generan en local y se suben cifradas aparte, o no hay
  thumbs para cifrados (v1).
- Búsqueda: el nombre real va cifrado en la cabecera; el índice local
  guarda el nombre en claro (solo en el móvil).

## Riesgos

- Es la parte con más riesgo de bugs que pierden datos: tests de ida y
  vuelta con archivos grandes, corrupción y contraseña incorrecta antes de
  activarlo para nadie.
