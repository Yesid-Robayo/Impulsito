package io.github.yesidrobayo.shopledger.ui.rescue

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupCopyList
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupDestinationKind
import io.github.yesidrobayo.shopledger.domain.model.backup.BeforeUpdateCopy
import io.github.yesidrobayo.shopledger.ui.backup.BackupAction
import io.github.yesidrobayo.shopledger.ui.backup.RestoreUi

/**
 * La pantalla de rescate. No trae ningún dato del negocio: solo la causa, el destino guardado y lo
 * que la persona tiene abierto.
 *
 * @property cause por qué la base no abrió.
 * @property destinationKind el destino de `link.json`, o nulo si no hay ninguno guardado. Sin él no
 *   hay lista que ofrecer, y `Buscar un archivo` pasa a ser la acción principal.
 * @property copiesOpen si la hoja con las copias del destino está abierta.
 * @property copies las copias del destino; nulo mientras se leen.
 * @property damagedAvailable si una restauración apartó una base dañada que se puede compartir.
 * @property sharing si el diálogo para compartir esa base está abierto.
 * @property beforeUpdate la copia previa a la última actualización, o nula si no hay.
 */
data class RescueUiState(
    val cause: RescueReason,
    val destinationKind: BackupDestinationKind? = null,
    val copiesOpen: Boolean = false,
    val copies: BackupCopyList? = null,
    val restore: RestoreUi = RestoreUi.Idle,
    val supportOpen: Boolean = false,
    val languageOpen: Boolean = false,
    val damagedAvailable: Boolean = false,
    val sharing: Boolean = false,
    val beforeUpdate: BeforeUpdateCopy? = null,
) {
    /** La acción principal, según la causa y lo que haya en el teléfono. */
    val primary: RescuePrimary
        get() = when {
            cause is RescueReason.NotEnoughSpace -> RescuePrimary.RETRY

            cause == RescueReason.MigrationFailed && beforeUpdate != null ->
                RescuePrimary.BEFORE_UPDATE

            destinationKind == null -> RescuePrimary.FIND_FILE

            else -> RescuePrimary.RESTORE
        }

    /** Si se ofrece `Restaurar` desde el destino: solo con uno guardado. */
    val canRestoreFromDestination: Boolean get() = destinationKind != null
}

/** La acción que va en el relleno de marca. */
enum class RescuePrimary {
    /** `Restaurar`: las copias del destino guardado. */
    RESTORE,

    /** `Buscar un archivo`: no hay destino guardado del que listar. */
    FIND_FILE,

    /** `Reintentar`: sin espacio para migrar, tras liberarlo. */
    RETRY,

    /** `Volver a antes de la actualización`: la migración falló y existe la copia previa. */
    BEFORE_UPDATE,
}

/** Lo que la persona hace en la pantalla de rescate. */
sealed interface RescueAction {
    /** `Restaurar`: abre la hoja y lee las copias del destino guardado. */
    data object ShowCopies : RescueAction

    data object CloseCopies : RescueAction

    data object RetryCopies : RescueAction

    /** `Reintentar`: reinicia la app, que vuelve a pasar por la compuerta. */
    data object Retry : RescueAction

    /** `Volver a antes de la actualización`: primero la confirmación, con el día de la copia. */
    data object BeforeUpdate : RescueAction

    /** Lo de restaurar, igual que en la pantalla del respaldo. */
    data class Restore(val action: BackupAction) : RescueAction

    data object ShowSupport : RescueAction

    data object CloseSupport : RescueAction

    /** `Copiar` en la hoja de soporte: lo copia la pantalla, que tiene el portapapeles. */
    data object CopySupport : RescueAction

    data object ShowLanguage : RescueAction

    data object CloseLanguage : RescueAction

    /** `Compartir datos dañados`: primero la advertencia. */
    data object ShareDamaged : RescueAction

    data object CloseShareDamaged : RescueAction

    /** La persona confirmó compartir la base dañada. */
    data object ConfirmShareDamaged : RescueAction
}
