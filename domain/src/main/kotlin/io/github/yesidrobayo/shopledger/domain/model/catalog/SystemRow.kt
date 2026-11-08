package io.github.yesidrobayo.shopledger.domain.model.catalog

import io.github.yesidrobayo.shopledger.domain.model.SeedTable

/**
 * Una fila inicial que un caso de uso necesita encontrar por su clave estable, sin suponer su id.
 *
 * La base las crea al nacer y nunca las archiva ni las borra. Cada una dice en qué tabla vive y
 * con qué clave, que es una de las de [SeedTable] para esa tabla.
 */
enum class SystemRow(val table: SeedTable, val systemKey: String) {
    /** La unidad de conteo: la unidad base de todo producto que no dice otra. */
    DEFAULT_UNIT(SeedTable.UNITS, "UNIT"),

    /** La ubicación inicial, donde cae todo el stock mientras haya una sola. */
    MAIN_LOCATION(SeedTable.LOCATIONS, "MAIN"),

    /** La lista de precios inicial, cuyo precio es el precio de venta de cada producto. */
    DEFAULT_PRICE_LIST(SeedTable.PRICE_LISTS, "DEFAULT"),
}
