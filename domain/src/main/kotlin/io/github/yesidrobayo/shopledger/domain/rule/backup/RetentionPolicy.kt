package io.github.yesidrobayo.shopledger.domain.rule.backup

import io.github.yesidrobayo.shopledger.domain.model.backup.RetainedCopy
import io.github.yesidrobayo.shopledger.domain.model.backup.RetentionDay
import io.github.yesidrobayo.shopledger.domain.model.backup.RetentionInput
import io.github.yesidrobayo.shopledger.domain.model.backup.RetentionPlan

/**
 * Qué copias de WebDAV se conservan y cuáles se podan. Solo corre con la copia nueva ya
 * verificada y con una lista completa del servicio, y recibe solo las copias que cuentan: con
 * metadatos válidos, con el tamaño que dicen, y fuera de las generaciones rechazadas.
 *
 * Las copias se agrupan por el día UTC del instante de su nombre, nunca por la fecha del servicio.
 * Ese instante sale del reloj del teléfono, así que el orden confiable es la generación. Conserva,
 * en este orden:
 *
 * 1. La cabeza: el conjunto de copias con la mayor generación.
 * 2. Toda copia fijada.
 * 3. De las copias válidas, sin las de un reloj raro ni las dañadas, la de mayor generación de cada
 *    uno de los últimos [RETAINED_DAYS] días que tengan alguna: días con copia, no de calendario.
 * 4. De cada otro teléfono, la más reciente de esas mismas copias, si su día está entre esos.
 * 5. Si la cabeza es inválida o está dañada, toda copia válida que no esté dañada.
 * 6. Si la cabeza es fatal, la copia más reciente que no lo es: esa sí se restaura.
 * 7. La última copia leída completa.
 * 8. De las copias con un instante mayor que el de una generación posterior, las
 *    [ODD_CLOCK_KEPT] de mayor generación. Su día no es confiable: no se podan por él, y tampoco
 *    ocupan lugar entre los días del punto 3.
 * 9. Sin ninguna copia válida y sana con la clave vigente, toda copia con otra clave: nunca se
 *    queda sin una copia legible al activar, cambiar o apagar el cifrado.
 *
 * Lo demás se poda, salvo que alguna copia por podar tenga más registros que la cabeza y ninguna
 * fijada con la clave vigente tenga al menos esos registros: entonces no se poda nada, y la persona
 * lo ve. Es la defensa contra un error de la app que dejó la base con menos datos.
 */
object RetentionPolicy {
    /** Los días con copia que se conservan. */
    const val RETAINED_DAYS = 7

    /** Las copias de un reloj raro que se conservan como mucho. */
    const val ODD_CLOCK_KEPT = 7

    fun retain(input: RetentionInput): RetentionPlan {
        val copies = input.copies.sortedWith(NEWEST_FIRST)
        if (copies.isEmpty()) {
            return RetentionPlan(
                emptyList(),
                emptyList(),
                blockedByRecords = false,
            )
        }
        val top = copies.first().generation
        val head = copies.filter { it.generation == top }
        val damaged = { copy: RetainedCopy -> copy.generation in input.damagedGenerations }
        val sound = { copy: RetainedCopy -> copy.valid && !damaged(copy) }
        val odd = oddClock(copies)
        val kept = LinkedHashSet<RetainedCopy>()

        kept += head
        kept += copies.filter(RetainedCopy::pinned)

        val dayCandidates = copies.filter { sound(it) && it !in odd }
        val days =
            dayCandidates.map { it.createdAt.retentionDay }.distinct()
                .sortedDescending().take(RETAINED_DAYS).toSet()
        kept += newestOfEachDay(dayCandidates, days)

        dayCandidates
            .filter { it.deviceId != input.deviceId }
            .groupBy(RetainedCopy::deviceId)
            .values
            .map { it.first() }
            .filter { it.createdAt.retentionDay in days }
            .forEach { kept += it }

        if (head.any { !it.valid || damaged(it) }) kept += copies.filter(sound)

        if (head.any(RetainedCopy::fatal)) {
            val restorable = copies.filterNot(RetainedCopy::fatal)
            restorable.firstOrNull()?.let { newest ->
                kept += restorable.filter { it.generation == newest.generation }
            }
        }

        input.fullVerifyGeneration?.let { full -> kept += copies.filter { it.generation == full } }

        kept += odd.take(ODD_CLOCK_KEPT)

        if (copies.none { sound(it) && it.keyId == input.currentKeyId }) {
            kept += copies.filter { it.keyId != input.currentKeyId }
        }

        val prune = copies.filterNot { it in kept }.reversed()
        // Los registros nunca son negativos: empezar en 0 no cambia el máximo.
        val headRecords = head.fold(0L) { most, copy -> maxOf(most, copy.records) }
        val cover =
            copies.filter { it.pinned && it.keyId == input.currentKeyId }
                .maxOfOrNull(RetainedCopy::records)
        val uncovered = prune.any {
            it.records > headRecords &&
                (cover == null || it.records > cover)
        }
        return if (uncovered) {
            RetentionPlan(copies, emptyList(), blockedByRecords = true)
        } else {
            RetentionPlan(copies.filter { it in kept }, prune, blockedByRecords = false)
        }
    }

    /**
     * Las copias cuyo instante es mayor que el de alguna generación posterior: el reloj cambió
     * entre las dos. De mayor a menor generación.
     */
    private fun oddClock(newestFirst: List<RetainedCopy>): List<RetainedCopy> {
        val odd = ArrayList<RetainedCopy>()
        var earliestLater: Long? = null
        for (group in newestFirst.groupBy(RetainedCopy::generation).values) {
            val bound = earliestLater
            if (bound != null) odd += group.filter { it.createdAt.epochSeconds > bound }
            val earliest =
                group.fold(Long.MAX_VALUE) { least, copy ->
                    minOf(least, copy.createdAt.epochSeconds)
                }
            earliestLater = if (bound == null) earliest else minOf(bound, earliest)
        }
        return odd
    }

    /** De cada día de [days], las copias con la mayor generación de ese día. */
    private fun newestOfEachDay(
        newestFirst: List<RetainedCopy>,
        days: Set<RetentionDay>,
    ): List<RetainedCopy> = newestFirst
        .groupBy { it.createdAt.retentionDay }
        .filterKeys { it in days }
        .values
        .flatMap { group -> group.filter { it.generation == group.first().generation } }

    /** De mayor a menor generación, y entre iguales por nombre: el orden de la lista no decide. */
    private val NEWEST_FIRST =
        compareByDescending(RetainedCopy::generation).thenBy(RetainedCopy::key)
}
