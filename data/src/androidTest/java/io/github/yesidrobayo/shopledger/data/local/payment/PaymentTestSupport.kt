package io.github.yesidrobayo.shopledger.data.local.payment

import io.github.yesidrobayo.shopledger.data.local.AppDatabase
import io.github.yesidrobayo.shopledger.data.local.BUSINESS_DATE
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.sale
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

// Apoyo de las pruebas de pagos. Los nombres, comisiones y montos son inventados, y ninguno es una
// marca.

/** Un método que creó la persona: con nombre, sin clave inicial, sin comisión y vigente. */
internal fun paymentMethod(
    name: String,
    kind: String = "WALLET",
    sortOrder: Long = 10,
    archivedAt: Long? = null,
): PaymentMethodEntity = PaymentMethodEntity(
    systemKey = null,
    name = name,
    normalizedName = TextNormalizer.normalize(name),
    kind = kind,
    feeBp = 0,
    feeFixed = 0,
    settlementDays = 0,
    priceAdjustmentBp = 0,
    moneyAccountId = null,
    instructionsText = null,
    instructionsMediaId = null,
    sortOrder = sortOrder,
    archivedAt = archivedAt,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

/** Un pago con tarjeta de 3.001 con su comisión de 83, que se acredita el mismo día. */
internal fun cardPayment(saleId: Long, methodId: Long): SalePaymentEntity = SalePaymentEntity(
    saleId = saleId,
    paymentMethodId = methodId,
    methodNameSnapshot = "Tarjeta",
    methodKindSnapshot = "CARD",
    amount = 3_001,
    feeAmount = 83,
    settlementDate = BUSINESS_DATE,
    moneyAccountId = null,
    tenderedAmount = null,
    changeAmount = null,
    tenderCurrencyCode = null,
    tenderCurrencyDecimals = null,
    tenderAmount = null,
    fxRateMicro = null,
    createdAt = CREATED_AT,
)

/** El resto en efectivo: 9.799, con 10.000 recibidos y 201 de cambio. */
internal fun cashPayment(saleId: Long, methodId: Long): SalePaymentEntity =
    cardPayment(saleId, methodId).copy(
        methodNameSnapshot = "Efectivo",
        methodKindSnapshot = "CASH",
        amount = 9_799,
        feeAmount = 0,
        tenderedAmount = 10_000,
        changeAmount = 201,
    )

/**
 * Guarda una venta en [priceListId] cobrada con tarjeta y devuelve el id de su pago, para las
 * filas que apuntan a un pago, como el cobro o la comisión en el libro de dinero.
 */
internal suspend fun AppDatabase.insertSaleWithCardPayment(priceListId: Long): Long {
    val saleId = saleDao().insert(sale(priceListId))
    val methodId = requireNotNull(paymentMethodDao().findBySystemKey("CARD")) { "Falta CARD" }.id
    return salePaymentDao().insert(cardPayment(saleId, methodId))
}
