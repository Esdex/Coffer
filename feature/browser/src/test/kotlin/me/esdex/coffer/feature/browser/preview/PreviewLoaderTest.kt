package me.esdex.coffer.feature.browser.preview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.esdex.coffer.core.crypto.Id
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Previews on the phone: made once, kept sealed, never kept when they could not be made. The seal here
 * is a stand-in that only marks and reverses the bytes; the real one is checked against the oracle in
 * :core:crypto (LocalSealTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewLoaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun id(n: Int) = Id.of(ByteArray(16) { if (it == 0) n.toByte() else 0 })

    private object Marked : PreviewSeal {
        override fun seal(fileId: Id, picture: ByteArray) = byteArrayOf(7) + fileId.toByteArray() + picture.reversedArray()

        override fun open(fileId: Id, sealed: ByteArray): ByteArray? {
            if (sealed.size < 17 || sealed[0] != 7.toByte()) return null
            if (!sealed.copyOfRange(1, 17).contentEquals(fileId.toByteArray())) return null
            return sealed.copyOfRange(17, sealed.size).reversedArray()
        }
    }

    private val picture = byteArrayOf(1, 2, 3, 4)
    private var unlocked = true
    private fun store() = PreviewStore(temp.root.resolve("previews"))
    private fun loader(store: PreviewStore = store(), idle: suspend () -> Unit = {}) = PreviewLoader(store, { if (unlocked) Marked else null }, idle)

    @Test
    fun madeOnceAndKeptSealed() = runTest {
        var made = 0
        val store = store()
        assertArrayEquals(picture, loader(store).load(id(1)) { made++; picture })
        // On the phone it is not the picture itself.
        val kept = store.read(id(1))!!
        assertFalse(kept.contentEquals(picture))
        assertArrayEquals(picture, Marked.open(id(1), kept))
        // A new process reads it from the phone.
        assertArrayEquals(picture, loader(store()).load(id(1)) { made++; byteArrayOf(9) })
        assertEquals(1, made)
    }

    @Test
    fun aFileWithNothingToShowIsNotLookedAtAgain() = runTest {
        var made = 0
        val loader = loader()
        assertEquals(0, loader.load(id(1)) { made++; ByteArray(0) }!!.size)
        assertEquals(0, loader.load(id(1)) { made++; picture }!!.size)
        assertEquals(1, made)
    }

    @Test
    fun aPreviewThatCouldNotBeMadeIsTriedAgain() = runTest {
        var made = 0
        val loader = loader()
        assertNull(loader.load(id(1)) { made++; null })
        assertNull(store().read(id(1)))
        assertArrayEquals(picture, loader.load(id(1)) { made++; picture })
        assertEquals(2, made)
    }

    @Test
    fun lockedNothingIsMadeOrKept() = runTest {
        unlocked = false
        var made = 0
        assertNull(loader().load(id(1)) { made++; picture })
        assertEquals(0, made)
        // Locked while it was being made: the picture is not kept, sealed or not.
        unlocked = true
        assertNull(loader().load(id(2)) { unlocked = false; picture })
        assertNull(store().read(id(2)))
    }

    @Test
    fun onlyThisFilesSealOpens() = runTest {
        val store = store()
        // Another file's preview under this file's name, and bytes from another vault: made again.
        store.write(id(1), Marked.seal(id(2), byteArrayOf(5)))
        assertArrayEquals(picture, loader(store).load(id(1)) { picture })
        store.write(id(3), byteArrayOf(0, 1, 2))
        assertArrayEquals(picture, loader(store).load(id(3)) { picture })
        assertArrayEquals(picture, Marked.open(id(3), store.read(id(3))!!))
    }

    @Test
    fun atMostTwoAreMadeAtOnceAndOnlyWhenIdle() = runTest {
        val idle = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var running = 0
        var most = 0
        var started = 0
        val loader = loader(idle = { idle.await() })
        val all = (1..5).map { n ->
            async {
                loader.load(id(n)) {
                    started++
                    running++
                    most = maxOf(most, running)
                    release.await()
                    running--
                    picture
                }
            }
        }
        runCurrent()
        // Transfers are running: nothing is made yet.
        assertEquals(0, started)
        idle.complete(Unit)
        runCurrent()
        assertEquals(2, started)
        release.complete(Unit)
        all.awaitAll()
        assertEquals(5, started)
        assertEquals(2, most)
    }

    @Test
    fun oneFileAskedForTwiceIsMadeOnce() = runTest {
        val release = CompletableDeferred<Unit>()
        var made = 0
        val loader = loader()
        val both = List(2) { async { loader.load(id(1)) { made++; release.await(); picture } } }
        runCurrent()
        release.complete(Unit)
        both.awaitAll().forEach { assertArrayEquals(picture, it) }
        assertEquals(1, made)
    }

    @Test
    fun previewsOfFilesGoneAreDeleted() {
        val store = store()
        store.write(id(1), byteArrayOf(1))
        store.write(id(2), byteArrayOf(2, 2))
        temp.root.resolve("previews/not-a-preview.tmp").writeText("x")
        assertEquals(3L + 1, store.size())
        store.retain(setOf(id(2)))
        assertNull(store.read(id(1)))
        assertArrayEquals(byteArrayOf(2, 2), store.read(id(2)))
        assertEquals(listOf(id(2)).size, temp.root.resolve("previews").list()!!.size)
        store.clear()
        assertNull(store.read(id(2)))
        assertEquals(0L, store.size())
    }

    @Test
    fun picturesVideosAndMusicHavePreviews() {
        assertEquals(PreviewKind.PICTURE, PreviewKind.of("image/jpeg"))
        assertEquals(PreviewKind.PICTURE, PreviewKind.of("image/heic"))
        assertNull(PreviewKind.of("image/svg+xml"))
        assertEquals(PreviewKind.VIDEO, PreviewKind.of("video/mp4"))
        assertEquals(PreviewKind.AUDIO, PreviewKind.of("audio/flac"))
        assertNull(PreviewKind.of("application/pdf"))
        assertTrue(PreviewKind.of("text/plain") == null)
    }
}
