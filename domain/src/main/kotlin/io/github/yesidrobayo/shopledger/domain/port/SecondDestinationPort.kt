package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.ConnectFolderResult
import io.github.yesidrobayo.shopledger.domain.model.backup.FoundLocalCopies
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationView

/**
 * El segundo destino automático: una carpeta local que elige la persona con el selector del
 * sistema. Nada de esto usa la red, y nada toca el estado del respaldo ni el destino conectado. Lo
 * implementa la capa de datos.
 */
interface SecondDestinationPort {
    /** Lo guardado de la carpeta, para `Copias locales`. */
    suspend fun view(): SecondDestinationView

    /**
     * Elige la carpeta [treeUri] que devolvió el selector. Solo se acepta el almacenamiento del
     * teléfono; otra se rechaza sin guardar el permiso. Reemplaza la carpeta anterior: sus copias
     * quedan donde están.
     */
    suspend fun connect(treeUri: String, appName: String): ConnectFolderResult

    /** `Mantener al día esta carpeta`, apagado por defecto. */
    suspend fun setFollowMainBackup(follow: Boolean)

    /** `Quitar destino`: deja de escribir allí. Los archivos de la carpeta se conservan. */
    suspend fun remove()

    /**
     * `Buscar mi copia en este teléfono`: las copias de la app en la carpeta [treeUri], tras
     * reinstalar. Solo lee: no guarda nada ni toma el permiso.
     */
    suspend fun find(treeUri: String, appName: String): FoundLocalCopies

    /**
     * Antes de restaurar una copia de [treeUri], deja la carpeta conectada otra vez, con la opción
     * de seguir apagada, y adopta sus copias: no se podan hasta una copia nueva verificada.
     */
    suspend fun reconnectAndAdopt(treeUri: String, appName: String): ConnectFolderResult
}
