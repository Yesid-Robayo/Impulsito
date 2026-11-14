package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.SeedTable

/**
 * Los nombres con que un archivo para importar puede nombrar una fila inicial, como la unidad de
 * conteo o la lista de precios inicial: su nombre y su símbolo en cada idioma que trae la app. Lo
 * implementa la interfaz, que tiene los textos.
 *
 * No depende del idioma elegido: un archivo exportado en portugués nombra sus unidades en
 * portugués, y entra igual con la app en español.
 */
fun interface ImportSeedLabels {
    /** Los nombres y símbolos de la fila [systemKey] de [table], en cualquier orden. */
    fun names(table: SeedTable, systemKey: String): List<String>
}
