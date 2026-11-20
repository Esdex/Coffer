package me.esdex.coffer.core.vault

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.esdex.coffer.core.crypto.Argon2Params
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.MasterKeys
import me.esdex.coffer.core.crypto.PasswordKdf
import me.esdex.coffer.core.crypto.bip39.Bip39
import me.esdex.coffer.core.crypto.bip39.InvalidMnemonicException
import me.esdex.coffer.core.crypto.format.VaultHeader
import me.esdex.coffer.core.crypto.format.VaultHeaderCodec
import me.esdex.coffer.core.crypto.sodium.LazySodiumAdapter
import me.esdex.coffer.core.crypto.sodium.Sodium
import me.esdex.coffer.core.storage.FakeCloud
import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageException
import me.esdex.coffer.core.storage.StoredMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.EmptyCoroutineContext

private val sodium = LazySodiumAdapter(LazySodiumJava(SodiumJava()))

/** The cheapest cost the format allows, so tests stay fast; the default is checked elsewhere. */
private val fast = Argon2Params(1, 8L * 1024 * 1024)

private fun newVault(password: String, createdAt: Long = 1) = VaultCreation.prepare(sodium, password, createdAt, fast)

/** The same master key wrapped under another password, as a password change writes it. */
private fun rewrapped(vault: NewVault, password: String): VaultHeader {
    val salt = PasswordKdf.newSalt(sodium)
    val wrapped = PasswordKdf.deriveKek(sodium, password, salt, fast).use { kek ->
        vault.vault.withMasterKey { MasterKeys.wrap(sodium, kek, it) }
    }
    return vault.header.copy(salt = salt, wrappedMaster = wrapped, createdAt = vault.header.createdAt + 1)
}

private suspend fun StorageBackend.sendHeader(header: VaultHeader) = sendText(VaultHeaderCodec.encode(header))

/** Counts Argon2 runs: each is the cost an unlock pays. */
private class CountingSodium(private val inner: Sodium) : Sodium by inner {
    var argonRuns = 0

    override fun argon2id(outSize: Int, password: ByteArray, salt: ByteArray, opsLimit: Long, memLimitBytes: Long): ByteArray {
        argonRuns++
        return inner.argon2id(outSize, password, salt, opsLimit, memLimitBytes)
    }
}

class VaultKeysTest {

    @Test
    fun passwordAndRecoveryKeyOpenTheSameVault() = runTest {
        val storage = FakeCloud().device()
        val created = newVault("correct horse battery staple")
        val words = created.recoveryWords
        VaultHeaders.publish(storage, created)
        val headers = VaultHeaders.find(storage)

        val byPassword = VaultUnlock.withPassword(sodium, headers, "correct horse battery staple")!!
        assertEquals(created.vault.masterId, byPassword.masterId)
        assertNull(VaultUnlock.withPassword(sodium, headers, "correct horse battery stapler"))

        val byWords = VaultUnlock.withMnemonic(sodium, headers, words) as VaultUnlock.MnemonicResult.Unlocked
        assertEquals(created.vault.masterId, byWords.vault.masterId)
        byPassword.keys.metaKey.withBytes { a -> byWords.vault.keys.metaKey.withBytes { b -> assertTrue(a.contentEquals(b)) } }
    }

    @Test
    fun recoveryKeyOfAnotherVaultIsRecognized() = runTest {
        val storage = FakeCloud().device()
        VaultHeaders.publish(storage, newVault("first vault password"))
        val other = newVault("second vault password").recoveryWords
        assertEquals(VaultUnlock.MnemonicResult.OtherVault, VaultUnlock.withMnemonic(sodium, VaultHeaders.find(storage), other))
        val broken = other.dropLast(1) + other.last().let { if (it == "abandon") "ability" else "abandon" }
        assertThrows(InvalidMnemonicException::class.java) { VaultUnlock.withMnemonic(sodium, emptyList(), broken) }
    }

    @Test
    fun pinnedHeadersComeFirstAndHistoryIsTheFallback() = runTest {
        val cloud = FakeCloud(pageCap = 7)
        val storage = cloud.device()
        repeat(30) { storage.sendText("note $it") }
        val vault = newVault("a long enough password")
        val unpinned = storage.sendHeader(vault.header)
        repeat(30) { storage.sendText("later note $it") }

        var progress = 0
        val viaHistory = VaultHeaders.find(storage) { progress = it }
        assertEquals(listOf(unpinned.id), viaHistory.map { it.messageId })
        assertEquals(61, progress)

        // Once something is pinned, the full pass is skipped.
        val pinned = storage.sendHeader(rewrapped(vault, "another password"))
        storage.pin(pinned.id)
        assertEquals(listOf(pinned.id), VaultHeaders.find(storage).map { it.messageId })
    }

    @Test
    fun halfDonePasswordChangeKeepsTheOldPasswordWorking() = runTest {
        val storage = FakeCloud().device()
        val vault = newVault("old password here")
        storage.sendHeader(vault.header)
        storage.sendHeader(rewrapped(vault, "new password here"))
        val headers = VaultHeaders.find(storage)
        assertEquals(2, headers.size)
        assertNotNull(VaultUnlock.withPassword(sodium, headers, "new password here"))
        assertNotNull(VaultUnlock.withPassword(sodium, headers, "old password here"))
    }

    @Test
    fun eachVaultsPasswordOpensItsOwnVault() = runTest {
        // A newer header of another vault, forged or not, must not hide the older one (audit 1, M2).
        val storage = FakeCloud().device()
        val older = newVault("older vault password")
        val newer = newVault("newer vault password")
        storage.sendHeader(older.header)
        storage.sendHeader(newer.header)
        val headers = VaultHeaders.find(storage)
        assertEquals(older.vault.masterId, VaultUnlock.withPassword(sodium, headers, "older vault password")?.masterId)
        assertEquals(newer.vault.masterId, VaultUnlock.withPassword(sodium, headers, "newer vault password")?.masterId)
    }

    @Test
    fun aHeaderAskingForMoreWorkThanCofferWritesIsNotTried() = runTest {
        // Audit 1, M3: the format allows 16 passes over 1 GiB, about 32 times the default.
        assertTrue(VaultUnlock.affordable(Argon2Params.DEFAULT))
        assertTrue(VaultUnlock.affordable(Argon2Params(16, 128L * 1024 * 1024)))
        assertTrue(VaultUnlock.affordable(Argon2Params(8, 256L * 1024 * 1024)))
        assertFalse(VaultUnlock.affordable(Argon2Params(16, 256L * 1024 * 1024)))
        assertFalse(VaultUnlock.affordable(Argon2Params(1, 512L * 1024 * 1024)))
        assertFalse(VaultUnlock.affordable(Argon2Params(16, 1024L * 1024 * 1024)))

        val storage = FakeCloud().device()
        val real = newVault("the right password")
        storage.sendHeader(real.header)
        storage.sendHeader(real.header.copy(argon = Argon2Params(16, 1024L * 1024 * 1024), createdAt = 9))
        val counting = CountingSodium(sodium)
        assertNotNull(VaultUnlock.withPassword(counting, VaultHeaders.find(storage), "the right password"))
        assertEquals(1, counting.argonRuns)
    }

    @Test
    fun theHeaderThatOpenedLastTimeIsTriedFirst() = runTest {
        // Audit 1, M3: newer forged headers with the vault's own master_id, each an Argon2 run.
        val storage = FakeCloud().device()
        val real = newVault("the right password")
        val realId = storage.sendHeader(real.header).id
        repeat(5) { storage.sendHeader(real.header.copy(salt = PasswordKdf.newSalt(sodium), createdAt = 10L + it)) }
        val headers = VaultHeaders.find(storage)

        val first = CountingSodium(sodium)
        val opened = VaultUnlock.withPasswordFrom(first, headers, "the right password")!!
        assertEquals(realId, opened.second)
        assertEquals(6, first.argonRuns)

        val again = CountingSodium(sodium)
        assertNotNull(VaultUnlock.withPasswordFrom(again, headers, "the right password", preferred = realId))
        assertEquals(1, again.argonRuns)
    }

    @Test
    fun copiesOfOneHeaderCostOneArgonRunAndASecondPassOnlyWhatIsNew() = runTest {
        // Audit 2, L-a: the real header posted again, as anyone in the account can, cost a run per copy.
        val storage = FakeCloud().device()
        val real = newVault("the right password")
        repeat(4) { storage.sendHeader(real.header) }
        storage.sendHeader(real.header.copy(salt = PasswordKdf.newSalt(sodium), createdAt = 10))
        val headers = VaultHeaders.find(storage)
        assertEquals(5, headers.size)

        val counting = CountingSodium(sodium)
        val tried = HashSet<VaultUnlock.KdfInput>()
        assertNull(VaultUnlock.withPasswordFrom(counting, headers, "a wrong password", tried = tried))
        assertEquals(2, counting.argonRuns)
        // The same password again, as after the search past the pins: nothing new, nothing worked out.
        assertNull(VaultUnlock.withPasswordFrom(counting, headers, "a wrong password", tried = tried))
        assertEquals(2, counting.argonRuns)
        assertNotNull(VaultUnlock.withPasswordFrom(counting, headers, "the right password"))

        // The real salt and cost over a wrapping that opens nothing, posted last so it comes first: the
        // one run for that salt still tries the real wrapping too.
        storage.sendHeader(real.header.copy(wrappedMaster = Bytes.of(sodium.randomBytes(real.header.wrappedMaster.size)), createdAt = 20))
        val withJunk = CountingSodium(sodium)
        assertNotNull(VaultUnlock.withPasswordFrom(withJunk, VaultHeaders.find(storage), "the right password"))
        assertEquals(1, withJunk.argonRuns)
    }

    @Test
    fun aHeaderThatFailsToWorkOutOpensNothingAndTheNextIsTried() = runTest {
        val storage = FakeCloud().device()
        val real = newVault("the right password")
        storage.sendHeader(real.header)
        val broken = real.header.copy(salt = PasswordKdf.newSalt(sodium), createdAt = 9)
        storage.sendHeader(broken)
        // As lazysodium fails when the phone cannot give the memory.
        val failing = object : Sodium by sodium {
            override fun argon2id(outSize: Int, password: ByteArray, salt: ByteArray, opsLimit: Long, memLimitBytes: Long): ByteArray {
                check(!salt.contentEquals(broken.salt.toByteArray())) { "argon2id failed" }
                return sodium.argon2id(outSize, password, salt, opsLimit, memLimitBytes)
            }
        }
        assertNotNull(VaultUnlock.withPassword(failing, VaultHeaders.find(storage), "the right password"))
    }

    @Test
    fun aPinnedHeaderOfAnotherVaultDoesNotHideTheKnownOne() = runTest {
        val storage = FakeCloud().device()
        val real = newVault("the real vault password")
        val unpinned = storage.sendHeader(real.header)
        val forged = storage.sendHeader(newVault("someone else's password").header)
        storage.pin(forged.id)
        // Knowing which vault it looks for, the search goes past a pinned header of another one.
        val found = VaultHeaders.find(storage, known = real.vault.masterId)
        assertTrue(found.any { it.messageId == unpinned.id })
    }

    @Test
    fun headerWhoseMasterIdDoesNotMatchItsKeyIsRejected() = runTest {
        val storage = FakeCloud().device()
        val real = newVault("the right password")
        val forged = real.header.copy(masterId = newVault("x").vault.masterId)
        storage.sendHeader(forged)
        assertNull(VaultUnlock.withPassword(sodium, VaultHeaders.find(storage), "the right password"))
    }

    @Test
    fun publishRefusesWhenAVaultAppeared() = runTest {
        val cloud = FakeCloud()
        val here = cloud.device()
        val there = cloud.device()
        val mine = newVault("my password is long")
        VaultHeaders.publish(there, newVault("their password is long"))
        assertThrows(VaultExistsException::class.java) { kotlinx.coroutines.runBlocking { VaultHeaders.publish(here, mine) } }
        assertEquals(1, cloud.allMessages().count { it.content is MessageContent.Text })
    }

    @Test
    fun publishPinsTheHeader() = runTest {
        val storage = FakeCloud().device()
        val published = VaultHeaders.publish(storage, newVault("pin me please"))
        assertEquals(listOf(published.messageId), storage.pinnedMessages().map { it.id })
    }

    @Test
    fun closingWipesTheKeys() {
        val vault = newVault("to be wiped").vault
        val meta = vault.keys.metaKey
        val local = vault.localKey
        vault.close()
        assertTrue(meta.isClosed)
        assertTrue(local.isClosed)
        assertThrows(IllegalStateException::class.java) { vault.withMasterKey { it.withBytes { } } }
    }
}

class VaultManagerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun manager(store: VaultStore = VaultStore(temp.root)) = VaultManager(sodium, store, { 42 }, EmptyCoroutineContext)

    @Test
    fun createLockUnlockAndForget() = runTest {
        val storage = FakeCloud().device()
        val manager = manager()
        assertEquals(VaultManager.State.Unknown, manager.state.value)
        manager.locate(storage)
        assertEquals(VaultManager.State.NotFound, manager.state.value)

        val created = manager.prepareNew("a password for the manager")
        assertEquals(42, created.header.createdAt)
        // SPEC §3.3, calibrated on a real phone: 128 MiB and 4 passes.
        assertEquals(Argon2Params(opsLimit = 4, memLimitBytes = 128L * 1024 * 1024), created.header.argon)
        manager.publish(storage, created)
        val unlocked = manager.state.value as VaultManager.State.Unlocked

        manager.lockVault()
        assertTrue(unlocked.vault.keys.metaKey.isClosed)
        assertEquals(VaultManager.State.Locked(created.vault.masterId), manager.state.value)
        assertFalse(manager.unlockWithPassword("wrong password for the manager"))
        assertNotNull(manager.lastKdfMillis)
        assertTrue(manager.unlockWithPassword("a password for the manager"))

        // Locked with the Lock button: said so, for the unlock screen to wait for a fingerprint until asked.
        manager.lockVault(byHand = true)
        assertEquals(VaultManager.State.Locked(created.vault.masterId, byHand = true), manager.state.value)
        assertTrue(manager.unlockWithPassword("a password for the manager"))

        // A second launch remembers the header and starts locked, without a search.
        manager.lockVault()
        assertEquals(VaultManager.State.Locked(created.vault.masterId), manager.state.value)
        val again = manager(VaultStore(temp.root))
        assertEquals(VaultManager.State.Locked(created.vault.masterId), again.state.value)
        assertTrue(again.unlockWithPassword("a password for the manager"))

        again.forget()
        assertEquals(VaultManager.State.Unknown, again.state.value)
        assertEquals(VaultManager.State.Unknown, manager(VaultStore(temp.root)).state.value)
    }

    @Test
    fun passwordIsCheckedAgainstTheOpenVault() = runTest {
        val storage = FakeCloud().device()
        val manager = manager()
        manager.locate(storage)
        val created = manager.prepareNew("the password of this vault")
        val words = created.recoveryWords
        // Locked: nothing to check against, and no words to show.
        assertFalse(manager.checkPassword("the password of this vault"))
        manager.publish(storage, created)
        assertTrue(manager.checkPassword("the password of this vault"))
        assertFalse(manager.checkPassword("the password of this vaulT"))
        assertEquals(words, manager.recoveryWords())
        manager.lockVault()
        assertNull(manager.recoveryWords())
        assertFalse(manager.checkPassword("the password of this vault"))
    }

    @Test
    fun changingThePasswordReplacesTheHeader() = runTest {
        val cloud = FakeCloud()
        val storage = cloud.device()
        val manager = manager()
        manager.locate(storage)
        val created = manager.prepareNew("old password for changing")
        val masterId = created.vault.masterId
        val words = created.recoveryWords
        manager.publish(storage, created)
        val oldHeader = storage.pinnedMessages().single()

        assertEquals(VaultManager.PasswordChange.WrongPassword, manager.changePassword(storage, "not the old password", "new password after change"))
        assertEquals(listOf(oldHeader), storage.pinnedMessages())

        assertEquals(VaultManager.PasswordChange.Changed, manager.changePassword(storage, "old password for changing", "new password after change"))
        // SPEC §4.1: a new pinned header, the old one gone, the same vault behind it.
        val headers = cloud.allMessages().mapNotNull(FoundHeader::from)
        assertEquals(1, headers.size)
        assertEquals(storage.pinnedMessages().single().id, headers.single().messageId)
        assertEquals(masterId, headers.single().header.masterId)
        // A fresh salt: the old wrapping cannot be reused to test guesses against the new one.
        val oldSalt = FoundHeader.from(oldHeader)!!.header.salt
        assertNotEquals(oldSalt, headers.single().header.salt)
        assertNotEquals(Bytes.of(ByteArray(16)), headers.single().header.salt)
        assertTrue(manager.state.value is VaultManager.State.Unlocked)

        // On this device, offline, and on a fresh one.
        manager.lockVault()
        val again = manager(VaultStore(temp.root))
        assertFalse(again.unlockWithPassword("old password for changing"))
        assertTrue(again.unlockWithPassword("new password after change"))
        val other = VaultManager(sodium, VaultStore(temp.newFolder()), { 42 }, EmptyCoroutineContext)
        other.locate(cloud.device())
        assertFalse(other.unlockWithPassword("old password for changing"))
        assertTrue(other.unlockWithPassword("new password after change"))
        // The recovery key did not change: the master key is the same.
        assertEquals(words, other.recoveryWords())
    }

    @Test
    fun theRecoveryKeySetsANewPasswordPastAForgedHeader() = runTest {
        // Audit 2, N1: the real header deleted, a forged one with its master_id copied in its place. The
        // recovery key opens the vault, no password does, and the old password is not known to change it.
        val cloud = FakeCloud()
        val storage = cloud.device()
        val owner = manager()
        owner.locate(storage)
        val created = owner.prepareNew("the password of the real vault")
        val words = created.recoveryWords
        val masterId = created.vault.masterId
        val forgedHeader = created.header.copy(
            salt = PasswordKdf.newSalt(sodium),
            wrappedMaster = Bytes.of(sodium.randomBytes(created.header.wrappedMaster.size)),
        )
        owner.publish(storage, created)
        storage.delete(cloud.allMessages().filter { FoundHeader.from(it) != null }.map { it.id })
        storage.pin(storage.sendHeader(forgedHeader).id)

        val device = VaultManager(sodium, VaultStore(temp.newFolder()), { 42 }, EmptyCoroutineContext)
        val deviceStorage = cloud.device()
        device.locate(deviceStorage)
        assertFalse(device.unlockWithPassword("the password of the real vault", deviceStorage))
        assertTrue(device.unlockWithRecoveryKey(words, deviceStorage) is VaultUnlock.MnemonicResult.Unlocked)

        // Another vault's words, or no recovery key at all, do not stand in for the password.
        val otherWords = newVault("another vault's password").recoveryWords
        assertEquals(VaultManager.PasswordChange.WrongPassword, device.changePasswordWithRecoveryKey(deviceStorage, otherWords, "a new password after the loss"))
        assertEquals(VaultManager.PasswordChange.WrongPassword, device.changePasswordWithRecoveryKey(deviceStorage, words.drop(1), "a new password after the loss"))
        assertEquals(listOf(forgedHeader), cloud.allMessages().mapNotNull(FoundHeader::from).map { it.header })

        assertEquals(VaultManager.PasswordChange.Changed, device.changePasswordWithRecoveryKey(deviceStorage, words, "a new password after the loss"))
        // The forged header is gone with the change; the new password opens the same vault anywhere.
        val headers = cloud.allMessages().mapNotNull(FoundHeader::from)
        assertEquals(1, headers.size)
        assertNotEquals(forgedHeader, headers.single().header)
        val other = VaultManager(sodium, VaultStore(temp.newFolder()), { 42 }, EmptyCoroutineContext)
        val otherStorage = cloud.device()
        other.locate(otherStorage)
        assertTrue(other.unlockWithPassword("a new password after the loss", otherStorage))
        assertEquals(masterId, (other.state.value as VaultManager.State.Unlocked).vault.masterId)
    }

    /** Two devices on one Saved Messages: [here] made the vault, [there] found and opened it. */
    private suspend fun twoDevices(cloud: FakeCloud, password: String): Triple<VaultManager, VaultManager, Pair<StorageBackend, StorageBackend>> {
        val hereStorage = cloud.device()
        val here = manager()
        here.locate(hereStorage)
        here.publish(hereStorage, here.prepareNew(password))
        val thereStorage = cloud.device()
        val there = VaultManager(sodium, VaultStore(temp.newFolder()), { 42 }, EmptyCoroutineContext)
        there.locate(thereStorage)
        assertTrue(there.unlockWithPassword(password, thereStorage))
        return Triple(here, there, hereStorage to thereStorage)
    }

    @Test
    fun aPasswordChangedElsewhereStopsTheOldOneAtTheNextUnlockHere() = runTest {
        val cloud = FakeCloud()
        val (here, there, storages) = twoDevices(cloud, "old password on both devices")
        val (hereStorage, thereStorage) = storages
        there.lockVault()
        assertEquals(VaultManager.PasswordChange.Changed, here.changePassword(hereStorage, "old password on both devices", "new password changed over here"))
        // There, still running and holding the old header: with Telegram answering, the old password is refused.
        assertFalse(there.unlockWithPassword("old password on both devices", thereStorage))
        assertTrue(there.unlockWithPassword("new password changed over here", thereStorage))
    }

    @Test
    fun theHeadersAreKeptSealedOnThePhone() = runTest {
        // Audit 1, L9: from a copy of the storage they would feed offline password guessing.
        val sealer = object : me.esdex.coffer.core.storage.DeviceSealer {
            override fun seal(plain: ByteArray, purpose: String) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
            override fun open(sealed: ByteArray, purpose: String) = seal(sealed, purpose)
        }
        val dir = temp.newFolder()
        val storage = FakeCloud().device()
        val manager = VaultManager(sodium, VaultStore(dir, sealer), { 42 }, EmptyCoroutineContext)
        manager.locate(storage)
        manager.publish(storage, manager.prepareNew("the password of the sealed vault"))
        val file = java.io.File(dir, "headers.txt")
        assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("coffer:"))
        // Read back by the next start, which then opens with the password.
        val again = VaultManager(sodium, VaultStore(dir, sealer), { 42 }, EmptyCoroutineContext)
        assertTrue(again.state.value is VaultManager.State.Locked)
        assertTrue(again.unlockWithPassword("the password of the sealed vault"))
    }

    @Test
    fun aChangeWhosePinFailsLeavesNoHeaderBehind() = runTest {
        // Audit 1, L15.
        val cloud = FakeCloud()
        val storage = cloud.device()
        val manager = manager()
        manager.locate(storage)
        manager.publish(storage, manager.prepareNew("the old password of the vault"))
        val before = cloud.allMessages().map { it.id }.toSet()
        val noPins = object : StorageBackend by storage {
            override suspend fun pin(id: MessageId) = throw StorageException("connection lost")
        }
        assertThrows(StorageException::class.java) {
            kotlinx.coroutines.runBlocking { manager.changePassword(noPins, "the old password of the vault", "a new password nobody saw") }
        }
        assertEquals(before, cloud.allMessages().map { it.id }.toSet())
        manager.lockVault()
        assertTrue(manager.unlockWithPassword("the old password of the vault", storage))
    }

    @Test
    fun aPasswordChangedElsewhereTurnsTheFingerprintHereOff() = runTest {
        // Audit 1, M7: the master key stays the same, so the fingerprint would still open the vault.
        val cloud = FakeCloud()
        val hereStore = VaultStore(temp.newFolder())
        val here = VaultManager(sodium, hereStore, { 42 }, EmptyCoroutineContext)
        val hereStorage = cloud.device()
        here.locate(hereStorage)
        here.publish(hereStorage, here.prepareNew("old password on both devices"))
        val thereStore = VaultStore(temp.newFolder())
        val there = VaultManager(sodium, thereStore, { 42 }, EmptyCoroutineContext)
        val thereStorage = cloud.device()
        there.locate(thereStorage)
        assertTrue(there.unlockWithPassword("old password on both devices", thereStorage))

        here.changePassword(hereStorage, "old password on both devices", "new password changed over here")
        // Not on the device that changed it: its user typed the new password there.
        assertFalse(hereStore.deviceUnlockStale)
        there.headersChanged(thereStorage)
        assertTrue(thereStore.deviceUnlockStale)
    }

    @Test
    fun aChangeElsewhereAfterOneHereIsNoticed() = runTest {
        val cloud = FakeCloud()
        val (here, there, storages) = twoDevices(cloud, "first password of the vault")
        val (hereStorage, thereStorage) = storages
        val thereStore = VaultStore(temp.newFolder())
        val third = VaultManager(sodium, thereStore, { 42 }, EmptyCoroutineContext)
        third.locate(thereStorage)
        assertTrue(third.unlockWithPassword("first password of the vault", thereStorage))
        // Changed on the third device itself, then on the first one.
        assertEquals(VaultManager.PasswordChange.Changed, third.changePassword(thereStorage, "first password of the vault", "second password, set on the third"))
        here.headersChanged(hereStorage)
        assertTrue(here.unlockWithPassword("second password, set on the third", hereStorage))
        here.changePassword(hereStorage, "second password, set on the third", "third password, set on the first")
        third.headersChanged(thereStorage)
        assertTrue(thereStore.deviceUnlockStale)
    }

    @Test
    fun aDeviceThatWasLockedLearnsOfTheChangeAtItsNextSearch() = runTest {
        val cloud = FakeCloud()
        val (here, there, storages) = twoDevices(cloud, "old password on both devices")
        val (hereStorage, thereStorage) = storages
        there.lockVault()
        here.changePassword(hereStorage, "old password on both devices", "new password changed over here")
        there.locate(thereStorage)
        val state = there.state.value
        assertTrue(state is VaultManager.State.Locked && state.passwordChangedElsewhere)
    }

    @Test
    fun aForgedHeaderPostedAndDeletedIsNotAPasswordChange() = runTest {
        val cloud = FakeCloud()
        val (_, there, storages) = twoDevices(cloud, "the password of the real vault")
        val (hereStorage, thereStorage) = storages
        there.lockVault()
        // With the vault's own master_id and pinned, so the device does take it in, then deleted.
        val header = hereStorage.pinnedMessages().single().let { FoundHeader.from(it)!!.header }
        val forged = hereStorage.sendHeader(header.copy(salt = PasswordKdf.newSalt(sodium), createdAt = 77))
        hereStorage.pin(forged.id)
        there.locate(thereStorage)
        hereStorage.delete(listOf(forged.id))
        there.locate(thereStorage)
        val state = there.state.value
        assertTrue(state is VaultManager.State.Locked && !state.passwordChangedElsewhere)
    }

    @Test
    fun theHeaderPostedAgainWithTheOriginalDeletedIsNotAPasswordChange() = runTest {
        // Audit 2, L-b: the same text under a new message id, then the original deleted, locked every
        // device as changed elsewhere and turned fingerprint unlock off.
        val cloud = FakeCloud()
        val store = VaultStore(temp.newFolder())
        val (_, _, storages) = twoDevices(cloud, "the password of the real vault")
        val (hereStorage, thereStorage) = storages
        val there = VaultManager(sodium, store, { 42 }, EmptyCoroutineContext)
        there.locate(thereStorage)
        assertTrue(there.unlockWithPassword("the password of the real vault", thereStorage))
        val original = hereStorage.pinnedMessages().single()
        val copy = hereStorage.sendText((original.content as MessageContent.Text).text)
        hereStorage.pin(copy.id)
        hereStorage.delete(listOf(original.id))

        there.headersChanged(thereStorage)
        assertTrue(there.state.value is VaultManager.State.Unlocked)
        assertFalse(store.deviceUnlockStale)
        assertEquals(copy.id, store.openedHeader)
    }

    @Test
    fun aHeaderDeletedWithTheFilesStillThereIsPostedAgainNotTakenForADeletion() = runTest {
        // Audit 2, L-b: every device wiped itself as if the vault had been deleted, its files all still there.
        val cloud = FakeCloud()
        val (_, _, storages) = twoDevices(cloud, "the password of the real vault")
        val (hereStorage, thereStorage) = storages
        val there = VaultManager(sodium, VaultStore(temp.newFolder()), { 42 }, EmptyCoroutineContext, contentRemains = { true })
        there.locate(thereStorage)
        assertTrue(there.unlockWithPassword("the password of the real vault", thereStorage))
        val header = FoundHeader.from(hereStorage.pinnedMessages().single())!!.header
        deleteHeaders(cloud, hereStorage)

        there.headersChanged(thereStorage)
        assertNull(there.deleted.value)
        assertTrue(there.state.value is VaultManager.State.Unlocked)
        // The same header, pinned again, and a new device finds and opens the vault with it.
        assertEquals(listOf(header), thereStorage.pinnedMessages().mapNotNull(FoundHeader::from).map { it.header })
        val fresh = VaultManager(sodium, VaultStore(temp.newFolder()), { 42 }, EmptyCoroutineContext)
        val freshStorage = cloud.device()
        fresh.locate(freshStorage)
        assertTrue(fresh.unlockWithPassword("the password of the real vault", freshStorage))
    }

    @Test
    fun anOpenVaultLocksWhenItsHeaderIsReplacedElsewhere() = runTest {
        val cloud = FakeCloud()
        val (here, there, storages) = twoDevices(cloud, "old password on both devices")
        val (hereStorage, thereStorage) = storages
        here.changePassword(hereStorage, "old password on both devices", "new password changed over here")
        there.headersChanged(thereStorage)
        val state = there.state.value
        assertTrue(state is VaultManager.State.Locked && state.passwordChangedElsewhere)
        // The old header is forgotten here too: not even offline does the old password open it any more.
        assertFalse(there.unlockWithPassword("old password on both devices"))
        assertTrue(there.unlockWithPassword("new password changed over here"))
    }

    @Test
    fun aForgedHeaderOfAnotherVaultDoesNotLockTheUserOut() = runTest {
        // Audit 1, M2: someone in the Telegram account pins a newer header of a vault of their own.
        val cloud = FakeCloud()
        val (here, _, storages) = twoDevices(cloud, "the password of the real vault")
        val (hereStorage, thereStorage) = storages
        here.lockVault()
        val forged = thereStorage.sendHeader(newVault("the forger's own password").header)
        thereStorage.pin(forged.id)

        // The device that knows the vault keeps it, after a restart too.
        here.locate(hereStorage)
        val again = manager()
        again.locate(hereStorage)
        val knownId = (again.state.value as VaultManager.State.Locked).masterId
        assertTrue(again.unlockWithPassword("the password of the real vault", hereStorage))
        assertEquals(knownId, (again.state.value as VaultManager.State.Unlocked).vault.masterId)

        // A new device, which finds the forged header pinned, still opens the real vault with its password.
        val fresh = VaultManager(sodium, VaultStore(temp.newFolder()), { 42 }, EmptyCoroutineContext)
        val freshStorage = cloud.device()
        fresh.locate(freshStorage)
        assertTrue(fresh.unlockWithPassword("the password of the real vault", freshStorage))
        assertEquals(knownId, (fresh.state.value as VaultManager.State.Unlocked).vault.masterId)
    }

    @Test
    fun forgedHeadersWithTheVaultsMasterIdCostOneUnlockOnly() = runTest {
        // Audit 1, M3: after one unlock, the header that opened is tried first.
        val cloud = FakeCloud()
        val storage = cloud.device()
        val owner = manager()
        owner.locate(storage)
        owner.publish(storage, owner.prepareNew("the password of the real vault"))
        owner.lockVault()
        val header = storage.pinnedMessages().single().let { FoundHeader.from(it)!!.header }
        // Pinned too, as anyone in the account can, so the device does see them.
        repeat(3) { storage.pin(storage.sendHeader(header.copy(salt = PasswordKdf.newSalt(sodium), createdAt = 100L + it)).id) }

        val counting = CountingSodium(sodium)
        val device = VaultManager(counting, VaultStore(temp.newFolder()), { 42 }, EmptyCoroutineContext)
        val deviceStorage = cloud.device()
        device.locate(deviceStorage)
        assertTrue(device.unlockWithPassword("the password of the real vault", deviceStorage))
        device.lockVault()
        val before = counting.argonRuns
        assertTrue(device.unlockWithPassword("the password of the real vault", deviceStorage))
        assertEquals(1, counting.argonRuns - before)
    }

    @Test
    fun theDeviceThatMadeTheVaultTriesItsOwnHeaderFirst() = runTest {
        // Audit 2, L-a: publishing did not note which header the password opens, so forged ones came first.
        val storage = FakeCloud().device()
        val store = VaultStore(temp.newFolder())
        val counting = CountingSodium(sodium)
        val manager = VaultManager(counting, store, { 42 }, EmptyCoroutineContext)
        manager.locate(storage)
        manager.publish(storage, manager.prepareNew("the password of the real vault"))
        val header = storage.pinnedMessages().single().let { FoundHeader.from(it)!!.header }
        repeat(3) { storage.pin(storage.sendHeader(header.copy(salt = PasswordKdf.newSalt(sodium), createdAt = 100L + it)).id) }
        manager.lockVault()

        val before = counting.argonRuns
        assertTrue(manager.unlockWithPassword("the password of the real vault", storage))
        assertEquals(1, counting.argonRuns - before)
    }

    @Test
    fun theRecoveryKeyBringsBackAVaultWhoseHeaderIsGone() = runTest {
        // Audit 1, M1: the one header deleted in the Telegram app, every file still there.
        val cloud = FakeCloud()
        val storage = cloud.device()
        val owner = manager()
        owner.locate(storage)
        val created = owner.prepareNew("the password of the vault")
        val words = created.recoveryWords
        val masterId = created.vault.masterId
        owner.publish(storage, created)
        storage.delete(cloud.allMessages().filter { FoundHeader.from(it) != null }.map { it.id })

        val device = VaultManager(sodium, VaultStore(temp.newFolder()), { 42 }, EmptyCoroutineContext)
        val deviceStorage = cloud.device()
        device.locate(deviceStorage)
        assertEquals(VaultManager.State.NotFound, device.state.value)
        assertTrue(device.unlockWithRecoveryKey(words, deviceStorage) is VaultUnlock.MnemonicResult.OtherVault)

        // Even with a header of someone else's pinned in the way.
        deviceStorage.pin(deviceStorage.sendHeader(newVault("someone else's password").header).id)
        device.publish(deviceStorage, device.prepareRestore(words, "a new password after the loss"), restoring = true)
        assertEquals(masterId, (device.state.value as VaultManager.State.Unlocked).vault.masterId)

        // The same vault, now opened by the new password and still by the same recovery key, anywhere.
        val other = VaultManager(sodium, VaultStore(temp.newFolder()), { 42 }, EmptyCoroutineContext)
        val otherStorage = cloud.device()
        other.locate(otherStorage)
        assertTrue(other.unlockWithPassword("a new password after the loss", otherStorage))
        assertEquals(masterId, (other.state.value as VaultManager.State.Unlocked).vault.masterId)
        other.lockVault()
        assertTrue(other.unlockWithRecoveryKey(words, otherStorage) is VaultUnlock.MnemonicResult.Unlocked)
    }

    @Test
    fun aForgedHeaderOfAnotherVaultDoesNotStopTheRecoveryKey() = runTest {
        val cloud = FakeCloud()
        val storage = cloud.device()
        val owner = manager()
        owner.locate(storage)
        val created = owner.prepareNew("the password of the real vault")
        val words = created.recoveryWords
        owner.publish(storage, created)
        val forged = storage.sendHeader(newVault("the forger's own password").header)
        storage.pin(forged.id)

        val fresh = VaultManager(sodium, VaultStore(temp.newFolder()), { 42 }, EmptyCoroutineContext)
        val freshStorage = cloud.device()
        fresh.locate(freshStorage)
        val result = fresh.unlockWithRecoveryKey(words, freshStorage)
        assertTrue(result is VaultUnlock.MnemonicResult.Unlocked)
    }

    @Test
    fun swappingThePinForAForgedHeaderIsNotADeletion() = runTest {
        // Audit 1, M2: unpin the real header, pin a forged one, then delete the forged one. The real
        // header is still there, so this device must not forget the vault as if deleted elsewhere.
        val cloud = FakeCloud()
        val (here, _, storages) = twoDevices(cloud, "the password of the real vault")
        val (hereStorage, thereStorage) = storages
        here.lockVault()
        val realHeader = thereStorage.pinnedMessages().single()
        thereStorage.unpin(realHeader.id)
        val forged = thereStorage.sendHeader(newVault("the forger's own password").header)
        thereStorage.pin(forged.id)
        here.locate(hereStorage)
        thereStorage.delete(listOf(forged.id))
        here.headersChanged(hereStorage)
        here.locate(hereStorage)

        assertNull(here.deleted.value)
        assertTrue(here.state.value is VaultManager.State.Locked)
        assertTrue(here.unlockWithPassword("the password of the real vault", hereStorage))
    }

    /** Deletes every header of [here]'s vault from Saved Messages, as the deletion does last (SPEC §5.9). */
    private suspend fun deleteHeaders(cloud: FakeCloud, storage: StorageBackend) {
        storage.delete(cloud.allMessages().filter { FoundHeader.from(it) != null }.map { it.id })
    }

    @Test
    fun theDeviceThatDeletesForgetsTheVaultAndKeepsItsSettings() = runTest {
        val cloud = FakeCloud()
        val (here, there, storages) = twoDevices(cloud, "password of a vault to delete")
        val store = VaultStore(temp.root)
        store.autoLockMinutes = 15
        here.delete { masterId ->
            // Its own deletes come back as events while it runs: not taken for a deletion elsewhere.
            deleteHeaders(cloud, storages.first)
            here.headersChanged(storages.first)
            assertTrue(here.state.value is VaultManager.State.Unlocked)
            assertEquals((there.state.value as VaultManager.State.Unlocked).vault.masterId, masterId)
        }
        assertEquals(VaultManager.State.NotFound, here.state.value)
        assertEquals(VaultManager.Deleted.HERE, here.deleted.value)
        // Gone from this device too, and a fresh start finds nothing; the settings are not the vault's.
        assertTrue(store.headers().isEmpty())
        assertEquals(15, VaultStore(temp.root).autoLockMinutes)
        assertEquals(VaultManager.State.Unknown, manager(VaultStore(temp.root)).state.value)
        // A new vault clears the word.
        here.publish(storages.first, here.prepareNew("password of the next vault"))
        assertNull(here.deleted.value)
    }

    @Test
    fun aDeletionThatFailsLeavesTheVaultOpen() = runTest {
        val cloud = FakeCloud()
        val (here, _, _) = twoDevices(cloud, "password of a vault to keep")
        try {
            here.delete { throw IllegalStateException("Telegram went away") }
        } catch (_: IllegalStateException) {
        }
        assertTrue(here.state.value is VaultManager.State.Unlocked)
        assertNull(here.deleted.value)
        here.lockVault()
        assertTrue(here.unlockWithPassword("password of a vault to keep"))
    }

    @Test
    fun anOpenVaultDeletedElsewhereIsForgottenHere() = runTest {
        val cloud = FakeCloud()
        val (here, there, storages) = twoDevices(cloud, "password of a vault deleted there")
        here.delete { deleteHeaders(cloud, storages.first) }
        there.headersChanged(storages.second)
        assertEquals(VaultManager.State.NotFound, there.state.value)
        assertEquals(VaultManager.Deleted.ELSEWHERE, there.deleted.value)
        assertFalse(there.unlockWithPassword("password of a vault deleted there"))
    }

    @Test
    fun aDeviceThatWasOffFindsOutAtItsNextStart() = runTest {
        val cloud = FakeCloud()
        val (here, there, storages) = twoDevices(cloud, "password of a vault deleted meanwhile")
        there.lockVault()
        here.delete { deleteHeaders(cloud, storages.first) }
        // No event reached it: the search at its next start is where it learns.
        assertTrue(there.state.value is VaultManager.State.Locked)
        there.locate(storages.second)
        assertEquals(VaultManager.State.NotFound, there.state.value)
        assertEquals(VaultManager.Deleted.ELSEWHERE, there.deleted.value)
    }

    @Test
    fun anEmptyAnswerFromTelegramDoesNotWipeTheVault() = runTest {
        val cloud = FakeCloud()
        val (_, there, storages) = twoDevices(cloud, "password of a vault still there")
        there.lockVault()
        // Offline, Telegram can answer the search with nothing at all; the headers are still there.
        val blank = object : StorageBackend by storages.second {
            override suspend fun pinnedMessages(): List<StoredMessage> = emptyList()
            override suspend fun historyBefore(before: MessageId?, limit: Int): List<StoredMessage> = emptyList()
        }
        there.locate(blank)
        there.headersChanged(blank)
        assertTrue(there.state.value is VaultManager.State.Locked)
        assertNull(there.deleted.value)
        assertTrue(there.unlockWithPassword("password of a vault still there"))
    }

    @Test
    fun aPasswordChangeElsewhereIsNotADeletion() = runTest {
        val cloud = FakeCloud()
        val (here, there, storages) = twoDevices(cloud, "old password before a change")
        here.changePassword(storages.first, "old password before a change", "new password after the change")
        there.headersChanged(storages.second)
        assertNull(there.deleted.value)
        assertTrue((there.state.value as VaultManager.State.Locked).passwordChangedElsewhere)
    }

    @Test
    fun theDeviceThatChangedThePasswordStaysOpen() = runTest {
        val cloud = FakeCloud()
        val (here, _, storages) = twoDevices(cloud, "old password on both devices")
        here.changePassword(storages.first, "old password on both devices", "new password changed over here")
        // Its own new header and the delete of the old one come back as events.
        here.headersChanged(storages.first)
        assertTrue(here.state.value is VaultManager.State.Unlocked)
    }

    @Test
    fun aHeaderCheckThatStartedBeforeOwnChangeDoesNotUndoIt() = runTest {
        val cloud = FakeCloud()
        val (here, _, storages) = twoDevices(cloud, "old password on both devices")
        // An event set off a check; it has read the pinned headers (the old one) and is on its way back.
        val gate = CompletableDeferred<Unit>()
        val slow = object : StorageBackend by storages.first {
            override suspend fun pinnedMessages(): List<StoredMessage> = storages.first.pinnedMessages().also { gate.await() }
        }
        val check = launch { here.headersChanged(slow) }
        runCurrent()
        here.changePassword(storages.first, "old password on both devices", "new password changed over here")
        gate.complete(Unit)
        check.join()
        assertTrue(here.state.value is VaultManager.State.Unlocked)
        here.lockVault()
        assertFalse(here.unlockWithPassword("old password on both devices"))
        assertTrue(here.unlockWithPassword("new password changed over here"))
    }

    @Test
    fun aNewHeaderAloneLeavesTheVaultOpen() = runTest {
        val cloud = FakeCloud()
        val (here, there, storages) = twoDevices(cloud, "old password on both devices")
        // A change cut short over there: the new header is pinned, the old one still in place. Both work.
        val cut = object : StorageBackend by storages.first {
            override suspend fun delete(ids: Collection<MessageId>) = throw StorageException("connection lost")
        }
        runCatching { here.changePassword(cut, "old password on both devices", "new password cut short") }
        there.headersChanged(storages.second)
        assertTrue(there.state.value is VaultManager.State.Unlocked)
    }

    @Test
    fun withoutAnAnswerFromTelegramTheKnownHeadersAreUsed() = runTest {
        val cloud = FakeCloud()
        val (_, there, storages) = twoDevices(cloud, "old password on both devices")
        there.lockVault()
        // Telegram never answers: after a short wait the headers this device knows are tried.
        val silent = object : StorageBackend by storages.second {
            override suspend fun pinnedMessages(): List<StoredMessage> = kotlinx.coroutines.awaitCancellation()
        }
        assertTrue(there.unlockWithPassword("old password on both devices", silent))
    }

    @Test
    fun aChangeCutShortKeepsBothPasswordsWorking() = runTest {
        val cloud = FakeCloud()
        val storage = cloud.device()
        val manager = manager()
        manager.locate(storage)
        manager.publish(storage, manager.prepareNew("old password before cut"))
        // The connection drops right after the new header is pinned, before the old one is deleted.
        val cut = object : StorageBackend by storage {
            override suspend fun delete(ids: Collection<MessageId>) = throw StorageException("connection lost")
        }
        try {
            manager.changePassword(cut, "old password before cut", "new password before cut")
            fail("the delete should have failed")
        } catch (_: StorageException) {
        }
        assertEquals(2, cloud.allMessages().mapNotNull(FoundHeader::from).size)
        val fresh = VaultManager(sodium, VaultStore(temp.newFolder()), { 42 }, EmptyCoroutineContext)
        fresh.locate(cloud.device())
        assertTrue(fresh.unlockWithPassword("new password before cut"))
        fresh.lockVault()
        assertTrue(fresh.unlockWithPassword("old password before cut"))
    }

    @Test
    fun aRefreshThatStartedBeforeAPasswordChangeDoesNotUndoIt() = runTest {
        val cloud = FakeCloud()
        val storage = cloud.device()
        val manager = manager()
        manager.locate(storage)
        manager.publish(storage, manager.prepareNew("password before the change"))
        // The refresh at startup has read the pinned headers (the old one) and is still on its way back.
        val gate = CompletableDeferred<Unit>()
        val slow = object : StorageBackend by storage {
            override suspend fun pinnedMessages(): List<StoredMessage> = storage.pinnedMessages().also { gate.await() }
        }
        val refresh = launch { manager.locate(slow) }
        runCurrent()
        assertEquals(VaultManager.PasswordChange.Changed, manager.changePassword(storage, "password before the change", "password after the change"))
        gate.complete(Unit)
        refresh.join()

        // The new header is what this device remembers: the new password opens the vault without the network.
        manager.lockVault()
        val again = manager(VaultStore(temp.root))
        assertTrue(again.unlockWithPassword("password after the change"))
        assertEquals(storage.pinnedMessages().single().id, VaultStore(temp.root).headers().first().messageId)
    }

    @Test
    fun passwordChangedElsewhereIsPickedUp() = runTest {
        val cloud = FakeCloud()
        val here = cloud.device()
        val vault = newVault("password before change")
        VaultHeaders.publish(here, vault)
        val manager = manager()
        manager.locate(here)

        // Another device changes the password: new header, pinned, old one deleted (SPEC §4.1).
        val there = cloud.device()
        val old = there.pinnedMessages().single()
        val changed = there.sendHeader(rewrapped(vault, "password after change"))
        there.pin(changed.id)
        there.delete(listOf(old.id))

        assertFalse(manager.unlockWithPassword("password after change"))
        assertTrue(manager.unlockWithPassword("password after change", here))
    }

    @Test
    fun passwordChangedElsewhereWhileUnlockedIsRemembered() = runTest {
        val cloud = FakeCloud()
        val here = cloud.device()
        val vault = newVault("password before change")
        VaultHeaders.publish(here, vault)
        val manager = manager()
        manager.locate(here)
        assertTrue(manager.unlockWithPassword("password before change"))

        val there = cloud.device()
        val old = there.pinnedMessages().single()
        val changed = there.sendHeader(rewrapped(vault, "password after change"))
        there.pin(changed.id)
        there.delete(listOf(old.id))

        // A refresh while unlocked keeps the vault open and stores the new header for next time,
        // so the new password works offline after locking.
        manager.locate(here)
        assertTrue(manager.state.value is VaultManager.State.Unlocked)
        manager.lockVault()
        assertTrue(manager(VaultStore(temp.root)).unlockWithPassword("password after change"))
    }

    @Test
    fun recoveryKeyUnlocks() = runTest {
        val storage = FakeCloud().device()
        val vault = newVault("password nobody remembers")
        val words = vault.recoveryWords
        VaultHeaders.publish(storage, vault)
        val manager = manager()
        manager.locate(storage)
        assertTrue(manager.unlockWithRecoveryKey(words) is VaultUnlock.MnemonicResult.Unlocked)
        assertTrue(manager.state.value is VaultManager.State.Unlocked)
    }

    @Test
    fun deviceKeyMustBelongToThisVault() = runTest {
        val storage = FakeCloud().device()
        val vault = newVault("device key password")
        val master = vault.vault.withMasterKey { it.copy() }
        VaultHeaders.publish(storage, vault)
        val manager = manager()
        manager.locate(storage)
        assertFalse(manager.unlockWithDeviceKey(MasterKeys.generate(sodium)))
        assertTrue(manager.unlockWithDeviceKey(master))
    }
}

class PasswordPolicyTest {
    @Test
    fun weakPasswordsAreRefused() {
        assertFalse(PasswordPolicy.check("password123").acceptable)
        assertFalse(PasswordPolicy.check("qwertyuiop").acceptable)
        // Strong but short.
        assertFalse(PasswordPolicy.check("Xq7#vB2!m").acceptable)
        assertTrue(PasswordPolicy.check("Xq7#vB2!m").score >= 3)
        assertTrue(PasswordPolicy.check("plum orbit tundra 47 glass").acceptable)
        // Audit 1, M8: three unrelated words will do; zxcvbn's top score alone does not.
        assertTrue(PasswordPolicy.check("plum orbit tundra").acceptable)
        val topScore = PasswordPolicy.check("MySecretPass2024")
        assertEquals(4, topScore.score)
        assertFalse(topScore.acceptable)
        // The meter does not say "strong" over a password it refuses.
        assertEquals(3, topScore.meterScore)
        assertEquals(4, PasswordPolicy.check("plum orbit tundra").meterScore)
        // Long enough, but only "somewhat guessable" (score 2): refused. Scores checked in the test so the
        // fixtures cannot drift with a zxcvbn update.
        for (fair in listOf("correcthorse", "Summer2020!")) {
            val check = PasswordPolicy.check(fair)
            assertEquals(fair, 2, check.score)
            assertTrue(check.longEnough)
            assertFalse(check.acceptable)
        }
        assertFalse(PasswordPolicy.check("").acceptable)
    }

    @Test
    fun theVaultRefusesAWeakPasswordWhateverTheScreenDid() = runTest {
        // Audit 1, CRYPTO-10: the policy is checked in the core too, not only on screen.
        val manager = VaultManager(sodium, VaultStore(temp.newFolder()), { 42 }, EmptyCoroutineContext)
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { manager.prepareNew("Summer2020!") } }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { manager.prepareRestore(Bip39.toMnemonic(MasterKeys.generate(sodium)), "Summer2020!") }
        }
        val storage = FakeCloud().device()
        manager.locate(storage)
        manager.publish(storage, manager.prepareNew("plum orbit tundra 47 glass"))
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { manager.changePassword(storage, "plum orbit tundra 47 glass", "Summer2020!") }
        }
    }

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun weakPasswordsGetAdvice() {
        val check = PasswordPolicy.check("password")
        assertTrue(check.warning != null || check.suggestions.isNotEmpty())
    }
}

class RecoveryQuizTest {
    private val words = Bip39.toMnemonic(MasterKeys.generate(sodium))

    @Test
    fun asksThreeDistinctPositions() {
        repeat(50) {
            val quiz = RecoveryQuiz(words)
            assertEquals(3, quiz.positions.toSet().size)
            assertEquals(quiz.positions.sorted(), quiz.positions)
            assertTrue(quiz.positions.all { it in 0 until 24 })
        }
    }

    @Test
    fun checksAnswers() {
        val quiz = RecoveryQuiz(words)
        val right = quiz.positions.map { words[it] }
        assertTrue(quiz.check(right))
        assertTrue(quiz.check(right.map { " ${it.uppercase()} " }))
        assertFalse(quiz.check(right.reversed().takeIf { it != right } ?: right.map { it + "x" }))
        assertFalse(quiz.check(right.dropLast(1)))
        assertFalse(quiz.check(right.mapIndexed { i, w -> if (i == 0) "$w $w" else w }))
    }
}

class VaultStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun headersAndPreferencesSurvive() = runTest {
        val store = VaultStore(temp.root)
        assertEquals(5, store.autoLockMinutes)
        store.autoLockMinutes = null
        assertNull(VaultStore(temp.root).autoLockMinutes)
        store.autoLockMinutes = 15
        assertEquals(15, VaultStore(temp.root).autoLockMinutes)

        val storage = FakeCloud().device()
        val published = VaultHeaders.publish(storage, newVault("stored header password"))
        store.saveHeaders(listOf(published))
        assertEquals(listOf(published), VaultStore(temp.root).headers())

        store.clear()
        assertEquals(emptyList<FoundHeader>(), VaultStore(temp.root).headers())
        assertEquals(5, VaultStore(temp.root).autoLockMinutes)
    }

    @Test
    fun switchesHaveTheirDefaultsAndSurvive() {
        val store = VaultStore(temp.root)
        // SPEC §9 and §5.8: screenshots allowed, weekly check on.
        assertFalse(store.blockScreenshots)
        assertTrue(store.weeklyCheck)
        assertFalse(store.debugTools)
        // SPEC §4.5: sizes hidden unless turned off.
        assertTrue(store.hideSizes)
        val before = store.changes.value
        store.blockScreenshots = true
        store.weeklyCheck = false
        store.debugTools = true
        store.hideSizes = false
        assertEquals(before + 4, store.changes.value)
        val reread = VaultStore(temp.root)
        assertTrue(reread.blockScreenshots)
        assertFalse(reread.weeklyCheck)
        assertTrue(reread.debugTools)
        assertFalse(reread.hideSizes)
        store.clear()
        assertFalse(VaultStore(temp.root).blockScreenshots)
        assertTrue(VaultStore(temp.root).weeklyCheck)
    }
}
