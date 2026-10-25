# Cómo conseguir tus claves de Telegram y entrar en Nimbo

Nimbo se conecta a Telegram como una app propia, con **tus** claves de API
(`api_id` y `api_hash`). Se crean una sola vez, gratis, en my.telegram.org.

El proceso pide **dos códigos distintos** de Telegram, y conviene no
confundirlos:

| | Para qué | Dónde llega | Cómo es |
|---|---|---|---|
| **Código 1** | Entrar en my.telegram.org y crear la app | Chat de *Telegram* (cuenta oficial con tilde azul) | Letras y números, p. ej. `aB3dEfGh1jK` |
| **Código 2** | Iniciar sesión en Nimbo | Chat de *Telegram* (o SMS si no hay sesión abierta) | 5 dígitos, p. ej. `12345` |

Necesitas Telegram abierto en algún dispositivo (el móvil o Telegram Desktop)
para recibir los dos códigos.

---

## Parte 1 — Crear la app en my.telegram.org

### 1. Entrar con tu número

1. Abre <https://my.telegram.org> en el navegador (mejor en un ordenador; en el
   móvil también funciona).
2. Escribe tu número **en formato internacional**, con `+` y código de país,
   sin espacios: `+34600111222`.
3. Pulsa **Next**.

### 2. Código 1: el de my.telegram.org

1. Abre Telegram. En el chat **Telegram** (cuenta oficial, con tilde azul) te
   llega un mensaje *"Web login code"* con un código de letras y números.
   **No llega por SMS.**
2. Cópialo tal cual, respetando mayúsculas y minúsculas, en el campo
   **Confirmation code** y pulsa **Sign In**.

> El mensaje dice que nunca compartas ese código: es verdad. Es solo para ti, en
> my.telegram.org. Nimbo nunca te lo va a pedir.

### 3. Abrir "API development tools"

En el menú de my.telegram.org, entra en **API development tools**.

### 4. Rellenar el formulario *Create new application*

| Campo | Qué poner | Ejemplo |
|---|---|---|
| **App title** | Cualquier nombre. No uses la palabra "Telegram". | `Nimbo` |
| **Short name** | Solo letras y números, **5 a 32 caracteres**, sin espacios. | `nimbocloud` |
| **URL** | Opcional, déjalo vacío. | |
| **Platform** | Android. | `Android` |
| **Description** | Opcional. | `Mi nube personal` |

Pulsa **Create application**.

### 5. Copiar `api_id` y `api_hash`

En la página **App configuration** aparecen:

- **App api_id**: un número, p. ej. `1234567`.
- **App api_hash**: 32 caracteres hexadecimales, p. ej.
  `0123456789abcdef0123456789abcdef`.

Son los dos únicos datos que necesitas. El resto de la página (servidores MTProto,
claves públicas) no hace falta.

- Cada cuenta tiene **una sola app**. Si vuelves a entrar más tarde, verás las
  mismas claves; no hace falta crear otra.
- Trata el `api_hash` como una contraseña: no lo publiques ni lo subas a ningún
  repositorio.

### Si sale "ERROR" al crear la app

Es un fallo conocido de my.telegram.org y casi siempre se arregla con alguna de
estas cosas:

- **Apaga la VPN o el proxy** (es la causa más común).
- Desactiva bloqueadores de anuncios, o prueba en otro navegador o en una
  ventana privada.
- Cambia el **Short name** por otro solo de letras minúsculas, y el
  **App title** por uno que no contenga "Telegram".
- Espera unos minutos, cierra sesión en my.telegram.org y vuelve a entrar (te
  pedirá un **Código 1** nuevo).

---

## Parte 2 — Entrar en Nimbo

### 6. Pegar las claves

1. Abre Nimbo. Aparece **Claves de API de Telegram**.
2. Pega el número en **API ID** y el hash en **API hash**.
3. Pulsa **Guardar y conectar**.

Se guardan cifradas solo en el teléfono (no salen de él ni entran en copias de
seguridad).

### 7. Tu número

1. Elige el **País** y escribe tu **Número de teléfono** (sin el código de país,
   que ya lo pone el selector).
2. Pulsa **Continuar**.

### 8. Código 2: el de Nimbo

1. Telegram te manda un **código de 5 dígitos**, normalmente al chat
   **Telegram** de la app que ya tengas abierta (si no tienes ninguna sesión,
   por SMS).
2. Escríbelo en **Escribe el código**.
   - ¿No llega? Pulsa **Reenviar código**.
   - ¿Número mal escrito? Pulsa **¿Número equivocado?** y corrígelo.

> Este **no** es el mismo código que el de my.telegram.org. Si pegas el de
> letras y números, Nimbo te dirá que el código no es correcto.

### 9. Verificación en dos pasos (solo si la tienes activada)

Si tu cuenta tiene contraseña adicional, Nimbo muestra **Verificación en dos
pasos**: escribe esa contraseña de Telegram (no es ninguno de los códigos) y
pulsa **Desbloquear**.

Listo: verás tus archivos de Mensajes guardados.

---

## Después

- Nimbo aparece en Telegram → **Ajustes → Dispositivos** como una sesión más.
  Desde ahí puedes cerrarla en remoto.
- Cerrar sesión en Nimbo (**Más**) borra del teléfono la lista de archivos, el
  índice y las posiciones de reproducción. Tus archivos siguen en Telegram.
- Si reinstalas Nimbo tendrás que volver a pegar las claves (las mismas del paso
  5) e iniciar sesión con un **Código 2** nuevo. No hace falta volver a
  my.telegram.org.

## Límites de Telegram

Todo vive como mensajes en tus Mensajes guardados, así que mandan los límites de
Telegram:

- **2 GB por archivo** (4 GB con Telegram Premium). Los más grandes fallan al
  subir; Nimbo no los parte.
- **El pie de foto (caption) admite 1024 caracteres** (2048 con Premium). Ahí van
  la ruta de la carpeta y las etiquetas de música, así que nombres de carpeta
  larguísimos o muy anidados pueden fallar al guardarse.
- **Límites de velocidad.** Telegram frena a las cuentas que hacen muchas
  peticiones seguidas. Mover o renombrar una carpeta con muchos archivos edita los
  captions de uno en uno con una pausa, así que los lotes grandes tardan.
- **Velocidad de descarga**: sin Premium puede ser menor; depende de Telegram.
- **Las cuentas inactivas se borran.** Telegram borra la cuenta (y sus Mensajes
  guardados) tras un tiempo sin usarla: 6 meses por defecto. Cámbialo en
  Telegram → Ajustes → Privacidad y seguridad → Eliminar mi cuenta → Si me ausento
  durante. Nimbo no es una copia de seguridad: guarda aparte lo que no puedas
  perder.
- **No es cifrado de extremo a extremo.** Mensajes guardados es un chat en la nube:
  va cifrado en tránsito y se guarda en los servidores de Telegram.
- El espacio total en Mensajes guardados no tiene un límite publicado.
