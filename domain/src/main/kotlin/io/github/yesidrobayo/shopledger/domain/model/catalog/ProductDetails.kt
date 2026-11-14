package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.BasisPoints
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Lo que la persona edita de un producto, sin el precio de venta ni nada que se derive del libro de
 * stock.
 *
 * El precio cambia aparte porque cada cambio deja una fila en su historial. El saldo, el costo
 * promedio y el valor del stock tampoco están aquí: los fija el libro, en la misma transacción que
 * cada fila, y una edición nunca debe pisarlos.
 *
 * [baseUnitId] es la unidad del libro de stock. Con [stockMode] y [tracksLots], ya no cambia cuando
 * el producto tiene historia: el libro no admite filas en dos unidades ni en dos modos.
 * [fixedUnitCost] solo vale en un producto sin stock propio, y es cero en los demás. [minStock] es
 * el mínimo de la alerta: cero avisa al agotarse. [parentProductId] y los ejes y valores de
 * variante describen una variante de otro producto, con un solo nivel. [targetMargin] precede al de
 * la categoría y al del negocio. [referenceCost] y [referencePrice] van en la moneda de referencia
 * que eligió la persona, nunca en la del libro. [usage] dice si se vende, si es un insumo o las
 * dos cosas: cambia libremente, porque no es historia.
 */
data class ProductDetails(
    val name: String,
    val normalizedName: String,
    val categoryId: Long?,
    val defaultSupplierId: Long?,
    val baseUnitId: Long,
    val stockMode: ProductStockMode,
    val tracksLots: Boolean,
    val fixedUnitCost: Money,
    val minStock: QuantityMilli,
    val barcode: String?,
    val parentProductId: Long?,
    val variantAxis1: String?,
    val variantAxis2: String?,
    val variantValue1: String?,
    val variantValue2: String?,
    val photoMediaId: Long?,
    val taxRateId: Long?,
    val targetMargin: BasisPoints?,
    val referenceCost: Money?,
    val referencePrice: Money?,
    val expiryNoticeDays: Int?,
    val usage: ProductUsage = ProductUsage.SALE,
)
