# Leave Coffer's messages as they are

Your vault lives in Saved Messages, next to your own messages. In the Telegram app you can see
Coffer's messages too, and you can delete or edit them like any other. Please do not.

## Coffer's notes: `coffer:`

Short text messages starting with `coffer:`, one of them pinned. They hold your vault's key,
folders and names.

**Editing or deleting them damages the vault.** The pinned one is the vault header: without it,
neither the password nor the recovery key can open the vault.

## The files: `cfr_*.bin`

Documents named like `cfr_1a2b3c4d5e6f.bin` are your files, encrypted, sometimes cut into parts.

**Deleting one in the Telegram app deletes the file it belongs to, for good.** Coffer marks such a
file as Damaged: a part is missing from Saved Messages.

## Delete and move files only in Coffer

Coffer keeps the vault whole when you work through it: a delete removes every part and tells your
other devices; a move or rename is recorded so every device agrees.

## If something was deleted anyway

- In Coffer, open Settings, Saved Messages, **Check integrity**. It compares the file list with
  Saved Messages, marks files with missing parts and cleans up leftovers.
- A file marked Damaged cannot be restored by Coffer. Delete it in Coffer and add it again if you
  still have it.
- If the pinned note itself was deleted, a phone where the vault is set up puts it back by itself the
  next time Coffer runs there. With no such phone, Coffer finds no vault, and your recovery key brings
  it back: see [Your recovery key](recovery-key.md).
