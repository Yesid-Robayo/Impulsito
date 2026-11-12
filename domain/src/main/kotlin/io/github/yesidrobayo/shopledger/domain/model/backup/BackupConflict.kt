package io.github.yesidrobayo.shopledger.domain.model.backup

import kotlin.time.Instant

/**
 * Los dos lados de un conflicto del respaldo, en conteos neutros: nunca nombres, montos ni la
 * dirección del servicio.
 *
 * @property destinationKind el destino en conflicto: con WebDAV, conservar este teléfono fija la
 *   cabeza; con ranuras, la conserva la reserva.
 * @property cloudCopyKey la clave de la copia de la cabeza en la lista de copias, para restaurarla;
 *   nula si el destino ya no tiene cabeza.
 * @property cloudCreatedAt cuándo se hizo esa copia, si se sabe.
 * @property cloudDeviceLabel el fabricante y el modelo del teléfono que la hizo, si se sabe.
 * @property cloudFromThisPhone si toda la cabeza la escribió este mismo teléfono, con una base que
 *   ya no es esta.
 * @property cloudRecords los registros de la cabeza, o nulo si ya no hay cabeza.
 * @property cloudCatalogCount los productos, proveedores y clientes de la cabeza, o nulo.
 */
data class ConflictSides(
    val kind: BackupConflictKind,
    val destinationKind: BackupDestinationKind,
    val localRecords: Long,
    val localCatalogCount: Long,
    val pendingChanges: Long,
    val cloudCopyKey: String?,
    val cloudCreatedAt: Instant?,
    val cloudDeviceLabel: String?,
    val cloudFromThisPhone: Boolean,
    val cloudRecords: Long?,
    val cloudCatalogCount: Long?,
) {
    /** Si conservar este teléfono deja menos datos que la cabeza: pide confirmación reforzada. */
    val localHasFewer: Boolean
        get() =
            (cloudRecords != null && localRecords < cloudRecords) ||
                (cloudCatalogCount != null && localCatalogCount < cloudCatalogCount)

    /** Cuántos registros menos tiene este teléfono que la cabeza, o 0 si no tiene menos. */
    val missingRecords: Long
        get() = cloudRecords?.let { (it - localRecords).coerceAtLeast(0L) } ?: 0L

    /**
     * Si los dos lados tienen algo que el otro no: este teléfono tiene cambios sin copia y la nube
     * tiene una cabeza que no es la suya. La app no mezcla, y conviene exportar antes de descartar.
     */
    val bothSidesChanged: Boolean
        get() = pendingChanges > 0 && cloudRecords != null
}

/** Lo que el diálogo del conflicto puede mostrar. */
sealed interface ConflictLookup {
    /** Los dos lados, para decidir. */
    data class Shown(val sides: ConflictSides) : ConflictLookup

    /** El respaldo no está detenido por un conflicto. */
    data object NotInConflict : ConflictLookup

    /** La base no tiene nada del negocio: solo se ofrece restaurar o decidir después. */
    data object EmptyDatabase : ConflictLookup

    /**
     * Con la nube del teléfono, la ranura [slot] cambió por fuera o las tres están protegidas: la
     * app no sabe qué tiene ese archivo, y seguir pide un archivo nuevo para esa ranura.
     *
     * @property fileName el nombre del archivo en esa nube, si se sabe. Es texto no confiable.
     * @property threeBlocked si las tres ranuras están protegidas, y no una que cambió por fuera.
     */
    data class NewSlotFileRequired(
        val slot: BackupSlot?,
        val fileName: String?,
        val threeBlocked: Boolean,
        val localRecords: Long,
        val pendingChanges: Long,
    ) : ConflictLookup

    /** El destino no respondió, o no hay internet: sin él no se decide. */
    data object Unreachable : ConflictLookup
}

/** Cómo terminó `Conservar este teléfono`. Salvo [Kept], el conflicto sigue igual. */
sealed interface KeepPhoneResult {
    /** Las copias siguen: la próxima va por encima de la cabeza, que queda. */
    data object Kept : KeepPhoneResult

    /** Este teléfono tiene menos que la cabeza: hace falta la confirmación reforzada. */
    data class NeedsConfirmation(val sides: ConflictSides) : KeepPhoneResult

    /** La cabeza cambió desde que se mostró: la persona decide otra vez con lo nuevo. */
    data class CloudChanged(val sides: ConflictSides) : KeepPhoneResult

    /** Con la nube del teléfono, hace falta un archivo nuevo para [slot] antes de seguir. */
    data class NewSlotFileRequired(val slot: BackupSlot?) : KeepPhoneResult

    /** Ya hay tres copias fijadas: hay que liberar una antes de seguir. */
    data object TooManyPinned : KeepPhoneResult

    /** El destino no respondió, o no se pudo fijar la cabeza: sin eso no se confirma. */
    data object Unreachable : KeepPhoneResult

    /** No se ofrece con la base vacía. */
    data object EmptyDatabase : KeepPhoneResult

    /** El respaldo ya no estaba en conflicto. */
    data object NotInConflict : KeepPhoneResult
}

/** Cómo terminó desconectar el destino. */
enum class DisconnectResult {
    /** Quedó desconectado: las copias del destino y los datos del teléfono siguen intactos. */
    DISCONNECTED,

    /** No había un destino conectado: nada cambió. */
    NOT_CONNECTED,

    /** La base no abre, o el teléfono no pudo guardar. Nada cambió, y se puede repetir. */
    FAILED,
}
