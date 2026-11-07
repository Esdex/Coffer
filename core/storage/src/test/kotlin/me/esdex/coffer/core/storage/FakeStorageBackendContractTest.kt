package me.esdex.coffer.core.storage

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class FakeStorageBackendContractTest : StorageBackendContract() {
    @get:Rule
    val temp = TemporaryFolder()

    private val cloud = FakeCloud()
    override val device: StorageBackend = cloud.device()
    override val otherDevice: StorageBackend = cloud.device()
    override val scratch: File get() = temp.root
}

/** Same contract with one-message pages, so nothing passes by assuming full pages. */
class ShortPageFakeContractTest : StorageBackendContract() {
    @get:Rule
    val temp = TemporaryFolder()

    private val cloud = FakeCloud(pageCap = 1)
    override val device: StorageBackend = cloud.device()
    override val otherDevice: StorageBackend = cloud.device()
    override val scratch: File get() = temp.root
}
