package me.esdex.coffer.core.tdlib

/** Who is signed in, as Settings shows it. [phoneNumber] is digits only, as Telegram gives it. */
data class TelegramAccount(
    val firstName: String,
    val lastName: String,
    val username: String?,
    val phoneNumber: String,
    val isPremium: Boolean,
    /** A local copy of the small profile photo, or null to show initials. */
    val photoPath: String?,
) {
    val displayName: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")

    val initials: String
        get() = listOf(firstName, lastName).mapNotNull { name -> name.trim().firstOrNull()?.uppercaseChar() }.joinToString("").ifEmpty { "?" }
}
