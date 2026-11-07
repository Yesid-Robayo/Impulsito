package io.github.yesidrobayo.shopledger.data.destination

import io.github.yesidrobayo.shopledger.data.destination.slots.DocumentSlotDestination
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind

/**
 * Abre el destino que recuerda el estado del respaldo, para una ejecución del motor. El motor no
 * sabe cómo se abre cada uno: solo recibe un [BackupDestination] o por qué no abrió.
 */
internal fun interface DestinationOpener {
    suspend fun open(link: ConnectedDestination): DestinationOpen
}

/**
 * El destino conectado, tal como lo guarda el estado del respaldo.
 *
 * @property destinationId la huella del destino, con que se compara la credencial sellada.
 * @property folderUrl la carpeta de copias, solo con WebDAV.
 */
internal data class ConnectedDestination(
    val kind: BackupDestinationKind,
    val destinationId: String,
    val folderUrl: String?,
) {
    // La carpeta lleva la dirección del servicio: no va a un registro.
    override fun toString(): String = "ConnectedDestination($kind)"
}

/**
 * Los destinos de producción: el servicio WebDAV con su credencial sellada, o las tres ranuras con
 * su estado en el teléfono.
 *
 * - **WebDAV:** la credencial se abre en cada ejecución y solo vive en memoria mientras dura. Si no
 *   abre, es de otro destino o la carpeta guardada no está debajo de su dirección, el acceso se
 *   perdió. Un fallo pasajero de Keystore o del disco se reintenta.
 * - **Ranuras:** no hay credencial. Que los documentos sigan ahí y con permiso lo comprueba el
 *   propio destino al listar, antes de escribir nada.
 *
 * @param webDav abre el destino WebDAV.
 * @param slots crea el destino de las ranuras.
 */
internal class ProductionDestinationOpener(
    private val webDav: WebDavDestinationOpener,
    private val slots: () -> DocumentSlotDestination,
) : DestinationOpener {
    override suspend fun open(link: ConnectedDestination): DestinationOpen = when (link.kind) {
        BackupDestinationKind.WEBDAV -> {
            val folder = link.folderUrl
            if (folder == null) {
                DestinationOpen.PermissionLost
            } else {
                webDav.open(link.destinationId, folder)
            }
        }

        BackupDestinationKind.DOCUMENT_SLOTS -> DestinationOpen.Opened(slots())
    }
}
