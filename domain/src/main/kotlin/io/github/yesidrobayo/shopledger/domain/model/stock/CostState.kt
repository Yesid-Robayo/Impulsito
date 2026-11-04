package io.github.yesidrobayo.shopledger.domain.model.stock

import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Lo que el costo de un producto sabe de su stock: cuántas milésimas hay y a qué costo promedio.
 *
 * [onHand] puede ser negativo, porque vender nunca se bloquea por falta de stock: el número
 * negativo avisa que falta registrar una compra o un conteo. [averageCost] es el costo de una
 * unidad entera en la unidad menor de la moneda, y es el que congela cada venta al confirmarse.
 *
 * El valor total del stock no se guarda aquí: se deduce del promedio cuando una cuenta lo necesita,
 * como `onHand × averageCost / 1000`, redondeado, y vale cero sin unidades. Ese redondeo puede
 * dejar una unidad menor de diferencia entre lo comprado y lo vendido al vaciar el stock.
 */
data class CostState(val onHand: QuantityMilli, val averageCost: Money)
