package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Lo que la persona escribe al crear o editar un producto, tal como lo escribió: el caso de uso lo
 * recorta, lo normaliza y lo valida antes de guardar nada.
 *
 * [baseUnitId] `null` es la unidad inicial de conteo, la de todo producto que no dice otra. [cost]
 * es lo que cuesta una unidad entera según el modo de stock: con stock propio, el costo con que
 * empieza su libro; sin stock, el costo fijo de cada unidad vendida; y un producto hecho de
 * componentes no lleva costo propio, porque sale de sus componentes. [minStock] es el mínimo de la
 * alerta, y cero avisa al agotarse. [barcode] en blanco es sin código.
 *
 * [replacementCost] es lo que costaría comprar hoy una unidad base, corregido a mano: cero es sin
 * dato, y `null` lo deja como está. Solo lo lleva un producto con stock propio, porque es el único
 * que se compra; lo normal es que lo actualice la última compra.
 *
 * [referencePrice] es el precio de venta escrito en la moneda de referencia, en su unidad menor,
 * del que parte repreciar con una cotización: cero es sin dato, y `null` lo deja como está.
 *
 * [photo] dice qué pasa con la foto: por omisión queda la que tenía.
 *
 * Lo que no está aquí, como las variantes o el impuesto, lo conserva la edición tal como estaba.
 */
data class ProductForm(
    val name: String,
    val categoryId: Long? = null,
    val defaultSupplierId: Long? = null,
    val baseUnitId: Long? = null,
    val stockMode: ProductStockMode = ProductStockMode.TRACKED,
    val salePrice: Money = Money.ZERO,
    val cost: Money = Money.ZERO,
    val minStock: QuantityMilli = QuantityMilli.ZERO,
    val barcode: String? = null,
    val replacementCost: Money? = null,
    val referencePrice: Money? = null,
    val photo: ProductPhotoChange = ProductPhotoChange.Keep,
)
