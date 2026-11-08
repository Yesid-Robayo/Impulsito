package io.github.yesidrobayo.shopledger.domain.rule.region

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.BusinessCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.CsvSeparator
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettingsInput
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo que revisa una configuración regional: la forma de cada dato y el bloqueo de la moneda, sin
 * ninguna lista de países ni monedas. Las monedas son los códigos de prueba de ISO 4217.
 */
class RegionalSettingsRulesTest {
    private val xts = requireNotNull(CurrencyCode.of("XTS"))
    private val xtt = requireNotNull(CurrencyCode.of("XTT"))

    private val valid =
        RegionalSettingsInput(
            currencyCode = xts,
            currencyDecimals = 2,
            currencySymbol = null,
            cashRounding = Money(5),
            formatLocaleTag = "es-419",
            businessTimeZoneId = "Etc/GMT+5",
            weekStartDay = DayOfWeek.MONDAY,
            csvSeparator = CsvSeparator.SEMICOLON,
        )

    private val unlocked = BusinessCurrency(CurrencyCode.UNCONFIRMED, 0, null, lockedAt = null)
    private val locked = BusinessCurrency(xts, 2, null, lockedAt = Instant.fromEpochMilliseconds(1))

    private fun problems(
        input: RegionalSettingsInput,
        current: BusinessCurrency = unlocked,
    ): Set<RegionalSettingsProblem> =
        when (val check = RegionalSettingsRules.check(input, current)) {
            is RegionalSettingsCheck.Valid -> emptySet()
            is RegionalSettingsCheck.Invalid -> check.problems
        }

    @Test
    fun `a complete choice is valid as it came`() {
        assertEquals(
            RegionalSettingsCheck.Valid(valid),
            RegionalSettingsRules.check(valid, unlocked),
        )
    }

    @Test
    fun `without a confirmed currency nothing is saved`() {
        assertEquals(
            setOf(RegionalSettingsProblem.CURRENCY_UNCONFIRMED),
            problems(valid.copy(currencyCode = CurrencyCode.UNCONFIRMED)),
        )
    }

    @Test
    fun `decimals go from zero to four, whatever the standard says`() {
        for (decimals in 0..4) {
            assertEquals(
                emptySet<Any>(),
                problems(valid.copy(currencyDecimals = decimals)),
            )
        }
        assertEquals(
            setOf(RegionalSettingsProblem.DECIMALS_OUT_OF_RANGE),
            problems(valid.copy(currencyDecimals = 5)),
        )
        assertEquals(
            setOf(RegionalSettingsProblem.DECIMALS_OUT_OF_RANGE),
            problems(valid.copy(currencyDecimals = -1)),
        )
    }

    @Test
    fun `once locked, the currency and its decimals cannot change`() {
        assertEquals(emptySet<Any>(), problems(valid, locked))
        assertEquals(
            setOf(RegionalSettingsProblem.CURRENCY_LOCKED),
            problems(valid.copy(currencyCode = xtt), locked),
        )
        assertEquals(
            setOf(RegionalSettingsProblem.CURRENCY_LOCKED),
            problems(valid.copy(currencyDecimals = 3), locked),
        )
    }

    @Test
    fun `once locked, the symbol, rounding and formats stay editable`() {
        val changed =
            valid.copy(
                currencySymbol = "¤",
                cashRounding = Money(50),
                formatLocaleTag = "pt",
                businessTimeZoneId = "Etc/GMT-9",
                weekStartDay = DayOfWeek.SUNDAY,
                csvSeparator = CsvSeparator.TAB,
            )
        assertEquals(
            RegionalSettingsCheck.Valid(changed),
            RegionalSettingsRules.check(changed, locked),
        )
    }

    @Test
    fun `a blank symbol means the usual one, and spaces around are dropped`() {
        assertEquals(
            RegionalSettingsCheck.Valid(valid),
            RegionalSettingsRules.check(valid.copy(currencySymbol = "   "), unlocked),
        )
        assertEquals(
            RegionalSettingsCheck.Valid(valid.copy(currencySymbol = "¤")),
            RegionalSettingsRules.check(valid.copy(currencySymbol = " ¤ "), unlocked),
        )
    }

    @Test
    fun `a symbol has at most eight characters, counted as the database counts them`() {
        assertEquals(emptySet<Any>(), problems(valid.copy(currencySymbol = "ABCDEFGH")))
        assertEquals(
            setOf(RegionalSettingsProblem.SYMBOL_TOO_LONG),
            problems(valid.copy(currencySymbol = "ABCDEFGHI")),
        )
        // Ocho signos fuera del plano básico son dieciséis Char de Kotlin, y siguen siendo ocho.
        val astral = "𝐀".repeat(8)
        assertEquals(emptySet<Any>(), problems(valid.copy(currencySymbol = astral)))
    }

    @Test
    fun `the cash rounding step goes from zero to the unit price cap`() {
        assertEquals(emptySet<Any>(), problems(valid.copy(cashRounding = Money.ZERO)))
        assertEquals(emptySet<Any>(), problems(valid.copy(cashRounding = Money(1_000_000_000_000))))
        assertEquals(
            setOf(RegionalSettingsProblem.CASH_ROUNDING_OUT_OF_RANGE),
            problems(valid.copy(cashRounding = Money(1_000_000_000_001))),
        )
        assertEquals(
            setOf(RegionalSettingsProblem.CASH_ROUNDING_OUT_OF_RANGE),
            problems(valid.copy(cashRounding = Money(-1))),
        )
    }

    @Test
    fun `the format is a language tag with a language, checked only by its shape`() {
        for (tag in listOf("es", "es-419", "pt-CV", "zh-Hant-TW", "xx-ZZ")) {
            assertEquals(tag, emptySet<Any>(), problems(valid.copy(formatLocaleTag = tag)))
        }
        for (tag in listOf(
            "",
            "und",
            "UND-419",
            "e",
            "es_ES",
            "es-",
            "a".repeat(36),
            "es-ABCDEFGHI",
        )) {
            assertEquals(
                tag,
                setOf(RegionalSettingsProblem.FORMAT_LOCALE_INVALID),
                problems(valid.copy(formatLocaleTag = tag)),
            )
        }
    }

    @Test
    fun `a zone this phone does not know cannot be chosen`() {
        assertEquals(
            setOf(RegionalSettingsProblem.TIME_ZONE_UNKNOWN),
            problems(valid.copy(businessTimeZoneId = "Nowhere/Invented")),
        )
    }

    @Test
    fun `every problem is reported at once`() {
        val wrong =
            valid.copy(
                currencyCode = CurrencyCode.UNCONFIRMED,
                currencyDecimals = 9,
                currencySymbol = "ABCDEFGHIJ",
                cashRounding = Money(-5),
                formatLocaleTag = "und",
                businessTimeZoneId = "",
            )
        assertEquals(
            setOf(
                RegionalSettingsProblem.CURRENCY_UNCONFIRMED,
                RegionalSettingsProblem.CURRENCY_LOCKED,
                RegionalSettingsProblem.DECIMALS_OUT_OF_RANGE,
                RegionalSettingsProblem.SYMBOL_TOO_LONG,
                RegionalSettingsProblem.CASH_ROUNDING_OUT_OF_RANGE,
                RegionalSettingsProblem.FORMAT_LOCALE_INVALID,
                RegionalSettingsProblem.TIME_ZONE_UNKNOWN,
            ),
            problems(wrong, locked),
        )
    }
}
