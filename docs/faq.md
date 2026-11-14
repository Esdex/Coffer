# FAQ and troubleshooting

## The sign-in code does not come

It can take a minute. Look in every app where you use Telegram, on a phone or a computer: the code
comes as a message from Telegram, in the chat named Telegram with a blue check mark. Each new code
makes the one before it stop working, and starting over too often can get sign-in blocked for a
while. If no code comes, sign in with a QR code instead, scanned with Telegram on another phone or
tablet.

## "Telegram asked to wait"

Telegram limits how fast an account may send requests. When it asks Coffer to wait (`FLOOD_WAIT`),
Coffer waits exactly as long as asked and then goes on by itself. The Transfers screen shows the
countdown.

## Downloads are slow

Without Telegram Premium, Telegram limits download speed. Settings, Account shows whether Premium
is active. Everything else in Coffer works the same without it.

## I forgot the password

Open the vault with the recovery key, then set a new password with it in Settings, Vault, Change
password: see [Your recovery key](recovery-key.md). Without the password and the recovery key, nobody
can open the vault.

## A file says Damaged

A part of it is missing from Saved Messages, usually because it was deleted in the Telegram app.
Coffer cannot restore it. See [Leave Coffer's messages as they are](saved-messages.md).

## A file says Incomplete

Some of its parts are still on their way. Keep Coffer open, or let the transfer finish in the
background.

## The file list looks wrong

Settings, Saved Messages, **Check integrity** compares the list with Saved Messages and fixes what it
can. **Rescan Saved Messages** rebuilds the list from scratch; nothing in Saved Messages changes.

## Can I see my files in the Telegram app?

You see documents named like `cfr_1a2b3c4d5e6f.bin` and short notes starting with `coffer:`. They
are encrypted: only Coffer can open them. Please leave them as they are.

## Can I use Coffer on two phones?

Yes. Sign in to the same Telegram account and unlock with the same password. See
[More than one phone](devices.md).

## How big can a file be?

Large files are sent to Telegram in parts of up to 512 MB each. Saving a decrypted copy needs as
much free space on the phone as the file takes.

## Does Coffer work offline?

Files kept on the phone open with no network: see [Offline files and the cache](offline.md).
Everything else needs a connection to Telegram.

## Something else went wrong

Please open an issue on [GitHub](https://github.com/Esdex/Coffer/issues). Do not include
passwords, recovery words or file names.
