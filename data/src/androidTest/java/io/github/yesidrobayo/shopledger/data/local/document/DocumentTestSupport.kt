package io.github.yesidrobayo.shopledger.data.local.document

import io.github.yesidrobayo.shopledger.data.local.BUSINESS_DATE
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.data.local.catalog.UNIT_ID

// Apoyo de las pruebas de cotizaciones, pedidos, marcas y fotos de comprobantes. Todos los nombres,
// referencias y montos son inventados. Cada documento cuadra como lo exigen sus guardas: el total
// es neto más impuesto.

/** Una cotización abierta de 10.000 más 1.900 de impuesto, sin cliente. */
internal fun quote(businessDate: String = BUSINESS_DATE): QuoteEntity = QuoteEntity(
    customerId = null,
    status = "OPEN",
    validUntil = "2026-01-31",
    netTotal = 10_000,
    taxTotal = 1_900,
    total = 11_900,
    convertedSaleId = null,
    note = null,
    occurredAt = CREATED_AT,
    businessDate = businessDate,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

/** La línea de [quote]: dos unidades a 5.000, con impuesto del 19 %. */
internal fun quoteLine(quoteId: Long, productId: Long): QuoteLineEntity = QuoteLineEntity(
    quoteId = quoteId,
    productId = productId,
    unitId = UNIT_ID,
    unitFactorMilli = 1_000,
    enteredQuantityMilli = 2_000,
    quantityMilli = 2_000,
    unitPrice = 5_000,
    lineDiscount = 0,
    netAmount = 10_000,
    taxRateBp = 1_900,
    taxNameSnapshot = "Tasa general",
    taxAmount = 1_900,
    lineTotal = 11_900,
    productNameSnapshot = "Café",
)

/** Un pedido pendiente de 25.000, con 3.000 de envío y sin cliente. */
internal fun order(businessDate: String = BUSINESS_DATE): OrderEntity = OrderEntity(
    customerId = null,
    status = "PENDING",
    deliveryAddress = "Calle de ejemplo 1",
    deliveryNote = null,
    shippingAmount = 3_000,
    dueDate = "2026-01-05",
    deliveredSaleId = null,
    total = 25_000,
    occurredAt = CREATED_AT,
    businessDate = businessDate,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

/** Una línea de pedido de [quantityMilli] milésimas a 11.000 la unidad, sin impuesto. */
internal fun orderLine(
    orderId: Long,
    productId: Long,
    quantityMilli: Long = 2_000,
): OrderLineEntity = OrderLineEntity(
    orderId = orderId,
    productId = productId,
    unitId = UNIT_ID,
    unitFactorMilli = 1_000,
    enteredQuantityMilli = quantityMilli,
    quantityMilli = quantityMilli,
    unitPrice = 11_000,
    lineDiscount = 0,
    netAmount = 22_000,
    taxRateBp = 0,
    taxNameSnapshot = null,
    taxAmount = 0,
    lineTotal = 22_000,
    productNameSnapshot = "Café",
)
