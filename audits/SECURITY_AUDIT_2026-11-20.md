# Coffer Security Audit

**Date:** 20 November 2026
**Auditor:** Claude Opus 5.5, four independent reviews
**Type:** Third audit; follows 14 and 15 November 2026
**Scope:** everything since version 1.0.0, commits `v1.0.0..1e37239`, before version 1.1.0

This review was AI-assisted: four separate AI auditors read the code, each in one area, without seeing
each other's work. Every finding they reported was checked against the code by hand before it was
fixed.

---

## Goal

None of what changed after 1.0.0 had been audited: a new version of the vault format that puts several
changes in one message, a queue that sends changes at Telegram's pace and keeps them across restarts,
deletes in that queue, the handling of names already taken (Replace, Keep both, Skip, Merge), editing a
file in another app, Coffer in the system file picker, and the new Lock button. The auditors also
checked what the CHANGELOG, the settings texts and the docs say about these against what the code does.

---

## Threat Model

As before: **A.** Telegram, or anyone with access to the Telegram account; **B.** someone with the phone,
locked; **C.** another app on the same phone, now including an app given a file through the system
picker or an edit link; **D.** the network, only for what Coffer adds. A user action must not lose data
when the app is killed, locked or signed out at the wrong moment, or when Telegram asks for a pause.

Decisions taken on purpose were judged only for being wider than intended: a copy being edited stays
decrypted on the phone, also while locked, until it is sent and for an hour after; Coffer 1.0.0 does not
read the new format; the picker setting is off by default; the browser keeps its list on screen for the
padlock's flight after the keys are wiped.

---

## Method

Four areas, one auditor each, reading the code only:

1. The vault format version 3 and how its messages are read (attacker A)
2. The queue, deletes, Replace and name conflicts, and data integrity under failures
3. The Android surface: the picker's provider, the edit provider and its sessions, the Lock changes
4. Data at rest and leaks: decrypted copies, the picker's records, the index, logs, public texts

Duplicates across areas were merged. Each fix is one commit. Where the code can be tested off a device,
the fix comes with a test that fails without it, checked by breaking the code on purpose (a mutant) and
seeing the test catch it; format changes also come with vectors from the independent Python oracle.
What only a phone can show was checked on a device by the developer.

---

## Summary of Findings

No Critical or High findings.

| ID | Description | Severity | Status |
|----|-------------|----------|--------|
| FMT3-1 | A phone still on 1.0.0 could make updated phones undo changes | Medium | FIXED, unit-tested, oracle vectors |
| FMT3-2 | Compaction kept the copy of a change sent twice and deleted the original | Medium | FIXED, unit-tested |
| DATA3-1 | Copies being edited were removed only while the vault was open | Medium | FIXED, device-verified |
| DATA3-2 | The cleanup could remove a copy still open in an editor, or a save it did not hear | Medium | FIXED, unit-tested |
| Q3-1 | Merging into a folder during its delete's Undo lost what was moved in | Medium | FIXED, unit-tested, device-verified |
| Q3-2 | A save whose app crashed mid-write replaced the file | Medium | FIXED, unit-tested |
| DATA3-3 | A file opened to be changed through the picker was decrypted whole onto the phone | Medium | FIXED, texts corrected |
| DATA3-4 | The system picker remembers the vault's folder names | Medium | DOCUMENTED, cannot be fixed in Coffer |
| Q3-3 | A Replace upload cut off as it ended could lose both versions | Low | FIXED, unit-tested |
| Q3-4 | The queue was not forgotten when Telegram's data was wiped | Low | FIXED, checked in the code |
| Q3-5 | A move with Replace cut off midway deleted the old file with nothing in its place | Low | FIXED, unit-tested |
| Q3-6 | A file picked with its own folder was placed twice | Low | FIXED, unit-tested |
| AND3-3 | Coffer could read its own picker descriptor handed back by another app | Low | FIXED, unit-tested |
| AND3-4 | Each open to write decrypted the whole file again | Low | FIXED, unit-tested |
| AND3-5 | Lock then Home at once left the file list in the recent apps picture | Low | FIXED, device-verified |
| AND3-6 | Android 11 to 13 let any app time the vault through a document's type | Low | FIXED, device-verified |
| AND3-7 | Grants to picked files outlived the setting, sign-out and vault deletion | Low | FIXED, device-verified |
| DATA3-5 | A failed edit save crashed the app | Low | FIXED, checked in the code |
| DATA3-6 | The picker's records were a timed log of saves and opens | Low | FIXED, unit-tested, device-verified |
| DATA3-7 | Edit sessions kept the file's plain content hash | Low | FIXED, unit-tested, oracle vector |
| D | Texts promising more than the code does | Low | FIXED |
| I | Info items | Info | FIXED or noted |

---

## Findings

### Medium

#### FMT3-1: A phone on 1.0.0 could make updated phones undo changes

Version 1.0.0 skips the new messages that carry several changes, but still counts them as read, and its
snapshot of the vault's list says so. A phone on 1.1 that started from such a snapshot never read those
changes: renames and moves went back, and deleted folders and files came back as damaged. This went
further than "1.0.0 does not see them". **Fix:** snapshots made by 1.1 carry a new format version; 1.1
neither starts from a 1.0.0 snapshot nor counts it, and 1.0.0 does not read 1.1's. The first updated
phone in a vault reads the history once in full. Commit `910542c`.

#### FMT3-2: Compaction and a change sent twice

The queue sends a message again if Coffer is closed mid-send, so the same change can be there twice.
Compaction took the copy for the later write and deleted the original; the phone that made the change
remembers it by the original, so its next full reread skipped the copy and undid it. **Fix:** a copy is
never applied and decides nothing; it is the one that goes. Commit `e799789`.

#### DATA3-1: Copies being edited, kept too long

The cleanup of copies being edited ran only after a save with the vault open. On a phone that stayed
locked, a copy stayed decrypted under its own name until the next unlock, not an hour. **Fix:** the
cleanup needs no key: it runs every minute while Coffer runs, locked or not, and from a scheduled job
when it does not. Commit `13f4bc8`; seen on the phone: a session aged by hand went within a minute
while locked.

#### DATA3-2: The cleanup and editors

An editor that kept a copy open for over an hour lost its later save to the cleanup, and a save made
while Android had killed Coffer was never heard and went with the copy. **Fix:** a session knows which
process handed it out for writing and keeps it while that process lives, up to a day; a copy that changed
without Coffer hearing it is sent, not deleted. Commit `c7e8733`.

#### Q3-1: Merging into a folder being deleted

During the six seconds a delete can be undone, the folder still counted as a name taken, so a folder of
the same name could be merged into it, and the delete then took what was moved in. **Fix:** a delete
takes what the folder holds when the user deletes it, and names are settled without what is being
deleted. Commit `ca7d59c`.

#### Q3-2: A crash while saving

Android tells a provider when the writing app closed its file with an error, as on a crash; Coffer did not
listen, and a file cut short, or emptied, was uploaded in place of the real one, which was then deleted.
**Fix:** such a copy is marked broken: never sent, read or written to, and removed. Commit `bfae2ec`.

#### DATA3-3: What the picker writes to the phone

A file opened to read through the picker is decrypted as it is read, with nothing written. One opened to
be changed is decrypted whole into a copy on the phone first, though the texts said nothing is written;
and that decryption went on after a lock. **Fix:** it is cut off by a lock like a read, and the texts say
which opening writes a copy and how long it stays. Commit `190db5a`.

#### DATA3-4: The system picker remembers folders

The picker is part of Android: it is not kept off screenshots, and it keeps the names of the folders it
went through. Coffer cannot change or clear that. **Status:** the setting's text, the CHANGELOG and the
docs say so. Commit `a391bd1`.

### Low

- **Q3-3** `575a41a`: the upload leaves the queue before the old file is held for deleting; a cut in
  between keeps both files.
- **Q3-4** `9638271`: signing out, a reset of Telegram's data and a change of keys are one event that
  forgets the vault and its queue; before, a reset left queued deletes that could have gone to the next
  account signed in.
- **Q3-5** `71de978`: a move with Replace seals all its messages before queuing any, the moves first.
- **Q3-6** `2739f69`: picked items inside another picked folder go with it alone.
- **AND3-3** `4ae11f0`: what other apps hand Coffer to read is refused when it is Coffer's own picker
  descriptor, which read in process can leave Coffer unable to start until the phone restarts.
- **AND3-4** `8af4d4e`: opens to write the same version of a file share one copy.
- **AND3-5** `653501c`: leaving the app during the padlock's flight ends it, and the list with it.
- **AND3-6** `bfec4b4`: a document's type is told only to an app that may read it. The first version of
  this fix turned the system picker away too; this was found in the device check and fixed in
  `5b278bc`.
- **AND3-7** `585406d`: turning the setting off, signing out and deleting the vault revoke every grant to
  the picker's documents.
- **DATA3-5** `0f737b0`: each edit save fails on its own and is tried again a minute later; a save
  that could not cancel the one before is not queued beside it.
- **DATA3-6** `3c6451e`: the picker's forwards keep order numbers, not times, are written only while the
  setting is on, are cleared when it is turned off, and are not rewritten when a file is only opened.
  The file's own header still held the time of writing; seen on the phone and fixed in `785d398`.
- **DATA3-7** `c5795c7`: sessions keep the content hash keyed with a key derived from the phone's local
  key, and a session whose download was dropped at a lock goes with it.
- **D** `1b02849`: the CHANGELOG says that 1.0.0 misses renames, moves and deletes too, shows files
  deleted on 1.1 as damaged, and leaves 1.1's changes behind when a vault is deleted from it; the
  Replace setting says what it does on a move. The docs were updated in `7702af2`.

### Info

- Fixed: Edit in another app offers editors first (`d9586d0`); a copy saved as `session.properties`
  keeps its name (`98a3aeb`); a numbered name stays within 255 bytes (`29dbb6e`); the new name-holding
  classes print no names (`84c1b30`); tests for packs of every refused shape, a folder delete in a pack
  under compaction (`bee4269`), and queued rows through the index versions since 7 (`86e850a`).
- Noted, without direct risk: a pack's count is checked after an overflow the strict reading already
  refuses; Telegram can tell a save that replaces a file by its upload and the delete after it; no size
  limit on what an app writes into an edit copy; editor apps keep their own traces of a file; names are
  matched without Unicode normalization, which keeps both forms, never replaces; a replaced file shows as
  Deleting while another Replace upload is stuck.

Checked only in the code, as no test or phone can show them without signing out or forcing Telegram's
pause: Q3-4 and DATA3-5.
