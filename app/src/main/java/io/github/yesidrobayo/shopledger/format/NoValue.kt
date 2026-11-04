package io.github.yesidrobayo.shopledger.format

/**
 * Lo que se muestra en lugar de una cifra sin valor definido, como el margen de un producto sin
 * precio o una suma de reporte que no cabe: una raya, igual en todos los idiomas y regiones. Nunca
 * un cero, que sería un dato falso, ni un espacio en blanco, que parece un error de la pantalla.
 */
const val NO_VALUE_TEXT: String = "\u2014"
