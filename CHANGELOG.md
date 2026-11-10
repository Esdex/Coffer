# Changelog

## [Unreleased]

### Added
- Sign in to Telegram with a QR code or a phone number, including two-step verification and email codes.
- Built-in Telegram API keys, so signing in needs nothing from my.telegram.org; your own keys (api_id and api_hash) remain an option on the sign-in screen.
- A first-start screen explaining what Coffer does and that its files in Saved Messages must not be deleted there.
- Create a vault: a password checked for strength, a 24-word recovery key, and a check that it was written down.
- The vault password is stretched with Argon2id at 128 MiB and 4 passes, about half a second on a recent phone; an existing vault takes the new strength at its next password change.
- Unlock the vault with its password, the recovery key, or a fingerprint or face. The phone's PIN does not open it, and adding a new fingerprint turns fingerprint unlock off until the next password unlock.
- The vault locks after 5 minutes in the background.
- Add files to the vault: they are encrypted on the phone and sent to Saved Messages, with progress in a notification and a Transfers screen (percent, how many MB of how many, and what a transfer is waiting for); interrupted uploads resume, and a paused one says so in a notification.
- Files list with search by name and sorting by name, date or size; files added from other devices show up too.
- The title of the files list says when Telegram cannot be reached: Waiting for network, Connecting, Updating.
- Select several files and folders with a long press to move, save or delete them together; saving puts decrypted copies into a folder you pick.
- Add a whole folder from the phone, and save a vault folder back to the phone, keeping its subfolders.
- A delete can be undone for a few seconds before it goes to Telegram, where it is final.
- Pictures and videos open in Coffer's own viewer, read from Saved Messages a piece at a time and never written out decrypted: swipe through a folder, pinch or double tap to zoom a picture; HEIF, animated GIF and WebP included. A video starts before it is downloaded, seeks anywhere, and has double tap to skip 10 seconds, swipes for volume and brightness, hold for 2x, speed, repeat, a touch lock and full screen; each extra has a switch in Settings, Player.
- PDFs open in Coffer too, read the same way: pages drawn as they scroll into view, pinch or double tap to zoom with the text kept sharp, and a page counter. A password-protected PDF says so and can still be opened in another app.
- Text files open in Coffer too: notes, logs, config files, JSON, XML, code and subtitles up to 256 KB (larger ones open in another app), with line numbers and syntax colours; Markdown opens formatted, with headings, lists, checkboxes, tables and code blocks, and one tap shows its source. Font size, monospace, line wrap, line numbers, visible spaces, colours and formatted Markdown each have a setting in Settings, Text viewer.
- Music and other audio play in Coffer, read in place like video: the tracks of a folder one after another, with the cover, title, artist and album from the tags, a seek bar, previous and next, shuffle and repeat. A call or another app's sound pauses it, and so do headphones coming out. It plays on in the background, with its controls in a notification, on the lock screen and on headset buttons, and goes on after the vault locks until you stop it. Back folds the player into a bar under the files while music plays, and closes it when paused; the notification says only Coffer unless you choose to show the track. Both have a switch in Settings, Player.
- Open a file in another app, or save a decrypted copy anywhere; the copy is checked against its checksum.
- Delete a file from all devices.
- Folders: create, rename, move and delete them (a folder is deleted with everything in it, after a confirmation); rename and move files; new files go into the folder on screen. Changes made on one device show up on the others.
- Index snapshots in Saved Messages: a new device, or one long offline, loads the latest snapshot instead of reading the whole history. The last 3 are kept.
- Settings, Storage: what the vault holds by kind (pictures, videos, music, documents, other) on a ring, each kind opening its files largest first, with a tap taking you to the file's folder; and what Coffer keeps in Saved Messages: parts of files, changes, index snapshots, the vault header, and how many messages are your own.
- Settings, On this phone: what Coffer takes up on the phone apart from the vault: the file cache, Telegram's database, Coffer's index and temporary files.
- A file cache: files opened, played or saved keep their encrypted pieces on the phone, so they open again without a download and without traffic. Up to 2 GB by default (500 MB to 10 GB), the files read longest ago making room first, never one that is open. It can be turned off, cleared, or cleared each time the vault locks, in Settings, On this phone.
- Rescan Saved Messages from the menu, to rebuild the list of files and folders from scratch.
- Integrity check, weekly and from the menu: finds files with missing parts, brings back files the list missed, removes leftovers of unfinished uploads and deletions, and clears out old operations that are no longer needed.
- Settings: auto-lock after 1 to 60 minutes or off, unlock with fingerprint on or off, block screenshots, weekly integrity check on or off. Integrity check, rescan and log out moved here; log out asks first.
- Decrypted copies opened in another app are removed from the phone 10 minutes after opening.
- Change the vault password, and show the recovery key again; both ask for the current password first. A password change locks the vault on your other devices, and only the new password opens it there.
- Share to Coffer from any app: one file or several, into a folder you pick; Coffer returns to the app you shared from.
- A new look, shared with Arcanum: grouped settings, large collapsing titles, file type icons, a path bar, search in the top bar, one + button for files and folders, sliding transitions, animated progress and results, and a paged welcome.
- Appearance settings: theme (system, light or dark), pure black for AMOLED screens, and wallpaper colours.
