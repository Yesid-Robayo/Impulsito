package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.Checked
import io.github.yesidrobayo.shopledger.domain.model.Limit
import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli
import io.github.yesidrobayo.shopledger.domain.model.Scale
import io.github.yesidrobayo.shopledger.domain.rule.mulDivRound

/**
 * Lleva la cantidad que escribe la persona en la unidad de una línea a la unidad base del producto,
 * que es la del libro de stock.
 *
 * El factor de una conversión son milésimas de la unidad base por una unidad entera de la unidad
 * de la línea: una caja de 24 de un producto que se lleva por unidad trae 24000, y un gramo de uno
 * que se lleva en kilogramos trae 1. Con la unidad base misma, el factor es [BASE_UNIT_FACTOR_MILLI].
 * La línea copia el factor al registrarse, así que cambiar la conversión después no mueve lo ya
 * vendido ni lo ya comprado.
 *
 * El resultado se redondea al entero más cercano, con el empate lejos de cero, y la cuenta corre en
 * 128 bits: nunca se desborda. Una cantidad que no se puede guardar en la línea llega como rechazo,
 * con su motivo, y no se guarda nada.
 */
object UnitConversion {
    /** El factor de la unidad base consigo misma: una unidad entera trae mil milésimas de sí. */
    const val BASE_UNIT_FACTOR_MILLI: Long = Scale.MILLI

    /**
     * Si [factorMilli] sirve como factor de una conversión: al menos una milésima de la unidad base,
     * y no más que la cantidad máxima de una línea.
     */
    fun isValidFactor(factorMilli: Long): Boolean =
        factorMilli >= 1L && Limit.LINE_QUANTITY_MILLI.allows(factorMilli)

    /**
     * La cantidad en la unidad base que corresponde a [entered], escrita en una unidad cuyo factor
     * es [factorMilli]: `mulDivRound(entered, factorMilli, 1000)`.
     *
     * La cantidad escrita y la convertida van de una milésima hasta la cantidad máxima de una
     * línea. Una cantidad escrita que, convertida, redondea a cero no mueve nada, y se rechaza en
     * vez de guardar una línea vacía.
     */
    fun toBaseQuantity(entered: QuantityMilli, factorMilli: Long): UnitConversionResult = when {
        !isValidFactor(factorMilli) -> UnitConversionResult.InvalidFactor
        entered.milli <= 0L -> UnitConversionResult.NotPositive
        !Limit.LINE_QUANTITY_MILLI.allows(entered.milli) -> UnitConversionResult.AboveLineLimit
        else -> convert(entered.milli, factorMilli)
    }

    // Con la cantidad y el factor dentro del tope, su producto cabe en Long: solo el tope del
    // resultado puede rechazar.
    private fun convert(entered: Long, factorMilli: Long): UnitConversionResult {
        val base =
            mulDivRound(entered, factorMilli, Scale.MILLI, Limit.LINE_QUANTITY_MILLI)
        return when {
            base !is Checked.Value -> UnitConversionResult.AboveLineLimit
            base.value == 0L -> UnitConversionResult.RoundsToZero
            else -> UnitConversionResult.Converted(QuantityMilli(base.value))
        }
    }
}
