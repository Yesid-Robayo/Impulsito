package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.erase.EraseSummary
import io.github.yesidrobayo.shopledger.domain.model.erase.StorageUsage

/**
 * Lo que `Almacenamiento y borrado` lee del teléfono, fuera del hilo principal. Solo lee: no borra
 * ni escribe nada, ni usa la red.
 */
interface StoragePort {
    /** Cuánto ocupa cada parte. Lo que no se puede medir queda en `null`. */
    suspend fun usage(): StorageUsage

    /** Lo que se perdería al borrar, o `null` si la base no se lee. */
    suspend fun summary(): EraseSummary?
}
