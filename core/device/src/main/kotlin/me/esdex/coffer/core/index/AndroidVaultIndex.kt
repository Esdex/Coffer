package me.esdex.coffer.core.index

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import java.io.File
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The app's index on the phone, encrypted with [key] by SQLCipher (audit 1, M9): file sizes, part counts,
 * access times and the transfer queue are not readable from a copy of the phone's storage. A database
 * left plain by an older version is turned into an encrypted one first, its rows kept.
 */
fun androidVaultIndex(context: Context, key: ByteArray, name: String = "index.db"): RoomVaultIndex {
    val db = openIndex(context, key, name)
    return RoomVaultIndex(
        db,
        object : IndexPlatform {
            override suspend fun <R> transaction(block: suspend () -> R): R = db.withTransaction(block)
        },
    )
}

private fun openIndex(context: Context, key: ByteArray, name: String): IndexDatabase {
    // sqlcipher-android does not load its own native library (as Arcanum found).
    System.loadLibrary("sqlcipher")
    // A raw key, so SQLCipher skips its own key derivation: the key is random already.
    val passphrase = "x'${key.joinToString("") { "%02x".format(it) }}'".toByteArray(Charsets.US_ASCII)
    val factory = SupportOpenHelperFactory(passphrase)
    encryptIfPlain(context, name, factory)
    return Room.databaseBuilder(context, IndexDatabase::class.java, name).openHelperFactory(factory).build()
}

/** True if [file] is a plain SQLite database: its first 16 bytes say so. Encrypted ones look random. */
fun isPlainDatabase(file: File): Boolean {
    if (!file.exists() || file.length() < PLAIN_HEADER.size) return false
    val head = ByteArray(PLAIN_HEADER.size)
    file.inputStream().use { it.read(head) }
    return head.contentEquals(PLAIN_HEADER)
}

/**
 * Copies a plain index, as an older version left it, row by row into an encrypted one, then puts
 * that in its place. Through Room on both sides: it brings the plain one up to the current schema
 * first, and SQLCipher's own export runs its statements on whichever connection of its pool is
 * free, where the attached database may not be (seen on the emulator).
 */
private fun encryptIfPlain(context: Context, name: String, factory: SupportOpenHelperFactory) {
    val file = context.getDatabasePath(name)
    if (!isPlainDatabase(file)) return
    val temporary = "$name.encrypting"
    context.deleteDatabase(temporary)
    val plain = Room.databaseBuilder(context, IndexDatabase::class.java, name).build()
    val encrypted = Room.databaseBuilder(context, IndexDatabase::class.java, temporary).openHelperFactory(factory).build()
    try {
        runBlocking { RoomVaultIndex.copy(plain, encrypted) }
    } finally {
        plain.close()
        encrypted.close()
    }
    context.deleteDatabase(name)
    for (suffix in listOf("", "-wal", "-shm", "-journal")) {
        val from = context.getDatabasePath(temporary + suffix)
        if (from.exists()) check(from.renameTo(context.getDatabasePath(name + suffix))) { "could not replace the plain index" }
    }
}

private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
