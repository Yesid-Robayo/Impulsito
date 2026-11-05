package io.github.yesidrobayo.shopledger.domain.matrix

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.settings.CurrencyCode
import io.github.yesidrobayo.shopledger.domain.rule.backup.RegionalShape
import io.github.yesidrobayo.shopledger.domain.rule.backup.StoredValueRules
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Los ajustes que guarda un negocio con cada fila de la matriz: una copia con ellos pasa la revisión
 * de forma de los datos regionales, que nunca los compara con una lista de países, monedas o zonas.
 * Con la moneda sin confirmar, los decimales valen cero.
 */
@RunWith(Parameterized::class)
class RegionalMatrixSettingsTest(
    private val row: RegionalMatrixRow,
    defaults: ForeignSystemDefaults,
) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    private val settings = row.regionalSettings()

    @Test
    fun `a copy with the regional values of the row passes the shape review`() {
        val values =
            listOfNotNull(
                RegionalShape.CURRENCY_CODE to settings.currency.code.code,
                RegionalShape.LANGUAGE_TAG to settings.formatLocaleTag,
                RegionalShape.TIME_ZONE_ID to settings.businessTimeZoneId,
                row.uiLanguageTag?.let { RegionalShape.LANGUAGE_TAG to it },
            )
        for ((shape, text) in values) {
            assertNull("$row $shape «$text»", StoredValueRules.checkRegionalShape(shape, text))
        }
    }

    @Test
    fun `the currency of the row keeps its decimals between zero and four`() {
        val currency = settings.currency
        assertEquals(currency.code, CurrencyCode.of(currency.code.code))
        assertTrue("$row", currency.decimals in 0..4)
        assertTrue("$row", !currency.isLocked)
        if (!currency.code.isConfirmed) {
            // Sin moneda elegida no hay unidad menor: nada con dinero se guarda todavía.
            assertEquals(CurrencyCode.UNCONFIRMED, currency.code)
            assertEquals(0, currency.decimals)
        }
    }

    @Test
    fun `the cash rounding step of the row is a valid stored amount`() {
        assertTrue("$row", settings.cashRounding >= Money.ZERO)
        assertTrue("$row", Limit.UNIT_MONEY.allows(settings.cashRounding.minor))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun parameters(): List<Array<Any>> = RegionalMatrix.withForeignDefaults()
    }
}
