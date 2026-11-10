package me.esdex.coffer.feature.browser

import me.esdex.coffer.core.crypto.Id
import me.esdex.coffer.core.crypto.format.Base64Url

/**
 * A list row's key for [this] id: the whole id, as text a Bundle can hold. A hash or Id.toString's
 * short prefix is 32 bits, and two rows with one key make Compose throw; with ten thousand files the
 * chance of that is about one in a hundred.
 */
fun Id.rowKey(): String = Base64Url.encode(toByteArray())
