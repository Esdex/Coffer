# Files and folders

## Adding files

Tap **+**:

- **Add files**: pick files from this phone
- **Add a folder**: a whole folder, with everything in it and its subfolders
- **New folder**: an empty folder inside the folder on screen

New files go into the folder on screen. From another app, share files to Coffer: **Add to
Coffer** lets you choose the folder.

Each file is encrypted on the phone and sent to Saved Messages. Interrupted uploads resume where
they stopped.

## Working with a file or folder

Tap a file to open it in Coffer's viewers: see [Viewers](viewers.md). The menu on each item has:

- **Rename** and **Move**
- **Save a copy**: a decrypted copy anywhere on the phone, checked against its checksum
- **Save to phone** for a folder: the folder with its subfolders
- **Cache on this phone**: keep it on the phone for opening with no network, see
  [Offline files and the cache](offline.md)
- **Open in another app**, from a viewer's menu: Coffer hands over a decrypted copy and removes it
  about 10 minutes after opening, at the latest when the vault locks or Coffer next starts
- **Delete**

## Several at once

Long-press a file or folder to start a selection, then tap others, or **Select all**. Move, save or
delete them together.

## Deleting

A delete removes the file from Saved Messages on all your devices. For a few seconds you can tap
**Undo**; after that it has gone out to Telegram and is final. Deleting a folder deletes everything
in it, after a confirmation.

## Search and sort

The search looks through the names of every file and folder in the vault. Sort by name, date or
size, ascending or descending.

## Transfers

The Transfers screen, from the menu, lists every upload, download and caching job: failures first,
then what is moving, then what waits and what it waits for (the network, Telegram's request to
wait, or a retry). **Cancel all** stops them all; files already in the vault are not touched.

## File states

- **Incomplete**: some parts are still on their way
- **Damaged**: a part is missing from Saved Messages, deleted outside Coffer or never finished.
  See [Leave Coffer's messages as they are](saved-messages.md).

A name can be up to 255 bytes long.
