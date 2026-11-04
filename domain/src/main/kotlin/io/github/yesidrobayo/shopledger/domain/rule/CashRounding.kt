package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.Money

/**
 * El redondeo del cobro en efectivo, cuando no hay monedas para dar el monto exacto.
 *
 * Toca solo la parte en efectivo: tarjeta, transferencia y los demás medios cobran el monto exacto,
 * así que una venta pagada sin efectivo no se redondea. El paso lo escribe la persona en unidad
 * menor, porque depende de las monedas que circulan donde vende, y ningún valor está fijo en el
 * código. La misma cuenta sirve con cualquier cantidad de decimales: un paso 5 son cinco unidades
 * en una moneda sin decimales y 0,05 en una con dos.
 */
object CashRounding {
    /**
     * Lo que se suma a [cashPart] para llevarla al múltiplo de [step] más cercano: `cash_part`
     * redondeada al paso, menos `cash_part`. Un empate sube, como en todo redondeo de dinero. Con
     * paso 0 no hay redondeo y el resultado es 0.
     *
     * El resultado nunca pasa de medio paso. Negativo, se cobra menos y es un costo de cobrar;
     * positivo, se cobra más y reduce ese costo.
     *
     * Un paso negativo es un error de programación, porque los ajustes no lo admiten. Lanza
     * [ArithmeticException] si el monto redondeado no cabe en `Long`, algo que con un total dentro
     * de su tope no pasa.
     */
    fun amount(cashPart: Money, step: Money): Money {
        require(step >= Money.ZERO) { "El paso de redondeo no puede ser negativo" }
        if (step == Money.ZERO) return Money.ZERO
        val rounded = Math.multiplyExact(roundDiv(cashPart.minor, step.minor), step.minor)
        return Money(rounded) - cashPart
    }
}
