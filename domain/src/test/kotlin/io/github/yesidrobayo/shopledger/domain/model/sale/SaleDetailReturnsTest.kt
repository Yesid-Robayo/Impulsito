package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.returns.LineAmounts
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que el detalle de una venta dice de sus devoluciones: con una devolución activa no se anula y se
 * devuelve el resto; anulada la devolución, se vuelve a poder anular; y lo que falta de cada línea es
 * lo vendido menos lo devuelto en devoluciones activas.
 */
class SaleDetailReturnsTest {
    private val soap =
        sampleLine(1L, productId = 10L, name = "Soap", quantity = 3_000, unitPrice = 1_000)
    private val rice =
        sampleLine(2L, productId = 11L, name = "Rice", quantity = 2_000, unitPrice = 500)
    private val detail = sampleDetail(listOf(soap, rice), emptyList(), total = 4_000)

    private fun saleReturn(id: Long, voided: Boolean = false) = SaleReturn(
        id = id,
        content = NewSaleReturn(
            saleId = 1L,
            resolution = ReturnResolution.REFUND,
            netRefund = Money(1_000),
            taxRefund = Money.ZERO,
            totalRefund = Money(1_000),
            costReturned = Money.ZERO,
            moneyAccountId = null,
            replacementSaleId = null,
            note = null,
            occurredAt = SAMPLE_AT,
            businessDate = SAMPLE_DATE,
            createdAt = SAMPLE_AT,
        ),
        voiding = if (voided) Voiding(SAMPLE_AT, VoidReason.ENTRY_ERROR, null) else null,
    )

    private fun taken(milli: Long) =
        LineAmounts(QuantityMilli(milli), Money(milli), Money.ZERO, Money.ZERO)

    @Test
    fun aSaleWithoutReturnsCanBeVoidedAndReturned() {
        assertFalse(detail.hasActiveReturns)
        assertTrue(detail.canBeVoided)
        assertTrue(detail.canBeReturned)
        assertEquals(QuantityMilli(3_000), detail.remainingOf(soap))
    }

    @Test
    fun anActiveReturnBlocksVoidingAndLeavesTheRestToReturn() {
        val returned = detail.copy(
            returns = listOf(saleReturn(5L)),
            returned = mapOf(soap.id to taken(1_000)),
        )

        assertTrue(returned.hasActiveReturns)
        assertFalse(returned.canBeVoided)
        assertTrue(returned.canBeReturned)
        assertEquals(QuantityMilli(2_000), returned.remainingOf(soap))
        assertEquals(QuantityMilli(2_000), returned.remainingOf(rice))
    }

    @Test
    fun aVoidedReturnNoLongerBlocksVoiding() {
        val undone = detail.copy(returns = listOf(saleReturn(5L, voided = true)))

        assertFalse(undone.hasActiveReturns)
        assertTrue(undone.canBeVoided)
    }

    @Test
    fun everythingReturnedLeavesNothingToReturn() {
        val all = detail.copy(
            returns = listOf(saleReturn(5L), saleReturn(6L)),
            returned = mapOf(soap.id to taken(3_000), rice.id to taken(2_000)),
        )

        assertFalse(all.canBeReturned)
        assertFalse(all.canBeVoided)
        assertEquals(QuantityMilli.ZERO, all.remainingOf(rice))
    }

    @Test
    fun aVoidedSaleIsNeitherVoidedNorReturnedAgain() {
        val voided = detail.copy(
            sale = detail.sale.copy(voiding = Voiding(SAMPLE_AT, VoidReason.ENTRY_ERROR, null)),
        )

        assertFalse(voided.canBeVoided)
        assertFalse(voided.canBeReturned)
    }
}
