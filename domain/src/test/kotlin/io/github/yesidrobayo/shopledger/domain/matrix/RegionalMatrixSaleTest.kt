package io.github.yesidrobayo.shopledger.domain.matrix

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.LineDiscount
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLineRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePaymentRequest
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleRequest
import io.github.yesidrobayo.shopledger.domain.model.settings.TaxMode
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaults
import io.github.yesidrobayo.shopledger.domain.testing.ForeignSystemDefaultsRule
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrix
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixRow
import io.github.yesidrobayo.shopledger.domain.testing.RegionalMatrixSale
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
 * La venta completa que la matriz recorre en el dispositivo, con dos descuentos de línea, uno de
 * toda la venta, dos pagos y el redondeo del efectivo, da aquí las cifras escritas a mano de
 * [RegionalMatrixSale]: así un error de cuenta en esas cifras se ve en `check`, antes del
 * teléfono.
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
        // La venta de la matriz en el dispositivo: el aceite y el queso, con la tasa por defecto,
        // y la tarjeta para el segundo pago.
        addMethod(method(id = CARD, kind = PaymentMethodKind.CARD))
        addProduct(
            product(
                OIL,
                salePrice = RegionalMatrixSale.OIL_PRICE,
                stockMode = ProductStockMode.UNTRACKED,
            ),
        )
        addProduct(
            product(
                CHEESE,
                salePrice = RegionalMatrixSale.CHEESE_PRICE,
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
        // Se cobra en efectivo: lo que se cobra es el total redondeado al paso de la fila. Las filas
        // con paso 5 y 50 cobran 15100, que ya es múltiplo; la de 1000 baja de 15100 a 15000.
        val rounding = if (row.cashRounding == Money(1_000L)) -100L else 0L
        assertEquals(Money(rounding), sale.cashRoundingAmount)
        assertEquals(sale.total + sale.cashRoundingAmount, sale.amountDue)
    }

    @Test
    fun `the full sale of the device matrix gives the hand written minor units`() = runTest {
        val expected = RegionalMatrixSale.saleFor(row.taxMode)
        val request = SaleRequest(
            lines = listOf(
                SaleLineRequest(
                    OIL,
                    QuantityMilli(RegionalMatrixSale.OIL_SOLD_MILLI),
                    discount =
                        LineDiscount.Percent(BasisPoints(RegionalMatrixSale.OIL_DISCOUNT_BP)),
                ),
                SaleLineRequest(
                    CHEESE,
                    QuantityMilli(RegionalMatrixSale.CHEESE_SOLD_MILLI),
                    discount = LineDiscount.Amount(Money(RegionalMatrixSale.CHEESE_DISCOUNT)),
                ),
            ),
            paymentMethodId = SaleWorld.CASH,
            payments = listOf(
                SalePaymentRequest(SaleWorld.CASH, Money(expected.cashPart)),
                SalePaymentRequest(CARD, Money(RegionalMatrixSale.CARD_PAYMENT)),
            ),
            orderAdjustment = Money(RegionalMatrixSale.ORDER_ADJUSTMENT),
        )

        val result = world.registerSale()(request)

        if (!row.currencyCode.isConfirmed) {
            assertEquals(RegisterSaleResult.CurrencyNotConfirmed, result)
            return@runTest
        }
        val saleId = (result as RegisterSaleResult.Registered).saleId
        val sale = world.tables.sales.getValue(saleId).content
        val lines = world.tables.lines.values.sortedBy { it.id }.map { it.content }
        val discounts = listOf(2_420L, RegionalMatrixSale.CHEESE_DISCOUNT)
        val shares = listOf(RegionalMatrixSale.OIL_SHARE, RegionalMatrixSale.CHEESE_SHARE)
        assertEquals(
            expected.lines.mapIndexed { index, line ->
                listOf(discounts[index], shares[index], line.net, line.tax, line.total)
            },
            lines.map {
                listOf(
                    it.lineDiscount.minor,
                    it.orderAdjustmentShare.minor,
                    it.netAmount.minor,
                    it.taxAmount.minor,
                    it.lineTotal.minor,
                )
            },
        )
        assertEquals(Money(expected.net), sale.netTotal)
        assertEquals(Money(expected.tax), sale.taxTotal)
        assertEquals(Money(expected.total), sale.total)
        val rounding =
            RegionalMatrixSale.cashRoundingOf(expected.cashPart, row.cashRounding.minor)
        assertEquals(Money(rounding), sale.cashRoundingAmount)
        assertEquals(Money(expected.total + rounding), sale.amountDue)
        // El efectivo lleva su redondeo; la tarjeta cobra lo escrito.
        assertEquals(
            listOf(expected.cashPart + rounding, RegionalMatrixSale.CARD_PAYMENT),
            world.tables.payments.values.sortedBy { it.id }.map { it.content.amount.minor },
        )
    }

    /** Lo que deja cada modo, escrito a mano: neto, impuesto y total por línea, y la cabecera. */
    private data class Expected(val lines: List<List<Long>>, val net: Long, val tax: Long)

    companion object {
        private const val CARD = 2L
        private const val OIL = 3L
        private const val CHEESE = 4L

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
