package me.esdex.coffer.desktop.tray

import me.esdex.coffer.core.sync.TransferStage
import me.esdex.coffer.desktop.vault.Drafts
import me.esdex.coffer.desktop.vault.VaultWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrayViewTest {
    private fun unlocked(transfers: VaultWriter.Transfers = VaultWriter.Transfers.NONE, catchingUp: Int? = null) =
        TrayView.of(TrayState(signedIn = true, vault = TrayState.Vault.UNLOCKED, catchingUp = catchingUp, transfers = transfers))

    private val mb = 1024L * 1024

    @Test
    fun lockedSignedOutAndSearchingSayWhereThingsStand() {
        assertEquals(TrayView("Not signed in to Telegram", emptyList(), TrayView.Lock.NONE), TrayView.of(TrayState(false, TrayState.Vault.LOCKED, null, VaultWriter.Transfers.NONE)))
        assertEquals(TrayView("Locked", emptyList(), TrayView.Lock.UNLOCK), TrayView.of(TrayState(true, TrayState.Vault.LOCKED, null, VaultWriter.Transfers.NONE)))
        assertEquals(TrayView.Lock.NONE, TrayView.of(TrayState(true, TrayState.Vault.LOOKING, null, VaultWriter.Transfers.NONE)).lock)
    }

    @Test
    fun nothingGoingOnIsUpToDate() {
        assertEquals(TrayView("Up to date", listOf("Up to date"), TrayView.Lock.LOCK), unlocked())
    }

    @Test
    fun oneUploadShowsItsNameShareAndSizes() {
        val view = unlocked(VaultWriter.Transfers(listOf(Drafts.Progress("video.mp4", 82 * mb, 182 * mb, TransferStage.MOVING)), 0, 0))
        assertEquals("Uploading video.mp4: 45% (82 of 182 MB)", view.status)
        assertEquals(listOf(view.status), view.lines)
    }

    @Test
    fun severalUploadsAndOperationsAreCounted() {
        val view = unlocked(
            VaultWriter.Transfers(listOf(Drafts.Progress("a.jpg", 0, 1000, TransferStage.PREPARING)), waiting = 2, operations = 3),
            catchingUp = 40,
        )
        assertEquals("Uploading 3 files", view.status)
        assertEquals(listOf("Preparing a.jpg", "2 more files waiting", "Sending 3 changes", "Reading what changed: 40 messages"), view.lines)
    }

    @Test
    fun singularsAreSingular() {
        assertEquals(listOf("1 file waiting", "Sending 1 change"), unlocked(VaultWriter.Transfers(emptyList(), 1, 1)).lines)
        assertEquals("Sending 1 change", unlocked(VaultWriter.Transfers(emptyList(), 0, 1)).status)
    }

    @Test
    fun sizesUseTheUnitOfTheWhole() {
        assertEquals("0 of 500 bytes", TrayView.sizes(0, 500))
        assertEquals("1 of 2 KB", TrayView.sizes(1500, 2048))
        assertEquals("1.5 of 3.0 GB", TrayView.sizes(3L * 512 * mb, 3L * 1024 * mb))
    }

    @Test
    fun longNamesKeepTheirStartAndEnd() {
        val short = TrayView.short("a very long name of a holiday video from the summer.mp4")
        assertEquals(40, short.length)
        assertTrue(short, short.startsWith("a very long") && short.endsWith("summer.mp4"))
        assertEquals("short.txt", TrayView.short("short.txt"))
    }
}
