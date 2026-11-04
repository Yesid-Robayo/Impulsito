package io.github.yesidrobayo.shopledger.domain.rule

/** Resultado de [UnitQuantityRule]. El formulario muestra el motivo antes de confirmar. */
sealed interface UnitQuantityCheck {
    data object Valid : UnitQuantityCheck

    /** Cero o menos: una línea o un ajuste siempre mueven algo. */
    data object NotPositive : UnitQuantityCheck

    /** La unidad no admite fracciones y la cantidad no es un número entero de unidades. */
    data object FractionNotAllowed : UnitQuantityCheck
}
