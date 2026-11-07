package io.github.yesidrobayo.shopledger.domain.model.backup

/**
 * Lo que el estado local dice de las tres ranuras a la vez.
 *
 * Siempre lleva las tres: un estado que falta o no se lee nunca significa ranuras vacías, así que
 * quien lo carga decide antes qué hacer, y aquí no hay forma de pedir una ranura que no está.
 */
data class SlotTable(val a: SlotContent, val b: SlotContent, val c: SlotContent) {
    /** El contenido de [slot]. */
    operator fun get(slot: BackupSlot): SlotContent = when (slot) {
        BackupSlot.A -> a
        BackupSlot.B -> b
        BackupSlot.C -> c
    }

    /** Las ranuras que guardan una copia confirmada, en el orden `A`, `B` y `C`. */
    val copies: List<Pair<BackupSlot, SlotCopy>>
        get() = BackupSlot.entries.mapNotNull { slot ->
            (get(slot) as? SlotContent.Holding)?.let { slot to it.copy }
        }
}
