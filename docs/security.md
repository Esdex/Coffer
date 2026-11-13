# Security

## The encryption

- **Cipher**: XChaCha20-Poly1305, from libsodium. Every file, file name, folder and change is
  encrypted and authenticated, so a changed byte is noticed, not read as something else.
- **Password**: stretched with Argon2id at 128 MiB and 4 passes, about half a second on a recent
  phone, so guessing it is slow.
- **Keys**: the vault's master key is random. The vault header keeps it locked with your password.
  Changing the password locks it anew; the files are not re-encrypted.
- **Recovery key**: the master key itself, written as 24 BIP39 words.
- All cryptography sits in one small module of the source code, `:core:crypto`, built on libsodium.

## Hidden sizes

New files are padded inside the encryption, so a file cannot be recognised by its exact size.
Changes and names are padded to one length per kind, so they do not show how long a name is.
Settings, Security, **Hide exact file sizes** turns the file padding off.

## On the phone

- Fingerprint unlock wraps the master key with a key in the phone's secure hardware that needs your
  fingerprint or face. The phone's PIN does not open the vault.
- Locking wipes the keys from memory.
- Coffer's index keeps names and folders encrypted. Telegram's database is encrypted with a key in
  Android's Keystore. The file cache and previews are encrypted.
- Coffer's data is left out of Android backups.
- Nothing secret goes into logs: no keys, passwords, recovery words, file names or decrypted
  metadata.

## What Coffer protects against

- **Telegram, and anyone who can read your Saved Messages**: they see encrypted documents and notes
  of padded sizes. They cannot read your files, names or folders, or tell exact sizes.
- **Someone who finds your phone locked**: the vault needs the password, the recovery key or your
  fingerprint.

## What it does not protect against

- **Someone in your Telegram account**: they cannot read the vault, but they can delete its messages,
  and they can try to guess your password. A strong password is what stops them: a few unrelated
  words work well. Turn on Telegram's two-step verification too.
- **A phone that is compromised while the vault is open**: malware with full control of the phone
  can see what you see.
- **What any connection shows**: Telegram sees when you upload and download, roughly how much, and
  from which IP address.
- **Losing your Telegram account**: the vault lives in it. Telegram deletes accounts that stay
  inactive for the period set in its privacy settings, and the vault would go with it. Coffer is not
  a backup of Telegram: keep copies of files you cannot lose somewhere else as well.
- **Losing both the password and the recovery key**: nobody can open the vault then.

## Reporting a vulnerability

Please use [GitHub Security Advisories](https://github.com/Esdex/Coffer/security/advisories/new)
to report security issues privately. Do not open a public issue for vulnerabilities.
