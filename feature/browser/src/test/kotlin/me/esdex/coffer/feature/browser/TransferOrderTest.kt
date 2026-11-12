package me.esdex.coffer.feature.browser

import me.esdex.coffer.feature.browser.transfer.TransferRegistry.Failure
import me.esdex.coffer.feature.browser.transfer.TransferRegistry.Phase
import me.esdex.coffer.feature.browser.transfer.TransferRegistry.Progress
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Transfers screen: failures first, then what runs, then what waits, each in the queue's own order. */
class TransferOrderTest {

    private fun item(id: String, progress: Progress? = null, failure: Failure? = null, preparing: Boolean = false) =
        TransferItem(id, isUpload = false, name = id, progress = progress, failure = failure, preparing = preparing)

    @Test
    fun failuresThenRunningThenWaiting() {
        val list = listOf(
            item("waiting"),
            item("queued", Progress(0, 0, Phase.QUEUED)),
            item("moving", Progress(5, 10, Phase.MOVING)),
            item("failed", failure = Failure.MISSING_PART),
            item("encrypting", Progress(0, 10, Phase.PREPARING)),
            item("preparing", preparing = true),
            // Failed while it was moving: the failure is what counts.
            item("failed2", Progress(5, 10, Phase.MOVING), Failure.NO_SPACE),
        )
        assertEquals(
            listOf("failed", "failed2", "moving", "encrypting", "preparing", "waiting", "queued"),
            list.inQueueOrder().map { it.id },
        )
    }
}
