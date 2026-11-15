package me.esdex.coffer.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The bug report link from Settings, About: the form, with the version and the phone filled in. */
class BugReportUrlTest {
    private val form = "https://github.com/Esdex/Coffer/issues/new?template=bug_report.yml"

    @Test
    fun theVersionAndThePhoneAreFilledIn() {
        assertEquals(
            "$form&version=0.1.0&android=Android%2014%2C%20Google%20Pixel%208",
            bugReportUrl("0.1.0", "14", "Google", "Pixel 8"),
        )
    }

    @Test
    fun aMakerInTheModelIsNotSaidTwice() {
        assertEquals(
            "$form&version=0.1.0-debug&android=Android%2015%2C%20Nothing%20A063",
            bugReportUrl("0.1.0-debug", "15", "Nothing", "Nothing A063"),
        )
    }

    @Test
    fun whatCouldBreakTheLinkIsEncoded() {
        assertEquals(
            "$form&version=1%261%3D2&android=Android%2016%2C%20A%23B%20C%2BD",
            bugReportUrl("1&1=2", "16", "A#B", "C+D"),
        )
    }
}
