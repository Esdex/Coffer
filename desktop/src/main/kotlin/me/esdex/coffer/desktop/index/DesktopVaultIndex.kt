package me.esdex.coffer.desktop.index

import androidx.room.Room
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.Dispatchers
import me.esdex.coffer.core.index.IndexDatabase
import me.esdex.coffer.core.index.IndexDatabase_Impl
import me.esdex.coffer.core.index.IndexPlatform
import me.esdex.coffer.core.index.RoomVaultIndex
import java.io.File

/**
 * The desktop's index: the same Room database as the phone's, in [file], encrypted with [key] by
 * [SqlCipherDriver] (audit 1, M9).
 */
fun desktopVaultIndex(file: File, key: ByteArray): RoomVaultIndex {
    val db = Room.databaseBuilder<IndexDatabase>(name = file.path, factory = { IndexDatabase_Impl() })
        .setDriver(SqlCipherDriver(key))
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
    return RoomVaultIndex(
        db,
        object : IndexPlatform {
            // Room's own transaction API: what room-ktx's withTransaction is on Android.
            override suspend fun <R> transaction(block: suspend () -> R): R =
                db.useWriterConnection { connection -> connection.immediateTransaction { block() } }
        },
    )
}
