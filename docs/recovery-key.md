# Your recovery key

When you create a vault, Coffer shows a recovery key: 24 words from the standard BIP39 English
word list. It opens the vault if you forget the password.

## Keep it safe

- Write the words on paper, in order. Coffer blocks screenshots while they are on screen.
- Keep the paper somewhere safe, away from the phone.
- **Anyone with these words can open your vault.** Treat them like the password itself.

## If you forget the password

On the unlock screen, tap **Use the recovery key instead** and type the 24 words, separated by
spaces. Coffer checks each word against the list and tells you which ones it does not know.

Then set a new password in Settings, Vault, **Change password**.

## If the vault itself was deleted

Coffer keeps the vault's key in one pinned note in Saved Messages. If that note was deleted, for
example by mistake in the Telegram app, Coffer finds no vault, even though your files are still there.
On the screen that offers a new vault, tap **Restore a vault with its recovery key**, type the 24 words
and choose a new password. The vault comes back with all its files, and the recovery key stays the
same.

## See the words again

Settings, Vault, **Show recovery key**. Coffer asks for the vault password first. Make sure nobody
is looking at the screen.

## Changing the password

Settings, Vault, **Change password**. The new password works on all your devices; the recovery key
and your files stay the same. Your other devices lock, open again only with the new password, and
turn fingerprint unlock off until it is typed there once.

A password change does not change the key your files are encrypted with. Someone who knew the old
password and kept a copy of the vault from your Telegram account could still open it with the old
one. If you think that happened, create a new vault and move your files into it.

## If both are lost

There is no Coffer server and no reset. Without the password and the recovery key, nobody can open
the vault: not Telegram, not the developers of Coffer. The files stay in Saved Messages, encrypted
and unreadable.
