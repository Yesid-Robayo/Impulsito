package io.github.yesidrobayo.shopledger.domain.usecase.catalog

import io.github.yesidrobayo.shopledger.domain.model.catalog.ConversionDetails
import io.github.yesidrobayo.shopledger.domain.model.catalog.ProductConversion

/**
 * Siembra una conversión de [productId] con el código [barcode], como la dejaría la base: es el
 * estado del que parte una prueba, no una escritura de un caso de uso.
 */
internal fun CatalogFixture.seedConversion(
    id: Long,
    productId: Long,
    barcode: String?,
): ProductConversion {
    val details =
        ConversionDetails(
            unitId = CatalogFixture.KILOGRAM_ID,
            factorMilli = 24_000L,
            salePrice = null,
            barcode = barcode,
            forSale = true,
            forPurchase = true,
        )
    val conversion = ProductConversion(id, productId, details)
    database.seed { it.copy(productUnits = it.productUnits + (id to conversion)) }
    return conversion
}
