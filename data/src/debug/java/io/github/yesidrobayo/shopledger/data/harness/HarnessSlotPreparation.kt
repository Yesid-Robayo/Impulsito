package io.github.yesidrobayo.shopledger.data.harness

import io.github.yesidrobayo.shopledger.data.destination.slots.PickedDocument
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotEntry
import io.github.yesidrobayo.shopledger.data.destination.slots.SlotState
import io.github.yesidrobayo.shopledger.data.destination.slots.WriteMode
import io.github.yesidrobayo.shopledger.data.destination.slots.slotProviderOf
import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot

/** Por qué no se preparan las tres ranuras. Es un código para la pantalla, sin datos. */
enum class SlotRejection {
    /** No llegaron tres documentos. */
    NOT_THREE,

    /** El proveedor no concedió el permiso persistente, o no respondió a la consulta. */
    NO_ACCESS,

    /** La nube no está en la lista fija: otra nube o una carpeta del teléfono. */
    PROVIDER_NOT_ALLOWED,

    /** El proveedor no deja escribir el documento. */
    NOT_WRITABLE,

    /** Dos ranuras serían el mismo documento: escribir en una destruiría la otra. */
    SAME_DOCUMENT,

    /** El proveedor no dice el tamaño: la guarda antes de escribir no podría contrastarlo. */
    SIZE_UNKNOWN,
}

/**
 * Revisa los tres documentos que eligió la persona, en el orden de la tabla de preparar las
 * ranuras, y arma el estado de `slots.json` si todos pasan. No toca ningún documento.
 *
 * @param uris los documentos de las ranuras A, B y C, en ese orden.
 * @param documents lo que el proveedor dijo de cada uno, o nulo si no respondió.
 * @return el estado nuevo, sin copias y sin entregas, o por qué se rechaza.
 */
internal fun prepareSlotState(
    uris: List<String>,
    documents: List<PickedDocument?>,
): SlotPreparation {
    if (uris.size != BackupSlot.entries.size || documents.size != uris.size) {
        return SlotPreparation.Rejected(SlotRejection.NOT_THREE)
    }
    val checked = documents.map { it ?: return SlotPreparation.Rejected(SlotRejection.NO_ACCESS) }
    val labels =
        checked.map {
            // La lista fija de nubes aprobadas es la misma de la pantalla de la app.
            slotProviderOf(it.authority)?.label
                ?: return SlotPreparation.Rejected(SlotRejection.PROVIDER_NOT_ALLOWED)
        }
    if (checked.any { !it.writable }) return SlotPreparation.Rejected(SlotRejection.NOT_WRITABLE)
    if (uris.distinct().size != uris.size) {
        return SlotPreparation.Rejected(SlotRejection.SAME_DOCUMENT)
    }
    if (checked.any { it.sizeBytes == null }) {
        return SlotPreparation.Rejected(SlotRejection.SIZE_UNKNOWN)
    }
    val slots =
        BackupSlot.entries.mapIndexed { index, slot ->
            slot to
                SlotEntry(
                    uri = uris[index],
                    providerLabel = labels[index],
                    displayName = checked[index].displayName,
                    copy = null,
                    writing = null,
                )
        }.toMap()
    // Archivos nuevos: nadie vio todavía ninguna entrega en ellos.
    return SlotPreparation.Ready(
        SlotState(slots, firstHandoffAt = null, writeMode = WriteMode.WT, sessionRequired = false),
    )
}

/** Lo que dio revisar los tres documentos. */
internal sealed interface SlotPreparation {
    data class Ready(val state: SlotState) : SlotPreparation

    data class Rejected(val reason: SlotRejection) : SlotPreparation
}
