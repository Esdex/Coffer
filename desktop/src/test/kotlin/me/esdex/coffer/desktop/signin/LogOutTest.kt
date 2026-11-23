package me.esdex.coffer.desktop.signin

import me.esdex.coffer.core.sync.TransferStage
import me.esdex.coffer.desktop.vault.Drafts
import me.esdex.coffer.desktop.vault.VaultWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogOutTest {
    private fun upload() = Drafts.Progress("a.jpg", 1, 2, TransferStage.MOVING)

    @Test
    fun nothingWaitingSaysOnlyWhatIsForgotten() {
        val text = logOutText(VaultWriter.Transfers.NONE, unsentFiles = false)
        assertEquals(1, text.size)
        assertTrue(text[0].contains("stay in Saved Messages"))
    }

    @Test
    fun whatWouldBeLostIsCounted() {
        assertEquals("1 file has not reached Telegram yet and will be lost.", logOutText(VaultWriter.Transfers(listOf(upload()), 0, 0), true)[1])
        assertEquals(
            "3 files and 2 changes have not reached Telegram yet and will be lost.",
            logOutText(VaultWriter.Transfers(listOf(upload()), 2, 2), true)[1],
        )
        assertEquals("1 change has not reached Telegram yet and will be lost.", logOutText(VaultWriter.Transfers(emptyList(), 0, 1), false)[1])
    }

    @Test
    fun aLockedVaultWithUnsentFilesStillWarns() {
        assertEquals(
            "Files written on this computer that have not reached Telegram yet will be lost.",
            logOutText(VaultWriter.Transfers.NONE, unsentFiles = true)[1],
        )
    }
}
