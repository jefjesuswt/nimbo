# Spec 16 — Encolar subidas desde Kitto (segundo plano)

Kitto descomprime un archivo, renombra la carpeta y quiere subirla a Nimbo
sin abrir nada. Compartir (ACTION_SEND) no sirve: un solo archivo, abre la
Activity y Android bloquea abrir Activities desde segundo plano.

## Contrato (no romper: Kitto lo usa tal cual)
- Broadcast explícito a `com.nimbo`, acción `com.nimbo.action.ENQUEUE_UPLOAD`,
  `FLAG_RECEIVER_FOREGROUND`.
- `Intent.EXTRA_STREAM`: `ArrayList<Uri>` `content://` con lectura concedida
  (`grantUriPermission("com.nimbo", …)`). Lo que no sea `content://` se ignora.
- `folder`: ruta en la nube, puede ser anidada (`Título/mp3`). Se normaliza
  (barras de sobra fuera); con un segmento `..` se rechaza todo el broadcast.
  Carpetas = tag `#nimbo/<ruta>` en el caption: no hay que crear las intermedias.
- Un broadcast por carpeta de origen (≤ 200 URIs). Imagen canónica
  (`cover.jpg`…) junto a audio en el mismo broadcast = cover de la carpeta.
- Receptor declarado en el manifest (Kitto comprueba
  `queryBroadcastReceivers` y si no hay, pide actualizar Nimbo).

## Permiso
- Nimbo define `com.nimbo.permission.ENQUEUE_UPLOAD` con `signature`; el
  receptor lo exige. Kitto se firma con la misma clave (`NIMBO_KEYSTORE`).
- Kitto NO define el permiso (solo `uses-permission`): dos apps con firmas
  distintas definiendo el mismo permiso → `INSTALL_FAILED_DUPLICATE_PERMISSION`.
- Kitto instalado antes que Nimbo no recibe el permiso: tras instalar Nimbo,
  reinstalar Kitto (`adb install -r`).

## Comportamiento
- `EnqueueReceiver` (`@AndroidEntryPoint`) valida y llama a
  `TransferManager.enqueueWhenReady(uris, folder, background = true)`.
  Inyecta `AuthRepository` para que TDLib arranque si el proceso nació por
  el broadcast.
- `background`: `UploadService` arranca ya, dentro de la ventana del
  broadcast (Android 12+ no deja iniciar un FGS más tarde desde segundo
  plano). Espera sesión 60 s; sin sesión, se rinde y para el servicio.
- Choques con lo ya subido: se saltan (no hay nadie para el diálogo).
- El servicio se para cuando no queda ningún lote en la lista (antes: ninguno
  "corriendo", y paraba el servicio con lotes en cola esperando turno).

## Tests
- `safeFolder`: barras, espacios en los bordes, null, `..`.
- En celu (Android 14+): broadcast con la app cerrada → notificación de
  subida, carpeta anidada creada, sin sesión → servicio se para.
