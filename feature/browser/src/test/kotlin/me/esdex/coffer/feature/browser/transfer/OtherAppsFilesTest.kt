package me.esdex.coffer.feature.browser.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 3, AND3-3: Coffer's own proxy descriptors, handed back by another app, are not read in process. */
class OtherAppsFilesTest {
    @Test
    fun onlyCoffersOwnAppfuseMountIsRefused() {
        assertTrue(OtherAppsFiles.isOwnProxy("/mnt/appfuse/10084_50/7", 10084))
        // Another app's proxy is served by that app's process: read here, nothing hangs.
        assertFalse(OtherAppsFiles.isOwnProxy("/mnt/appfuse/10085_50/7", 10084))
        assertFalse(OtherAppsFiles.isOwnProxy("/mnt/appfuse/100840_50/7", 10084))
        assertFalse(OtherAppsFiles.isOwnProxy("/storage/emulated/0/Download/a.txt", 10084))
        assertFalse(OtherAppsFiles.isOwnProxy("/data/user/0/com.other/files/10084_50", 10084))
    }
}
