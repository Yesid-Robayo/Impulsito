package io.github.yesidrobayo.shopledger.domain.rule.region

import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCorrection
import io.github.yesidrobayo.shopledger.domain.model.settings.ReferenceCurrency
import io.github.yesidrobayo.shopledger.domain.model.settings.RegionalSettings
import io.github.yesidrobayo.shopledger.domain.testing.regionalSettings
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lo que la corrección de la moneda revisa con lo guardado: solo con la moneda bloqueada, a otra
 * moneda confirmada, con los mismos decimales y que no sea la de referencia. Las monedas son
 * códigos de prueba, de ninguna parte.
 */
class CurrencyCorrectionRulesTest {
    private val xts = code("XTS")
    private val xtt = code("XTT")
    private val lockedAt = Instant.fromEpochMilliseconds(1_793_593_800_000)

    // El negocio en XTS, con dos decimales y ya con registros con dinero.
    private val locked: RegionalSettings =
        regionalSettings("Etc/GMT+5", DayOfWeek.MONDAY).let {
            it.copy(currency = it.currency.copy(code = xts, decimals = 2, lockedAt = lockedAt))
        }

    @Test
    fun `another confirmed currency with the same decimals can be corrected to`() {
        assertNull(CurrencyCorrectionRules.check(locked, CurrencyCorrection(xtt, 2)))
        // Una referencia en otra moneda no estorba.
        val withReference = locked.copy(referenceCurrency = ReferenceCurrency(code("XTU"), 2))
        assertNull(CurrencyCorrectionRules.check(withReference, CurrencyCorrection(xtt, 2)))
    }

    @Test
    fun `before the lock there is nothing to correct`() {
        val unlocked = locked.copy(currency = locked.currency.copy(lockedAt = null))

        assertEquals(
            CurrencyCorrectionProblem.NOT_LOCKED,
            CurrencyCorrectionRules.check(unlocked, CurrencyCorrection(xtt, 2)),
        )
    }

    @Test
    fun `the currency without a code is never a correction`() {
        assertEquals(
            CurrencyCorrectionProblem.UNCONFIRMED,
            CurrencyCorrectionRules.check(locked, CurrencyCorrection(CurrencyCode.UNCONFIRMED, 2)),
        )
    }

    @Test
    fun `the same currency is not a correction`() {
        assertEquals(
            CurrencyCorrectionProblem.SAME_AS_CURRENT,
            CurrencyCorrectionRules.check(locked, CurrencyCorrection(xts, 2)),
        )
    }

    @Test
    fun `other decimals would change the value of every saved amount`() {
        for (decimals in listOf(0, 1, 3, 4)) {
            assertEquals(
                CurrencyCorrectionProblem.DECIMALS_DIFFER,
                CurrencyCorrectionRules.check(locked, CurrencyCorrection(xtt, decimals)),
            )
        }
    }

    @Test
    fun `the reference currency cannot become the business currency`() {
        val withReference = locked.copy(referenceCurrency = ReferenceCurrency(xtt, 2))

        assertEquals(
            CurrencyCorrectionProblem.SAME_AS_REFERENCE,
            CurrencyCorrectionRules.check(withReference, CurrencyCorrection(xtt, 2)),
        )
    }

    private fun code(text: String): CurrencyCode = requireNotNull(CurrencyCode.of(text))
}
