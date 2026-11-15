# How it works

Coffer turns the Saved Messages of your Telegram account into encrypted storage. There is no
Coffer server: the app talks straight to Telegram with your account.

## What Coffer keeps in Saved Messages

- **Parts of files.** Each file is encrypted on your phone and sent as one or more documents with
  random names like `cfr_1a2b3c4d5e6f.bin`. A large file is cut into parts of up to 512 MB.
- **The vault header.** A pinned text note starting with `coffer:` that holds the vault's keys,
  locked with your password. The recovery key opens them too.
- **Changes.** Short encrypted notes for every rename, move and delete. They are how your other
  devices hear of them.
- **Index snapshots.** The whole list of files and folders in one note, so a new device need not
  read every change. The last 3 are kept.

Everything else in Saved Messages is your own. Coffer does not read it or touch it.

Settings, Storage shows how many of each kind there are.

## What Telegram can see

Telegram stores the documents and notes, but cannot read them. It does not see:

- what is in your files
- their names, folders or types
- their exact sizes, with **Hide exact file sizes** on, as it is by default: contents are padded
  inside the encryption, so a file cannot be recognised by its size, and the notes are padded to one
  length per kind, so they do not show how long a name is. A file of a few bytes is padded by a few
  bytes at most, and files sent with the setting off keep their size

Telegram does see what any Telegram app shows it: that your account has these documents and
notes, their approximate sizes, when they were sent, how many there are, and the IP address you
connect from.

## On your phone

Coffer keeps an index of your files so the vault opens without reading Saved Messages each time.
The whole index is an encrypted database, and names and folders are encrypted again inside it. Telegram's own database on the phone is encrypted with a
key kept in Android's Keystore. Opened files can be kept in an encrypted cache: see
[Offline files and the cache](offline.md).

## Playing fair with Telegram

Coffer sends files as documents only, never as photos or videos, so Telegram does not recompress
them. It runs at most 3 transfers at a time, and when Telegram asks it to wait (`FLOOD_WAIT`), it
waits as long as asked.

See [Security](security.md) for the encryption itself.
