package me.esdex.coffer.core.tdlib

/**
 * What TDLib keeps on the phone, from its own quick count: the files it downloaded or still holds
 * from uploads, and its databases (messages, the language pack) with its log.
 */
data class TelegramUsage(val filesBytes: Long, val fileCount: Int, val databaseBytes: Long)
