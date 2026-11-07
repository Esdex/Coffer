package me.esdex.coffer.debug

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.Progress
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageBackendContract
import me.esdex.coffer.core.storage.StoredMessage
import me.esdex.coffer.core.tdlib.TdlibStorageBackend
import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File
import java.util.Collections

/**
 * Debug builds only: runs the StorageBackend contract against the signed-in account's real Saved
 * Messages, then deletes every message it created. Telegram's test servers no longer accept new
 * test accounts, so this is how TdlibStorageBackend gets checked against the real thing.
 *
 * Both "devices" of the contract are this one session, so cross-device delivery is checked by hand.
 */
class StorageSelfCheck(private val backend: TdlibStorageBackend, private val scratch: File) {

    data class TestResult(val name: String, val failure: String?)

    data class Report(val results: List<TestResult>, val deleted: Int, val leftOver: Int)

    suspend fun run(): Report = withContext(Dispatchers.IO) {
        val before = backend.historyBefore(null, 1).firstOrNull()?.id
        val recording = RecordingBackend(backend)
        scratch.deleteRecursively()
        scratch.mkdirs()
        target = recording
        targetScratch = scratch

        val results = Collections.synchronizedList(mutableListOf<TestResult>())
        val failures = Collections.synchronizedMap(mutableMapOf<Description, Failure>())
        val core = JUnitCore()
        core.addListener(object : RunListener() {
            override fun testFailure(failure: Failure) {
                failures[failure.description] = failure
            }

            override fun testFinished(description: Description) {
                val failure = failures[description]
                results += TestResult(description.methodName, failure?.let { "${it.exception}" })
            }
        })
        core.run(Contract::class.java)

        // Telegram adds a service message for every pin; those go too.
        val notices = before?.let { backend.pinNoticesAfter(it) }.orEmpty()
        val created = recording.sent.toList() + notices
        backend.delete(created)
        val leftOver = created.count { backend.message(it) != null }
        scratch.deleteRecursively()
        Report(results.toList(), deleted = created.size - leftOver, leftOver = leftOver)
    }

    /** JUnit builds the test class itself, so it reaches the backend through these. */
    class Contract : StorageBackendContract() {
        override val device: StorageBackend get() = target
        override val otherDevice: StorageBackend get() = target
        override val scratch: File get() = targetScratch
    }

    private class RecordingBackend(private val delegate: StorageBackend) : StorageBackend by delegate {
        val sent: MutableSet<MessageId> = Collections.synchronizedSet(mutableSetOf())

        override suspend fun sendText(text: String): StoredMessage =
            delegate.sendText(text).also { sent += it.id }

        override suspend fun sendBlob(file: File, caption: String, onProgress: (Progress) -> Unit): StoredMessage =
            delegate.sendBlob(file, caption, onProgress).also { sent += it.id }
    }

    private companion object {
        @Volatile
        lateinit var target: StorageBackend

        @Volatile
        lateinit var targetScratch: File
    }
}
