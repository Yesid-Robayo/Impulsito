package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationInfo
import io.github.yesidrobayo.shopledger.domain.model.backup.HandoffCopy

/**
 * Lo que la pantalla del respaldo lee del destino conectado. Solo lee: nunca sube, borra ni fija
 * nada.
 */
interface BackupDestinationSource {
    /** El destino conectado, sin usar la red, o nulo si no hay ninguno. */
    suspend fun describe(): BackupDestinationInfo?

    /**
     * Las copias del destino. Con WebDAV pide la lista al servicio y necesita internet; con la nube
     * del teléfono sale de lo que la app recuerda de sus tres ranuras, sin red.
     */
    suspend fun listCopies(): BackupCopyList

    /**
     * Con la nube del teléfono, la última copia entregada y la última revisión, para revisarla en
     * esa nube; nula con WebDAV, sin destino o antes de la primera entrega. No usa la red.
     */
    suspend fun latestHandoff(): HandoffCopy?

    /**
     * Si el teléfono tiene ahora una red que el sistema comprobó. No la usa: sirve para decir, al
     * tocar `Respaldar ahora` sin internet, que la copia sale cuando vuelva la conexión.
     */
    fun hasNetwork(): Boolean
}
