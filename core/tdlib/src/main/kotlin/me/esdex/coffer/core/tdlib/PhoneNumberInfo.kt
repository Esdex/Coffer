package me.esdex.coffer.core.tdlib

/**
 * What Telegram makes of the start of a phone number, before signing in: the country it is from, if it
 * can tell, and the number as Telegram's own apps lay it out. [pattern] is the number without its calling
 * code, the digits given so far in place and those still expected as '-' ("123 45-- ----").
 * [countryHidden]: a country Telegram lists but does not offer, as Clipperton Island under +1.
 */
data class PhoneNumberInfo(
    val callingCode: String,
    val countryCode: String?,
    val countryHidden: Boolean,
    val pattern: String,
)

/** A country as Telegram lists it: its two letters, its calling codes, and whether it is hidden. */
data class PhoneCountry(val code: String, val callingCodes: List<String>, val hidden: Boolean)
