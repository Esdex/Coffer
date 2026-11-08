# Changelog

## [Unreleased]

### Added
- Sign in to Telegram with a QR code or a phone number, including two-step verification and email codes.
- Public build: enter your own Telegram API keys (api_id and api_hash) on first start.
- A first-start screen explaining what Coffer does and that its files in Saved Messages must not be deleted there.
- Create a vault: a password checked for strength, a 24-word recovery key, and a check that it was written down.
- Unlock the vault with its password, the recovery key, or fingerprint and screen lock.
- The vault locks after 5 minutes in the background.
- Add files to the vault: they are encrypted on the phone and sent to Saved Messages, with progress in a notification and a Transfers screen; interrupted uploads resume.
- Files list with search by name and sorting by name, date or size; files added from other devices show up too.
- Open a file in another app, or save a decrypted copy anywhere; the copy is checked against its checksum.
- Delete a file from all devices.
- Folders: create, rename, move and delete them (a folder is deleted with everything in it, after a confirmation); rename and move files; new files go into the folder on screen. Changes made on one device show up on the others.
- Index snapshots in Saved Messages: a new device, or one long offline, loads the latest snapshot instead of reading the whole history. The last 3 are kept.
- Rescan Saved Messages from the menu, to rebuild the list of files and folders from scratch.
