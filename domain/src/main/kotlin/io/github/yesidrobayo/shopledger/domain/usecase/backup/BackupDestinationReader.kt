package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.port.BackupDestinationSource

/**
 * Lee el destino conectado para la pantalla del respaldo: su nombre, sus copias y si hay red. No
 * escribe nada: nunca sube, poda ni fija una copia.
 */
class BackupDestinationReader(private val source: BackupDestinationSource) {
    /** El destino conectado, sin usar la red, o nulo si no hay ninguno. */
    suspend fun describe(): BackupDestinationInfo? = source.describe()

    /** Las copias del destino: con WebDAV, la lista del servicio; con ranuras, `slots.json`. */
    suspend fun listCopies(): BackupCopyList = source.listCopies()

    /** Si hay ahora una red comprobada, para decir que la copia sale cuando vuelva. */
    fun hasNetwork(): Boolean = source.hasNetwork()
}
