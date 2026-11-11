package io.github.yesidrobayo.shopledger.domain.port

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotFileOutcome
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupConfirm
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotSetupLook

/**
 * Preparar los tres archivos de la nube del teléfono, uno por uno, con el selector del sistema.
 *
 * Los archivos creados a mitad se conservan aunque la persona salga, y al volver se sigue con los
 * que falten. Las ranuras que ya había y siguen abriendo no se crean de nuevo. Nada usa la red.
 */
interface SlotFilesSetupPort {
    /** Lo que hay que mostrar, o nulo si la base no abre. */
    suspend fun look(): SlotSetupLook?

    /**
     * Revisa el documento [uri] que la persona acaba de crear para [slot]. Si sirve, conserva su
     * permiso y lo guarda como creado. Si no, suelta el permiso, y nunca escribe ni borra ese
     * documento.
     */
    suspend fun addFile(slot: BackupSlot, uri: String): SlotFileOutcome

    /**
     * Con los tres archivos, deja las ranuras como destino: escribe su estado, conecta y pide la
     * primera copia si hay datos. Repetirlo tras un corte termina lo que quedó a mitad.
     */
    suspend fun confirm(): SlotSetupConfirm
}
