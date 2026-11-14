package io.github.yesidrobayo.shopledger.domain.rule.document

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.DocumentStatus
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.VoidReason
import io.github.yesidrobayo.shopledger.domain.model.Voiding
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptAmount
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptDocument
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptHeader
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptKind
import io.github.yesidrobayo.shopledger.domain.model.document.ReceiptLine
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.returns.NewSaleReturn
import io.github.yesidrobayo.shopledger.domain.model.returns.ReturnResolution
import io.github.yesidrobayo.shopledger.domain.model.returns.SaleReturn
import io.github.yesidrobayo.shopledger.domain.model.sale.SAMPLE_AT
import io.github.yesidrobayo.shopledger.domain.model.sale.SAMPLE_DATE
import io.github.yesidrobayo.shopledger.domain.model.sale.SENTINEL_COST
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.sampleDetail
import io.github.yesidrobayo.shopledger.domain.model.sale.sampleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.samplePayment
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El comprobante de una venta: encabezado, líneas antes del ajuste y cifras en el orden del papel,
 * sin costo, comisión ni nota. Las cifras esperadas están hechas a mano.
 */
class SaleReceiptDocumentTest {
    private val template = ReceiptSettings(
        logoMediaId = 5L,
        businessDetails = "  Calle Mayor 8, local 2  ",
        footerText = " Gracias por venir ",
        fiscalPortalUrl = "https://example.com/portal",
    )

    /**
     * Dos líneas, 7000 y 18000 tras su descuento, un descuento de 1000 para toda la venta, 10 % de
     * impuesto sumado encima, 1000 de propina y −400 de redondeo: se cobran 27000. El efectivo
     * cubre 20000 con 25000 recibidos, y 7000 quedan a crédito.
     */
    private fun taxedSale(): SaleDetail {
        val first = taxed(sampleLine(10L, 1L, "Arroz 1 kg", 2_000L, 3_500L), -280L, 672L)
        val second = taxed(
            sampleLine(11L, 2L, "Aceite 900 ml", 1_000L, 20_000L, discount = 2_000L),
            -720L,
            1_728L,
        )
        val cash = payment(
            samplePayment(20L, 1L, 20_000L),
            "Efectivo",
            PaymentMethodKind.CASH,
            tendered = 25_000L,
            change = 5_000L,
        )
        val credit =
            payment(samplePayment(21L, 2L, 7_000L), "A crédito", PaymentMethodKind.ON_ACCOUNT)
        val base = sampleDetail(listOf(first, second), listOf(cash, credit), total = 26_400L)
        return base.copy(
            sale = base.sale.copy(
                content = base.sale.content.copy(
                    taxTotal = Money(2_400L),
                    orderAdjustment = Money(-1_000L),
                    tipAmount = Money(1_000L),
                    cashRoundingAmount = Money(-400L),
                    amountDue = Money(27_000L),
                ),
            ),
        )
    }

    @Test
    fun `a taxed sale lists its lines before the adjustment and every amount in paper order`() {
        val document = SaleReceiptDocument.of(
            taxedSale(),
            template,
            " Tienda Central ",
            " Ana ",
            Money.ZERO,
        )

        assertEquals(
            ReceiptDocument(
                kind = ReceiptKind.SALE,
                number = 1L,
                voided = false,
                header = ReceiptHeader(5L, "Tienda Central", "Calle Mayor 8, local 2"),
                businessDate = SAMPLE_DATE,
                occurredAt = SAMPLE_AT,
                customerName = "Ana",
                lines = listOf(
                    ReceiptLine("Arroz 1 kg", QuantityMilli(2_000L), 1L, Money(7_000L), false),
                    ReceiptLine("Aceite 900 ml", QuantityMilli(1_000L), 1L, Money(18_000L), false),
                ),
                amounts = listOf(
                    ReceiptAmount.Subtotal(Money(25_000L)),
                    ReceiptAmount.Adjustment(Money(-1_000L)),
                    ReceiptAmount.Tax("General", BasisPoints(1_000L), Money(2_400L), false),
                    ReceiptAmount.Rounding(Money(-400L)),
                    ReceiptAmount.Tip(Money(1_000L)),
                    ReceiptAmount.Total(Money(27_000L)),
                    ReceiptAmount.Payment("Efectivo", Money(20_000L)),
                    ReceiptAmount.Payment("A crédito", Money(7_000L)),
                    ReceiptAmount.Change(Money(5_000L)),
                    ReceiptAmount.BalanceDue(Money(7_000L)),
                ),
                footerText = "Gracias por venir",
            ),
            document,
        )
    }

    /**
     * Lo pagado a crédito primero consume el saldo a favor que el cliente ya tenía: con 10.000 a
     * favor, los 7.000 a crédito son un anticipo aplicado y no queda nada pendiente. Con 3.000 a
     * favor, 3.000 se aplican y 4.000 quedan debiendo.
     */
    @Test
    fun `credit on account first uses the credit balance, and only the rest is due`() {
        val covered = SaleReceiptDocument.of(taxedSale(), template, null, "Ana", Money(10_000L))
        val partly = SaleReceiptDocument.of(taxedSale(), template, null, "Ana", Money(3_000L))

        assertEquals(
            listOf(
                ReceiptAmount.Change(Money(5_000L)),
                ReceiptAmount.CreditApplied(Money(7_000L)),
            ),
            covered.amounts.takeLast(2),
        )
        assertTrue(covered.amounts.none { it is ReceiptAmount.BalanceDue })
        assertEquals(
            listOf(
                ReceiptAmount.Change(Money(5_000L)),
                ReceiptAmount.CreditApplied(Money(3_000L)),
                ReceiptAmount.BalanceDue(Money(4_000L)),
            ),
            partly.amounts.takeLast(3),
        )
    }

    /**
     * La entrega de un pedido de 20.000 con 1.500 de envío y 5.000 de anticipo: la venta cobra
     * 21.500, 5.000 a crédito con el anticipo y 16.500 en efectivo. El cliente no debe nada, así
     * que el comprobante dice que se aplicó el anticipo y no lleva saldo pendiente.
     */
    @Test
    fun `a delivered order shows its deposit as applied and nothing due`() {
        val cash = payment(samplePayment(20L, 1L, 16_500L), "Efectivo", PaymentMethodKind.CASH)
        val credit =
            payment(samplePayment(21L, 2L, 5_000L), "A crédito", PaymentMethodKind.ON_ACCOUNT)
        val base = sampleDetail(
            listOf(sampleLine(10L, 1L, "Pastel", 1_000L, 20_000L)),
            listOf(cash, credit),
            total = 21_500L,
        )
        val detail = base.copy(
            sale = base.sale.copy(
                content = base.sale.content.copy(
                    orderAdjustment = Money(1_500L),
                    amountDue = Money(21_500L),
                ),
            ),
        )

        val document = SaleReceiptDocument.of(detail, template, null, "Ana", Money(5_000L))

        assertEquals(
            listOf(
                ReceiptAmount.Subtotal(Money(20_000L)),
                ReceiptAmount.Adjustment(Money(1_500L)),
                ReceiptAmount.Total(Money(21_500L)),
                ReceiptAmount.Payment("Efectivo", Money(16_500L)),
                ReceiptAmount.Payment("A crédito", Money(5_000L)),
                ReceiptAmount.CreditApplied(Money(5_000L)),
            ),
            document.amounts,
        )
    }

    /**
     * Sin pago a crédito, el saldo a favor no se toca y no aparece; un saldo negativo, que sería
     * deuda, no cuenta como saldo a favor.
     */
    @Test
    fun `a credit balance shows only when the sale used it`() {
        val detail = sampleDetail(
            listOf(sampleLine(10L, 1L, "Arroz", 1_000L, 5_000L)),
            listOf(samplePayment(20L, 1L, 5_000L)),
            5_000L,
        )

        val cash = SaleReceiptDocument.of(detail, template, null, "Ana", Money(8_000L))
        val owed = SaleReceiptDocument.of(taxedSale(), template, null, "Ana", Money(-2_000L))

        assertTrue(cash.amounts.none { it is ReceiptAmount.CreditApplied })
        assertTrue(owed.amounts.none { it is ReceiptAmount.CreditApplied })
        assertEquals(ReceiptAmount.BalanceDue(Money(7_000L)), owed.amounts.last())
    }

    @Test
    fun `tax already in the prices is reported as included`() {
        val line = taxed(sampleLine(10L, 1L, "Café", 1_000L, 11_000L), 0L, 1_000L)
            .let { it.copy(content = it.content.copy(lineTotal = Money(11_000L))) }
        val detail = sampleDetail(listOf(line), listOf(samplePayment(20L, 1L, 11_000L)), 11_000L)

        val document = SaleReceiptDocument.of(detail, template, null, null, Money.ZERO)

        assertEquals(
            listOf(
                ReceiptAmount.Subtotal(Money(11_000L)),
                ReceiptAmount.Tax("General", BasisPoints(1_000L), Money(1_000L), true),
                ReceiptAmount.Total(Money(11_000L)),
                ReceiptAmount.Payment("Método 1", Money(11_000L)),
            ),
            document.amounts,
        )
    }

    @Test
    fun `taxes with another name or rate get their own row, in the order of their first line`() {
        val lines = listOf(
            taxed(sampleLine(10L, 1L, "A", 1_000L, 1_000L), 0L, 100L),
            taxed(sampleLine(11L, 2L, "B", 1_000L, 1_000L), 0L, 50L, rate = 500L),
            taxed(sampleLine(12L, 3L, "C", 1_000L, 1_000L), 0L, 100L),
            taxed(sampleLine(13L, 4L, "D", 1_000L, 1_000L), 0L, 100L, name = null),
        )
        val detail = sampleDetail(lines, listOf(samplePayment(20L, 1L, 4_350L)), 4_350L)

        val taxes = SaleReceiptDocument.of(detail, template, null, null, Money.ZERO)
            .amounts.filterIsInstance<ReceiptAmount.Tax>()

        assertEquals(
            listOf(
                ReceiptAmount.Tax("General", BasisPoints(1_000L), Money(200L), false),
                ReceiptAmount.Tax("General", BasisPoints(500L), Money(50L), false),
                ReceiptAmount.Tax(null, BasisPoints(1_000L), Money(100L), false),
            ),
            taxes,
        )
    }

    @Test
    fun `an extra is marked, and the empty template heads nothing`() {
        val lines = listOf(
            sampleLine(10L, 1L, "Hamburguesa", 1_000L, 9_000L),
            sampleLine(11L, 2L, "Queso extra", 1_000L, 1_500L, parentLineId = 10L),
        )
        val detail = sampleDetail(lines, listOf(samplePayment(20L, 1L, 10_500L)), 10_500L)
        val empty = ReceiptSettings(null, null, "   ", null)

        val document = SaleReceiptDocument.of(detail, empty, "  ", null, Money.ZERO)

        assertEquals(listOf(false, true), document.lines.map { it.extra })
        assertEquals(ReceiptHeader(null, null, null), document.header)
        assertNull(document.footerText)
        assertNull(document.customerName)
    }

    @Test
    fun `a voided sale says so, and only active returns are listed, as money going back`() {
        val detail = sampleDetail(
            listOf(sampleLine(10L, 1L, "Arroz", 1_000L, 5_000L)),
            listOf(samplePayment(20L, 1L, 5_000L)),
            5_000L,
        ).copy(
            returns = listOf(
                saleReturn(30L, 2_000L, voided = false),
                saleReturn(31L, 1_000L, voided = true),
            ),
        )
        val voided = detail.copy(sale = detail.sale.copy(voiding = VOIDING))

        val active = SaleReceiptDocument.of(detail, template, null, null, Money.ZERO)
        val document = SaleReceiptDocument.of(voided, template, null, null, Money.ZERO)

        assertFalse(active.voided)
        assertTrue(document.voided)
        assertEquals(DocumentStatus.VOIDED, voided.sale.status)
        assertEquals(
            listOf(ReceiptAmount.Return(30L, Money(-2_000L))),
            document.amounts.filterIsInstance<ReceiptAmount.Return>(),
        )
    }

    @Test
    fun `a line whose amount does not fit falls back to its stored total`() {
        val huge = sampleLine(10L, 1L, "Lote", 1_000L, 1L).let {
            it.copy(
                content = it.content.copy(
                    enteredQuantity = QuantityMilli(Long.MAX_VALUE),
                    unitPrice = Money(Long.MAX_VALUE),
                    lineTotal = Money(9_000L),
                ),
            )
        }
        val detail = sampleDetail(listOf(huge), listOf(samplePayment(20L, 1L, 9_000L)), 9_000L)

        val document = SaleReceiptDocument.of(detail, template, null, null, Money.ZERO)

        assertEquals(Money(9_000L), document.lines.single().amount)
    }

    @Test
    fun `no cost, fee nor note reaches the document`() {
        val document = SaleReceiptDocument.of(taxedSale(), template, "Tienda", "Ana", Money.ZERO)
        val text = document.toString()

        assertFalse(text.contains(SENTINEL_COST.toString()))
        assertFalse(text.contains("Nota interna"))
        // Ni siquiera hay dónde ponerlos: ningún campo se llama como algo que no va al cliente.
        val fields =
            Regex("""(\w+)=""").findAll(text).map { it.groupValues[1].lowercase() }.toSet()
        val forbidden = listOf("cost", "margin", "profit", "fee", "note", "supplier")
        assertTrue(fields.none { field -> forbidden.any { it in field } })
    }

    private fun taxed(
        line: SaleLine,
        share: Long,
        tax: Long,
        rate: Long = 1_000L,
        name: String? = "General",
    ): SaleLine {
        val net = line.content.lineTotal.minor + share
        return line.copy(
            content = line.content.copy(
                orderAdjustmentShare = Money(share),
                netAmount = Money(net),
                taxRate = BasisPoints(rate),
                taxNameSnapshot = name,
                taxAmount = Money(tax),
                lineTotal = Money(net + tax),
            ),
        )
    }

    private fun payment(
        payment: SalePayment,
        name: String,
        kind: PaymentMethodKind,
        tendered: Long? = null,
        change: Long? = null,
    ) = payment.copy(
        content = payment.content.copy(
            methodNameSnapshot = name,
            methodKind = kind,
            tenderedAmount = tendered?.let(::Money),
            changeAmount = change?.let(::Money),
        ),
    )

    private fun saleReturn(id: Long, total: Long, voided: Boolean) = SaleReturn(
        id = id,
        content = NewSaleReturn(
            saleId = 1L,
            resolution = ReturnResolution.REFUND,
            netRefund = Money(total),
            taxRefund = Money.ZERO,
            totalRefund = Money(total),
            costReturned = Money(SENTINEL_COST),
            moneyAccountId = null,
            replacementSaleId = null,
            note = "Nota interna",
            occurredAt = SAMPLE_AT,
            businessDate = SAMPLE_DATE,
            createdAt = SAMPLE_AT,
        ),
        voiding = if (voided) VOIDING else null,
    )

    private companion object {
        val VOIDING = Voiding(SAMPLE_AT, VoidReason.ENTRY_ERROR, null)
    }
}
