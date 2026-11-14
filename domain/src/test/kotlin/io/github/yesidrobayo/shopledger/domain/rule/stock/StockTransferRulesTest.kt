package io.github.yesidrobayo.shopledger.domain.rule.stock

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.catalog.Product
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductStockMode
import io.github.yesidrobayo.shopledger.domain.model.stock.StockLot
import io.github.yesidrobayo.shopledger.domain.model.stock.StockTransferLineRejection
import io.github.yesidrobayo.shopledger.domain.rule.catalog.ComboTestProducts.product
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qué línea de una transferencia se puede mover, y el saldo que deja su salida. */
class StockTransferRulesTest {
    private val tracked = product(1L)
    private val lotted = product(4L).let { it.copy(details = it.details.copy(tracksLots = true)) }

    private fun check(
        quantity: Long,
        allowsFraction: Boolean = false,
        subject: Product? = tracked,
    ) = StockTransferRules.lineRejection(subject, allowsFraction, QuantityMilli(quantity))

    private fun lot(id: Long, productId: Long, unassigned: Boolean = false) = StockLot(
        id = id,
        productId = productId,
        code = if (unassigned) null else "L-$id",
        isUnassigned = unassigned,
        expiryDate = null,
        purchaseLineId = null,
        expiryNotifiedAt = null,
        createdAt = Instant.fromEpochSeconds(0L),
        closedAt = null,
    )

    private fun checkLot(subject: Product, lotId: Long, found: StockLot?) =
        StockTransferRules.lineRejection(
            subject,
            allowsFraction = false,
            quantity = QuantityMilli(1_000L),
            chosenLotId = lotId,
            chosenLot = found,
        )

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
        assertEquals(StockTransferLineRejection.INVALID_QUANTITY, check(1_500L))
        assertEquals(StockTransferLineRejection.INVALID_QUANTITY, check(0L))
        assertEquals(StockTransferLineRejection.QUANTITY_TOO_LARGE, check(1_000_001_000L))
    }

    @Test
    fun `a product with lots moves, with or without a chosen lot of its own`() {
        assertNull(check(1_000L, subject = lotted))
        assertNull(checkLot(lotted, 7L, lot(7L, productId = 4L)))
        // El lote sin asignar también se elige: saca todo de él.
        assertNull(checkLot(lotted, 8L, lot(8L, productId = 4L, unassigned = true)))
    }

    @Test
    fun `a chosen lot that is missing, foreign or on a product without lots is rejected`() {
        val missing = StockTransferLineRejection.LOT_NOT_FOUND
        assertEquals(missing, checkLot(lotted, 7L, found = null))
        assertEquals(missing, checkLot(lotted, 7L, lot(7L, productId = 9L)))
        assertEquals(missing, checkLot(lotted, 7L, lot(6L, productId = 4L)))
        assertEquals(missing, checkLot(tracked, 7L, lot(7L, productId = 1L)))
        // El lote se revisa antes que la cantidad, pero después del producto.
        assertEquals(
            StockTransferLineRejection.PRODUCT_NOT_FOUND,
            StockTransferRules.lineRejection(null, false, QuantityMilli(1_000L), 7L, null),
        )
    }

    @Test
    fun `a lot fits only when it is the chosen one of a product with lots`() {
        assertTrue(StockTransferRules.lotFits(lotted, 7L, lot(7L, productId = 4L)))
        assertFalse(StockTransferRules.lotFits(tracked, 7L, lot(7L, productId = 1L)))
        assertFalse(StockTransferRules.lotFits(lotted, 7L, null))
        assertFalse(StockTransferRules.lotFits(lotted, 7L, lot(5L, productId = 4L)))
        assertFalse(StockTransferRules.lotFits(lotted, 7L, lot(7L, productId = 2L)))
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
