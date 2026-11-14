package io.github.yesidrobayo.shopledger.ui.document

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import io.github.yesidrobayo.shopledger.domain.model.sale.LineStockEffect
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSale
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.NewSalePayment
import io.github.yesidrobayo.shopledger.domain.model.sale.Sale
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleDetail
import io.github.yesidrobayo.shopledger.domain.model.sale.SaleLine
import io.github.yesidrobayo.shopledger.domain.model.sale.SalePayment
import io.github.yesidrobayo.shopledger.domain.model.settings.ReceiptSettings
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

// Ventas inventadas para las pruebas del comprobante en papel. Costos, comisiones y notas llevan
// cifras y textos centinela que ningún documento debe contener.

internal val SAMPLE_DATE = BusinessDate(LocalDate(2026, 10, 28))
internal val SAMPLE_AT: Instant = Instant.fromEpochMilliseconds(1_793_198_520_000L)
internal const val SENTINEL_COST = 777_777L
internal const val SENTINEL_NOTE = "Nota interna centinela"

internal val SAMPLE_TEMPLATE = ReceiptSettings(
    logoMediaId = null,
    businessDetails = "Calle Mayor 8, local 2",
    footerText = "Gracias por venir",
    fiscalPortalUrl = null,
)

/** Una línea de [quantity] milésimas a [unitPrice], con [tax] encima, y costos de [cost]. */
internal fun sampleLine(
    id: Long,
    name: String,
    quantity: Long,
    unitPrice: Long,
    tax: Long = 0L,
    cost: Long = SENTINEL_COST,
    parentLineId: Long? = null,
): SaleLine {
    val net = quantity * unitPrice / 1_000L
    return SaleLine(
        id = id,
        saleId = 1_204L,
        content = NewSaleLine(
            productId = id,
            unitId = 1L,
            unitFactor = QuantityMilli(1_000L),
            enteredQuantity = QuantityMilli(quantity),
            quantity = QuantityMilli(quantity),
            unitPrice = Money(unitPrice),
            unitCost = Money(cost),
            lineDiscount = Money.ZERO,
            orderAdjustmentShare = Money.ZERO,
            netAmount = Money(net),
            taxRate = BasisPoints(if (tax == 0L) 0L else 1_000L),
            taxNameSnapshot = if (tax == 0L) null else "Impuesto general",
            taxAmount = Money(tax),
            lineTotal = Money(net + tax),
            lineCost = Money(cost),
            unitReplacementCost = Money(cost),
            lineReplacementCost = Money(cost),
            productNameSnapshot = name,
            stockEffect = LineStockEffect.DIRECT,
            parentLineId = parentLineId,
            priceListId = 1L,
        ),
    )
}

/** Un pago de [amount] con [name], con una comisión de [fee]. */
internal fun samplePayment(
    id: Long,
    name: String,
    kind: PaymentMethodKind,
    amount: Long,
    fee: Long = SENTINEL_COST,
    tendered: Long? = null,
    change: Long? = null,
) = SalePayment(
    id = id,
    saleId = 1_204L,
    content = NewSalePayment(
        paymentMethodId = id,
        methodNameSnapshot = name,
        methodKind = kind,
        amount = Money(amount),
        feeAmount = Money(fee),
        settlementDate = SAMPLE_DATE,
        moneyAccountId = null,
        createdAt = SAMPLE_AT,
        tenderedAmount = tendered?.let(::Money),
        changeAmount = change?.let(::Money),
    ),
)

/**
 * La venta 1.204: arroz, aceite y un extra, con impuesto sumado encima, pagada en efectivo con
 * cambio y una parte a crédito. Con [cost], [fee] y [note] se cambian los centinelas.
 */
internal fun sampleSale(
    cost: Long = SENTINEL_COST,
    fee: Long = SENTINEL_COST,
    note: String? = SENTINEL_NOTE,
    lines: List<SaleLine> = listOf(
        sampleLine(1L, "Arroz blanco 1 kg", 2_000L, 4_200L, tax = 840L, cost = cost),
        sampleLine(2L, "Aceite de girasol 900 ml", 1_000L, 12_500L, tax = 1_250L, cost = cost),
        sampleLine(3L, "Bolsa reutilizable", 1_000L, 500L, cost = cost, parentLineId = 2L),
    ),
): SaleDetail {
    val net = lines.sumOf { it.content.netAmount.minor }
    val tax = lines.sumOf { it.content.taxAmount.minor }
    val total = net + tax
    return SaleDetail(
        sale = Sale(
            id = 1_204L,
            content = NewSale(
                netTotal = Money(net),
                taxTotal = Money(tax),
                total = Money(total),
                totalCost = Money(cost),
                totalReplacementCost = Money(cost),
                orderAdjustment = Money.ZERO,
                tipAmount = Money.ZERO,
                cashRoundingAmount = Money.ZERO,
                amountDue = Money(total),
                customerId = 3L,
                priceListId = 1L,
                locationId = 1L,
                cashSessionId = null,
                quoteId = null,
                orderId = null,
                note = note,
                occurredAt = SAMPLE_AT,
                businessDate = SAMPLE_DATE,
                createdAt = SAMPLE_AT,
            ),
            voiding = null,
        ),
        lines = lines,
        payments = listOf(
            samplePayment(
                1L,
                "Efectivo",
                PaymentMethodKind.CASH,
                total - 5_000L,
                fee,
                tendered = total,
                change = 5_000L,
            ),
            samplePayment(2L, "A crédito", PaymentMethodKind.ON_ACCOUNT, 5_000L, fee),
        ),
        archivedProductIds = emptySet(),
    )
}
