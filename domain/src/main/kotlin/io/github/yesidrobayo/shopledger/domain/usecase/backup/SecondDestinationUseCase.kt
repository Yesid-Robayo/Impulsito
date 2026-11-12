package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.ConnectFolderResult
import io.github.yesidrobayo.shopledger.domain.model.backup.FoundLocalCopies
import io.github.yesidrobayo.shopledger.domain.model.backup.SecondDestinationView
import io.github.yesidrobayo.shopledger.domain.port.SecondDestinationPort

/**
 * El segundo destino para las pantallas: leerlo, elegir o quitar la carpeta, seguir a la copia
 * principal y, tras reinstalar, encontrar las copias y volver a conectar la carpeta. Nada de esto
 * usa la red ni toca el estado del respaldo.
 */
class SecondDestinationUseCase(private val port: SecondDestinationPort) {
    suspend fun view(): SecondDestinationView = port.view()

    suspend fun connect(treeUri: String, appName: String): ConnectFolderResult =
        port.connect(treeUri, appName)

    suspend fun setFollowMainBackup(follow: Boolean) = port.setFollowMainBackup(follow)

    suspend fun remove() = port.remove()

    suspend fun find(treeUri: String, appName: String): FoundLocalCopies =
        port.find(treeUri, appName)

    suspend fun reconnectAndAdopt(treeUri: String, appName: String): ConnectFolderResult =
        port.reconnectAndAdopt(treeUri, appName)
}
