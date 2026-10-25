**English** · [Español](telegram-api-keys.es.md)

# Getting your Telegram API keys and signing in to Nimbo

Nimbo connects to Telegram as its own app, using **your** API keys (`api_id` and
`api_hash`). You create them once, for free, at my.telegram.org.

Telegram sends you **two different codes** along the way. Don't mix them up:

| | What for | Where it arrives | What it looks like |
|---|---|---|---|
| **Code 1** | Log in to my.telegram.org and create the app | The *Telegram* chat (official account, blue check) | Letters and numbers, e.g. `aB3dEfGh1jK` |
| **Code 2** | Sign in to Nimbo | The *Telegram* chat (or SMS if you have no open session) | 5 digits, e.g. `12345` |

You need Telegram open on some device (phone or Telegram Desktop) to receive
both codes.

---

## Part 1: Create the app at my.telegram.org

### 1. Log in with your number

1. Open <https://my.telegram.org> in a browser (a computer is easier, but a
   phone works too).
2. Enter your number **in international format**, with `+` and the country
   code, no spaces: `+34600111222`.
3. Tap **Next**.

### 2. Code 1: the my.telegram.org code

1. Open Telegram. In the **Telegram** chat (official account, blue check) you
   get a *"Web login code"* message with a code made of letters and numbers.
   **It doesn't come by SMS.**
2. Copy it exactly, keeping upper and lower case, into **Confirmation code** and
   tap **Sign In**.

> The message tells you never to share that code, and it's right. It's only for
> you, on my.telegram.org. Nimbo will never ask for it.

### 3. Open "API development tools"

In the my.telegram.org menu, open **API development tools**.

### 4. Fill in *Create new application*

| Field | What to enter | Example |
|---|---|---|
| **App title** | Any name. Don't use the word "Telegram". | `Nimbo` |
| **Short name** | Letters and numbers only, **5 to 32 characters**, no spaces. | `nimbocloud` |
| **URL** | Optional, leave it empty. | |
| **Platform** | Android. | `Android` |
| **Description** | Optional. | `My personal cloud` |

Tap **Create application**.

### 5. Copy `api_id` and `api_hash`

The **App configuration** page shows:

- **App api_id**: a number, e.g. `1234567`.
- **App api_hash**: 32 hexadecimal characters, e.g.
  `0123456789abcdef0123456789abcdef`.

Those two are all you need. The rest of the page (MTProto servers, public keys)
isn't used.

- Each account has **a single app**. If you come back later you'll see the same
  keys; there's no need to create another one.
- Treat `api_hash` like a password: don't publish it or commit it to a
  repository.

### If you get "ERROR" when creating the app

It's a known my.telegram.org issue, and one of these usually fixes it:

- **Turn off your VPN or proxy** (the most common cause).
- Disable ad blockers, or try another browser or a private window.
- Change the **Short name** to lowercase letters only, and make sure the
  **App title** doesn't contain "Telegram".
- Wait a few minutes, log out of my.telegram.org and log in again (you'll get a
  new **Code 1**).

---

## Part 2: Sign in to Nimbo

### 6. Paste the keys

1. Open Nimbo. You'll see **Telegram API keys**.
2. Paste the number into **API ID** and the hash into **API hash**.
3. Tap **Save & connect**.

They're stored encrypted on the phone only (they never leave it and aren't
included in backups).

### 7. Your number

1. Pick your **Country** and type your **Phone number** (without the country
   code; the picker adds it).
2. Tap **Continue**.

### 8. Code 2: the Nimbo code

1. Telegram sends you a **5-digit code**, usually to the **Telegram** chat in the
   app you already have open (by SMS if you have no session anywhere).
2. Type it under **Enter the code**.
   - Didn't arrive? Tap **Resend code**.
   - Typo in the number? Tap **Wrong number?** and fix it.

> This is **not** the my.telegram.org code. If you paste the letters-and-numbers
> one, Nimbo will tell you the code isn't right.

### 9. Two-step verification (only if you have it on)

If your account has an extra password, Nimbo shows **Two-step verification**:
type that Telegram password (it's not either of the codes) and tap **Unlock**.

Done: you'll see the files in your Saved Messages.

---

## Afterwards

- Nimbo shows up in Telegram → **Settings → Devices** as one more session. You
  can end it remotely from there.
- Signing out in Nimbo (**More**) clears the file list, the index and the
  playback positions from the phone. Your files stay in Telegram.
- If you reinstall Nimbo, paste the same keys from step 5 again and sign in with
  a new **Code 2**. No need to go back to my.telegram.org.

## Telegram limits

2 GB per file, caption length, inactive accounts and more: see
[Limits in the README](../README.md#limits).
