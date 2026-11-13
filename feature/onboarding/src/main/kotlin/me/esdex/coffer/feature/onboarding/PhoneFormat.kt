package me.esdex.coffer.feature.onboarding

import me.esdex.coffer.core.tdlib.PhoneCountry

/**
 * The phone number on the sign-in step, laid out as Telegram's own apps lay it out (from TDLib's
 * getPhoneNumberInfo): plain functions, so what the field shows is tested apart from the field.
 */
internal object PhoneFormat {

    /** Calling codes run to four digits ("1242" is the Bahamas). */
    const val MAX_CODE = 4

    /**
     * [text] to show: up to [typedLength] the digits typed with the country's gaps, after it the digits
     * still expected as a hint. [after] gives, for n digits typed, the position just after the n-th.
     */
    class Layout(val text: String, val typedLength: Int, val after: IntArray) {
        /** Where a cursor after [digits] digits stands in [text]. */
        fun toShown(digits: Int): Int = after[digits.coerceIn(0, after.size - 1)]

        /** How many digits stand before a cursor at [position] in [text]. */
        fun toTyped(position: Int): Int {
            var n = 0
            while (n + 1 < after.size && after[n + 1] <= position) n++
            return n
        }
    }

    /**
     * Lays [digits] into [pattern], where a digit or '-' is a place for one and anything else a separator
     * ("123 45-- ----"). Places past the digits typed stay '-' as the hint; digits beyond the pattern
     * follow at its end. With no pattern the digits stand as they are.
     */
    fun layout(digits: String, pattern: String?): Layout {
        val places = pattern?.count { it.isDigit() || it == '-' } ?: 0
        if (pattern == null || places == 0) return Layout(digits, digits.length, IntArray(digits.length + 1) { it })
        val text = StringBuilder()
        val after = IntArray(digits.length + 1)
        var used = 0
        var typedLength = 0
        for (c in pattern) {
            if (c.isDigit() || c == '-') {
                if (used < digits.length) {
                    text.append(digits[used])
                    after[++used] = text.length
                    typedLength = text.length
                } else {
                    text.append('-')
                }
            } else {
                text.append(c)
            }
        }
        // More than the pattern holds (it may be a keystroke behind): the rest follows, typed, after the hint.
        if (used < digits.length) {
            text.setLength(typedLength)
            while (used < digits.length) {
                text.append(digits[used])
                after[++used] = text.length
            }
            typedLength = text.length
        }
        return Layout(text.toString(), typedLength, after)
    }

    /** What the code field holds after [Split]: the [code], the [rest] for the number, and whether the code is settled. */
    data class Split(val code: String, val rest: String, val settled: Boolean)

    /**
     * What goes where when [digits] are typed or pasted into the code field. The code settles as soon as
     * the digits start with one, as Telegram's apps do: "1" moves on to the number at once, and the
     * Bahamas, +1 242, are told by their number ([reading]). Calling codes are prefix-free apart from that
     * +1 family, so the first code found is the code. Before, "1" waited to see whether 1242 was coming,
     * and the cursor stayed in the code (Esdex, 2026-11-13); and a cut at four digits first made "12125"
     * into 1212, which no country has. With no code at the start, the field keeps what a code can hold.
     */
    fun split(digits: String, codes: Set<String>): Split {
        for (length in 1..minOf(MAX_CODE, digits.length)) {
            val code = digits.take(length)
            if (code in codes) return Split(code, digits.drop(length), settled = true)
        }
        return Split(digits.take(MAX_CODE), digits.drop(MAX_CODE), settled = false)
    }

    /**
     * Telegram's [pattern] for the number, if its reading fits the [code] and [number] in the fields.
     * It may read a longer calling code than the field holds, its first digits being the number's (the
     * code field has 1, Telegram reads 1242 for the Bahamas): then those digits lead the pattern, so the
     * number's own digits still line up. Null for a reading of other digits, one a keystroke behind.
     */
    fun reading(code: String, number: String, callingCode: String, pattern: String): String? {
        if (callingCode == code) return pattern
        val lead = callingCode.removePrefix(code)
        if (lead == callingCode || !number.startsWith(lead)) return null
        return if (pattern.isEmpty()) lead else "$lead $pattern"
    }

    /**
     * The pattern to lay the number out by: Telegram's [read], unless it read a hidden country. Under +1
     * Telegram ties the United States with Clipperton Island, a hidden atoll that has no pattern, and the
     * tie goes to whichever its list puts first: the number then stood unformatted, no hint either (seen
     * 2026-11-13). The code's own pattern stands in then, kept only where it is sure: the whole of +1
     * shares one layout.
     */
    fun patternFor(code: String, read: String?, readHidden: Boolean): String? = if (readHidden) OWN_PATTERN[code] else read

    private val OWN_PATTERN = mapOf("1" to "--- --- ----")

    /**
     * The country to show for [code], as two letters, or null. With a number under way Telegram's own
     * reading stands ([read]), as it tells countries apart by the number (Canada by its area code,
     * Kazakhstan by 77). With only the code, Telegram picks among the countries sharing it as its list
     * happens to run, and once picked Clipperton Island, a hidden atoll, for +1 (seen 2026-11-13); so
     * then the code's own country is shown, as Telegram's apps do: the only one if there is one, the
     * phone's own [region] if it is among them, else the one the code is known for ([MAIN]).
     */
    fun countryFor(code: String, numberTyped: Boolean, read: String?, readHidden: Boolean, countries: List<PhoneCountry>, region: String?): String? {
        if (numberTyped && read != null && !readHidden) return read
        val sharing = countries.filter { !it.hidden && code in it.callingCodes }.map { it.code }
        return when {
            sharing.size == 1 -> sharing.single()
            region != null && region.uppercase() in sharing -> region.uppercase()
            MAIN[code] in sharing -> MAIN[code]
            read != null && !readHidden -> read
            else -> sharing.firstOrNull()
        }
    }

    /** The country each shared calling code is known for, as Telegram's apps show it with no number yet. */
    private val MAIN = mapOf(
        "1" to "US", "7" to "RU", "44" to "GB", "39" to "IT", "47" to "NO", "61" to "AU",
        "212" to "MA", "262" to "RE", "290" to "SH", "358" to "FI", "590" to "GP", "599" to "CW",
    )

    /** A flag from two letters, as the two regional indicator symbols that draw it. */
    fun flag(country: String): String? {
        if (country.length != 2 || !country.all { it in 'A'..'Z' || it in 'a'..'z' }) return null
        return country.uppercase().map { Character.toChars(REGIONAL_A + (it - 'A')) }.joinToString("") { String(it) }
    }

    private const val REGIONAL_A = 0x1F1E6
}

