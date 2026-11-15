# Coffer Security Audit

**Date:** 15 November 2026
**Auditor:** Claude Opus 5.5, four independent reviews
**Type:** Second full audit; follows 14 November 2026
**Scope:** the whole app at version 0.1.0, commit `77059f6`, after every fix of the first audit

This review was AI-assisted: four separate AI auditors read the code, each in one area, without seeing
each other's work. Every finding they reported was checked against the code by hand before it was
fixed.

---

## Goal

Two questions. Did the first audit's fixes hold, and did they bring new problems? And, read again from
scratch, what else is there? The fixes added code no one had audited: the encrypted index and its
migration, sealed files on the phone, the restore with the recovery key, the record of applied
operations, the choice of snapshots by state, the gate of the music session, the sweep of copies for
other apps, the limits on sharing, the keyboard flag, the R8 rules, and the split into a debug app and a
release app. The auditors also checked what the public texts (README, the docs, the store listing and
the app's own strings) promise against what the code does.

---

## Threat Model

As on 14 November: **A.** Telegram, or anyone with access to the Telegram account; **B.** someone with
the phone, locked; **C.** another app on the same phone; **D.** the network, only for what Coffer adds.
Two residuals of the first audit were taken as known and examined only for being wider than documented:
a phone that sets up the vault later may take a copy of an old change (M4), and a password change does
not change the vault key (M7).

---

## Method

The same four areas and rules as on 14 November, each auditor given the first audit's findings and fix
commits to re-check. As before, each fix is one commit, with a test that fails without it, checked by a
mutant, where the code can be tested off a device; the rest was checked on a device by the developer.

---

## The First Audit, Re-checked

| Result | Findings |
|--------|----------|
| Held | M2, M5, M7 (residual as documented), M8, M9, M12, L1, L3, L4, L5, L6, L7, L9, L10, L11, L13, L14, L15 |
| Held in part | M1 (N1), M3 (L-a), M4 (N2), M6 (L-d), M10 (L-j, L-k), M11 (L-m), M13 (N3), L8 (L-l), L12 (L-c) |
| Undone | L2 (L-i) |

Each "in part" and the one undone are new findings below, now fixed.

---

## Summary of Findings

No Critical or High findings.

| ID | Description | Severity | Status |
|----|-------------|----------|--------|
| N1 | With any header in Saved Messages, the recovery key could open the vault but never set a new password | Medium | FIXED, unit-tested |
| N2 | The record of applied operations was emptied by every snapshot load and rescan | Medium | FIXED, unit-tested |
| N3 | The music session's gate trusted a version number the connecting app names itself | Medium | FIXED, unit-tested, device-verified |
| N4 | Files shared into Coffer waited on the phone unencrypted, before sign-in or unlock | Medium | FIXED, unit-tested, device-verified |
| L-a | Forged headers still multiplied the cost of an unlock | Low | FIXED in part, unit-tested |
| L-b | Deleting the header alone made every phone wipe itself; posting it again locked them | Low | FIXED, unit-tested |
| L-c | A real part caption over another document was taken for the part | Low | FIXED, unit-tested |
| L-d | The history check missed a cut on the first integrity check | Low | FIXED, unit-tested |
| L-e | Deleting the vault left what other phones sent while it ran | Low | FIXED, unit-tested |
| L-f | Copies of a snapshot's caption over junk pushed genuine snapshots out | Low | FIXED, unit-tested |
| L-g | A flood of messages could run catch-up out of memory at every unlock | Low | FIXED, unit-tested |
| L-h | A storage error during catch-up crashed the app | Low | FIXED, unit-tested |
| L-i | The screenshot block on the recovery words was cleared by the screen sliding out | Low | FIXED, unit-tested, device-verified |
| L-j | `content://0@` plus Coffer's own provider passed the sharing rule | Low | FIXED, unit-tested |
| L-k | A cancelled share with a stalled stream came back with its file | Low | FIXED, unit-tested |
| L-l | Android 12 and later report an unreachable Keystore key as lost, which still wiped data | Low | FIXED, unit-tested |
| L-m | An "open in another app" download queued before a lock still wrote its copy after it | Low | FIXED, device-verified |
| L-n | An export resumed after a restart failed every transfer, and its error named the file | Low | FIXED, device-verified |
| I-1 | A copy for another app was aged from the start of its download | Info | FIXED, unit-tested |
| D | Public texts promising more than the code does | - | FIXED |

---

## Findings

### Medium

#### N1: The recovery key could not lead back to a password

The restore of the first audit was offered only when Saved Messages held no header at all. With any
header there, a user who forgot the password could open the vault with the 24 words but not set a new
password: changing it asked for the old one. Attacker A could force this by deleting the real header and
posting a forged one, and the docs promised the opposite. **Fix:** Change password takes the 24 words in
place of the current password, and the change deletes every other header of the vault, a forged one
included. The unlock screen offers a restore when the recovery key belongs to none of the headers found.
Commit `c5e9f7b`.

#### N2: Copies of old operations after a snapshot load or a rescan

Both emptied the record of applied operations, so a copy of an old rename counted as new again, and two
phones could end up with different names. With the original operation gone, a deleted file whose parts
were posted again came back on a rescan. **Fix:** each operation is remembered with the message it first
came in; the record survives snapshot loads and rescans, and a rescan applies an operation again only
from that message. Deleted files and folders survive both too. Commit `48e08b0`.

#### N3: The music session's gate

The gate of the first audit let in "platform controllers", told apart by a version number the connecting
app writes itself. An app with notification access could claim it and read the tags of every track.
**Fix:** every controller but Coffer's own gets the player's controls and nothing about what plays. The
lock screen and notification listeners read the platform session, which says only "Coffer" unless asked.
Commit `56f6094`.

#### N4: Shared files waiting on the phone

A file shared into Coffer is copied at once, as the permission to read it ends with the screen, maybe
before sign-in, and may wait until the next unlock. The copy was in the clear. **Fix:** each copy is
encrypted as it is written, in chunks as vault content is, under a key of its own sealed by Android's
Keystore, and decrypted only as it is uploaded. Commit `dc827b7`.

### Low

- **L-a** `06df567`: headers that share salt and cost take one Argon2 run, a wrong password works out each
  header once, and the phone that made or restored the vault tries its own header first. **Residual:**
  each forged header with a salt of its own still costs one run. A hard cap would let forged headers
  lock the password out, which is worse.
- **L-b** `f5d91f1`: headers gone while the vault's files are still there are posted again from what the
  phone knows; a header is told by what opens it, not by its message, so a copy is the same header.
- **L-c** `82c628b`: a part counts only if its document is exactly as long as its caption says.
- **L-d** `155f3e7`: the history check also asks for the vault's headers, the first of which is usually
  the vault's first message.
- **L-e** `8aaba7f`: after the first pass the deletion reads on until nothing more of the vault comes,
  then deletes the headers, then looks once more.
- **L-f** `69d1add`: a copy carries its snapshot's caption letter for letter; copies are dropped before
  the three snapshots to keep are chosen.
- **L-g** `866d45f`: catch-up keeps nothing past its page and applies the history a page at a time; the
  integrity check and the deletion keep only Coffer's records. A regression of this fix, which crashed
  a rescan, was found in the device check and fixed in `6243966`.
- **L-h** `2a0518b`: a storage error during catch-up is waited out and tried again.
- **L-i** `73f49e1`: screens that block screenshots are counted; the block goes only with the last.
- **L-j** `86568d0`: the provider is read without a user before it or a port after it.
- **L-k** `5c1ef05`: each share has a generation, and only the current one may change what is shown.
- **L-l** `49ab89c`: only a key invalidated for good, or a tag that fails, counts as lost; a Keystore that
  keeps failing stops the start instead of wiping.
- **L-m** `d7fe604`: such a download is dropped once the vault is locked, and any end but the hand-over
  deletes the copy.
- **L-n** `a87fba2`: the save target's grant lasts until nothing is queued, and a lost grant fails only
  that export.

### Info

- **I-1** `dbb1af5`: a copy's age is its last write.
- **D** `b7dd1b8`: the texts say that sizes are hidden with the default setting and pad small files
  little; that locking wipes the vault's keys while a running transfer and the playing track keep their
  own file's key until they end; that the vault key leaves the phone and is stored only locked; that
  copies for other apps go about 10 minutes after opening, at the latest at lock or the next start; and
  what someone in the account can do with copies of old changes.

Left open, all without direct risk: other phones' unpinned headers survive a password change; two error
paths at vault creation drop keys unzeroed; the Argon2 cap follows this version's defaults; sealed files
on the phone accept an older plain copy; the SQLCipher key is not zeroed; the index migration has no
fallback; copies for other apps go "about" 10 minutes, as Android may defer the job; the track's length
and queue size are visible to platform controllers; a lock during vault creation leaves the screen
waiting; read grants for single uploads are not released; text copied from the text viewer is not marked
sensitive; several data classes still print names in `toString`; the recent apps thumbnail is saved when
screenshots are allowed; a cancelled export leaves its part-written file; Gradle does not verify
dependency checksums.
