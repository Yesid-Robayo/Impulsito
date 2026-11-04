package io.github.yesidrobayo.shopledger.data.local.pricing

import io.github.yesidrobayo.shopledger.data.local.BUSINESS_DATE
import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

// Apoyo de las pruebas de precios. Los nombres, precios y cotizaciones son inventados, y la otra
// moneda usa XTS, el código que ISO 4217 reserva para pruebas.

/** Una lista fija que creó la persona, sin clave inicial y vigente. */
internal fun fixedList(name: String, archivedAt: Long? = null): PriceListEntity = PriceListEntity(
    systemKey = null,
    name = name,
    normalizedName = TextNormalizer.normalize(name),
    kind = "FIXED",
    basePriceListId = null,
    adjustmentBp = null,
    archivedAt = archivedAt,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

/** Una lista relativa a [baseId], con su ajuste en puntos básicos. */
internal fun relativeList(name: String, baseId: Long, adjustmentBp: Long): PriceListEntity =
    fixedList(name).copy(kind = "RELATIVE", basePriceListId = baseId, adjustmentBp = adjustmentBp)

internal fun productPrice(priceListId: Long, productId: Long, price: Long): ProductPriceEntity =
    ProductPriceEntity(
        priceListId = priceListId,
        productId = productId,
        price = price,
        createdAt = CREATED_AT,
        updatedAt = CREATED_AT,
    )

/** Una cotización de la moneda de pruebas, sin nota. */
internal fun fxRate(rateMicro: Long, occurredAt: Long = CREATED_AT): FxRateEntity = FxRateEntity(
    currencyCode = TEST_CURRENCY,
    rateMicro = rateMicro,
    note = null,
    occurredAt = occurredAt,
    businessDate = BUSINESS_DATE,
    createdAt = occurredAt,
)

internal const val TEST_CURRENCY = "XTS"
