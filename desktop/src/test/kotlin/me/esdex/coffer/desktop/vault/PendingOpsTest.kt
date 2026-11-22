package me.esdex.coffer.desktop.vault

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import me.esdex.coffer.core.crypto.Bytes
import me.esdex.coffer.core.crypto.CofferFormat
import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.KeyRing
import me.esdex.coffer.core.crypto.LocalKeys
import me.esdex.coffer.core.crypto.SecretKey
import me.esdex.coffer.core.crypto.format.FileMeta
import me.esdex.coffer.core.crypto.format.LocalFolder
import me.esdex.coffer.core.crypto.format.Op
import me.esdex.coffer.core.crypto.sodium.LazySodiumAdapter
import me.esdex.coffer.core.index.PendingOp
import me.esdex.coffer.core.sync.VaultKeys
import me.esdex.coffer.core.sync.VaultTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

class PendingOpsTest {
    private fun id(n: Int) = Id.of(ByteArray(16) { if (it == 15) n.toByte() else 1 })
    private val a = id(1)
    private val b = id(2)
    private val f = id(10)

    private val tree = VaultTree(
        mapOf(a to LocalFolder("A", null)),
        mapOf(f to FileMeta("f.txt", null, "text/plain", 1, 2, Bytes.of(ByteArray(CofferFormat.CONTENT_HASH_BYTES)), Bytes.of(ByteArray(CofferFormat.WRAPPED_KEY_BYTES)))),
    )

    @Test
    fun operationsApplyInOrder() {
        val after = PendingOps.apply(
            tree,
            listOf(
                Op.FolderCreate(id(20), 1, b, a, "B"),
                Op.FileMove(id(21), 2, f, b),
                Op.FileRename(id(22), 3, f, "g.txt"),
                Op.FolderRename(id(23), 4, a, "AA"),
                Op.FolderMove(id(24), 5, b, null),
            ),
        )
        assertEquals("AA", after.folders.getValue(a).name)
        assertEquals(LocalFolder("B", null), after.folders.getValue(b))
        assertEquals("g.txt", after.files.getValue(f).name)
        assertEquals(b, after.files.getValue(f).folderId)
    }

    @Test
    fun deletesTakeThingsAwayAndChangesToWhatIsGoneAreNothing() {
        val after = PendingOps.apply(tree, listOf(Op.FileDelete(id(20), 1, f), Op.FileRename(id(21), 2, f, "x"), Op.FolderDelete(id(22), 3, a)))
        assertNull(after.files[f])
        assertNull(after.folders[a])
    }

    @Test
    fun withoutTakesAFolderWithAllInside() {
        val inside = PendingOps.apply(tree, listOf(Op.FileMove(id(20), 1, f, a)))
        val after = PendingOps.without(inside, setOf(a))
        assertEquals(emptyMap<Id, LocalFolder>(), after.folders)
        assertEquals(emptyMap<Id, FileMeta>(), after.files)
    }

    @Test
    fun theQueueIsReadBackWithTheVaultKeyAndHeldRowsHoldNothing() {
        val sodium = LazySodiumAdapter(LazySodiumJava(SodiumJava()))
        val master = SecretKey.adopt(ByteArray(32).also(SecureRandom()::nextBytes))
        val keys = KeyRing.derive(sodium, master).let { VaultKeys(sodium, it.metaKey, it.indexKey, it.wrapKey, LocalKeys.derive(sodium, master)) }
        val ops = listOf(Op.FolderCreate(id(20), 1, b, null, "B"), Op.FileRename(id(21), 2, f, "g.txt"))
        val queue = listOf(
            PendingOp(1, "b", 2, "RENAME", keys.meta.encodeOpPack(ops), 2),
            PendingOp(2, "held", 1, "DELETE", "", 1),
            PendingOp(3, "c", 1, "DELETE", keys.meta.encodeOp(Op.FileDelete(id(22), 3, f)), 1),
        )
        assertEquals(ops + Op.FileDelete(id(22), 3, f), PendingOps.of(queue, keys))
    }
}
