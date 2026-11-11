package io.github.yesidrobayo.shopledger.domain.matrix

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixRow
import io.github.yesidrobayo.shopledger.domain.usecase.sale.RegisterSaleResult
import io.github.yesidrobayo.shopledger.domain.usecase.sale.SaleWorld
import io.github.yesidrobayo.shopledger.domain.usecase.sale.method
import io.github.yesidrobayo.shopledger.domain.usecase.sale.product
import io.github.yesidrobayo.shopledger.domain.usecase.sale.taxRate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Una venta con dos tasas distintas, confirmada con cada fila de la matriz de configuraciones: los
 * mismos enteros en unidad menor con cualquier moneda, decimales, formato o zona, según el modo de
 * impuesto de la fila. En todas, el total es lo neto más el impuesto. Una moneda sin confirmar no
 * vende.
 *
 * Corre con zonas e idiomas extraños de la JVM, que cambiarían el resultado si una regla los
 * leyera.
 */
@RunWith(Parameterized::class)
class RegionalMatrixSaleTest(private val row: RegionalMatrixRow, defaults: ForeignSystemDefaults) {
    @get:Rule
    val foreignDefaults = ForeignSystemDefaultsRule(defaults)

    private val region = row.regionOr(RegionalMatrix.INVENTED_DEVICE)

    private val world = SaleWorld(zoneId = region.businessTimeZoneId).apply {
        val regional = row.regionalSettings()
        val currency = regional.currency
        val locked = currency.copy(lockedAt = SaleWorld.EPOCH.takeIf { currency.code.isConfirmed })
        settings.settings.value =
            settings.settings.value.copy(regional = regional.copy(currency = locked))
        taxRates = mapOf(1L to taxRate(1, "Rate A", 2_100), 2L to taxRate(2, "Rate B", 1_900))
        setTaxes(row.taxMode, defaultTaxRateId = 2)
        addMethod(method())
        addProduct(
            product(1, salePrice = 12_100, stockMode = ProductStockMode.UNTRACKED, taxRateId = 1),
        )
        addProduct(
            product(
                2,
                salePrice = 2_000,
                stockMode = ProductStockMode.UNTRACKED,
                unitId = SaleWorld.KILOGRAM,
            ),
        )
    }

    @Test
    fun `a sale with two rates gives the same minor units in every configuration`() = runTest {
        // Una unidad a 12100 con su tasa de 21 %, y 1,500 kg a 2000 con la tasa por defecto, de
        // 19 %.
        val request = SaleRequest(
            listOf(
                SaleLineRequest(1, QuantityMilli(1_000)),
                SaleLineRequest(2, QuantityMilli(1_500)),
            ),
            SaleWorld.CASH,
        )

        val result = world.registerSale()(request)

        if (!row.currencyCode.isConfirmed) {
            assertEquals(RegisterSaleResult.CurrencyNotConfirmed, result)
            return@runTest
        }
        val saleId = (result as RegisterSaleResult.Registered).saleId
        val sale = world.tables.sales.getValue(saleId).content
        val lines = world.tables.lines.values.sortedBy { it.id }.map { it.content }
        val expected = EXPECTED.getValue(row.taxMode)
        assertEquals(
            expected.lines,
            lines.map { listOf(it.netAmount.minor, it.taxAmount.minor, it.lineTotal.minor) },
        )
        assertEquals(Money(expected.net), sale.netTotal)
        assertEquals(Money(expected.tax), sale.taxTotal)
        assertEquals(sale.netTotal + sale.taxTotal, sale.total)
        assertEquals(sale.total, sale.amountDue)
    }

    /** Lo que deja cada modo, escrito a mano: neto, impuesto y total por línea, y la cabecera. */
    private data class Expected(val lines: List<List<Long>>, val net: Long, val tax: Long)

    companion object {
        private val EXPECTED = mapOf(
            TaxMode.NONE to
                Expected(
                    listOf(listOf(12_100L, 0L, 12_100L), listOf(3_000L, 0L, 3_000L)),
                    15_100,
                    0,
                ),
            // 12100 / 1,21 es 10000, y 3000 / 1,19 es 2521,008.
            TaxMode.INCLUDED to
                Expected(
                    listOf(listOf(10_000L, 2_100L, 12_100L), listOf(2_521L, 479L, 3_000L)),
                    12_521,
                    2_579,
                ),
            // 21 % de 12100 es 2541, y 19 % de 3000 es 570.
            TaxMode.ADDED to
                Expected(
                    listOf(listOf(12_100L, 2_541L, 14_641L), listOf(3_000L, 570L, 3_570L)),
                    15_100,
                    3_111,
                ),
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun parameters(): List<Array<Any>> = RegionalMatrix.withForeignDefaults()
    }
}
