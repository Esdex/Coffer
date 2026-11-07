package me.esdex.coffer.core.tdlib

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.esdex.coffer.core.storage.StorageBackend
import me.esdex.coffer.core.storage.StorageBackendContract
import me.esdex.coffer.core.tdlib.TestDc.signInByPhone
import me.esdex.coffer.core.tdlib.TestDc.signInByQr
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.runner.RunWith
import java.io.File

/** The StorageBackend contract against TDLib and Telegram's test servers, two sessions on one account. */
@RunWith(AndroidJUnit4::class)
class TdlibStorageContractTest : StorageBackendContract() {

    override val device: StorageBackend get() = first
    override val otherDevice: StorageBackend get() = second
    override val scratch: File get() = TestDc.scratch("blobs")

    companion object {
        private lateinit var firstSession: TelegramSession
        private lateinit var secondSession: TelegramSession
        private lateinit var first: TdlibStorageBackend
        private lateinit var second: TdlibStorageBackend

        @BeforeClass
        @JvmStatic
        fun signIn() = runBlocking {
            firstSession = TestDc.newSession("contract-a")
            firstSession.signInByPhone(TestDc.newPhoneNumber())
            secondSession = TestDc.newSession("contract-b")
            secondSession.signInByQr(firstSession)
            first = firstSession.storage()
            second = secondSession.storage()
        }

        @AfterClass
        @JvmStatic
        fun signOut() = runBlocking {
            if (::secondSession.isInitialized) runCatching { secondSession.logOut() }
            if (::firstSession.isInitialized) runCatching { firstSession.logOut() }
            Unit
        }
    }
}
