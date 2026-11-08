package io.github.yesidrobayo.shopledger.domain.rule.tax

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxSettings
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRate
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDetails
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateDraft
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateEntry
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxRateInputError
import io.github.yesidrobayo.shopledger.domain.model.tax.TaxSetup
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los nombres son inventados: ninguna prueba nombra un impuesto de un país. */
class TaxRateRulesTest {
    @Test
    fun `a rate with a name and a percentage is saved trimmed with its normalized name`() {
        val checked = TaxRateRules.check(draft("  Tasa General  ", 2_100L))
        assertEquals(
            TaxRateCheck.Valid(TaxRateDetails("Tasa General", "tasa general", BasisPoints(2_100L))),
            checked,
        )
    }

    @Test
    fun `zero and one hundred percent are valid rates`() {
        assertTrue(TaxRateRules.check(draft("Exenta", 0L)) is TaxRateCheck.Valid)
        assertTrue(TaxRateRules.check(draft("Completa", 10_000L)) is TaxRateCheck.Valid)
    }

    @Test
    fun `a rate outside zero to one hundred percent is a field error`() {
        assertEquals(
            invalid(TaxRateInputError.RATE_OUT_OF_RANGE),
            TaxRateRules.check(draft("A", -1L)),
        )
        assertEquals(
            invalid(TaxRateInputError.RATE_OUT_OF_RANGE),
            TaxRateRules.check(draft("A", 10_001L)),
        )
    }

    @Test
    fun `a blank name or one with only marks is a field error`() {
        assertEquals(invalid(TaxRateInputError.NAME_BLANK), TaxRateRules.check(draft("   ", 100L)))
        assertEquals(invalid(TaxRateInputError.NAME_BLANK), TaxRateRules.check(draft("́", 100L)))
    }

    @Test
    fun `forty characters fit and forty one do not, counting each emoji as one`() {
        assertTrue(TaxRateRules.check(draft("a".repeat(40), 100L)) is TaxRateCheck.Valid)
        assertEquals(
            invalid(TaxRateInputError.NAME_TOO_LONG),
            TaxRateRules.check(draft("a".repeat(41), 100L)),
        )
        // Cuarenta emojis ocupan ochenta unidades de String, pero son cuarenta caracteres.
        assertTrue(TaxRateRules.check(draft("🧾".repeat(40), 100L)) is TaxRateCheck.Valid)
        // Una mitad suelta cuenta como un carácter.
        assertTrue(TaxRateRules.check(draft("a".repeat(39) + "\uD83E", 100L)) is TaxRateCheck.Valid)
        assertTrue(TaxRateRules.check(draft("\uD83E" + "a".repeat(39), 100L)) is TaxRateCheck.Valid)
    }

    @Test
    fun `every field error is reported at once`() {
        assertEquals(
            TaxRateCheck.Invalid(
                setOf(TaxRateInputError.NAME_BLANK, TaxRateInputError.RATE_OUT_OF_RANGE),
            ),
            TaxRateRules.check(draft("", 20_000L)),
        )
    }

    @Test
    fun `a rate used by a product or set as default is in use`() {
        val rate = TaxRate(1L, TaxRateDetails("A", "a", BasisPoints(100L)), null, AT, AT)
        assertFalse(TaxRateEntry(rate, isDefault = false, productCount = 0).isInUse)
        assertTrue(TaxRateEntry(rate, isDefault = true, productCount = 0).isInUse)
        assertTrue(TaxRateEntry(rate, isDefault = false, productCount = 2).isInUse)
        assertFalse(rate.isArchived)
        assertTrue(rate.copy(archivedAt = AT).isArchived)
    }

    @Test
    fun `charging tax without a default rate is flagged`() {
        fun setup(mode: TaxMode, default: Long?) =
            TaxSetup(TaxSettings(mode, default, purchaseTaxRecoverable = false), listOf(), listOf())
        assertFalse(setup(TaxMode.NONE, null).isMissingDefaultRate)
        assertTrue(setup(TaxMode.INCLUDED, null).isMissingDefaultRate)
        assertTrue(setup(TaxMode.ADDED, null).isMissingDefaultRate)
        assertFalse(setup(TaxMode.ADDED, 1L).isMissingDefaultRate)
    }

    private fun draft(name: String, rateBp: Long) =
        TaxRateDraft(name, BasisPoints(rateBp), useAsDefault = false)

    private fun invalid(error: TaxRateInputError) = TaxRateCheck.Invalid(setOf(error))

    private companion object {
        val AT: Instant = Instant.fromEpochMilliseconds(1_000L)
    }
}
