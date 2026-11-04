package io.github.yesidrobayo.shopledger.domain.model.settings

import io.github.yesidrobayo.shopledger.domain.model.region.DecimalSeparator

/**
 * El separador de columnas de los CSV que exporta el negocio. Es un valor guardado en los ajustes:
 * su nombre no cambia nunca.
 *
 * Depende de la región y no del idioma, porque la hoja de cálculo de la persona parte las columnas
 * según el formato regional de su equipo. Donde la coma es el separador decimal, las columnas van con
 * punto y coma: así un monto con coma no se parte en dos columnas.
 */
enum class CsvSeparator(
    /** El carácter que separa una columna de la siguiente. */
    val char: Char,
) {
    /** Punto y coma: el de las regiones con coma decimal. */
    SEMICOLON(';'),

    /** Coma: el de las regiones con punto decimal. */
    COMMA(','),

    /** Tabulador: no choca con ningún separador decimal. */
    TAB('\t'),
    ;

    companion object {
        /**
         * El separador con el que nace el ajuste para un formato regional con [decimalSeparator]
         * como separador decimal. La persona puede cambiarlo después.
         */
        fun defaultFor(decimalSeparator: DecimalSeparator): CsvSeparator = when (decimalSeparator) {
            DecimalSeparator.COMMA -> SEMICOLON
            DecimalSeparator.POINT -> COMMA
        }
    }
}
