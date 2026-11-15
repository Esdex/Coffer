# Privacy Policy for Coffer

**Last updated: November 2026**

## Summary

Coffer does not collect, transmit or share any of your data with us. There is no Coffer server.
The app talks only to Telegram, with your own Telegram account, and everything it sends there
is encrypted on your phone first.

## What Coffer Does NOT Do

- Coffer does **not** send anything to us: we run no server and receive no data
- Coffer does **not** collect analytics or usage statistics
- Coffer does **not** use crash reporting or telemetry services
- Coffer does **not** contain advertising or third-party trackers
- Coffer does **not** send your password, recovery key or vault key anywhere
- Coffer does **not** access your contacts, location, camera, microphone or other apps' data

## What Telegram Receives

Coffer is a Telegram client. To work, it connects to Telegram's servers with your account, so
Telegram receives what any Telegram app sends it:

- **Your sign-in**: your phone number and sign-in code, or a QR code scanned from another
  device, and your Telegram password if you use two-step verification
- **Your files, encrypted**: each file is encrypted on your phone and stored in your Saved
  Messages as a document with a random name. Its contents, name, folder and type are
  encrypted, and its size is padded, so Telegram cannot see what it is or, with the default
  settings, its exact size
- **Coffer's notes**: short encrypted text messages in your Saved Messages that hold the
  vault's key (itself encrypted under your password), folders and names
- **What any connection shows**: your IP address, when you are online, and when and how much
  you upload and download

How Telegram handles this is governed by Telegram's own Privacy Policy:
https://telegram.org/privacy

## Data Stored On Your Device

- **Telegram's database** (your session, cached messages): encrypted with a key kept in
  Android's Keystore
- **Coffer's index** of your files and folders: the whole database is encrypted with a key kept in
  Android's Keystore, and names and folders are encrypted once more inside it
- **File cache and previews**: kept encrypted, deleted when you turn them off or clear them
- **Files shared to Coffer** from another app: copied into Coffer at once, encrypted with a key kept
  in Android's Keystore, and deleted once uploaded or cancelled
- **Decrypted copies** you open in another app: removed about 10 minutes after opening, and at the
  latest when the vault locks or Coffer next starts
- **Biometric unlock**: the vault key is wrapped by a Keystore key that needs your fingerprint
  or face. Coffer never sees your biometric data
- **Settings**: stored locally

Coffer's data is excluded from Android backups. Your password is never stored. The vault's key, which
the recovery key spells out, is stored only locked: under your password in Saved Messages, and under
a Keystore key that needs your fingerprint when fingerprint unlock is on.

## Permissions Explained

| Permission | Purpose |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | To talk to Telegram, and to wait for the network when it is down |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | To finish uploads and downloads when the app is in the background |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | To keep playing music in the background |
| `POST_NOTIFICATIONS` | To show transfer progress and the music controls |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | To unlock the vault with your fingerprint or face (optional) |
| `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK` | Used by Android's WorkManager to resume transfers and the weekly integrity check |

## Telegram API Keys

Coffer identifies itself to Telegram with an API key. Builds may include a built-in key, or you
can enter your own from my.telegram.org. Either way, the key only tells Telegram which app is
connecting; it gives us no access to your account or data.

## Open Source

Coffer is open source: https://github.com/Esdex/Coffer
We encourage you to audit the code rather than simply trusting this policy.

## Children's Privacy

Coffer does not knowingly collect any data from anyone, including children, because it does
not collect data from anyone at all.

## Changes to This Policy

If this policy ever changes, we will update this page and note the change with its date.

## Contact

Questions about this Privacy Policy can be raised via GitHub Issues:
https://github.com/Esdex/Coffer/issues
