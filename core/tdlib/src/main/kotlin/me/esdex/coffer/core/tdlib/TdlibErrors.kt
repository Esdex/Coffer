package me.esdex.coffer.core.tdlib

import me.esdex.coffer.core.storage.FloodWaitException
import me.esdex.coffer.core.storage.StorageException
import org.drinkless.tdlib.TdApi

/** An error TDLib returned, with its code and Telegram's error text (for example PHONE_CODE_INVALID). */
class TdlibException(val code: Int, message: String) : StorageException(message)

/** TDLib reports flood waits as code 429 "Too Many Requests: retry after N"; older paths say FLOOD_WAIT_N. */
internal fun floodWaitSeconds(code: Int, message: String): Int? {
    if (code != 429 && !message.startsWith("FLOOD_WAIT_")) return null
    val seconds = Regex("""retry after (\d+)""").find(message)?.groupValues?.get(1)
        ?: Regex("""^FLOOD_WAIT_(\d+)""").find(message)?.groupValues?.get(1)
    return seconds?.toIntOrNull() ?: 0
}

internal fun TdApi.Error.toException(): StorageException =
    floodWaitSeconds(code, message)?.let(::FloodWaitException) ?: TdlibException(code, message)
