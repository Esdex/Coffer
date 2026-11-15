# More than one phone

The vault lives in Saved Messages, so every device signed in to the same Telegram account can open
it.

## Opening the vault on another phone

Install Coffer, sign in to the same Telegram account, and unlock with the vault password or the
recovery key. Coffer loads the latest index snapshot and then the changes after it, so even a
large vault opens quickly.

## Staying in step

Adding, renaming, moving and deleting on one phone shows up on the others. A device that was
offline for a long time catches up when it next connects.

## Per device

Some things belong to one phone only:

- fingerprint unlock, which is tied to that phone's secure hardware
- auto-lock, the file cache, previews and the other settings

## Password changes

Changing the password on one phone locks the vault on the others. There, once they have heard of
the change, only the new password opens it, and fingerprint unlock is turned off until the new
password is typed once. The recovery key stays the same.

## Deleting the vault

If the vault is deleted on one phone, the others say so and offer to create a new one. See
[Deleting the vault and logging out](delete-vault.md).
