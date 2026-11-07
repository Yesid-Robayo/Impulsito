package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.BackupSlot
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCandidate
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotChoice
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.SlotTable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Una ranura con la copia confirmada que guarda. */
private typealias SlotWithCopy = Pair<BackupSlot, SlotCopy>

/**
 * Elige en cuál de las tres ranuras cae una copia nueva.
 *
 * Abrir una ranura para escribir la vacía, y una muerte a mitad la deja sin copia: la ranura
 * elegida se da por perdida desde que se abre. Por eso la pregunta no es si la copia nueva es
 * mejor que lo que hay, sino qué puede perderse si esta escritura falla. Con ranuras no hay poda
 * ni copias fijadas: solo existen estos tres archivos.
 *
 * Se evalúa en este orden:
 *
 * 1. Una copia fatal no se escribe.
 * 2. Ninguna escritura toca la cabeza, la copia válida más reciente ni la reserva.
 * 3. Una ranura libre vacía o a medias va primero, porque no tiene nada que perder. Una copia
 *    válida va al ancla si el ancla es una de ellas, y si no, a la primera de `A`, `B` y `C`.
 * 4. Una copia válida renueva el ancla libre si su copia es inválida o ya tiene [ANCHOR_AGE].
 * 5. Si no, va a la de menor generación entre `A` y `B` que esté libre, y después al ancla.
 * 6. Con las tres protegidas no se escribe nada.
 *
 * Es una función pura: no abre archivos ni lee el reloj. Recibe el estado y devuelve la decisión.
 */
object SlotPlan {
    /**
     * La edad desde la que el ancla se renueva con la siguiente copia válida. Un reloj atrasado da
     * una edad negativa, y entonces el ancla no se renueva por edad.
     */
    val ANCHOR_AGE: Duration = 7.days

    /** Orden por generación, y entre generaciones iguales, por letra. */
    private val byGeneration: Comparator<SlotWithCopy> =
        compareBy({ it.second.generation }, { it.first.ordinal })

    /** La ranura que muestra un conflicto: más registros, luego más catálogo, luego generación. */
    private val byRecordsThenCatalog: Comparator<SlotWithCopy> =
        compareBy({ it.second.records }, { it.second.catalogCount }, { it.second.generation })

    /** Un conteo menor que cualquiera real, para cuando no hay cabeza. */
    private const val NO_COUNT = -1L

    /**
     * La ranura de [candidate], o por qué no se escribe.
     *
     * @param slots lo que el estado local dice de las tres ranuras.
     * @param now el instante actual, solo para la edad del ancla.
     * @param headFileRef la ranura de la cabeza que recuerda el estado del respaldo, si hay una.
     *     También se protege la de mayor generación en [slots]: tras una muerte entre confirmar la
     *     ranura y la cabeza, las dos pueden no coincidir, y ninguna debe perderse.
     */
    fun choose(
        slots: SlotTable,
        candidate: SlotCandidate,
        now: Instant,
        headFileRef: BackupSlot?,
    ): SlotChoice {
        if (candidate.marks.fatal) return SlotChoice.FatalCopy
        val copies = slots.copies
        val head = copies.maxWithOrNull(byGeneration)
        val latestValid = copies.filter { it.second.valid }.maxWithOrNull(byGeneration)
        val reserves = reservesOf(copies, head?.second, candidate)
        val locked = setOfNotNull(head?.first, headFileRef, latestValid?.first) +
            reserves.map { it.first }
        val free = BackupSlot.entries.filterNot(locked::contains)
        val opened = free.filter { slots[it].isOpen }
        val valid = candidate.marks.valid
        if (opened.isNotEmpty()) {
            val slot = if (valid && BackupSlot.C in opened) BackupSlot.C else opened.first()
            return SlotChoice.Write(slot)
        }
        // Desde aquí, toda ranura libre guarda una copia.
        val freeCopies = copies.filter { it.first in free }
        val anchor = freeCopies.firstOrNull { it.first == BackupSlot.C }?.second
        val anchorDue = anchor != null && (!anchor.valid || now - anchor.writtenAt >= ANCHOR_AGE)
        if (valid && anchorDue) return SlotChoice.Write(BackupSlot.C)
        val pair = freeCopies.filter { it.first != BackupSlot.C }
        val oldestOfPair = pair.minWithOrNull(byGeneration)
        return when {
            oldestOfPair != null -> SlotChoice.Write(oldestOfPair.first)
            anchor != null -> SlotChoice.Write(BackupSlot.C)
            else -> SlotChoice.AllLocked(reserves.maxWithOrNull(byRecordsThenCatalog)?.first)
        }
    }

    /**
     * La reserva: hasta dos ranuras, la de más registros si supera en registros a la cabeza y a la
     * copia nueva, y la de más catálogo si las supera en catálogo. Pueden ser una sola.
     *
     * Hace el trabajo de una copia fijada, que con ranuras no existe: tras restaurar una copia
     * vieja, o perder filas en una migración, las ranuras recientes tienen más que la base. Protege
     * por conteos, no por contenido, y se suelta en cuanto la copia nueva alcanza esos conteos.
     */
    private fun reservesOf(
        copies: List<SlotWithCopy>,
        head: SlotCopy?,
        candidate: SlotCandidate,
    ): List<SlotWithCopy> {
        val recordFloor = maxOf(candidate.records, head?.records ?: NO_COUNT)
        val catalogFloor = maxOf(candidate.catalogCount, head?.catalogCount ?: NO_COUNT)
        return listOfNotNull(
            richest(copies, recordFloor, SlotCopy::records),
            richest(copies, catalogFloor, SlotCopy::catalogCount),
        ).distinct()
    }

    /** La ranura con más de [count] por encima de [floor], o `null` si ninguna lo supera. */
    private fun richest(
        copies: List<SlotWithCopy>,
        floor: Long,
        count: (SlotCopy) -> Long,
    ): SlotWithCopy? {
        val byCount = compareBy<SlotWithCopy> { count(it.second) }.then(byGeneration)
        return copies.filter { count(it.second) > floor }.maxWithOrNull(byCount)
    }
}
