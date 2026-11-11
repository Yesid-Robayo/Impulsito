package io.github.yesidrobayo.shopledger.domain.model.sale

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.BusinessDate
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.payment.PaymentMethodKind
import kotlin.time.Instant
import kotlinx.datetime.LocalDate

// Ventas guardadas inventadas para las pruebas del comprobante y de la corrección. Los costos y las
// comisiones son cifras centinela que ningún comprobante debe contener.

internal val SAMPLE_DATE = BusinessDate(LocalDate(2026, 10, 30))
internal val SAMPLE_AT: Instant = Instant.fromEpochMilliseconds(1_793_386_800_000L)
internal const val SENTINEL_COST = 777_777L

/** Una línea guardada de [productId], con [quantity] milésimas a [unitPrice] y su descuento. */
internal fun sampleLine(
    id: Long,
    productId: Long,
    name: String,
    quantity: Long,
    unitPrice: Long,
    discount: Long = 0L,
    parentLineId: Long? = null,
): SaleLine {
    val gross = quantity * unitPrice / 1_000L
    return SaleLine(
        id = id,
        saleId = 1L,
        content = NewSaleLine(
            productId = productId,
            unitId = 1L,
            unitFactor = QuantityMilli(1_000L),
            enteredQuantity = QuantityMilli(quantity),
            quantity = QuantityMilli(quantity),
            unitPrice = Money(unitPrice),
            unitCost = Money(SENTINEL_COST),
            lineDiscount = Money(discount),
            orderAdjustmentShare = Money.ZERO,
            netAmount = Money(gross - discount),
            taxRate = BasisPoints.ZERO,
            taxNameSnapshot = null,
            taxAmount = Money.ZERO,
            lineTotal = Money(gross - discount),
            lineCost = Money(SENTINEL_COST),
            unitReplacementCost = Money(SENTINEL_COST),
            lineReplacementCost = Money(SENTINEL_COST),
            productNameSnapshot = name,
            stockEffect = LineStockEffect.DIRECT,
            parentLineId = parentLineId,
            priceListId = 1L,
        ),
    )
}

/** Un pago guardado de [amount] con el método [methodId]. */
internal fun samplePayment(id: Long, methodId: Long, amount: Long) = SalePayment(
    id = id,
    saleId = 1L,
    content = NewSalePayment(
        paymentMethodId = methodId,
        methodNameSnapshot = "Método $methodId",
        methodKind = PaymentMethodKind.OTHER,
        amount = Money(amount),
        feeAmount = Money(SENTINEL_COST),
        settlementDate = SAMPLE_DATE,
        moneyAccountId = null,
        createdAt = SAMPLE_AT,
    ),
)

/** Una venta de [total] con sus [lines] y [payments], y la [note] de la persona. */
internal fun sampleDetail(
    lines: List<SaleLine>,
    payments: List<SalePayment>,
    total: Long,
    note: String? = "Nota interna",
    archived: Set<Long> = emptySet(),
) = SaleDetail(
    sale = Sale(
        id = 1L,
        content = NewSale(
            netTotal = Money(total),
            taxTotal = Money.ZERO,
            total = Money(total),
            totalCost = Money(SENTINEL_COST),
            totalReplacementCost = Money(SENTINEL_COST),
            orderAdjustment = Money.ZERO,
            tipAmount = Money.ZERO,
            cashRoundingAmount = Money.ZERO,
            amountDue = Money(total),
            customerId = null,
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
    payments = payments,
    archivedProductIds = archived,
)
