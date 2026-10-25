<p align="center"><img src="docs/logo.svg" width="96" alt="Nimbo"></p>

<p align="center"><a href="README.md">English</a> · <b>Español</b></p>

# Nimbo

Tus archivos, música y fotos en tus propios Mensajes guardados de Telegram, con
carpetas de verdad, reproductor de música y visor de fotos. Android, Kotlin +
Material 3.

Sin relación con Telegram.

## Cómo funciona

- Todo vive en **tus** Mensajes guardados. Nimbo solo guarda un índice local para
  cargar rápido y funcionar sin conexión.
- Las carpetas son una etiqueta en el caption de cada archivo
  (`#nimbo/musica/albums`), así que también se entienden en la app oficial de
  Telegram.
- Las carátulas salen de la imagen incrustada en el audio o de un `cover.jpg` /
  `folder.png` (y similares) en la misma carpeta.

## Descarga

Baja el último APK en [Releases](https://github.com/jefjesuswt/nimbo/releases). Los
cambios están en [CHANGELOG.md](CHANGELOG.md) (en inglés).

## Configuración

1. Crea un `api_id` / `api_hash` en <https://my.telegram.org> → API development tools.
2. Abre Nimbo, pégalos (se guardan cifrados solo en el teléfono) e inicia sesión
   con tu número.

Telegram te manda dos códigos distintos: uno (letras y números) para entrar en
my.telegram.org y, después, otro de 5 dígitos para iniciar sesión en Nimbo. Paso a
paso, con los campos del formulario y cómo resolver el típico "ERROR":
[docs/telegram-api-keys.es.md](docs/telegram-api-keys.es.md).

## Límites

Nimbo guarda todo como mensajes en tus Mensajes guardados, así que se aplican los
límites de Telegram:

- **2 GB por archivo** (4 GB con Telegram Premium). Los más grandes fallan al
  subir; Nimbo no los parte.
- **El caption admite 1024 caracteres** (2048 con Premium). Ahí van la ruta de la
  carpeta y las etiquetas de música, así que nombres de carpeta larguísimos o muy
  anidados pueden fallar al guardarse.
- **Límites de velocidad.** Telegram frena a las cuentas que hacen muchas
  peticiones seguidas. Mover o renombrar una carpeta con muchos archivos edita los
  captions de uno en uno con una pausa, así que los lotes grandes tardan.
- **La velocidad de descarga** puede ser menor sin Premium; depende de Telegram,
  no de Nimbo.
- **Las cuentas inactivas se borran.** Telegram borra la cuenta (y sus Mensajes
  guardados) tras un tiempo sin usarla: 6 meses por defecto. Cámbialo en
  Telegram → Ajustes → Privacidad y seguridad → Eliminar mi cuenta → Si me ausento
  durante. Nimbo no es una copia de seguridad: guarda aparte lo que no puedas
  perder.
- **No es cifrado de extremo a extremo.** Mensajes guardados es un chat en la nube:
  va cifrado en tránsito y se guarda en los servidores de Telegram, como cualquier
  otro chat en la nube.
- El espacio total en Mensajes guardados no tiene un límite publicado.

## Compilar

```sh
./gradlew testPublicDebugUnitTest assemblePublicDebug
adb install -r app/build/outputs/apk/public/debug/app-public-debug.apk
```

Dos flavors:

- `public` (por defecto, el de Releases): la traducción se hace **en el teléfono**
  con ML Kit.
- `personal`: además ofrece el endpoint web no oficial de Google Translate (envía
  el texto a Google). No se publica.

```sh
./gradlew assemblePublicRelease     # GitHub Releases
./gradlew assemblePersonalDebug     # tu propio teléfono
```

Necesita JDK 17 y el Android SDK (`ANDROID_HOME`, por defecto `~/Android/Sdk`).

## Licencia

[MIT](LICENSE)
