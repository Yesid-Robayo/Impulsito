package io.github.yesidrobayo.shopledger.domain.rule

import io.github.yesidrobayo.shopledger.domain.model.Money

/** Resultado de [MarginCalculator.suggestedPrice]. La pantalla muestra el motivo de un rechazo. */
sealed interface PriceSuggestion {
    /** El menor precio, con el impuesto incluido si corresponde, que alcanza el margen pedido. */
    data class Suggested(val price: Money) : PriceSuggestion

    /**
     * Sin costo conocido no hay sugerencia: la pantalla pide corregir el costo primero. Pasa solo
     * sin costo de reposición, sin valor de stock y sin costo promedio. Un costo menor que una
     * unidad menor, como 0,4, sí es un costo.
     */
    data object NoCost : PriceSuggestion

    /**
     * El margen pedido es negativo o pasa del 99 %. Con 100 %, el precio tendría que ser infinito.
     */
    data object TargetMarginOutOfRange : PriceSuggestion

    /** El precio que alcanza ese margen supera el precio máximo por unidad entera. */
    data object AbovePriceLimit : PriceSuggestion
}
