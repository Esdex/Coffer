package me.esdex.coffer.desktop.tray

import me.esdex.coffer.core.sync.TransferStage
import me.esdex.coffer.desktop.vault.Drafts
import me.esdex.coffer.desktop.vault.VaultWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrayViewTest {
    private fun unlocked(transfers: VaultWriter.Transfers = VaultWriter.Transfers.NONE, catchingUp: Int? = null, link: TrayState.Link = TrayState.Link.UP) =
        TrayView.of(TrayState(signedIn = true, vault = TrayState.Vault.UNLOCKED, catchingUp = catchingUp, transfers = transfers, link = link))

    private fun vault(vault: TrayState.Vault, link: TrayState.Link) = TrayView.of(TrayState(true, vault, null, VaultWriter.Transfers.NONE, link))

    private val mb = 1024L * 1024

    @Test
    fun lockedSignedOutAndSearchingSayWhereThingsStand() {
        assertEquals(TrayView("Not signed in to Telegram", emptyList(), TrayView.Lock.NONE, click = TrayView.Click.SIGN_IN), TrayView.of(TrayState(false, TrayState.Vault.LOCKED, null, VaultWriter.Transfers.NONE)))
        assertEquals(TrayView("Locked", emptyList(), TrayView.Lock.UNLOCK, click = TrayView.Click.UNLOCK), TrayView.of(TrayState(true, TrayState.Vault.LOCKED, null, VaultWriter.Transfers.NONE)))
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
    fun withoutNetworkTheTooltipSaysSoAndTheTransfersWait() {
        val upload = VaultWriter.Transfers(listOf(Drafts.Progress("video.mp4", 82 * mb, 182 * mb, TransferStage.MOVING)), 0, 2)
        val view = unlocked(upload, link = TrayState.Link.WAITING_FOR_NETWORK)
        assertEquals("Waiting for network", view.status)
        assertEquals(listOf("Waiting for network", "Uploading video.mp4: 45% (82 of 182 MB)", "Sending 2 changes"), view.lines)
        assertEquals(TrayIcons.Look.OFFLINE, view.icon)
        assertEquals(TrayView("Waiting for network", listOf("Waiting for network"), TrayView.Lock.LOCK, offline = true), unlocked(link = TrayState.Link.WAITING_FOR_NETWORK))
        assertEquals("Connecting to Telegram", unlocked(link = TrayState.Link.CONNECTING).status)
        assertEquals(TrayIcons.Look.OFFLINE, unlocked(link = TrayState.Link.CONNECTING).icon)
    }

    @Test
    fun aLockedVaultStaysGreyAndSaysItInTheMenu() {
        val view = vault(TrayState.Vault.LOCKED, TrayState.Link.WAITING_FOR_NETWORK)
        assertEquals(TrayView("Locked", listOf("Waiting for network"), TrayView.Lock.UNLOCK, offline = true, click = TrayView.Click.UNLOCK), view)
        assertEquals(TrayIcons.Look.LOCKED, view.icon)
        assertEquals("Waiting for network", vault(TrayState.Vault.LOOKING, TrayState.Link.WAITING_FOR_NETWORK).status)
        assertEquals(listOf("Connecting to Telegram"), vault(TrayState.Vault.NONE, TrayState.Link.CONNECTING).lines)
        assertEquals(TrayView("Locked", emptyList(), TrayView.Lock.UNLOCK, click = TrayView.Click.UNLOCK), vault(TrayState.Vault.LOCKED, TrayState.Link.UP))
    }

    @Test
    fun notSignedInSaysNothingOfTheNetwork() {
        assertEquals(
            TrayView("Not signed in to Telegram", emptyList(), TrayView.Lock.NONE, click = TrayView.Click.SIGN_IN),
            TrayView.of(TrayState(false, TrayState.Vault.LOCKED, null, VaultWriter.Transfers.NONE, TrayState.Link.WAITING_FOR_NETWORK)),
        )
    }

    @Test
    fun aLeftClickOpensWhatStandsInTheWayOrElseTheMenu() {
        fun click(signedIn: Boolean, vault: TrayState.Vault) = TrayView.of(TrayState(signedIn, vault, null, VaultWriter.Transfers.NONE)).click
        assertEquals(TrayView.Click.SIGN_IN, click(false, TrayState.Vault.UNLOCKED))
        assertEquals(TrayView.Click.UNLOCK, click(true, TrayState.Vault.LOCKED))
        assertEquals(TrayView.Click.MENU, click(true, TrayState.Vault.UNLOCKED))
        assertEquals(TrayView.Click.MENU, click(true, TrayState.Vault.LOOKING))
        assertEquals(TrayView.Click.MENU, click(true, TrayState.Vault.NONE))
        assertEquals(TrayView.Click.UNLOCK, vault(TrayState.Vault.LOCKED, TrayState.Link.WAITING_FOR_NETWORK).click)
    }

    @Test
    fun theIconSaysWhereThingsStand() {
        assertEquals(TrayIcons.Look.OPEN, unlocked().icon)
        assertEquals(TrayIcons.Look.BUSY, unlocked(VaultWriter.Transfers(emptyList(), 0, 1)).icon)
        assertEquals(TrayIcons.Look.BUSY, unlocked(catchingUp = 5).icon)
        assertEquals(TrayIcons.Look.BUSY, unlocked(VaultWriter.Transfers(listOf(Drafts.Progress("a", 1, 2, TransferStage.MOVING)), 0, 0)).icon)
        assertEquals(TrayIcons.Look.LOCKED, TrayView.of(TrayState(true, TrayState.Vault.LOCKED, null, VaultWriter.Transfers.NONE)).icon)
        assertEquals(TrayIcons.Look.LOCKED, TrayView.of(TrayState(false, TrayState.Vault.UNLOCKED, null, VaultWriter.Transfers.NONE)).icon)
    }

    @Test
    fun everyLookComesInEverySizeAndTheyDiffer() {
        val looks = TrayIcons.Look.entries.map { TrayIcons.of(it) }
        for (look in looks) assertEquals(listOf(16, 22, 24, 32, 48, 64, 128), look.map { it.width })
        val middle = looks.map { it.first { image -> image.width == 32 } }
        fun pixels(i: java.awt.image.BufferedImage) = (0 until 32).flatMap { y -> (0 until 32).map { x -> i.getRGB(x, y) } }
        assertEquals("four different looks", 4, middle.map(::pixels).toSet().size)
    }

    @Test
    fun monochromeIsTheChestByNameWithOnlyTheArrowInColour() {
        val path = "/icons"
        assertEquals(KdeTray.Look.Named("coffer-tray-symbolic", path), TrayIcons.tray(TrayIcons.Look.OPEN, monochrome = true, themePath = path))
        assertEquals(KdeTray.Look.Named("coffer-tray-locked-symbolic", path), TrayIcons.tray(TrayIcons.Look.LOCKED, monochrome = true, themePath = path))
        assertEquals(KdeTray.Look.Named("coffer-tray-busy-symbolic", path), TrayIcons.tray(TrayIcons.Look.BUSY, monochrome = true, themePath = path))
        assertEquals(KdeTray.Look.Named("coffer-tray-offline-symbolic", path), TrayIcons.tray(TrayIcons.Look.OFFLINE, monochrome = true, themePath = path))
        assertEquals(KdeTray.Look.Pictures(TrayIcons.of(TrayIcons.Look.BUSY)), TrayIcons.tray(TrayIcons.Look.BUSY, monochrome = false, themePath = path))
    }

    @Test
    fun theSymbolicIconsAreWrittenWhereThePanelLooks() {
        val dir = java.nio.file.Files.createTempDirectory("icons").toFile()
        try {
            assertEquals(dir.absolutePath, TrayIcons.install(dir))
            for (name in listOf("coffer-tray-symbolic", "coffer-tray-locked-symbolic", "coffer-tray-busy-symbolic", "coffer-tray-offline-symbolic")) {
                val svg = java.io.File(dir, "hicolor/scalable/apps/$name.svg")
                assertTrue(name, svg.isFile && java.io.File(dir, "$name.svg").isFile)
                // The class the panel paints in its text colour.
                val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(svg)
                assertEquals("ColorScheme-Text", (doc.getElementsByTagName("path").item(0) as org.w3c.dom.Element).getAttribute("class"))
            }
        } finally {
            dir.deleteRecursively()
        }
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
