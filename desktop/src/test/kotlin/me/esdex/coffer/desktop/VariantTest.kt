package me.esdex.coffer.desktop

import me.esdex.coffer.desktop.keyring.Keyring
import org.junit.Assert.assertEquals
import org.junit.Test

class VariantTest {
    @Test
    fun onlyReleaseIsCoffer() {
        assertEquals(Variant.RELEASE, Variant.of("release"))
        for (value in listOf("dev", "", "Release", null)) assertEquals(value.toString(), Variant.DEV, Variant.of(value))
    }

    @Test
    fun cofferDevKeepsNothingUnderCoffersNames() {
        assertEquals(listOf("Coffer", "coffer", "me.esdex.coffer"), Variant.RELEASE.let { listOf(it.title, it.id, it.application) })
        assertEquals(listOf("Coffer Dev", "coffer-dev", "me.esdex.coffer.debug"), Variant.DEV.let { listOf(it.title, it.id, it.application) })
    }

    @Test
    fun aBuildFromGradleIsCofferDev() {
        assertEquals(Variant.DEV, BuildInfo.variant)
        assertEquals("me.esdex.coffer.debug", Keyring.attributes("x")["application"])
    }
}
