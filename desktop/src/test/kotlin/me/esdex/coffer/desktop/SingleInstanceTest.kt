package me.esdex.coffer.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** On the session bus, under a name of the test's own: nothing of Coffer's, and nothing that stays. */
class SingleInstanceTest {
    private val name = "me.esdex.coffer.test.n${System.nanoTime()}"

    @Test
    fun aSecondStartAsksTheFirstAndGoes() {
        val first = SingleInstance.claim(name)
        assumeTrue("no session bus", first !is SingleInstance.Claim.NoBus)
        first as SingleInstance.Claim.First
        first.instance.use { instance ->
            val asked = CountDownLatch(1)
            instance.onActivate = { asked.countDown() }
            assertEquals(SingleInstance.Claim.Running(asked = true), SingleInstance.claim(name))
            assertTrue("the first was asked", asked.await(5, TimeUnit.SECONDS))
        }
        // Once the first has gone, the next start is the first again.
        val next = SingleInstance.claim(name)
        assertTrue(next is SingleInstance.Claim.First)
        (next as SingleInstance.Claim.First).instance.close()
    }

    @Test
    fun aCallBeforeTheAppIsReadyDoesNothing() {
        val first = SingleInstance.claim(name)
        assumeTrue("no session bus", first !is SingleInstance.Claim.NoBus)
        (first as SingleInstance.Claim.First).instance.use {
            assertEquals(SingleInstance.Claim.Running(asked = true), SingleInstance.claim(name))
        }
    }
}
