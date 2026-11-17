package io.github.yesidrobayo.shopledger.domain.rule.phrase

import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.Money
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.model.map
import io.github.yesidrobayo.shopledger.domain.model.phrase.DraftLine
import io.github.yesidrobayo.shopledger.domain.model.valueOrNull
import io.github.yesidrobayo.shopledger.domain.rule.mulDivCeil
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound
import io.github.yesidrobayo.shopledger.domain.rule.timesQuantity

/**
 * Una línea de venta que cobra exactamente un total dicho: [unitPrice] por unidad entera y
 * [discount] de descuento. El precio va redondeado hacia arriba y el descuento quita lo que sobra,
 * así que la línea cobra el total sin perder ni sumar una unidad menor por el redondeo.
 */
data class ExactLine(val unitPrice: Money, val discount: Money)

/** Las cuentas de una tarjeta que no dependen de la base. */
object PhraseAmounts {
    /**
     * Lo que dice una línea por todo lo que lleva: su total, o su precio por la cantidad,
     * redondeado. `null` si no se dijo un monto, falta la cantidad o pasa del tope de un documento.
     */
    fun lineAmount(line: DraftLine): Money? {
        line.lineTotal?.let { return it }
        val price = line.unitPrice ?: return null
        val quantity = line.quantity ?: return null
        return price.timesQuantity(quantity, Limit.DOCUMENT_TOTAL).valueOrNull()
    }

    /**
     * El precio y el descuento con que [quantity] milésimas cobran exactamente [total], como "3
     * panes por 1000": el precio sube a 334 y el descuento de 2 deja la línea en 1000. `null` si el
     * total es negativo, la cantidad no es positiva o el precio pasa del tope de un precio.
     */
    fun exactLine(total: Money, quantity: QuantityMilli): ExactLine? {
        if (total < Money.ZERO || quantity.milli <= 0L) return null
        val price = mulDivCeil(total.minor, Scale.MILLI, quantity.milli).valueOrNull()
            ?.takeIf { Limit.UNIT_MONEY.allows(it) }
            ?: return null
        // Con el precio dentro de su tope y la cantidad positiva, el bruto siempre cabe en Long.
        return Money(price).timesQuantity(quantity)
            .map { gross -> ExactLine(Money(price), gross - total) }
            .valueOrNull()
    }
}

/**
 * Las medidas de las unidades iniciales métricas, que valen igual en todo el mundo. La libra, la
 * onza y el galón no están: su tamaño cambia según el lugar, y suponer uno sería suponer un país.
 * Una de esas, o una unidad propia, solo se convierte con la conversión que guardó la persona.
 */
object PhraseMeasures {
    /**
     * Lo que mide una unidad entera de la unidad inicial [systemKey], en milésimas de gramo, de
     * mililitro o de metro, o `null` si no es una de las métricas.
     */
    fun baseAmountMilli(systemKey: String?): Long? = systemKey?.let { METRIC[it] }

    /**
     * [quantity] milésimas de una unidad que mide [fromAmount], llevadas a una que mide
     * [toAmount], redondeado: 500 gramos son 0,500 kilogramos. `null` si redondea a cero o pasa
     * del tope de una línea.
     */
    fun convert(quantity: QuantityMilli, fromAmount: Long, toAmount: Long): QuantityMilli? =
        mulDivRound(quantity.milli, fromAmount, toAmount, Limit.LINE_QUANTITY_MILLI)
            .valueOrNull()
            ?.takeIf { it > 0L }
            ?.let(::QuantityMilli)

    /**
     * El precio de una unidad que mide [toAmount], a partir del de una que mide [fromAmount],
     * redondeado: 20 el gramo son 20000 el kilogramo. `null` si pasa del tope de un precio.
     */
    fun priceIn(price: Money, fromAmount: Long, toAmount: Long): Money? =
        mulDivRound(price.minor, toAmount, fromAmount, Limit.UNIT_MONEY).valueOrNull()?.let(::Money)

    private val METRIC: Map<String, Long> = mapOf(
        "KILOGRAM" to 1_000_000L,
        "GRAM" to 1_000L,
        "LITER" to 1_000_000L,
        "MILLILITER" to 1_000L,
        "METER" to 1_000L,
    )
}
