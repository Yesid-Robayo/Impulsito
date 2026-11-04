package io.github.yesidrobayo.shopledger.data.local.stock

import io.github.yesidrobayo.shopledger.data.local.catalog.CREATED_AT
import io.github.yesidrobayo.shopledger.domain.rule.TextNormalizer

// Apoyo de las pruebas de ubicaciones, lotes y saldos. Todos los nombres y códigos son inventados.

/** La ubicación inicial `MAIN`, la única que nace con la base. */
internal const val MAIN_LOCATION_ID = 1L

/** Una ubicación de la persona, con nombre y sin clave inicial. */
internal fun location(name: String, kind: String = "WAREHOUSE"): LocationEntity = LocationEntity(
    systemKey = null,
    name = name,
    normalizedName = TextNormalizer.normalize(name),
    kind = kind,
    archivedAt = null,
    createdAt = CREATED_AT,
    updatedAt = CREATED_AT,
)

/** Un lote con código, sin vencimiento ni línea de compra, abierto. */
internal fun lot(productId: Long, lotCode: String, expiryDate: String? = null): StockLotEntity =
    StockLotEntity(
        productId = productId,
        lotCode = lotCode,
        isUnassigned = 0,
        expiryDate = expiryDate,
        purchaseLineId = null,
        expiryNotifiedAt = null,
        createdAt = CREATED_AT,
        closedAt = null,
    )

/** El lote sin asignar de un producto: sin código ni vencimiento. */
internal fun unassignedLot(productId: Long): StockLotEntity =
    lot(productId, lotCode = "").copy(lotCode = null, isUnassigned = 1)
