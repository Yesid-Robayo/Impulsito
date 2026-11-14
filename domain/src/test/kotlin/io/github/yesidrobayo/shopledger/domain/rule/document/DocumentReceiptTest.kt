package io.github.yesidrobayo.shopledger.domain.rule.document

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.DocumentTotals
import io.github.yesidrobayo.shopledger.domain.model.document.NewDocumentLine
import io.github.yesidrobayo.shopledger.domain.model.document.Order
import io.github.yesidrobayo.shopledger.domain.model.document.OrderStatus
import io.github.yesidrobayo.shopledger.domain.model.document.Quote
import io.github.yesidrobayo.shopledger.domain.model.document.QuoteStatus
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptAmount
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptKind
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptLine
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptSettings
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El documento de una cotización y de un pedido: su propio tipo, sin costo ni nota, con el
 * impuesto sumado o incluido, el envío del pedido y la fecha que importa. Los montos esperados
 * están escritos a mano.
 */
class DocumentReceiptTest {
    @Test
    fun `a quote keeps its kind, its validity and adds the tax on top`() {
        val document = DocumentReceipt.ofQuote(
            quote(),
            listOf(RICE, OIL),
            TEMPLATE,
            "  Tienda  ",
            "Ana",
        )

        assertEquals(ReceiptKind.QUOTE, document.kind)
        assertEquals(41L, document.number)
        assertFalse(document.voided)
        assertEquals(ReceiptHeader(7L, "Tienda", null), document.header)
        assertEquals(VALID_UNTIL, document.validUntil)
        assertNull(document.dueDate)
        assertEquals(TODAY, document.businessDate)
        assertEquals(CREATED, document.occurredAt)
        assertEquals("Ana", document.customerName)
        assertEquals("Gracias", document.footerText)
        assertEquals(
            listOf(
                ReceiptLine("Arroz", QuantityMilli(2_000L), 1L, Money(5_000L), extra = false),
                ReceiptLine("Aceite", QuantityMilli(1_000L), 1L, Money(12_000L), extra = false),
            ),
            document.lines,
        )
        assertEquals(
            listOf(
                ReceiptAmount.Subtotal(Money(17_000L)),
                ReceiptAmount.Tax("Impuesto A", BasisPoints(1_900L), Money(950L), included = false),
                ReceiptAmount.Total(Money(17_950L)),
            ),
            document.amounts,
        )
    }

    @Test
    fun `an order adds its shipping and its delivery date`() {
        val document = DocumentReceipt.ofOrder(
            order(Money(1_500L)),
            listOf(RICE, OIL),
            TEMPLATE,
            null,
            null,
        )

        assertEquals(ReceiptKind.ORDER, document.kind)
        assertEquals(58L, document.number)
        assertEquals(DUE, document.dueDate)
        assertNull(document.validUntil)
        assertNull(document.customerName)
        assertNull(document.header.businessName)
        assertEquals(
            listOf(
                ReceiptAmount.Subtotal(Money(17_000L)),
                ReceiptAmount.Tax("Impuesto A", BasisPoints(1_900L), Money(950L), included = false),
                ReceiptAmount.Shipping(Money(1_500L)),
                ReceiptAmount.Total(Money(19_450L)),
            ),
            document.amounts,
        )
    }

    @Test
    fun `included tax is informed and an order without shipping has no shipping row`() {
        val included = line(
            name = "Pan",
            quantity = 1_000L,
            price = 1_190L,
            discount = 0L,
            tax = 190L,
            total = 1_190L,
        )
        val document = DocumentReceipt.ofOrder(
            order(Money.ZERO).copy(total = Money(1_190L)),
            listOf(included),
            ReceiptSettings(null, "  ", "", null),
            "Tienda",
            "  ",
        )

        assertEquals(
            listOf(
                ReceiptAmount.Subtotal(Money(1_190L)),
                ReceiptAmount.Tax("Impuesto A", BasisPoints(1_900L), Money(190L), included = true),
                ReceiptAmount.Total(Money(1_190L)),
            ),
            document.amounts,
        )
        assertNull(document.header.businessDetails)
        assertNull(document.footerText)
        assertNull(document.customerName)
    }

    @Test
    fun `a line whose gross does not fit uses its saved total`() {
        val huge = line(
            name = "Lote",
            quantity = Long.MAX_VALUE,
            price = Long.MAX_VALUE,
            discount = 0L,
            tax = 0L,
            total = 9_000L,
        )
        val document = DocumentReceipt.ofQuote(
            quote().copy(totals = DocumentTotals(Money(9_000L), Money.ZERO, Money(9_000L))),
            listOf(huge),
            TEMPLATE,
            null,
            null,
        )

        assertEquals(Money(9_000L), document.lines.single().amount)
        assertEquals(
            listOf(ReceiptAmount.Subtotal(Money(9_000L)), ReceiptAmount.Total(Money(9_000L))),
            document.amounts,
        )
    }

    private companion object {
        val TODAY = BusinessDate(LocalDate(2026, 11, 2))
        val VALID_UNTIL = BusinessDate(LocalDate(2026, 11, 20))
        val DUE = BusinessDate(LocalDate(2026, 11, 28))
        val CREATED = Instant.parse("2026-11-02T15:00:00Z")
        val TEMPLATE = ReceiptSettings(7L, null, " Gracias ", null)

        val RICE = line("Arroz", 2_000L, 2_500L, 0L, 950L, 5_950L)
        val OIL = line("Aceite", 1_000L, 12_500L, 500L, 0L, 12_000L)

        fun line(
            name: String,
            quantity: Long,
            price: Long,
            discount: Long,
            tax: Long,
            total: Long,
        ) = DocumentLine(
            id = 1L,
            documentId = 41L,
            content = NewDocumentLine(
                productId = 1L,
                unitId = 1L,
                unitFactor = QuantityMilli(1_000L),
                enteredQuantity = QuantityMilli(quantity),
                quantity = QuantityMilli(quantity),
                unitPrice = Money(price),
                lineDiscount = Money(discount),
                netAmount = Money(total - tax),
                taxRate = if (tax == 0L) BasisPoints.ZERO else BasisPoints(1_900L),
                taxNameSnapshot = if (tax == 0L) null else "Impuesto A",
                taxAmount = Money(tax),
                lineTotal = Money(total),
                productNameSnapshot = name,
            ),
        )

        fun quote() = Quote(
            id = 41L,
            customerId = 30L,
            status = QuoteStatus.OPEN,
            validUntil = VALID_UNTIL,
            totals = DocumentTotals(Money(17_000L), Money(950L), Money(17_950L)),
            convertedSaleId = null,
            note = "Nota interna",
            occurredAt = CREATED,
            businessDate = TODAY,
            createdAt = CREATED,
            updatedAt = CREATED,
        )

        fun order(shipping: Money) = Order(
            id = 58L,
            customerId = 30L,
            status = OrderStatus.PENDING,
            deliveryAddress = "Calle 8",
            deliveryNote = "Timbre",
            shippingAmount = shipping,
            dueDate = DUE,
            deliveredSaleId = null,
            total = Money(17_950L) + shipping,
            occurredAt = CREATED,
            businessDate = TODAY,
            createdAt = CREATED,
            updatedAt = CREATED,
        )
    }
}
