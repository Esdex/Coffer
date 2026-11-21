# Files in other apps

There are three ways to work on a vault file in another app. Which one to use depends on whether the
app only reads the file or also saves it back, and on whether it should keep coming back to it.

| | Open in another app | Edit in another app | The system file picker |
|---|---|---|---|
| Start from | Coffer | Coffer | the other app |
| The app can save | no | yes | yes |
| The app's link works | about 10 minutes | an hour after the last save | for good, while Coffer is unlocked |
| Good for | a quick look | a document in an editor | a password database, files you return to |

## Open in another app

From a viewer's menu. Coffer hands the app a decrypted copy to read and removes it about 10 minutes
after opening, at the latest when the vault locks or Coffer next starts. The app cannot save into
the vault, and if it offers to save, the changes go nowhere.

## Edit in another app

From a file's menu. Pick an editor, such as WPS or OnlyOffice: it changes a decrypted copy, and
each save goes back into the vault in place of the file, on all your devices. While you edit, and
for an hour after the last save, the copy stays decrypted on the phone, also while Coffer is locked;
a save made while locked goes in at the next unlock. Some apps, Microsoft Word and Excel among them,
may open the file only to read.

Settings, Security, **Edit in another app** turns this off. It is on by default.

## The system file picker

Android's file picker is the screen other apps open when they ask you to choose a file or a place to
save one. Coffer can appear in it beside the phone's storage, so an app such as KeePassDX can open a
file straight from the vault and keep saving to it.

Turn it on in Settings, Security, **Show Coffer in the system file picker**. It is off by default.

### Opening a file

In the other app, choose to open a file. In the picker, open its menu (the three lines at the top
left), tap **Coffer** and go to the file. The app gets that file and nothing else in the vault.

A password manager is the usual case. In KeePassDX: **Open existing database**, then Coffer, then
your database. KeePassDX remembers the file, opens it from Coffer from then on, and every change you
make in it is saved back into the vault.

### Saving a new file

When an app saves a new file, choose Coffer in the picker, go to a folder and save. If the name is
taken, Coffer adds a number to it rather than replace the file there. New folders are made in Coffer
itself, not in the picker.

### While Coffer is locked

Coffer stays in the picker, empty, with a note that it is locked. An app that opens a remembered file
gets an error until you open Coffer and unlock it; then it works again. Nothing decrypted is kept
around for this.

### What happens to the file

- **A file opened to read** is decrypted as it is read. Nothing is written to the phone.
- **A file opened to be changed** is decrypted whole into a copy on the phone. Each save goes into the
  vault in place of the old file, and the app's link keeps working after the save. The copy stays,
  also while Coffer is locked, until what was saved is sent and for an hour after.
- **Turning the setting off** takes Coffer out of the picker, and the apps that remembered vault files
  can no longer open them.

### What Coffer cannot control

The picker belongs to Android, not to Coffer. It cannot be kept off screenshots, even with
**Block screenshots** on, and it remembers the names of the folders you went through, which Coffer
cannot clear. See [Security](security.md) and the [privacy policy](privacy.md).

## I opened a file from Coffer, and the app cannot save it

Tapping a file in Coffer and choosing another app is **Open in another app**: a copy to read, which
goes nowhere when saved. To save back, use **Edit in another app** from the file's menu, or, for an
app that keeps coming back to the file (KeePassDX and other password managers), open the file from
the app itself through the system file picker.
