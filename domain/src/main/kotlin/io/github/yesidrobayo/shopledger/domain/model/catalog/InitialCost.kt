package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * Cómo escribió la persona el costo del stock inicial de un producto nuevo.
 *
 * Por unidad, el costo es el de [ProductForm.cost] y el valor del stock sale de multiplicarlo por
 * la cantidad. Con el total, lo pagado por todo el stock inicial entra entero al valor del stock,
 * como el total de una línea de compra, y el costo por unidad sale de dividirlo. Así un producto
 * barato que se lleva en gramos no pierde su valor por el redondeo: mil gramos que costaron 400
 * valen 400, aunque el gramo redondee a cero, y tres kilogramos por 1000 valen 1000, no los 999 de
 * 333 × 3.
 */
sealed interface InitialCost {
    /** El costo de una unidad entera es el del formulario. Es lo que se usa sin stock inicial. */
    data object PerUnit : InitialCost

    /**
     * Lo que costó todo el stock inicial, [total]. El costo del formulario no cuenta: lo reemplaza
     * el que sale de dividir el total por la cantidad, que es el costo inicial del producto.
     */
    data class Total(val total: Money) : InitialCost
}
