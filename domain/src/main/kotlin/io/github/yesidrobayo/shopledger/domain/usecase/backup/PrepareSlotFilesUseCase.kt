package io.github.yesidrobayo.shopledger.domain.usecase.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupConfirm
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupLook
import io.github.yesidrobayo.shopledger.domain.port.SlotFilesSetupPort

/**
 * Prepara los tres archivos de la nube del teléfono para su pantalla: lee lo que hay, revisa cada
 * archivo nuevo y confirma con los tres. Las reglas viven en la capa de datos, que conoce el
 * selector del sistema y el cerrojo del respaldo; esto solo las pone al alcance de la pantalla.
 */
class PrepareSlotFilesUseCase(private val setup: SlotFilesSetupPort) {
    /** Lo que hay que mostrar, o nulo si la base no abre. */
    suspend fun look(): SlotSetupLook? = setup.look()

    /** Revisa el documento [uri] creado para [slot]. */
    suspend fun addFile(slot: BackupSlot, uri: String): SlotFileOutcome = setup.addFile(slot, uri)

    /** Con los tres archivos, deja las ranuras como destino. */
    suspend fun confirm(): SlotSetupConfirm = setup.confirm()
}
