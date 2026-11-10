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
    fun onlyTheNewestVaultsHeadersAreTried() = runTest {
        val storage = FakeCloud().device()
        storage.sendHeader(newVault("older vault password").header)
        storage.sendHeader(newVault("newer vault password").header)
        val headers = VaultHeaders.find(storage)
        assertNull(VaultUnlock.withPassword(sodium, headers, "older vault password"))
        assertNotNull(VaultUnlock.withPassword(sodium, headers, "newer vault password"))
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

        // A second launch remembers the header and starts locked, without a search.
        manager.lockVault()
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
        assertTrue(PasswordPolicy.check("Xq7#vB2!m").score >= PasswordPolicy.MIN_SCORE)
        assertTrue(PasswordPolicy.check("plum orbit tundra 47 glass").acceptable)
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
        val before = store.changes.value
        store.blockScreenshots = true
        store.weeklyCheck = false
        store.debugTools = true
        assertEquals(before + 3, store.changes.value)
        val reread = VaultStore(temp.root)
        assertTrue(reread.blockScreenshots)
        assertFalse(reread.weeklyCheck)
        assertTrue(reread.debugTools)
        store.clear()
        assertFalse(VaultStore(temp.root).blockScreenshots)
        assertTrue(VaultStore(temp.root).weeklyCheck)
    }
}
