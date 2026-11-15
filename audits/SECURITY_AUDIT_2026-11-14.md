# Coffer Security Audit

**Date:** 14 November 2026
**Auditor:** Claude Opus 5.5, four independent reviews
**Type:** First full audit, before the first public release
**Scope:** the whole app at version 0.1.0, before any fix below

This review was AI-assisted: four separate AI auditors read the code, each in one area, without seeing
each other's work. Every finding they reported was then checked against the code by hand before it was
fixed. Nothing was taken on trust.

The auditors' detailed reports were lost with a temporary folder before they were published. This
report is put together from their summaries, which survived, and from the fix commits, each of which
names the finding it fixes.

---

## Goal

Can Coffer keep what it promises? File contents, names and folders, exact sizes, the vault key and the
recovery key must stay out of reach of Telegram, of anyone else in the Telegram account, of someone who
holds the phone while it is locked, and of other apps on the phone. Nobody but the user may replace,
roll back, resurrect or delete the vault's files.

---

## Threat Model

- **A. Telegram, or anyone with access to the Telegram account** (a stolen session, a server-side
  attacker): reads everything in Saved Messages; can delete, edit, reorder, pin, forward and post
  messages, including crafted documents and copies of old ones. Cannot break XChaCha20-Poly1305,
  Argon2id or BLAKE2b.
- **B. Someone with the phone, locked**: can read app-private files from a backup, a device transfer
  or a forensic image, and has the APK. A rooted or compromised phone while the vault is open is out
  of scope.
- **C. Another app on the same phone**, without special rights: sends intents, reads what Coffer hands
  out (shared files, clipboard, notifications, media session), sees the recent apps screen.
- **D. The network**: Telegram's MTProto is trusted as the transport; only what Coffer adds is in scope.

Out of scope: TDLib's and libsodium's own code (their use by Coffer is in scope), and weak passwords
chosen despite the strength meter (the meter's enforcement is in scope).

Severity: **Critical** breaks the vault's confidentiality or integrity; **High** is a realistic attack
that leaks protected data or forges, rolls back or destroys vault data; **Medium** needs unusual
conditions or leaks what the design means to hide; **Low** is hardening; **Info** carries no direct risk.

---

## Method

Four areas, one auditor each, all reading the code only (no builds, no devices, no network):

1. Cryptography and the vault format: key derivation and wrapping, nonces, associated data, padding,
   BIP39, local sealing, zeroing of keys
2. Sync and parsing: everything read from Saved Messages, treated as written by attacker A
3. The Android surface: exported components, sharing, files handed to other apps, notifications, the
   media session, screenshots, biometrics, backups, task hijacking
4. Data at rest and leaks: the TDLib database key, the index, caches, previews, temporary copies, logs,
   build settings, dependencies

Duplicates across areas were merged. Each fix is one commit. Where the code can be tested off a
device, the fix comes with a test that fails without it, checked by breaking the code on purpose
(a mutant) and seeing the test catch it. What only a phone can show was checked on a device by the
developer.

---

## Summary of Findings

No Critical or High findings. The auditors found the cryptography itself sound: nonces, associated
data, key separation, the size padding, strict decoding, backup exclusions and logs. The findings are
about how the app behaves around it.

| ID | Description | Severity | Status |
|----|-------------|----------|--------|
| M1 | Deleting the only vault header made the vault unopenable, even with the recovery key | Medium | FIXED, unit-tested (see 2026-11-15, N1) |
| M2 | A forged header of "another vault" blocked every unlock and could make a phone wipe its own state | Medium | FIXED, unit-tested |
| M3 | Forged headers asking for the most Argon2 work slowed every unlock and could crash it | Medium | FIXED, unit-tested (see 2026-11-15, L-a) |
| M4 | A forwarded copy of an old operation was applied again, undoing renames and moves | Medium | PARTLY FIXED by design, unit-tested (see 2026-11-15, N2) |
| M5 | An old snapshot sent again could roll a new phone back | Medium | FIXED, unit-tested |
| M6 | The integrity check and compaction trusted a history TDLib may cut short | Medium | FIXED, unit-tested (see 2026-11-15, L-d) |
| M7 | A password change does not revoke the old password; fingerprint unlock outlived a change made elsewhere | Medium | FIXED where possible, unit-tested; residual documented |
| M8 | The password floor (about 10^8 guesses) was too low for offline guessing, and enforced only on screen | Medium | FIXED, unit-tested |
| M9 | The index on the phone held exact file sizes and name lengths in the clear | Medium | FIXED, migration tested on a device |
| M10 | Sharing into Coffer: no size limit, `file://` and Coffer's own files accepted, copies outlived sign-out | Medium | FIXED, unit-tested |
| M11 | Decrypted copies for other apps could be written after a lock and outlive a killed process | Medium | FIXED, device-verified (see 2026-11-15, L-m) |
| M12 | Lock screen and headset controls could play the rest of the vault's music after it locked | Medium | FIXED, device-verified |
| M13 | An app with notification access could read the track's tags through Media3 | Medium | FIXED, unit-tested (see 2026-11-15, N3) |
| L1 | The recovery words showed again after a lock without the password | Low | FIXED |
| L2 | The three-word check at vault creation was not kept off screenshots | Low | FIXED (see 2026-11-15, L-i) |
| L3 | A file name was used unchecked as a path when copied for another app | Low | FIXED, unit-tested |
| L4 | A `file://` link in a Markdown file crashed the app | Low | FIXED, unit-tested |
| L5 | No `taskAffinity=""`, leaving room for task hijacking | Low | FIXED |
| L6 | The keyboard could learn names, searches and a shown password | Low | FIXED, device-verified |
| L7 | Deleting the vault did not empty the file cache | Low | FIXED |
| L8 | A Keystore error for a moment wiped Telegram's data and the index | Low | FIXED, unit-tested (see 2026-11-15, L-l) |
| L9 | The vault headers were kept on the phone in the clear | Low | FIXED, unit-tested |
| L10 | Locking zeroed keys while sync could still be using them | Low | FIXED, unit-tested |
| L11 | Showing the recovery key left an unwiped copy of the master key | Low | FIXED, existing tests |
| L12 | Snapshot documents were downloaded whole before their size was checked | Low | FIXED, unit-tested (see 2026-11-15, L-c) |
| L13 | A copy of a lost part left its file damaged until a second check | Low | FIXED, unit-tested |
| L14 | The file cache kept a record of what was opened when, in the clear | Low | FIXED |
| L15 | A password change whose pin failed left a stray header behind | Low | FIXED, unit-tested |
| I-1 | Data classes would print names, phone numbers or recovery words if ever logged | Info | FIXED, unit-tested |
| I-2 | Release builds were not shrunk by R8 | Info | FIXED, device-verified |

Info notes left open: tiny files and files sent with padding off keep about their size; Argon2
parameters of an older vault change only with its password; a full pass holds every message in memory
(see 2026-11-15, L-g); folder depth is not limited; names may hold control characters; the recent apps
thumbnail shows the file list when screenshots are allowed (a setting); auto-lock counts time only; the
transfer notification shows sizes; the fingerprint key prefers no StrongBox; debug builds export a
preview activity; logs hold message and file ids; TDLib's own files folder is not encrypted;
`androidx.biometric` 1.1.0 is the newest stable release.

---

## Findings

### Medium

#### M1: The recovery key could not bring back a vault whose header was gone

Coffer keeps the vault key, locked under the password, in one pinned note in Saved Messages. With that
note deleted, by mistake or by attacker A, every file was still there, but nothing opened the vault, not
even the 24 words. **Fix:** the screen that offers a new vault offers to restore one with its recovery
key and a new password, which writes a new header for the same key. Commit `7569545`.

#### M2: A forged header of another vault locked the user out

Posting one newer header with a random key id made phones take it for the vault: password, recovery
key and fingerprint all failed, and swapping the pin could make a phone wipe itself as if the vault had
been deleted. **Fix:** a phone keeps looking for the vault it knows; a header of another vault does not
replace it or count as a deletion. Commit `e297cd0`.

#### M3: Forged headers made every unlock slow, or crashed it

The format allows Argon2 settings up to 16 passes over 1 GiB. Headers asking that much were tried
first, and a phone that could not give the memory crashed. **Fix:** headers asking more than four times
the default work are not tried, the header that opened last time goes first, and an Argon2 failure
opens nothing instead of crashing. Commit `7a81421`.

#### M4: An old operation sent again was applied again

A forwarded copy of an old rename or move comes under a new message id and was applied as new.
**Fix:** each phone remembers which operations it applied and ignores copies; of two messages of one
file part, the first stays. **Residual, documented:** a phone that sets up the vault later may still take
a copy of a change whose original it never saw. Full protection needs a change of the vault format.
Commit `c149ef5`.

#### M5: An old snapshot sent again could roll a new phone back

A new phone loaded the first snapshot it met, the newest message, not the newest state. **Fix:**
snapshots are chosen and kept by the state they hold. Commit `f3ff061`.

#### M6: Maintenance trusted a history that may be cut short

TDLib can end a history early. The integrity check would then mark live files damaged, and compaction
delete operations still in force. **Fix:** both stop unless every message known below the cut is
confirmed gone. Commit `49b0959`.

#### M7: What a password change does

The vault key stays the same through a password change, so whoever kept an old header and knows the
old password can still open that copy, and a phone's fingerprint unlock kept working after a change
made elsewhere. The app's texts promised more. **Fix:** fingerprint unlock is turned off when the
password changes on another phone, and the texts say what a change does and does not do. Commit
`9c5c911`.

#### M8: The password floor was too low

A password the meter accepted could stand at about 10^8 guesses. **Fix:** the floor is 10^12 guesses
and at least 10 characters, enforced in the core as well as on screen. Commit `7d7ded7`.

#### M9: Sizes in the clear on the phone

The local index kept exact sizes and the lengths of names. **Fix:** the index is a SQLCipher database
with a key held by Android's Keystore; an existing index is moved over row by row. Commit `8208948`.

#### M10: Sharing into Coffer

Files shared in were copied without a size limit, `file://` paths and Coffer's own files were accepted,
and the copies outlived a sign-out or a deleted vault. **Fix:** only another app's `content://`, the copy
stops before the phone runs short of space, and copies go with a sign-out and a deleted vault. Commit
`ff893dc`.

#### M11: Decrypted copies for other apps

A copy could be written after the vault locked, and could outlive a process Android killed. **Fix:** no
copy is handed over after a lock, and a background job removes copies even when the process is gone.
Commit `03c8bdd`.

#### M12: Music after the lock

The lock screen and headset buttons could play the rest of a folder's music after the vault locked.
**Fix:** the track playing finishes, the rest of the queue and its keys are dropped. Commit `95773d8`.

#### M13: Track tags through Media3

An app with notification access could connect to the music session as a Media3 controller and read
the tags even with "show what is playing" off. **Fix:** only Coffer itself, the system and platform
controllers connect. Commit `a92b3cb`; completed on 2026-11-15 (N3).

### Low

- **L1** `57baedb`: the recovery words are forgotten when the vault locks.
- **L2** `9fdd758`: the three-word check is kept off screenshots.
- **L3** `15e4e4c`: a name keeps only its last part, without control characters, and never `.` or `..`.
- **L4** `62b7e0a`: only http, https and mailto links open, and an error does not crash the app.
- **L5** `478f86a`: `taskAffinity=""` on the only activity.
- **L6** `2adfc9d`: every text field asks the keyboard not to learn.
- **L7** `b296a58`: deleting the vault empties the file cache.
- **L8** `dd0d360`: a Keystore error is asked again before a secret counts as lost.
- **L9** `85a7d51`: the vault headers are kept sealed with a Keystore key.
- **L10** `db9ff75`: locking waits for work still using a key.
- **L11** `f853f19`: the copy of the master key made for the recovery words is wiped.
- **L12** `e5fc73d`: a snapshot whose document has the wrong size is skipped before download.
- **L13** `baaeb5d`: a copy of a lost part keeps its file whole at once.
- **L14** `07b965e`: the cache's record and queue are kept sealed.
- **L15** `7630a75`: a header whose pin failed is deleted again.

### Info

- `9504656`: data classes holding names, phone numbers or words print only their size.
- `8867a3f`: release builds are shrunk by R8.
