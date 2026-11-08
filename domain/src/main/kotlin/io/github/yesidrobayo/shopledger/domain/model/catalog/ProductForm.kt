package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Lo que la persona escribe al crear o editar un producto, tal como lo escribió: el caso de uso lo
 * recorta, lo normaliza y lo valida antes de guardar nada.
 *
 * [baseUnitId] `null` es la unidad inicial de conteo, la de todo producto que no dice otra.
 * [cost] es lo que cuesta una unidad entera según el modo de stock: con stock propio, el costo con
 * que empieza su libro; sin stock, el costo fijo de cada unidad vendida; y un producto hecho de
 * componentes no lleva costo propio, porque sale de sus componentes. [minStock] es el mínimo de la
 * alerta, y cero avisa al agotarse. [barcode] en blanco es sin código.
 *
 * Lo que no está aquí, como las variantes, la foto o el impuesto, lo conserva la edición tal como
 * estaba.
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
)
