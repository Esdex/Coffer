package me.esdex.coffer.feature.onboarding

import me.esdex.coffer.core.tdlib.PhoneCountry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone number on the sign-in step. Patterns are as TDLib's getPhoneNumberInfo gives them; what
 * the field must show is written out by hand.
 */
class PhoneFormatTest {

    // Real calling codes, enough for the cases: 1 starts 1242 and 1246, 7 and 44 stand alone.
    private val codes = setOf("1", "1242", "1246", "7", "44", "48", "49", "380")

    @Test
    fun digitsTakeThePatternsPlacesAndTheRestIsAHint() {
        val layout = PhoneFormat.layout("91612", "916 12- ----")
        assertEquals("916 12- ----", layout.text)
        // Typed up to and with the "2"; the gap and dashes after it are the hint.
        assertEquals(6, layout.typedLength)
        assertEquals(listOf(0, 1, 2, 3, 5, 6), layout.after.toList())
    }

    @Test
    fun nothingTypedIsAllHint() {
        val layout = PhoneFormat.layout("", "--- --- ----")
        assertEquals("--- --- ----", layout.text)
        assertEquals(0, layout.typedLength)
    }

    @Test
    fun digitsBeyondThePatternFollowItsEnd() {
        // The pattern can be a keystroke behind what was typed.
        val layout = PhoneFormat.layout("1234567", "123 45")
        assertEquals("123 4567", layout.text)
        assertEquals(8, layout.typedLength)
        assertEquals(8, layout.toShown(7))
    }

    @Test
    fun withoutAPatternTheDigitsStandAsTheyAre() {
        assertEquals("5551234", PhoneFormat.layout("5551234", null).text)
        assertEquals("5551234", PhoneFormat.layout("5551234", "").text)
    }

    @Test
    fun theCursorMapsBothWays() {
        val layout = PhoneFormat.layout("91612", "916 12- ----")
        // After the third digit, then over the gap: the gap belongs to no digit.
        assertEquals(3, layout.toShown(3))
        assertEquals(3, layout.toTyped(3))
        assertEquals(3, layout.toTyped(4))
        assertEquals(4, layout.toTyped(5))
        // Anywhere in the hint is after the last digit typed.
        assertEquals(5, layout.toTyped(10))
        for (n in 0..5) assertEquals(n, layout.toTyped(layout.toShown(n)))
    }

    @Test
    fun aPastedNumberSplitsAfterItsCode() {
        assertEquals(PhoneFormat.Split("44", "2071234567", settled = true), PhoneFormat.split("442071234567", codes))
        assertEquals(PhoneFormat.Split("380", "501234567", settled = true), PhoneFormat.split("380501234567", codes))
        // The Bahamas are +1 with their number starting 242, as Telegram's apps take them.
        assertEquals(PhoneFormat.Split("1", "2423591234", settled = true), PhoneFormat.split("12423591234", codes))
        assertEquals(PhoneFormat.Split("1", "2125550100", settled = true), PhoneFormat.split("12125550100", codes))
    }

    @Test
    fun aCodeSettlesAsSoonAsItIsOne() {
        // Typed one by one: 48 is Poland, 1 the United States, at once (Esdex, 2026-11-13).
        assertEquals(PhoneFormat.Split("48", "", settled = true), PhoneFormat.split("48", codes))
        assertEquals(PhoneFormat.Split("1", "", settled = true), PhoneFormat.split("1", codes))
        // 4 is no code yet.
        assertEquals(PhoneFormat.Split("4", "", settled = false), PhoneFormat.split("4", codes))
    }

    @Test
    fun telegramsReadingFitsTheFieldsOrIsSetAside() {
        assertEquals("212 555 ----", PhoneFormat.reading("1", "212555", "1", "212 555 ----"))
        // Telegram read +1242 for the Bahamas: the 242 the number begins with leads the pattern.
        assertEquals("242 359 ----", PhoneFormat.reading("1", "242359", "1242", "359 ----"))
        assertEquals("242", PhoneFormat.reading("1", "242", "1242", ""))
        // A reading of other digits, a keystroke behind or another code altogether.
        assertEquals(null, PhoneFormat.reading("1", "212", "1242", "359 ----"))
        assertEquals(null, PhoneFormat.reading("48", "123", "7", "--- --- ----"))
    }

    @Test
    fun noCodeAtTheStartKeepsWhatACodeHoldsAndSettlesNothing() {
        assertEquals(PhoneFormat.Split("9999", "12", settled = false), PhoneFormat.split("999912", codes))
        // Before Telegram has said which codes there are, nothing settles.
        assertEquals(PhoneFormat.Split("48", "", settled = false), PhoneFormat.split("48", emptySet()))
    }

    // As Telegram's list has them: +1 shared by Canada, the United States and a hidden Clipperton Island.
    private val countries = listOf(
        PhoneCountry("CA", listOf("1"), hidden = false),
        PhoneCountry("CP", listOf("1"), hidden = true),
        PhoneCountry("US", listOf("1"), hidden = false),
        PhoneCountry("KZ", listOf("7"), hidden = false),
        PhoneCountry("RU", listOf("7"), hidden = false),
        PhoneCountry("PL", listOf("48"), hidden = false),
    )

    @Test
    fun aSharedCodeAloneShowsItsOwnCountryNeverAHiddenOne() {
        // Telegram read +1 as Clipperton Island: the United States, as Telegram's apps show.
        assertEquals("US", PhoneFormat.countryFor("1", numberTyped = false, read = "CP", readHidden = true, countries, region = null))
        assertEquals("US", PhoneFormat.countryFor("1", numberTyped = false, read = "CA", readHidden = false, countries, region = null))
        assertEquals("RU", PhoneFormat.countryFor("7", numberTyped = false, read = "KZ", readHidden = false, countries, region = null))
        // A phone in Canada sees Canada first; one elsewhere still the code's own country.
        assertEquals("CA", PhoneFormat.countryFor("1", numberTyped = false, read = "US", readHidden = false, countries, region = "ca"))
        assertEquals("US", PhoneFormat.countryFor("1", numberTyped = false, read = null, readHidden = false, countries, region = "DE"))
        // A code only one country has.
        assertEquals("PL", PhoneFormat.countryFor("48", numberTyped = false, read = null, readHidden = false, countries, region = null))
        // A code with no country it is known for, a hidden one listed first: the one offered.
        val shared = listOf(PhoneCountry("ZZ", listOf("999"), hidden = true), PhoneCountry("XX", listOf("999"), hidden = false))
        assertEquals("XX", PhoneFormat.countryFor("999", numberTyped = false, read = "ZZ", readHidden = true, shared, region = null))
    }

    @Test
    fun withANumberTelegramsReadingStandsUnlessItIsHidden() {
        assertEquals("CA", PhoneFormat.countryFor("1", numberTyped = true, read = "CA", readHidden = false, countries, region = null))
        assertEquals("KZ", PhoneFormat.countryFor("7", numberTyped = true, read = "KZ", readHidden = false, countries, region = null))
        assertEquals("US", PhoneFormat.countryFor("1", numberTyped = true, read = "CP", readHidden = true, countries, region = null))
    }

    @Test
    fun aFlagIsDrawnFromTwoLetters() {
        // Written out as the emoji themselves, not built the way the code builds them.
        assertEquals("🇺🇸", PhoneFormat.flag("US"))
        assertEquals("🇵🇱", PhoneFormat.flag("pl"))
        assertEquals(null, PhoneFormat.flag("USA"))
        assertEquals(null, PhoneFormat.flag("U1"))
    }

    @Test
    fun aHiddenReadingGivesWayToTheCodesOwnPattern() {
        // Telegram read +1 as Clipperton Island, which has no pattern: the layout all of +1 shares.
        assertEquals("--- --- ----", PhoneFormat.patternFor("1", read = "", readHidden = true))
        assertEquals("212 555 0100", PhoneFormat.layout("2125550100", PhoneFormat.patternFor("1", read = "", readHidden = true)).text)
        // A reading of a country it offers stands as it is.
        assertEquals("9-- --- ----", PhoneFormat.patternFor("7", read = "9-- --- ----", readHidden = false))
        // No sure pattern for the code: nothing, rather than a guess.
        assertEquals(null, PhoneFormat.patternFor("44", read = "", readHidden = true))
    }
}

