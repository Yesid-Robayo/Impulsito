package io.github.yesidrobayo.shopledger.domain.rule.purchase

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.NewPurchaseLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.Purchase
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseDetail
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseDetailLine
import io.github.yesidrobayo.shopledger.domain.model.purchase.PurchaseLine
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que trae corregir una compra: las líneas en su unidad con su total, el proveedor si sigue
 * vigente, la referencia y la fecha. Datos inventados.
 */
class PurchaseCorrectionTest {
    private val at = Instant.fromEpochMilliseconds(0)
    private val date = BusinessDate(LocalDate(2026, 10, 30))

    private fun line(
        id: Long,
        productId: Long,
        unitId: Long,
        milli: Long,
        total: Long,
        archived: Boolean = false,
    ) = PurchaseDetailLine(
        line = PurchaseLine(
            id,
            1,
            NewPurchaseLine(
                productId = productId,
                unitId = unitId,
                unitFactor = QuantityMilli.ofUnits(1),
                enteredQuantity = QuantityMilli(milli),
                quantity = QuantityMilli(milli),
                unitCost = Money(0),
                lineTotal = Money(total),
                taxRate = BasisPoints.ZERO,
                taxNameSnapshot = null,
                taxAmount = Money.ZERO,
                costAmount = Money(total),
                lotId = null,
                referenceUnitCost = null,
            ),
        ),
        productName = "Producto inventado",
        productArchived = archived,
        unit = null,
    )

    private fun detail(supplierId: Long?, vararg lines: PurchaseDetailLine) = PurchaseDetail(
        purchase = Purchase(
            id = 1,
            content = NewPurchase(
                supplierId = supplierId,
                invoiceReference = "F-0042",
                netTotal = Money(0),
                taxTotal = Money.ZERO,
                total = Money(0),
                costTotal = Money(0),
                amountPaid = Money(0),
                moneyAccountId = null,
                dueDate = null,
                locationId = 1,
                foreignCurrency = null,
                occurredAt = at,
                businessDate = date,
                createdAt = at,
            ),
            voiding = null,
        ),
        supplierName = null,
        lines = lines.toList(),
        voidEffects = emptyList(),
    )

    @Test
    fun `lines travel in their unit with their total, in order, and repeated`() {
        val correction = PurchaseCorrections.of(
            detail(
                7,
                line(1, 10, unitId = 3, milli = 1_000, total = 10_000),
                line(2, 11, unitId = 1, milli = 2_500, total = 3_000),
                line(3, 10, unitId = 1, milli = 4_000, total = 1_600),
            ),
            supplierAvailable = true,
        )

        assertEquals(
            listOf(
                PurchaseCorrectionLine(10, 3, QuantityMilli(1_000), Money(10_000)),
                PurchaseCorrectionLine(11, 1, QuantityMilli(2_500), Money(3_000)),
                PurchaseCorrectionLine(10, 1, QuantityMilli(4_000), Money(1_600)),
            ),
            correction.lines,
        )
        assertEquals(7L, correction.supplierId)
        assertEquals("F-0042", correction.invoiceReference)
        assertEquals(date, correction.date)
        assertEquals(0, correction.leftOut)
        assertFalse(correction.supplierLeftOut)
    }

    @Test
    fun `archived products and an archived supplier are left out and counted`() {
        val correction = PurchaseCorrections.of(
            detail(
                7,
                line(1, 10, unitId = 1, milli = 1_000, total = 500, archived = true),
                line(2, 11, unitId = 1, milli = 1_000, total = 700),
            ),
            supplierAvailable = false,
        )

        assertEquals(listOf(11L), correction.lines.map { it.productId })
        assertEquals(1, correction.leftOut)
        assertNull(correction.supplierId)
        assertTrue(correction.supplierLeftOut)
    }

    @Test
    fun `a purchase without supplier never says the supplier was left out`() {
        val correction = PurchaseCorrections.of(
            detail(null, line(1, 10, unitId = 1, milli = 1_000, total = 500)),
            supplierAvailable = false,
        )

        assertNull(correction.supplierId)
        assertFalse(correction.supplierLeftOut)
    }
}
