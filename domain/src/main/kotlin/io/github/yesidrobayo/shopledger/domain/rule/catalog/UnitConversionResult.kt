package io.github.yesidrobayo.shopledger.domain.rule.catalog

import io.github.yesidrobayo.shopledger.domain.model.QuantityMilli

/** Resultado de [UnitConversion]. El formulario muestra el motivo antes de confirmar. */
sealed interface UnitConversionResult {
    /** [quantity] es la cantidad en la unidad base, la que va al libro de stock. */
    data class Converted(val quantity: QuantityMilli) : UnitConversionResult

    /** El factor es menor que una milésima de la unidad base o pasa de la cantidad de una línea. */
    data object InvalidFactor : UnitConversionResult

    /** Cero o menos: una línea siempre mueve algo. */
    data object NotPositive : UnitConversionResult

    /**
     * La cantidad escrita, o la convertida a la unidad base, pasa de la cantidad máxima de una
     * línea.
     */
    data object AboveLineLimit : UnitConversionResult

    /** Convertida a la unidad base, la cantidad redondea a cero: la línea no movería nada. */
    data object RoundsToZero : UnitConversionResult
}
