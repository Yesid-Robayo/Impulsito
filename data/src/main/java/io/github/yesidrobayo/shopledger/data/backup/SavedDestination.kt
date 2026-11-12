package io.github.yesidrobayo.shopledger.data.backup

import io.github.yesidrobayo.shopledger.data.destination.ConnectedDestination
import io.github.yesidrobayo.shopledger.data.destination.DestinationLink
import io.github.yesidrobayo.shopledger.data.local.backup.BackupStateEntity
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupLinkState

/**
 * El destino conectado, como lo necesitan la lista de copias y restaurar. Sale de la fila del
 * respaldo con la base abierta, o de `link.json` en el rescate, cuando la base no se puede leer:
 * así las dos pantallas leen el mismo destino con el mismo código.
 *
 * @property kind el tipo de destino, o nulo si lo guardado no se entiende.
 * @property account el usuario WebDAV. Nunca va a un registro.
 * @property lastHumanCheckAt la última vez que la persona vio la copia en su nube. `link.json` no la
 *   guarda, así que en el rescate es nula.
 */
internal data class SavedDestination(
    val kind: BackupDestinationKind?,
    val account: String?,
    val destinationId: String?,
    val folder: String?,
    val lastHumanCheckAt: Long?,
) {
    /** El destino para abrirlo, o nulo si falta su tipo o su huella. */
    fun connected(): ConnectedDestination? {
        val known = kind ?: return null
        val id = destinationId ?: return null
        return ConnectedDestination(known, id, folder)
    }

    // Ni el usuario ni la carpeta van a un registro.
    override fun toString(): String = "SavedDestination($kind)"
}

/** El destino de la fila del respaldo, o nulo si la persona no tiene uno conectado. */
internal fun BackupStateEntity.savedDestination(): SavedDestination? {
    if (linkState == BackupLinkState.NOT_CONNECTED.name) return null
    return SavedDestination(
        kind = BackupDestinationKind.entries.firstOrNull { it.name == destinationKind },
        account = destinationAccount,
        destinationId = destinationId,
        folder = destinationFolder,
        lastHumanCheckAt = lastHumanCheckAt,
    )
}

/** El destino que dejó `link.json`, sin la base. */
internal fun DestinationLink.savedDestination(): SavedDestination = SavedDestination(
    kind = kind,
    account = account,
    destinationId = destinationId,
    folder = folderUrl,
    lastHumanCheckAt = null,
)
