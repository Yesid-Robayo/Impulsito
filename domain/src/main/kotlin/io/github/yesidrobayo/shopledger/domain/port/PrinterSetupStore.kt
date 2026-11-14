package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.print.PrinterSetup
import kotlinx.coroutines.flow.Flow

/**
 * Dónde se guardan las impresoras de este teléfono: un archivo propio de la instalación, fuera de
 * la base y de las copias. Si no existe o no se entiende, se lee como [PrinterSetup.EMPTY]: no
 * guarda nada que no se pueda volver a elegir.
 */
interface PrinterSetupStore {
    /** Las impresoras guardadas, de nuevo cada vez que cambian. */
    fun observe(): Flow<PrinterSetup>

    /** Deja [setup] en el disco. Devuelve `false` si no quedó guardada; entonces no cambia nada. */
    suspend fun save(setup: PrinterSetup): Boolean
}
