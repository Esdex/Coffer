package me.esdex.coffer.feature.browser.viewer.text

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which links in a Markdown file may open outside Coffer (audit 1, L4). */
class LinksTest {
    @Test
    fun webPagesAndMailOpen() {
        assertTrue(opensOutside("https://example.org/page"))
        assertTrue(opensOutside("HTTP://example.org"))
        assertTrue(opensOutside("mailto:someone@example.org"))
    }

    @Test
    fun pathsAndOtherSchemesDoNot() {
        assertFalse(opensOutside("file:///data/data/me.esdex.coffer/shared_prefs/x.xml"))
        assertFalse(opensOutside("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(opensOutside("content://me.esdex.coffer.files/open/x"))
        assertFalse(opensOutside("javascript:alert(1)"))
        assertFalse(opensOutside("notes/books.md"))
        assertFalse(opensOutside(""))
    }
}
