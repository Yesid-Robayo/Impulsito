package io.github.yesidrobayo.shopledger.domain.model

/**
 * El margen de un precio o de unas ventas: la parte de lo vendido, sin impuesto, que queda como
 * ganancia bruta.
 *
 * Es el único indicador de rentabilidad que ve la persona. El sobreprecio se mide sobre el costo y
 * no aparece en la app: con precio 10000 y costo 8000, el margen es 20 % y el sobreprecio sería
 * 25 %, y mostrar los dos lleva a confundirlos.
 *
 * El margen no siempre existe, y por eso es un resultado que la pantalla debe mirar, no un número
 * que valga cero cuando falta.
 */
sealed interface Margin {
    /**
     * El margen existe y vale [value]. Puede ser negativo, si se vende por debajo del costo, y no
     * se acota: con un precio pequeño y un costo grande pasa de −100 %.
     */
    data class Defined(val value: BasisPoints) : Margin

    /**
     * Sin dato: el precio, o las ventas del período, son cero o negativos. No se divide por cero, y
     * la pantalla muestra «—» en vez de un 0 % que parecería medido.
     */
    data object NoData : Margin

    /**
     * El margen no cabe en `Long`. Solo pasa con cifras absurdas, como un precio de 1 con un costo
     * de miles de billones, y la pantalla muestra «—» en vez de caerse.
     */
    data object OutOfRange : Margin
}
