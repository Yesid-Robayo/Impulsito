package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupStatusFacts
import kotlinx.coroutines.flow.Flow

/**
 * Lo que el estado del respaldo necesita leer del teléfono: el estado guardado, las marcas de una
 * restauración, los trabajos de subida y la red. Lo implementa la capa de datos, que lo lee sin
 * esperar a la red ni a ningún trabajo en segundo plano.
 */
interface BackupStatusSource {
    /** Lo de ahora. */
    suspend fun current(): BackupStatusFacts

    /** Lo de ahora, y de nuevo cada vez que cambia el estado guardado del respaldo. */
    fun observe(): Flow<BackupStatusFacts>
}
