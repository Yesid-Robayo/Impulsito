package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoBackupFacts
import io.github.yesidrobayo.shopledger.domain.model.backup.PhotoExport

/**
 * El respaldo de las fotos visto desde las pantallas: qué falta subir y la exportación aparte. Nada
 * de esto usa la red ni cambia el estado del respaldo de la base.
 */
interface PhotoBackupPort {
    /** Lo que se sabe de las fotos, o `null` si la base todavía no se puede leer. */
    suspend fun facts(): PhotoBackupFacts?

    /**
     * Escribe las fotos en uso en el documento [documentUri], en un ZIP: en claro, o cada una
     * cifrada con el cifrado activo. Solo lee.
     */
    suspend fun export(documentUri: String): PhotoExport
}
