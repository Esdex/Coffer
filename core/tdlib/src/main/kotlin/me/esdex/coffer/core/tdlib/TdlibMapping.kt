package me.esdex.coffer.core.tdlib

import me.esdex.coffer.core.storage.MessageContent
import me.esdex.coffer.core.storage.MessageId
import me.esdex.coffer.core.storage.StorageEvent
import me.esdex.coffer.core.storage.StoredMessage
import org.drinkless.tdlib.TdApi

/**
 * Server message ids have their low 20 bits clear; TDLib gives messages that are not on the server
 * yet (or only local) other ids. Only server messages are part of the shared log.
 */
internal fun isServerMessageId(id: Long): Boolean = id > 0 && id and 0xFFFFF == 0L

internal fun TdApi.Message.toStored(): StoredMessage {
    val content = when (val c = content) {
        is TdApi.MessageText -> MessageContent.Text(c.text.text)
        is TdApi.MessageDocument -> MessageContent.Blob(c.caption.text, c.document.fileName, c.document.document.size)
        else -> MessageContent.Other
    }
    return StoredMessage(MessageId(id), content, isPinned)
}

/** A message that belongs in the log: in [chatId], sent, on the server, not scheduled. */
internal fun TdApi.Message.isLogged(chatId: Long): Boolean =
    this.chatId == chatId && sendingState == null && schedulingState == null && isServerMessageId(id)

/**
 * TDLib also sends updateDeleteMessages when it only drops messages from its local cache
 * (from_cache) or deletes them for this session only (not permanent). Neither means the message is
 * gone from Saved Messages, and treating it so would mark files damaged (SPEC §5.1).
 */
internal fun TdApi.UpdateDeleteMessages.toEvent(chatId: Long): StorageEvent.MessagesDeleted? {
    if (this.chatId != chatId || !isPermanent || fromCache) return null
    val ids = messageIds.filter(::isServerMessageId).mapTo(mutableSetOf(), ::MessageId)
    return if (ids.isEmpty()) null else StorageEvent.MessagesDeleted(ids)
}
