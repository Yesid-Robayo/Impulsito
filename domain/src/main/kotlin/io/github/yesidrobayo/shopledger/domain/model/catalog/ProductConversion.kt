package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Lo que dice una conversión de un producto, sin a qué producto pertenece.
 *
 * [factorMilli] son milésimas de la unidad base del producto por una unidad entera de [unitId]: una
 * caja de 24 de un producto que se lleva por unidad es 24000. Cada línea copia el factor al
 * registrarse, así que cambiarlo solo afecta a documentos nuevos. [salePrice] nulo es precio
 * calculado con el factor; con valor, es el precio de una unidad entera de [unitId]. [barcode] es el
 * código de esa presentación. [forSale] y [forPurchase] dicen dónde se ofrece.
 */
data class ConversionDetails(
    val unitId: Long,
    val factorMilli: Long,
    val salePrice: Money?,
    val barcode: String?,
    val forSale: Boolean,
    val forPurchase: Boolean,
)

/** Una conversión guardada de [productId]. */
data class ProductConversion(val id: Long, val productId: Long, val details: ConversionDetails)

/**
 * Lo que la persona escribe en la hoja de una conversión. [factorMilli] y [salePrice] ya vienen
 * leídos con el formato regional; [barcode] va tal cual, y en blanco es sin código.
 */
data class ConversionDraft(
    val productId: Long,
    val unitId: Long,
    val factorMilli: Long,
    val salePrice: Money?,
    val barcode: String?,
    val forSale: Boolean,
    val forPurchase: Boolean,
)

/** Por qué no se puede guardar una conversión. Cada uno va en su campo. */
enum class ConversionInputError {
    /** El factor es cero o menos: una conversión trae al menos una milésima de la unidad base. */
    FACTOR_NOT_POSITIVE,

    /** El factor pasa de la cantidad máxima de una línea. */
    FACTOR_TOO_HIGH,

    /** La unidad alterna es la base: una conversión une dos unidades distintas. */
    SAME_AS_BASE,

    /** El precio propio pasa del tope de un precio por unidad. */
    PRICE_TOO_HIGH,

    /** El código pasa del largo máximo. */
    BARCODE_TOO_LONG,
}

/** Una conversión con su producto, como la muestra la lista de conversiones. */
data class ConversionEntry(val conversion: ProductConversion, val product: Product)
