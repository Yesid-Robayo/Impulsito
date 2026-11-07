package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotChoice
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotContent
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotTable

/**
 * Las reglas que la elección nunca puede romper, escritas otra vez desde su definición y sin
 * mirar cómo decide el plan. Devuelve qué regla rompe una decisión, o `null` si no rompe ninguna.
 */
internal object SlotOracle {
    private val newest = compareBy<Pair<BackupSlot, SlotCopy>>(
        { it.second.generation },
        { it.first.ordinal },
    )

    fun violation(
        slots: SlotTable,
        candidate: SlotCandidate,
        headFileRef: BackupSlot?,
        choice: SlotChoice,
    ): String? {
        if (candidate.marks.fatal) {
            return if (choice == SlotChoice.FatalCopy) null else "escribió una copia fatal"
        }
        val holding = BackupSlot.entries.mapNotNull { slot ->
            (slots[slot] as? SlotContent.Holding)?.let { slot to it.copy }
        }
        val head = holding.maxWithOrNull(newest)
        val latestValid = holding.filter { it.second.valid }.maxWithOrNull(newest)?.first
        val recordFloor = maxOf(candidate.records, head?.second?.records ?: -1)
        val catalogFloor = maxOf(candidate.catalogCount, head?.second?.catalogCount ?: -1)
        val reserves = setOfNotNull(
            richest(holding.filter { it.second.records > recordFloor }) { it.records },
            richest(holding.filter { it.second.catalogCount > catalogFloor }) { it.catalogCount },
        )
        val locked = setOfNotNull(head?.first, headFileRef, latestValid) + reserves
        return when (choice) {
            SlotChoice.FatalCopy -> "rechazó como fatal una copia que no lo es"

            is SlotChoice.AllLocked -> allLockedViolation(choice, locked, reserves)

            is SlotChoice.Write -> {
                val slot = choice.slot
                val freeOpen = BackupSlot.entries.filter { it !in locked && slots[it].isOpen }
                when {
                    slot == head?.first -> "escribió en la cabeza"

                    slot == headFileRef -> "escribió en la cabeza que recuerda el estado"

                    slot == latestValid -> "escribió en la copia válida más reciente"

                    slot in reserves -> "escribió en la reserva"

                    freeOpen.isNotEmpty() && slot !in freeOpen ->
                        "destruyó una copia con una ranura abierta libre"

                    else -> null
                }
            }
        }
    }

    private fun allLockedViolation(
        choice: SlotChoice.AllLocked,
        locked: Set<BackupSlot>,
        reserves: Set<BackupSlot>,
    ): String? = when {
        locked.size < BackupSlot.entries.size -> "no escribió con una ranura libre"
        reserves.isEmpty() -> if (choice.conflictSlot == null) null else "conflicto sin reserva"
        choice.conflictSlot !in reserves -> "el conflicto no muestra una reserva"
        else -> null
    }

    private fun richest(
        above: List<Pair<BackupSlot, SlotCopy>>,
        count: (SlotCopy) -> Long,
    ): BackupSlot? =
        above.maxWithOrNull(compareBy<Pair<BackupSlot, SlotCopy>> { count(it.second) }.then(newest))
            ?.first
}
