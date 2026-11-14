package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRejection
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboTestProducts.product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Qué línea de una transferencia se puede mover, y el saldo que deja su salida. */
class StockTransferRulesTest {
    private val tracked = product(1L)

    private fun check(
        quantity: Long,
        allowsFraction: Boolean = false,
        subject: Product? = tracked,
    ) = StockTransferRules.lineRejection(subject, allowsFraction, QuantityMilli(quantity))

    @Test
    fun `a tracked product moves whole units, or fractions when its unit allows them`() {
        assertNull(check(4_000L))
        assertNull(check(1_500L, allowsFraction = true))
        assertNull(check(1_000_000_000L))
    }

    @Test
    fun `each reason is named before writing`() {
        assertEquals(StockTransferLineRejection.PRODUCT_NOT_FOUND, check(1_000L, subject = null))
        assertEquals(
            StockTransferLineRejection.PRODUCT_ARCHIVED,
            check(1_000L, subject = product(2L, archived = true)),
        )
        assertEquals(
            StockTransferLineRejection.NOT_TRACKED,
            check(1_000L, subject = product(3L, mode = ProductStockMode.COMPONENTS)),
        )
        val lotted = product(4L).let { it.copy(details = it.details.copy(tracksLots = true)) }
        assertEquals(StockTransferLineRejection.LOTS_NOT_SUPPORTED, check(1_000L, subject = lotted))
        assertEquals(StockTransferLineRejection.INVALID_QUANTITY, check(1_500L))
        assertEquals(StockTransferLineRejection.INVALID_QUANTITY, check(0L))
        assertEquals(StockTransferLineRejection.QUANTITY_TOO_LARGE, check(1_000_001_000L))
    }

    @Test
    fun `the exit lowers the balance and can leave it negative`() {
        assertEquals(
            QuantityMilli(6_000L),
            StockTransferRules.balanceAfterExit(QuantityMilli(10_000L), QuantityMilli(4_000L)),
        )
        assertEquals(
            QuantityMilli(-2_000L),
            StockTransferRules.balanceAfterExit(QuantityMilli(10_000L), QuantityMilli(12_000L)),
        )
    }
}
