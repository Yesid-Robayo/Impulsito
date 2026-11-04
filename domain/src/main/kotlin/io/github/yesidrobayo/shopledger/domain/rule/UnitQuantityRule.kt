package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/**
 * Valida la cantidad de una línea o de un ajuste según la unidad elegida en ella.
 *
 * Las unidades de conteo, como unidad, caja, paquete o docena, no admiten fracciones: media caja
 * casi siempre es un error al escribir, y quien vende por partes usa otra unidad o una conversión.
 * Su mínimo es una unidad entera. Las demás admiten hasta tres decimales, que es lo que cabe en
 * milésimas, y su mínimo es una milésima. Cada unidad, también las que crea la persona, dice si
 * admite fracciones.
 *
 * No aplica topes: cada caso de uso suma el suyo, porque una línea y un saldo de stock no tienen el
 * mismo máximo.
 */
object UnitQuantityRule {
    fun check(quantity: QuantityMilli, allowsFraction: Boolean): UnitQuantityCheck = when {
        quantity.milli <= 0L -> UnitQuantityCheck.NotPositive
        !allowsFraction && !quantity.isWholeUnits -> UnitQuantityCheck.FractionNotAllowed
        else -> UnitQuantityCheck.Valid
    }
}
